package com.elevator.service.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.elevator.service.common.BizException;
import com.elevator.service.common.QualityEntityChangeListener;
import com.elevator.service.domain.ElevatorDict;
import com.elevator.service.dto.QualityDtos;
import com.elevator.service.repository.QualityStatisticsRepository;

/**
 * 服务质量统计。
 *
 * <p>一次快照由同一只读事务生成，卡片、趋势、排行共用聚合结果；钻取与导出携带 snapshotId，
 * 数据版本变化时明确拒绝旧快照，避免页面与文件各算一遍。
 */
@Service
public class QualityAnalysisService {

    public static final String TZ_SHANGHAI = "Asia/Shanghai";
    public static final BigDecimal W_OVERDUE = new BigDecimal("0.35");
    public static final BigDecimal W_OPEN = new BigDecimal("0.30");
    public static final BigDecimal W_REPEAT = new BigDecimal("0.25");
    public static final BigDecimal W_INSPECT = new BigDecimal("0.10");

    private static final long SNAPSHOT_TTL_MS = 10 * 60_000L;
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final QualityStatisticsRepository repository;
    private final TransactionTemplate readOnlyTransactions;
    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentMap<CacheKey, CacheEntry> aggregateCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry> snapshotCache = new ConcurrentHashMap<>();

    public QualityAnalysisService(QualityStatisticsRepository repository,
                                  PlatformTransactionManager transactionManager,
                                  ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.readOnlyTransactions = new TransactionTemplate(transactionManager);
        this.readOnlyTransactions.setReadOnly(true);
        this.eventPublisher = eventPublisher;
        QualityEntityChangeListener.bind(() -> eventPublisher.publishEvent(new QualityDataChangedEvent()));
    }

    /** 供非 Spring 管理的实体监听器桥接，事件监听器在事务提交后清缓存。 */
    public void publishChanged() {
        eventPublisher.publishEvent(new QualityDataChangedEvent());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQualityDataChanged(QualityDataChangedEvent event) {
        invalidate();
    }

    /**
     * 获取统计快照。同 key 的并发线程进入同一把锁计算，异常时不会放入缓存。
     */
    public QualityDtos.QualitySnapshot getSnapshot(QualityDtos.SnapshotRequest request) {
        CacheKey key = CacheKey.of(normalize(request));
        CacheEntry cached = aggregateCache.get(key);
        if (cached != null && cached.valid()) {
            return cached.snapshot;
        }
        synchronized (key.internLock()) {
            cached = aggregateCache.get(key);
            if (cached != null && cached.valid()) {
                return cached.snapshot;
            }
            QualityDtos.SnapshotRequest normalized = normalize(request);
            QualityDtos.QualitySnapshot snapshot = readOnlyTransactions.execute(status -> computeSnapshot(normalized));
            long now = System.currentTimeMillis();
            aggregateCache.put(key, new CacheEntry(snapshot, now + SNAPSHOT_TTL_MS));
            snapshotCache.put(snapshot.getSnapshotId(), new CacheEntry(snapshot, now + SNAPSHOT_TTL_MS));
            return snapshot;
        }
    }

    @Transactional(readOnly = true)
    public QualityDtos.DetailPage drill(String snapshotId, String metric, String component,
                                        String cursor, int size, Long buildingId) {
        QualityDtos.QualitySnapshot snapshot = requireSnapshot(snapshotId);
        assertFresh(snapshot);
        String normalizedMetric = normalizeMetric(metric);
        String normalizedComponent = normalizeComponent(normalizedMetric, component);
        int limit = Math.min(Math.max(size, 1), 200);
        List<Map<String, Object>> rows = repository.detailRows(
                normalizedMetric,
                normalizedComponent,
                snapshot.getFilter().getStartDate(),
                snapshot.getFilter().getEndDate().plusDays(1),
                snapshot.getFilter().getAsOfDate(),
                snapshot.getFilter().getBuildingId(),
                snapshot.getFilter().getElevatorId(),
                buildingId,
                cursor,
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<QualityDtos.DetailRow> mapped = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(limit, rows.size()))) {
            mapped.add(toDetailRow(row, normalizedMetric, normalizedComponent));
        }
        String nextCursor = null;
        if (hasMore && !mapped.isEmpty()) {
            QualityDtos.DetailRow last = mapped.get(mapped.size() - 1);
            nextCursor = switch (normalizedMetric) {
                case "PLAN" -> QualityStatisticsRepository.encodeCursor(last.getDate(), last.getId());
                case "DURATION", "PART" -> QualityStatisticsRepository.encodeCursor(last.getDate(), last.getId());
                case "REPEAT" -> QualityStatisticsRepository.encodeCursor(last.getEventTime(), last.getId());
                case "RISK" -> QualityStatisticsRepository.encodeCursor(last.getDate(), sourceNoForRisk(last), last.getId());
                default -> null;
            };
        }
        Long total = drillTotal(snapshot, normalizedMetric, normalizedComponent, buildingId);
        return new QualityDtos.DetailPage(mapped, nextCursor, total);
    }

