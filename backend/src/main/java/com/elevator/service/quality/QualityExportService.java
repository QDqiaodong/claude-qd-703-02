package com.elevator.service.quality;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.elevator.service.quality.QualityStatsRepository.BuildingRow;
import com.elevator.service.quality.QualityStatsRepository.DetailQueryResult;

/**
 * 服务质量分析导出：
 *
 * <p>导出开始时，在<b>同一个 REPEATABLE READ 只读事务</b>内重算整份快照并逐页枚举全部明细。
 * MySQL InnoDB 同事务内所有 SELECT 共享同一 Read View —— 导出途中即使业务表变化，
 * 文件里的参数、汇总、排行、明细也严格一致，且与页面「同一口径」（SQL 全部来自
 * {@link QualityStatsRepository}，没有第二套计算）。
 *
 * <p>输出为 UTF-8 BOM 的多段 CSV（Excel 直接打开不乱码），含：参数 / 口径 / 汇总卡片 /
 * 趋势合计 / 楼宇风险排行 / 八类明细。
 */
@Service
public class QualityExportService {

    private static final int PAGE_SIZE = 1000;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QualityStatsService statsService;
    private final QualityStatsRepository repo;
    private final ZoneId zoneId;

    public QualityExportService(QualityStatsService statsService, QualityStatsRepository repo) {
        this.statsService = statsService;
        this.repo = repo;
        this.zoneId = ZoneId.of(QualityDict.ZONE);
    }