    @Transactional(readOnly = true)
    public void exportSnapshot(String snapshotId, HttpServletResponse response) {
        QualityDtos.QualitySnapshot snapshot = requireSnapshot(snapshotId);
        assertFresh(snapshot);
        LocalDateTime startedAt = LocalDateTime.now(ZoneId.of(TZ_SHANGHAI));
        String filename = "service-quality-" + startedAt.format(FILE_TS) + ".csv";
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        try {
            OutputStreamWriter writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
            writer.write('﻿');
            BufferedWriter out = new BufferedWriter(writer);
            writeExport(out, snapshot, startedAt);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------- 快照计算 ----------------

    private QualityDtos.QualitySnapshot computeSnapshot(QualityDtos.SnapshotRequest req) {
        LocalDate start = req.getStartDate();
        LocalDate endInclusive = req.getEndDate();
        LocalDate endExclusive = endInclusive.plusDays(1);
        LocalDateTime generatedAt = LocalDateTime.now(ZoneId.of(TZ_SHANGHAI));
        String version = repository.currentVersion(generatedAt);

        List<Map<String, Object>> planRows = repository.planDaily(start, endExclusive, req.getBuildingId(), req.getElevatorId());
        List<Map<String, Object>> durationRows = repository.orderDurationDaily(start, endExclusive, req.getBuildingId(), req.getElevatorId());
        List<Map<String, Object>> faultRows = repository.faultDaily(start, endExclusive, req.getBuildingId(), req.getElevatorId());
        List<Map<String, Object>> partRows = repository.partDaily(start, endExclusive, req.getBuildingId(), req.getElevatorId());
        LocalDate asOf = LocalDate.now(ZoneId.of(TZ_SHANGHAI));
        List<Map<String, Object>> riskRows = repository.buildingRisk(start, endExclusive, asOf, req.getBuildingId(), req.getElevatorId());

        Map<LocalDate, long[]> planByDate = longMap(planRows, "bucket_date", new String[]{"numerator", "denominator"});
        Map<LocalDate, long[]> durationByDate = longMap(durationRows, "bucket_date", new String[]{"duration_minutes", "duration_count"});
        Map<LocalDate, long[]> faultByDate = longMap(faultRows, "bucket_date", new String[]{"numerator", "denominator"});
        Map<LocalDate, BigDecimal[]> partByDate = partMap(partRows);

        List<QualityDtos.TrendBucket> trends = buildTrends(start, endInclusive, req.getGranularity(),
                planByDate, durationByDate, faultByDate, partByDate);

        long planNum = sum(trends, QualityDtos.TrendBucket::getOnTimeNumerator);
        long planDen = sum(trends, QualityDtos.TrendBucket::getOnTimeDenominator);
        long durationMinutes = sum(trends, QualityDtos.TrendBucket::getDurationTotalMinutes);
        long durationCount = sum(trends, QualityDtos.TrendBucket::getDurationCount);
        long repeatNum = sum(trends, QualityDtos.TrendBucket::getRepeatNumerator);
        long repeatDen = sum(trends, QualityDtos.TrendBucket::getRepeatDenominator);
        long partCount = sum(trends, QualityDtos.TrendBucket::getPartCount);
        long partQuantity = sum(trends, QualityDtos.TrendBucket::getPartQuantity);
        BigDecimal partAmount = trends.stream().map(t -> nz(t.getPartAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<QualityDtos.BuildingRank> ranks = buildRanks(riskRows);
        Map<String, QualityDtos.MetricSummary> summaries = new LinkedHashMap<>();
        summaries.put("PLAN_ON_TIME", metric("PLAN_ON_TIME", "计划准时率",
                divide(planNum, planDen, 4), percent(divide(planNum, planDen, 4)), "%", planNum, planDen, null, planDen));
        summaries.put("AVG_DURATION", metric("AVG_DURATION", "平均执行耗时",
                durationCount == 0 ? null : BigDecimal.valueOf(durationMinutes).divide(BigDecimal.valueOf(durationCount), 2, RoundingMode.HALF_UP),
                durationCount == 0 ? "—" : BigDecimal.valueOf(durationMinutes).divide(BigDecimal.valueOf(durationCount), 2, RoundingMode.HALF_UP) + " 小时",
                "小时", durationMinutes, durationCount, null, durationCount));
        summaries.put("REPEAT_RATE", metric("REPEAT_RATE", "重复事件率",
                divide(repeatNum, repeatDen, 4), percent(divide(repeatNum, repeatDen, 4)), "%", repeatNum, repeatDen, null, repeatDen));
        summaries.put("PART_COST", metric("PART_COST", "配件成本",
                partAmount.setScale(2, RoundingMode.HALF_UP), "¥ " + partAmount.setScale(2, RoundingMode.HALF_UP),
                "元", partCount, partCount, partAmount.setScale(2, RoundingMode.HALF_UP), partCount));
        long totalOverdue = ranks.stream().mapToLong(QualityDtos.BuildingRank::getOverduePlanCount).sum();
        long totalOpen = ranks.stream().mapToLong(QualityDtos.BuildingRank::getOpenEventCount).sum();
        long totalRepeatRisk = ranks.stream().mapToLong(QualityDtos.BuildingRank::getRepeatEventCount).sum();
        long totalInspect = ranks.stream().mapToLong(QualityDtos.BuildingRank::getInspectDue30dCount).sum();
        summaries.put("BUILDING_RISK", metric("BUILDING_RISK", "楼宇风险因子",
                BigDecimal.valueOf(totalOverdue + totalOpen + totalRepeatRisk + totalInspect),
                String.valueOf(totalOverdue + totalOpen + totalRepeatRisk + totalInspect),
                "项", totalOverdue + totalOpen + totalRepeatRisk + totalInspect,
                totalOverdue + totalOpen + totalRepeatRisk + totalInspect, null,
                totalOverdue + totalOpen + totalRepeatRisk + totalInspect));

        Map<String, Long> detailCounts = new LinkedHashMap<>();
        detailCounts.put("PLAN:DENOMINATOR", planDen);
        detailCounts.put("PLAN:NUMERATOR", planNum);
        detailCounts.put("DURATION:COUNTED", durationCount);
        detailCounts.put("REPEAT:DENOMINATOR", repeatDen);
        detailCounts.put("REPEAT:NUMERATOR", repeatNum);
        detailCounts.put("PART:COUNTED", partCount);
        detailCounts.put("RISK:RISK_OVERDUE", totalOverdue);
        detailCounts.put("RISK:RISK_OPEN", totalOpen);
        detailCounts.put("RISK:RISK_REPEAT", totalRepeatRisk);
        detailCounts.put("RISK:RISK_INSPECTION", totalInspect);
        detailCounts.put("RISK:ALL", totalOverdue + totalOpen + totalRepeatRisk + totalInspect);

        QualityDtos.FilterMeta filter = new QualityDtos.FilterMeta(
                start, endInclusive, start.atStartOfDay(), endExclusive.atStartOfDay(),
                req.getGranularity(), req.getBuildingId(), req.getElevatorId(), TZ_SHANGHAI, asOf);
        return new QualityDtos.QualitySnapshot(
                UUID.randomUUID().toString(),
                generatedAt,
                version,
                filter,
                definitions(),
                summaries,
                trends,
                ranks,
                detailCounts,
                Map.of("overduePlan", W_OVERDUE.toPlainString(), "openEvent", W_OPEN.toPlainString(),
                        "repeatEvent", W_REPEAT.toPlainString(), "inspectDue30d", W_INSPECT.toPlainString()));
    }

    private QualityDtos.SnapshotRequest normalize(QualityDtos.SnapshotRequest req) {
        if (req == null) {
            throw new BizException("统计筛选条件不能为空");
        }
        if (req.getTimezone() != null && !TZ_SHANGHAI.equals(req.getTimezone())) {
            throw new BizException("当前统计仅支持 Asia/Shanghai 自然日口径");
        }
        LocalDate today = LocalDate.now(ZoneId.of(TZ_SHANGHAI));
        LocalDate end = req.getEndDate() == null ? today : req.getEndDate();
        LocalDate start = req.getStartDate() == null ? end.minusDays(89) : req.getStartDate();
        if (start.isAfter(end)) {
            throw new BizException("开始日期不能晚于结束日期");
        }
        if (start.isBefore(LocalDate.of(2000, 1, 1)) || end.isAfter(today.plusYears(10))) {
            throw new BizException("日期范围超出允许区间");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        if (days > 731) {
            throw new BizException("单次统计窗口不能超过 731 天，请缩小日期范围");
        }
        String granularity = req.getGranularity() == null ? "day" : req.getGranularity().toLowerCase();
        if (!List.of("day", "week", "month").contains(granularity)) {
            throw new BizException("趋势粒度只能是 day / week / month");
        }
        return new QualityDtos.SnapshotRequest(start, end, granularity,
                req.getBuildingId(), req.getElevatorId(), TZ_SHANGHAI);
    }

    private List<QualityDtos.MetricDefinition> definitions() {
        return List.of(
                def("PLAN_ON_TIME", "计划准时率", "窗口内到期且非作废、状态 DONE 且关联维保记录实际完成日=计划日",
                        "窗口内到期且非作废计划", "%", "maintenance_plan.plan_date", "开始日包含、结束日包含；VOID 不入分母"),
                def("AVG_DURATION", "平均执行耗时", "actual_date - plan_date 的分钟数合计", "已有实际完成日的非作废/非撤销维保记录",
                        "小时", "maintenance_order.actual_date", "无计划日的记录按 0 分钟计入，仍计入记录数"),
                def("REPEAT_RATE", "重复事件率", "同电梯同事件类型，前序 30 天内存在未取消事件", "窗口内未取消故障事件",
                        "%", "fault_report.report_time", "30 天按事件时间向前滚动；同刻以单号 id 稳定判定先后"),
                def("PART_COST", "配件成本", "quantity × unit_price", "更换日在窗口内且 NORMAL 的配件记录",
                        "元", "part_replacement.replace_date", "不使用可能被改写的 amount，汇总时按数量乘单价重算"),
                def("BUILDING_RISK", "楼宇风险排行", "逾期计划×0.35 + 未完成事件×0.30 + 重复事件×0.25 + 未来30天检验×0.10",
                        "筛选范围内楼宇", "分", "多日期字段", "各因子计数后加权，并列按楼宇编号升序"));
    }

    private QualityDtos.MetricDefinition def(String key, String name, String numerator, String denominator,
                                             String unit, String dateField, String reason) {
        return new QualityDtos.MetricDefinition(key, name, numerator, denominator, unit, dateField, reason);
    }

    private List<QualityDtos.TrendBucket> buildTrends(LocalDate start, LocalDate end, String granularity,
                                                      Map<LocalDate, long[]> plan,
                                                      Map<LocalDate, long[]> duration,
                                                      Map<LocalDate, long[]> fault,
                                                      Map<LocalDate, BigDecimal[]> part) {
        List<QualityDtos.TrendBucket> list = new ArrayList<>();
        LocalDate cursor = bucketStart(start, granularity);
        while (!cursor.isAfter(end)) {
            LocalDate bStart = cursor;
            LocalDate bEnd = switch (granularity) {
                case "week" -> bStart.plusDays(6);
                case "month" -> bStart.with(TemporalAdjusters.lastDayOfMonth());
                default -> bStart;
            };
            LocalDate dataStart = bStart.isBefore(start) ? start : bStart;
            LocalDate dataEnd = bEnd.isAfter(end) ? end : bEnd;
            long[] p = new long[2];
            long[] d = new long[2];
            long[] f = new long[2];
            BigDecimal[] pr = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
            for (LocalDate day = dataStart; !day.isAfter(dataEnd); day = day.plusDays(1)) {
                add(p, plan.get(day));
                add(d, duration.get(day));
                add(f, fault.get(day));
                BigDecimal[] pv = part.get(day);
                if (pv != null) {
                    pr[0] = pr[0].add(nz(pv[0]));
                    pr[1] = pr[1].add(nz(pv[1]));
                    pr[2] = pr[2].add(nz(pv[2]));
                }
            }
            String key = switch (granularity) {
                case "week" -> bStart.toString() + "/" + bEnd.toString();
                case "month" -> String.format("%d-%02d", bStart.getYear(), bStart.getMonthValue());
                default -> bStart.toString();
            };
            String label = switch (granularity) {
                case "week" -> bStart + " 至 " + bEnd;
                case "month" -> bStart.getYear() + "年" + bStart.getMonthValue() + "月";
                default -> bStart.toString();
            };
            list.add(new QualityDtos.TrendBucket(
                    key, label, bStart, bEnd,
                    p[0], p[1], divide(p[0], p[1], 4),
                    d[0], d[1], d[1] == 0 ? null : BigDecimal.valueOf(d[0]).divide(BigDecimal.valueOf(d[1]), 2, RoundingMode.HALF_UP),
                    f[0], f[1], divide(f[0], f[1], 4),
                    pr[0].longValue(), pr[1].longValue(), pr[2].setScale(2, RoundingMode.HALF_UP)));
            cursor = switch (granularity) {
                case "week" -> bStart.plusWeeks(1);
                case "month" -> bStart.plusMonths(1);
                default -> bStart.plusDays(1);
            };
        }
        return list;
    }

    private List<QualityDtos.BuildingRank> buildRanks(List<Map<String, Object>> rows) {
        List<QualityDtos.BuildingRank> list = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long overdue = lng(row, "overdue_count");
            long open = lng(row, "open_count");
            long repeat = lng(row, "repeat_count");
            long inspect = lng(row, "inspect_count");
            BigDecimal score = W_OVERDUE.multiply(BigDecimal.valueOf(overdue))
                    .add(W_OPEN.multiply(BigDecimal.valueOf(open)))
                    .add(W_REPEAT.multiply(BigDecimal.valueOf(repeat)))
                    .add(W_INSPECT.multiply(BigDecimal.valueOf(inspect)))
                    .setScale(2, RoundingMode.HALF_UP);
            String status = str(row, "building_status");
            list.add(new QualityDtos.BuildingRank(
                    lng(row, "building_id"), str(row, "building_no"), str(row, "building_name"),
                    status, ElevatorDict.archiveStatusName(status),
                    overdue, open, repeat, inspect, score,
                    score.compareTo(new BigDecimal("3")) >= 0 ? "高" : score.compareTo(BigDecimal.ONE) >= 0 ? "中" : "低"));
        }
        list.sort(Comparator.comparing(QualityDtos.BuildingRank::getRiskScore).reversed()
                .thenComparing(QualityDtos.BuildingRank::getBuildingNo));
        return list;
    }

    private void writeExport(BufferedWriter out, QualityDtos.QualitySnapshot s, LocalDateTime startedAt) throws IOException {
        writeCsv(out, "维保服务质量分析导出");
        writeCsv(out, "快照ID", s.getSnapshotId());
        writeCsv(out, "生成时间", s.getGeneratedAt().toString());
        writeCsv(out, "导出开始时间", startedAt.toString());
        writeCsv(out, "数据版本", s.getDataVersion());
        writeCsv(out, "时区", s.getFilter().getTimezone());
        writeCsv(out, "开始日(包含)", s.getFilter().getStartDate().toString());
        writeCsv(out, "结束日(包含)", s.getFilter().getEndDate().toString());
        writeCsv(out, "后端区间", "[" + s.getFilter().getStartInclusive() + "," + s.getFilter().getEndExclusive() + ")");
        writeCsv(out, "粒度", s.getFilter().getGranularity());
        writeCsv(out, "楼宇ID", String.valueOf(s.getFilter().getBuildingId()));
        writeCsv(out, "电梯ID", String.valueOf(s.getFilter().getElevatorId()));
        out.newLine();

        writeCsv(out, "指标", "显示值", "分子", "分母", "金额/耗时", "口径");
        for (QualityDtos.MetricDefinition def : s.getDefinitions()) {
            QualityDtos.MetricSummary sum = s.getSummaries().get(def.getKey());
            writeCsv(out, def.getName(), sum == null ? "" : sum.getDisplayValue(),
                    String.valueOf(sum == null ? "" : nz(sum.getNumerator())),
                    String.valueOf(sum == null ? "" : nz(sum.getDenominator())),
                    String.valueOf(sum == null || sum.getAmount() == null ? "" : sum.getAmount()),
                    def.getNumerator() + " / " + def.getDenominator());
        }
        out.newLine();

        writeCsv(out, "风险权重", "逾期计划", W_OVERDUE.toPlainString(), "未完成事件", W_OPEN.toPlainString(),
                "重复事件", W_REPEAT.toPlainString(), "未来30天检验", W_INSPECT.toPlainString());
        writeCsv(out, "楼宇编号", "楼宇名称", "状态", "逾期计划", "未完成事件", "重复事件", "未来30天检验", "风险分", "风险等级");
        for (QualityDtos.BuildingRank r : s.getBuildingRanks()) {
            writeCsv(out, r.getBuildingNo(), r.getBuildingName(), r.getBuildingStatusName(),
                    r.getOverduePlanCount(), r.getOpenEventCount(), r.getRepeatEventCount(),
                    r.getInspectDue30dCount(), r.getRiskScore(), r.getRiskLevel());
        }
        out.newLine();

        writeCsv(out, "趋势粒度", "区间", "准时数", "到期计划", "准时率", "耗时分钟", "维保记录", "平均小时",
                "重复数", "事件数", "重复率", "配件数", "配件数量", "配件金额");
        for (QualityDtos.TrendBucket t : s.getTrends()) {
            writeCsv(out, t.getKey(), t.getLabel(), t.getOnTimeNumerator(), t.getOnTimeDenominator(), t.getOnTimeRate(),
                    t.getDurationTotalMinutes(), t.getDurationCount(), t.getAverageDurationHours(),
                    t.getRepeatNumerator(), t.getRepeatDenominator(), t.getRepeatRate(),
                    t.getPartCount(), t.getPartQuantity(), t.getPartAmount());
        }
        out.newLine();

        writeCsv(out, "明细指标", "构成", "来源单号", "来源类型", "楼宇编号", "楼宇", "电梯", "日期", "状态", "数量", "单价", "金额", "耗时分钟", "计入", "计入原因", "关联单号");
        for (String metric : List.of("PLAN", "DURATION", "REPEAT", "PART", "RISK")) {
            String cursor = null;
            do {
                QualityDtos.DetailPage page = drillExportPage(s.getSnapshotId(), metric, "ALL", cursor, 500, null);
                for (QualityDtos.DetailRow row : page.getRows()) {
                    writeCsv(out, metric, row.getComponent(), row.getSourceNo(), row.getSourceTypeName(),
                            row.getBuildingNo(), row.getBuildingName(), row.getElevatorNo(),
                            row.getMetricDate(), row.getStatusName(), row.getQuantity(), row.getUnitPrice(),
                            row.getAmount(), row.getDurationMinutes(), row.getCounted(), row.getIncludeReason(),
                            row.getRelatedSourceNo());
                }
                cursor = page.getNextCursor();
            } while (cursor != null);
        }
    }

    private QualityDtos.DetailPage drillExportPage(String snapshotId, String metric, String component,
                                                   String cursor, int size, Long buildingId) {
        // 导出已经处于快照校验事务中；直接复用仓库，避免自调用导致重复版本断言。
        QualityDtos.QualitySnapshot snapshot = requireSnapshot(snapshotId);
        List<Map<String, Object>> rows = repository.detailRows(metric, normalizeComponent(metric, component),
                snapshot.getFilter().getStartDate(), snapshot.getFilter().getEndDate().plusDays(1),
                snapshot.getFilter().getAsOfDate(),
                snapshot.getFilter().getBuildingId(), snapshot.getFilter().getElevatorId(), buildingId, cursor, size + 1);
        boolean more = rows.size() > size;
        List<QualityDtos.DetailRow> mapped = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(size, rows.size()))) {
            mapped.add(toDetailRow(row, metric, normalizeComponent(metric, component)));
        }
        String next = null;
        if (more && !mapped.isEmpty()) {
            QualityDtos.DetailRow last = mapped.get(mapped.size() - 1);
            next = switch (metric) {
                case "PLAN", "DURATION", "PART" -> QualityStatisticsRepository.encodeCursor(last.getDate(), last.getId());
                case "REPEAT" -> QualityStatisticsRepository.encodeCursor(last.getEventTime(), last.getId());
                case "RISK" -> QualityStatisticsRepository.encodeCursor(last.getDate(), sourceNoForRisk(last), last.getId());
                default -> null;
            };
        }
        return new QualityDtos.DetailPage(mapped, next, null);
    }

    private QualityDtos.DetailRow toDetailRow(Map<String, Object> row, String metric, String requestedComponent) {
        String component = str(row, "component");
        if ((component == null || component.isBlank()) && !requestedComponent.isBlank()) {
            component = requestedComponent;
        }
        String status = str(row, "status");
        String statusName = switch (metric) {
            case "PLAN" -> ElevatorDict.planStatusName(status);
            case "FAULT", "REPEAT" -> ElevatorDict.faultStatusName(status);
            case "RISK" -> switch (component) {
                case "RISK_OVERDUE" -> ElevatorDict.planStatusName(status);
                case "RISK_INSPECTION" -> ElevatorDict.elevatorStatusName(status);
                default -> ElevatorDict.faultStatusName(status);
            };
            case "PART" -> ElevatorDict.partStatusName(status);
            case "DURATION" -> ElevatorDict.isValidFaultStatus(status)
                    ? ElevatorDict.emergencyStatusName(status)
                    : ElevatorDict.planStatusName(status);
            default -> ElevatorDict.planStatusName(status);
        };
        String sourceType = str(row, "source_type");
        String sourceTypeName = switch (sourceType) {
            case "PLAN" -> "维保计划";
            case "ORDER" -> "维保记录";
            case "FAULT" -> "故障事件";
            case "ELEVATOR" -> "电梯台账";
            case "PART" -> "配件";
            default -> sourceType;
        };
        LocalDate date = date(row, "biz_date");
        LocalDate metricDate = date(row, "metric_date");
        LocalDateTime eventTime = time(row, "event_time");
        if ("REPEAT".equals(metric) && metricDate == null && eventTime != null) {
            metricDate = eventTime.toLocalDate();
        }
        return new QualityDtos.DetailRow(
                metric, component, lng(row, "id"), str(row, "source_no"), sourceType, sourceTypeName,
                lng(row, "building_id"), str(row, "building_no"), str(row, "building_name"),
                lng(row, "elevator_id"), str(row, "elevator_no"),
                date, eventTime, status, statusName, metricDate == null ? null : metricDate.toString(),
                decimal(row, "quantity"), decimal(row, "unit_price"), decimal(row, "amount"),
                row.get("duration_minutes") == null ? null : lng(row, "duration_minutes"),
                lng(row, "counted") > 0, str(row, "include_reason"), str(row, "related_source_no"),
                str(row, "title"));
    }

    private Long drillTotal(QualityDtos.QualitySnapshot snapshot, String metric, String component, Long buildingId) {
        String normalizedMetric = normalizeMetric(metric);
        String normalizedComponent = normalizeComponent(normalizedMetric, component);
        if (buildingId != null) {
            String countComponent = "ALL".equals(normalizedComponent) ? "ALL" : normalizedComponent;
            if ("RISK".equals(normalizedMetric) && "ALL".equals(normalizedComponent)) {
                return snapshot.getBuildingRanks().stream()
                        .filter(r -> buildingId.equals(r.getBuildingId()))
                        .findFirst()
                        .map(r -> riskComponentCount(r, null))
                        .orElse(0L);
            }
            return repository.countByBuilding(
                    normalizedMetric,
                    countComponent,
                    snapshot.getFilter().getStartDate(),
                    snapshot.getFilter().getEndDate().plusDays(1),
                    snapshot.getFilter().getAsOfDate(),
                    buildingId,
                    snapshot.getFilter().getElevatorId());
        }
        String key = metric + ":" + componentKey(normalizedMetric, normalizedComponent);
        Long value = snapshot.getDetailCounts().get(key);
        if (value != null) return value;
        if ("RISK".equals(normalizedMetric)) return snapshot.getDetailCounts().get("RISK:ALL");
        return null;
    }

    private long riskComponentCount(QualityDtos.BuildingRank r, String component) {
        return switch (component == null ? "ALL" : component) {
            case "RISK_OVERDUE" -> r.getOverduePlanCount();
            case "RISK_OPEN" -> r.getOpenEventCount();
            case "RISK_REPEAT" -> r.getRepeatEventCount();
            case "RISK_INSPECTION" -> r.getInspectDue30dCount();
            default -> r.getOverduePlanCount() + r.getOpenEventCount() + r.getRepeatEventCount() + r.getInspectDue30dCount();
        };
    }

    private String sourceNoForRisk(QualityDtos.DetailRow row) {
        return row.getSourceNo() == null ? "" : row.getSourceNo();
    }

    private String normalizeMetric(String metric) {
        if (metric == null) throw new BizException("metric 不能为空");
        String upper = metric.toUpperCase();
        if (!List.of("PLAN", "DURATION", "REPEAT", "PART", "RISK").contains(upper)) {
            throw new BizException("不支持的指标钻取：" + metric);
        }
        return upper;
    }

    private String normalizeComponent(String metric, String component) {
        String c = component == null || component.isBlank() ? "ALL" : component.toUpperCase();
        return switch (metric) {
            case "PLAN" -> List.of("DENOMINATOR", "NUMERATOR", "ALL").contains(c) ? c : "ALL";
            case "DURATION" -> "COUNTED";
            case "REPEAT" -> List.of("DENOMINATOR", "NUMERATOR", "NON_REPEAT", "ALL").contains(c) ? c : "ALL";
            case "PART" -> "COUNTED";
            case "RISK" -> List.of("RISK_OVERDUE", "RISK_OPEN", "RISK_REPEAT", "RISK_INSPECTION", "ALL").contains(c) ? c : "ALL";
            default -> "ALL";
        };
    }

    private String componentKey(String metric, String component) {
        if ("PLAN".equals(metric) && "ALL".equals(component)) return "DENOMINATOR";
        if ("REPEAT".equals(metric) && "ALL".equals(component)) return "DENOMINATOR";
        if ("RISK".equals(metric) && "ALL".equals(component)) return "ALL";
        return component;
    }

    private QualityDtos.QualitySnapshot requireSnapshot(String id) {
        if (id == null || id.isBlank()) {
            throw new BizException("snapshotId 不能为空，请先应用筛选生成统计快照");
        }
        CacheEntry entry = snapshotCache.get(id);
        if (entry == null || !entry.valid()) {
            throw new BizException("统计快照已过期，请刷新页面后重新钻取或导出");
        }
        return entry.snapshot;
    }

    private void assertFresh(QualityDtos.QualitySnapshot snapshot) {
        String current = repository.currentVersion(LocalDateTime.now(ZoneId.of(TZ_SHANGHAI)));
        if (!Objects.equals(current, snapshot.getDataVersion())) {
            snapshotCache.remove(snapshot.getSnapshotId());
            throw new BizException("源数据已变化，当前统计快照失效，请刷新后再查看或导出");
        }
    }

    private void invalidate() {
        aggregateCache.clear();
        snapshotCache.clear();
    }

    private LocalDate bucketStart(LocalDate date, String granularity) {
        return switch (granularity) {
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> date.withDayOfMonth(1);
            default -> date;
        };
    }

    private Map<LocalDate, long[]> longMap(List<Map<String, Object>> rows, String dateKey, String[] keys) {
        Map<LocalDate, long[]> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate date = date(row, dateKey);
            if (date == null) continue;
            long[] values = new long[keys.length];
            for (int i = 0; i < keys.length; i++) values[i] = lng(row, keys[i]);
            map.put(date, values);
        }
        return map;
    }

    private Map<LocalDate, BigDecimal[]> partMap(List<Map<String, Object>> rows) {
        Map<LocalDate, BigDecimal[]> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate date = date(row, "bucket_date");
            if (date == null) continue;
            map.put(date, new BigDecimal[]{
                    BigDecimal.valueOf(lng(row, "part_count")),
                    BigDecimal.valueOf(lng(row, "part_quantity")),
                    decimal(row, "part_amount")});
        }
        return map;
    }

    private void add(long[] target, long[] source) {
        if (source == null) return;
        for (int i = 0; i < target.length; i++) target[i] += source[i];
    }

    private long sum(List<QualityDtos.TrendBucket> rows, java.util.function.ToLongFunction<QualityDtos.TrendBucket> getter) {
        return rows.stream().mapToLong(getter).sum();
    }

    private QualityDtos.MetricSummary metric(String key, String name, BigDecimal value, String display,
                                             String unit, Long numerator, Long denominator,
                                             BigDecimal amount, Long count) {
        return new QualityDtos.MetricSummary(key, name, value, display, unit,
                numerator == null ? 0 : numerator, denominator == null ? 0 : denominator, amount, count == null ? 0 : count);
    }

    private BigDecimal divide(long numerator, long denominator, int scale) {
        if (denominator == 0) return null;
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP);
    }

    private String percent(BigDecimal value) {
        return value == null ? "—" : value.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    private Long lng(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return 0L;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        return new BigDecimal(String.valueOf(value));
    }

    private String str(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private LocalDate date(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof java.util.Date d) return d.toInstant().atZone(ZoneId.of(TZ_SHANGHAI)).toLocalDate();
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    private LocalDateTime time(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (value instanceof LocalDateTime t) return t;
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    private void writeCsv(BufferedWriter out, Object... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.write(',');
            out.write(csv(values[i]));
        }
        out.newLine();
    }

    private String csv(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (text.startsWith("=") || text.startsWith("+") || text.startsWith("-") || text.startsWith("@")) {
            text = "'" + text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private record CacheKey(LocalDate start, LocalDate end, String granularity, Long buildingId,
                            Long elevatorId, String timezone) {
        private static final Object[] STRIPED_LOCKS = createLocks();

        static CacheKey of(QualityDtos.SnapshotRequest r) {
            return new CacheKey(r.getStartDate(), r.getEndDate(), r.getGranularity(),
                    r.getBuildingId(), r.getElevatorId(), r.getTimezone());
        }

        private static Object[] createLocks() {
            Object[] locks = new Object[256];
            for (int i = 0; i < locks.length; i++) {
                locks[i] = new Object();
            }
            return locks;
        }

        Object internLock() {
            return STRIPED_LOCKS[Math.floorMod(hashCode(), STRIPED_LOCKS.length)];
        }
    }

    private record CacheEntry(QualityDtos.QualitySnapshot snapshot, long expiresAt) {
        boolean valid() {
            return System.currentTimeMillis() <= expiresAt;
        }
    }
}