    /**
     * 读写事务仅用于创建会话临时表（不修改任何业务表）；显式 REPEATABLE READ，
     * 整事务共享同一个 Read View，保证导出途中数据变化不影响文件内容。
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ExportFile export(QualityQuery q) {
        LocalDate today = LocalDate.now(zoneId);
        // 1) 事务开始后立刻取整份快照（本事务 Read View 的数字）
        QualityViews.QualitySnapshot snapshot = statsService.snapshotForExport(q);
        // 名称预载（全量电梯/楼宇一次取齐，避免逐页 IN 查询）
        Map<Long, String> elevatorNames = statsService.loadAllElevatorNos();
        Map<Long, BuildingRow> buildings = statsService.loadAllBuildings();
        // 事件重复标记一次性落入会话临时表，后续事件分页不再逐页重算 LAG（避免 O(n²)）
        LocalDate faultLookback = earliestWindowStart(q, today).minusDays(QualityDict.REPEAT_WINDOW_DAYS);
        repo.prepareFaultTempTable(faultLookback);

        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        out.write(0xEF);
        out.write(0xBB);
        out.write(0xBF);
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            writeHeader(w, snapshot, q);
            writeMetricDefs(w);
            writeCards(w, snapshot);
            writeTrends(w, snapshot);
            writeRanking(w, snapshot);
            writeAllDetails(w, q, today, elevatorNames, buildings);
            w.flush();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("导出文件生成失败：" + e.getMessage(), e);
        } finally {
            repo.dropFaultTempTable();
        }
        String filename = "service-quality-" + q.start() + "_" + q.end()
                + "-" + snapshot.head().snapshotId() + ".csv";
        return new ExportFile(filename, out.toByteArray());
    }

    private void writeHeader(BufferedWriter w, QualityViews.QualitySnapshot s, QualityQuery q)
            throws java.io.IOException {
        section(w, "一、导出参数与快照");
        row(w, "快照编号", s.head().snapshotId());
        row(w, "生成时间", s.head().generatedAt().format(TS_FMT));
        row(w, "数据版本", s.head().dataVersion());
        row(w, "时区口径", s.head().zone() + "（开始日包含、结束日包含，SQL 左闭右开）");
        row(w, "开始日期", String.valueOf(q.start()));
        row(w, "结束日期", String.valueOf(q.end()));
        row(w, "趋势粒度", q.granularity());
        row(w, "楼宇筛选", q.buildingId() == null ? "全部楼宇（含已撤场）" : String.valueOf(q.buildingId()));
        row(w, "电梯筛选", q.elevatorId() == null ? "全部电梯" : String.valueOf(q.elevatorId()));
        row(w, "范围内楼宇数", String.valueOf(s.head().scopeBuildingCount()));
        row(w, "范围内电梯数", String.valueOf(s.head().scopeElevatorCount()));
        row(w, "空数据", s.empty() ? "是" : "否");
        blank(w);
    }

    private void writeMetricDefs(BufferedWriter w) throws java.io.IOException {
        section(w, "二、指标可审计口径（前后端与导出同一份定义）");
        for (QualityDict.MetricDef d : QualityDict.metricDefs()) {
            row(w, "指标", d.name());
            row(w, "窗口锚点", d.windowAnchor());
            row(w, "分母口径", d.denominatorRule());
            row(w, "分子口径", d.numeratorRule());
            row(w, "计算公式", d.formula());
        }
        row(w, "风险权重", QualityDict.riskWeights().stream()
                .map(x -> x.name() + "=" + x.weight() + "分/单位").reduce((a, b) -> a + "；" + b).orElse(""));
        blank(w);
    }

    private void writeCards(BufferedWriter w, QualityViews.QualitySnapshot s) throws java.io.IOException {
        section(w, "三、汇总卡片（与页面快照一致）");
        csv(w, "指标", "单位", "分子", "分母", "比率/数值", "口径");
        for (QualityViews.MetricCard c : s.cards()) {
            csv(w, c.name(), c.unit(),
                    c.numerator() == null ? "" : String.valueOf(c.numerator()),
                    c.denominator() == null ? "" : String.valueOf(c.denominator()),
                    c.ratePercent() != null ? c.ratePercent().toPlainString()
                            : (c.value() == null ? "" : c.value().toPlainString()),
                    c.formula());
        }
        blank(w);
    }

    private void writeTrends(BufferedWriter w, QualityViews.QualitySnapshot s) throws java.io.IOException {
        section(w, "四、按" + granularityName(s.head().granularity()) + "趋势（折叠回总计与卡片一致）");
        csv(w, "桶起点", "区间",
                "到期计划", "准时计划", "准时率%",
                "完成记录", "耗时合计天", "平均耗时天",
                "事件总数", "重复事件", "重复率%",
                "配件记录", "配件数量", "配件金额");
        for (QualityViews.TrendBucket t : s.trends()) {
            csv(w, t.bucket(), t.label(),
                    nz(t.planDue()), nz(t.planOnTime()), txt(t.planRatePercent()),
                    nz(t.orderCount()), nz(t.durationDays()), txt(t.avgDurationDays()),
                    nz(t.eventCount()), nz(t.eventRepeat()), txt(t.eventRatePercent()),
                    nz(t.partRecords()), nz(t.partQuantity()), txt(t.partAmount()));
        }
        blank(w);
    }

    private void writeRanking(BufferedWriter w, QualityViews.QualitySnapshot s) throws java.io.IOException {
        section(w, "五、楼宇风险排行（分值降序、楼宇编号升序；含已撤场楼宇）");
        csv(w, "排名", "楼宇编号", "楼宇名称", "状态",
                "逾期计划(×" + QualityDict.W_OVERDUE_PLAN + ")",
                "未完成事件(×" + QualityDict.W_OPEN_EVENT + ")",
                "重复事件(×" + QualityDict.W_REPEAT_EVENT + ")",
                "检验到期(×" + QualityDict.W_INSPECT + ")",
                "电梯数", "风险总分", "风险等级");
        int rank = 1;
        for (QualityViews.RiskRankRow r : s.ranking()) {
            csv(w, String.valueOf(rank++), r.buildingNo(), r.buildingName(), r.buildingStatusName(),
                    nz(r.overduePlans()), nz(r.openEvents()), nz(r.repeatEvents()), nz(r.inspectDue()),
                    nz(r.elevatorCount()), String.valueOf(r.riskScore()), r.riskLevelName());
        }
        blank(w);
    }

    private void writeAllDetails(BufferedWriter w, QualityQuery q, LocalDate today,
                                 Map<Long, String> elevatorNames, Map<Long, BuildingRow> buildings)
            throws java.io.IOException {
        // 临时表覆盖的两个事件窗口：分析窗口 [start,end+1)、风险近30天窗口
        LocalDate anStart = q.start();
        LocalDate anEnd = q.end().plusDays(1);
        LocalDate rkStart = today.minusDays(QualityDict.RISK_RECENT_DAYS - 1L);
        LocalDate rkEnd = today.plusDays(1);

        writeDetailSection(w, "六-1 计划准时率·全部到期非作废计划（分母）",
                q, QualityDict.M_PLAN_ON_TIME, "DENOMINATOR", today, elevatorNames, buildings, null);
        writeDetailSection(w, "六-2 计划准时率·按计划日完成（分子）",
                q, QualityDict.M_PLAN_ON_TIME, "NUMERATOR", today, elevatorNames, buildings, null);
        writeDetailSection(w, "六-3 平均执行耗时·计入的维保记录",
                q, QualityDict.M_ORDER_DURATION, "DENOMINATOR", today, elevatorNames, buildings, null);
        writeDetailSection(w, "六-4 重复事件率·全部非撤销事件（分母）",
                q, QualityDict.M_EVENT_REPEAT, "DENOMINATOR", today, elevatorNames, buildings,
                cur -> repo.faultDetailsFromTemp(q, false, cur, PAGE_SIZE, anStart, anEnd));
        writeDetailSection(w, "六-5 重复事件率·重复事件（分子）",
                q, QualityDict.M_EVENT_REPEAT, "NUMERATOR", today, elevatorNames, buildings,
                cur -> repo.faultDetailsFromTemp(q, true, cur, PAGE_SIZE, anStart, anEnd));
        writeDetailSection(w, "六-6 配件成本·已登记配件明细",
                q, QualityDict.M_PART_COST, "DENOMINATOR", today, elevatorNames, buildings, null);
        writeDetailSection(w, "六-7 风险分量·逾期计划",
                q, QualityDict.M_RISK_OVERDUE_PLAN, "DENOMINATOR", today, elevatorNames, buildings, null);
        writeDetailSection(w, "六-8 风险分量·未完成事件",
                q, QualityDict.M_RISK_OPEN_EVENT, "DENOMINATOR", today, elevatorNames, buildings, null);
        writeDetailSection(w, "六-9 风险分量·近30天重复事件",
                q, QualityDict.M_RISK_REPEAT_EVENT, "DENOMINATOR", today, elevatorNames, buildings,
                cur -> repo.faultDetailsFromTemp(q, true, cur, PAGE_SIZE, rkStart, rkEnd));
        writeDetailSection(w, "六-10 风险分量·未来30天检验到期电梯",
                q, QualityDict.M_RISK_INSPECT, "DENOMINATOR", today, elevatorNames, buildings, null);
    }

    /** 临时表需要覆盖分析窗口与近30天风险窗口中更早的那个起点（再向前 30 天 lookback） */
    private LocalDate earliestWindowStart(QualityQuery q, LocalDate today) {
        LocalDate riskStart = today.minusDays(QualityDict.RISK_RECENT_DAYS - 1L);
        return q.start().isBefore(riskStart) ? q.start() : riskStart;
    }

    @FunctionalInterface
    private interface PageFetcher {
        DetailQueryResult fetch(String cursor);
    }

    private void writeDetailSection(BufferedWriter w, String title, QualityQuery q,
                                    String metric, String scope, LocalDate today,
                                    Map<Long, String> elevatorNames, Map<Long, BuildingRow> buildings,
                                    PageFetcher tempFetcher)
            throws java.io.IOException {
        section(w, title);
        csv(w, "来源类型", "来源单号", "电梯编号", "楼宇编号", "楼宇名称",
                "业务日期", "数量/计数1", "计数2", "金额", "数值1", "是否计入", "计入原因");
        String cursor = null;
        long total = 0;
        do {
            DetailQueryResult page = tempFetcher != null
                    ? tempFetcher.fetch(cursor)
                    : repo.details(q, metric, scope, cursor, PAGE_SIZE, today);
            List<QualityViews.DetailRow> rows = statsService.enrich(page.rows(), elevatorNames, buildings);
            for (QualityViews.DetailRow r : rows) {
                csv(w, r.sourceType(), txt(r.sourceNo()), txt(r.elevatorNo()),
                        txt(r.buildingNo()), txt(r.buildingName()),
                        r.sourceDate() == null ? "" : r.sourceDate().toString(),
                        r.count1() == null ? "" : String.valueOf(r.count1()),
                        r.count2() == null ? "" : String.valueOf(r.count2()),
                        r.amount() == null ? "" : r.amount().toPlainString(),
                        r.value1() == null ? "" : r.value1().toPlainString(),
                        r.included() ? "是" : "否", r.reason());
                total++;
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        csv(w, "本明细合计", "", "", "", "", "", String.valueOf(total), "", "", "", "", "");
        blank(w);
    }

    private static String granularityName(String g) {
        return switch (g == null ? "DAY" : g) {
            case "WEEK" -> "周";
            case "MONTH" -> "月";
            default -> "日";
        };
    }

    private static void section(BufferedWriter w, String title) throws java.io.IOException {
        w.write("# " + title);
        w.newLine();
    }

    private static void blank(BufferedWriter w) throws java.io.IOException {
        w.newLine();
    }

    private static void row(BufferedWriter w, String k, String v) throws java.io.IOException {
        csv(w, k, v);
    }

    /** 标准 CSV 转义（含逗号/引号/换行） */
    private static void csv(BufferedWriter w, String... cells) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            String c = cells[i] == null ? "" : cells[i];
            if (c.contains(",") || c.contains("\"") || c.contains("\n") || c.contains("\r")) {
                line.append('"').append(c.replace("\"", "\"\"")).append('"');
            } else {
                line.append(c);
            }
        }
        w.write(line.toString());
        w.newLine();
    }

    private static String nz(long v) {
        return String.valueOf(v);
    }

    private static String txt(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    public record ExportFile(String filename, byte[] content) {
    }
}
