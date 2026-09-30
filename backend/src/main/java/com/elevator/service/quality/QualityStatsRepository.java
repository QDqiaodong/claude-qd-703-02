package com.elevator.service.quality;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 质量分析的全部取数：只用数据库聚合 / 批量查询，不把全表装载进内存，也不按楼宇/电梯逐条查询。
 *
 * <p>一次快照固定执行约 9 条 SQL（4 条按日 GROUP BY + 4 条按楼宇 GROUP BY + 楼宇/名称查询），
 * 与楼宇数、电梯数无关。明细钻取走稳定排序 + keyset 游标分页。
 *
 * <p>日期口径：DATE 列直接与 {@code yyyy-MM-dd} 字面量比较；DATETIME 列与
 * {@code yyyy-MM-dd HH:mm:ss} 字面量比较，换算为左闭右开区间。字面量不带时区语义，
 * 写入即 Asia/Shanghai 墙上时间，杜绝浏览器时区 / DST 跨日偏差。
 */
@Repository
public class QualityStatsRepository {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int NAME_BATCH = 500;

    /** 重复事件窗口的秒数表示（30 天），用于 LAG 窗口函数比较；与 {@link QualityDict#REPEAT_WINDOW_DAYS} 一致 */
    private static final int REPEAT_GAP_SECONDS = QualityDict.REPEAT_WINDOW_DAYS * 86400;

    private final JdbcTemplate jdbc;

    public QualityStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // =====================================================================
    // 按日聚合（趋势 + 卡片同源；卡片由这些行在内存折叠，天然可核对）
    // =====================================================================

    /** 计划：到期非作废分母；按计划日完成分子（DONE 计划存在 DONE 工单且 actual_date = plan_date） */
    public List<DayPlan> planDaily(QualityQuery q) {
        Sql sql = new Sql();
        sql.add("""
                SELECT plan_date AS d,
                       COUNT(*) AS due,
                       SUM(CASE WHEN status = 'DONE' AND EXISTS (
                           SELECT 1 FROM maintenance_order o
                           WHERE o.plan_id = p.id AND o.status = 'DONE'
                             AND o.actual_date = p.plan_date
                       ) THEN 1 ELSE 0 END) AS ontime
                FROM maintenance_plan p
                WHERE p.status <> 'VOID' AND p.plan_date >= ? AND p.plan_date < ?
                """);
        appendScope(sql, q, "p");
        sql.add(" GROUP BY p.plan_date");
        bindWindow(sql, q, false);
        return jdbc.query(sql.text(), (rs, n) -> new DayPlan(
                rs.getDate("d").toLocalDate(),
                rs.getLong("due"),
                rs.getLong("ontime")), sql.args.toArray());
    }

    /** 维保记录：有实际完成日（且有计划日、非作废/撤销）的记录数与耗时天数合计 */
    public List<DayOrder> orderDaily(QualityQuery q) {
        Sql sql = new Sql();
        sql.add("""
                SELECT actual_date AS d,
                       COUNT(*) AS cnt,
                       COALESCE(SUM(DATEDIFF(actual_date, plan_date)), 0) AS days
                FROM maintenance_order
                WHERE actual_date IS NOT NULL
                  AND plan_date IS NOT NULL
                  AND status NOT IN ('VOID', 'CANCELLED')
                  AND actual_date >= ? AND actual_date < ?
                """);
        appendScope(sql, q, "maintenance_order");
        sql.add(" GROUP BY actual_date");
        bindWindow(sql, q, false);
        return jdbc.query(sql.text(), (rs, n) -> new DayOrder(
                rs.getDate("d").toLocalDate(),
                rs.getLong("cnt"),
                rs.getLong("days")), sql.args.toArray());
    }

    /**
     * 事件：非撤销事件总数 + 同电梯同类型 30 天内（含同日按 id 排序）再现的重复数。
     *
     * <p>重复标记用 LAG 窗口函数一次性算出（紧邻前序同类型事件在 30 天内 ⇔ 30 天内存在前序），
     * 避免按行相关 EXISTS 在 10 万级数据上的 N×M 开销；与原 EXISTS 语义经全量比对零差异。
     * 内层窗口扫描时放宽 30 天下界（lookback），保证窗口首日的重复判定能看到窗外前序事件。
     */
    public List<DayFault> faultDaily(QualityQuery q) {
        Sql sql = new Sql();
        sql.add("""
                SELECT DATE(f.report_time) AS d, COUNT(*) AS cnt, COALESCE(SUM(f.is_repeat), 0) AS rpt
                FROM (
                    SELECT x.id, x.elevator_id, x.building_id, x.report_time,
                           (COALESCE(TIMESTAMPDIFF(SECOND,
                                LAG(x.report_time) OVER w_fault, x.report_time),
                                %d) < %d) AS is_repeat
                    FROM fault_report x
                    WHERE x.status <> 'CANCELLED'
                      AND x.report_time >= ? AND x.report_time < ?
                    WINDOW w_fault AS (
                        PARTITION BY x.elevator_id, x.fault_type ORDER BY x.report_time, x.id)
                ) f
                WHERE f.report_time >= ? AND f.report_time < ?
                """.formatted(REPEAT_GAP_SECONDS + 1, REPEAT_GAP_SECONDS));
        appendScope(sql, q, "f");
        sql.add(" GROUP BY DATE(f.report_time)");
        // 内层下界放宽 30 天（窗外前序事件决定首日重复性），上界不变；外层再收回到真实窗口
        sql.arg(q.start().minusDays(QualityDict.REPEAT_WINDOW_DAYS).atStartOfDay().format(DT_FMT));
        sql.arg(q.end().plusDays(1).atStartOfDay().format(DT_FMT));
        sql.arg(q.start().atStartOfDay().format(DT_FMT));
        sql.arg(q.end().plusDays(1).atStartOfDay().format(DT_FMT));
        appendScopeArgs(sql, q);
        return jdbc.query(sql.text(), (rs, n) -> new DayFault(
                rs.getDate("d").toLocalDate(),
                rs.getLong("cnt"),
                rs.getLong("rpt")), sql.args.toArray());
    }

    /** 配件：已登记记录按更换日汇总数量与 数量×单价 */
    public List<DayPart> partDaily(QualityQuery q) {
        Sql sql = new Sql();
        sql.add("""
                SELECT replace_date AS d,
                       COUNT(*) AS cnt,
                       COALESCE(SUM(quantity), 0) AS qty,
                       COALESCE(SUM(quantity * unit_price), 0) AS amt
                FROM part_replacement
                WHERE status = 'NORMAL' AND replace_date IS NOT NULL
                  AND replace_date >= ? AND replace_date < ?
                """);
        appendScope(sql, q, "part_replacement");
        sql.add(" GROUP BY replace_date");
        bindWindow(sql, q, false);
        return jdbc.query(sql.text(), (rs, n) -> new DayPart(
                rs.getDate("d").toLocalDate(),
                rs.getLong("cnt"),
                rs.getLong("qty"),
                rs.getBigDecimal("amt")), sql.args.toArray());
    }

    // =====================================================================
    // 楼宇风险分量（每条一次 GROUP BY，不按楼宇循环）
    // =====================================================================

    /** 逾期计划：plan_date < today，状态 PENDING/DELAYED/SUSPENDED */
    public Map<Long, Long> riskOverduePlans(QualityQuery q, LocalDate today) {
        Sql sql = new Sql();
        sql.add("SELECT building_id AS b, COUNT(*) AS c FROM maintenance_plan")
                .add(" WHERE plan_date < ? AND status IN ('PENDING', 'DELAYED', 'SUSPENDED')");
        appendScope(sql, q, "maintenance_plan");
        sql.add(" GROUP BY building_id");
        sql.arg(today.toString());
        appendScopeArgs(sql, q);
        return groupByBuilding(sql);
    }

    /** 未完成事件：status NOT IN ('FIXED','CANCELLED') */
    public Map<Long, Long> riskOpenEvents(QualityQuery q) {
        Sql sql = new Sql();
        sql.add("SELECT building_id AS b, COUNT(*) AS c FROM fault_report")
                .add(" WHERE status NOT IN ('FIXED', 'CANCELLED')");
        appendScope(sql, q, "fault_report");
        sql.add(" GROUP BY building_id");
        appendScopeArgs(sql, q);
        return groupByBuilding(sql);
    }

    /** 近 30 个自然日（含今日）内的重复事件（重复判定仍为 30 天内再现；LAG 窗口一次算出） */
    public Map<Long, Long> riskRepeatEvents(QualityQuery q, LocalDate today) {
        Sql sql = new Sql();
        sql.add("""
                SELECT f.building_id AS b, COUNT(*) AS c FROM (
                    SELECT x.id, x.elevator_id, x.building_id, x.report_time,
                           (COALESCE(TIMESTAMPDIFF(SECOND,
                                LAG(x.report_time) OVER w_fault, x.report_time),
                                %d) < %d) AS is_repeat
                    FROM fault_report x
                    WHERE x.status <> 'CANCELLED'
                      AND x.report_time >= ? AND x.report_time < ?
                    WINDOW w_fault AS (
                        PARTITION BY x.elevator_id, x.fault_type ORDER BY x.report_time, x.id)
                ) f
                WHERE f.report_time >= ? AND f.report_time < ? AND f.is_repeat = 1
                """.formatted(REPEAT_GAP_SECONDS + 1, REPEAT_GAP_SECONDS));
        appendScope(sql, q, "f");
        sql.add(" GROUP BY f.building_id");
        sql.arg(today.minusDays(QualityDict.RISK_RECENT_DAYS - 1L + QualityDict.REPEAT_WINDOW_DAYS)
                .atStartOfDay().format(DT_FMT));
        sql.arg(today.plusDays(1).atStartOfDay().format(DT_FMT));
        sql.arg(today.minusDays(QualityDict.RISK_RECENT_DAYS - 1L).atStartOfDay().format(DT_FMT));
        sql.arg(today.plusDays(1).atStartOfDay().format(DT_FMT));
        appendScopeArgs(sql, q);
        return groupByBuilding(sql);
    }

    /** 未来 30 天检验到期电梯（含今日，左闭右开） */
    public Map<Long, Long> riskInspectDue(QualityQuery q, LocalDate today) {
        Sql sql = new Sql();
        sql.add("SELECT building_id AS b, COUNT(*) AS c FROM elevator")
                .add(" WHERE next_inspect_date IS NOT NULL AND next_inspect_date >= ? AND next_inspect_date < ?");
        appendElevatorScope(sql, q);
        sql.add(" GROUP BY building_id");
        sql.arg(today.toString());
        sql.arg(today.plusDays(QualityDict.INSPECT_WINDOW_DAYS).toString());
        appendElevatorScopeArgs(sql, q);
        return groupByBuilding(sql);
    }

    /** 筛选范围内每栋楼的电梯数（含停用梯，存量口径） */
    public Map<Long, Long> elevatorCounts(QualityQuery q) {
        Sql sql = new Sql();
        sql.add("SELECT building_id AS b, COUNT(*) AS c FROM elevator WHERE 1 = 1");
        appendElevatorScope(sql, q);
        sql.add(" GROUP BY building_id");
        appendElevatorScopeArgs(sql, q);
        return groupByBuilding(sql);
    }

    /** 全部楼宇（含已撤场），编号升序 */
    public List<BuildingRow> buildings() {
        return jdbc.query(
                "SELECT id, building_no, name, status FROM building ORDER BY building_no ASC, id ASC",
                (rs, n) -> new BuildingRow(rs.getLong("id"), rs.getString("building_no"),
                        rs.getString("name"), rs.getString("status")));
    }

    /** 全部电梯编号（导出全量明细时一次取齐，替代逐页 IN 查询） */
    public Map<Long, String> allElevatorNos() {
        Map<Long, String> map = new HashMap<>();
        jdbc.query("SELECT id, elevator_no FROM elevator",
                (org.springframework.jdbc.core.RowCallbackHandler) rs
                        -> map.put(rs.getLong(1), rs.getString(2)));
        return map;
    }

    /** 电梯编号批量解析（IN 查询，分页明细补名称用） */
    public Map<Long, String> elevatorNos(Collection<Long> ids) {
        Map<Long, String> map = new HashMap<>();
        for (List<Long> chunk : chunks(ids)) {
            String in = String.join(",", chunk.stream().map(x -> "?").toList());
            jdbc.query("SELECT id, elevator_no FROM elevator WHERE id IN (" + in + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs
                            -> map.put(rs.getLong(1), rs.getString(2)), chunk.toArray());
        }
        return map;
    }

    /** 全部楼宇行（导出全量明细时一次取齐） */
    public Map<Long, BuildingRow> allBuildingRows() {
        Map<Long, BuildingRow> map = new HashMap<>();
        for (BuildingRow b : buildings()) {
            map.put(b.id(), b);
        }
        return map;
    }

    /** 楼宇行批量解析 */
    public Map<Long, BuildingRow> buildingRows(Collection<Long> ids) {
        Map<Long, BuildingRow> map = new HashMap<>();
        for (List<Long> chunk : chunks(ids)) {
            String in = String.join(",", chunk.stream().map(x -> "?").toList());
            jdbc.query("SELECT id, building_no, name, status FROM building WHERE id IN (" + in + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> map.put(rs.getLong("id"),
                            new BuildingRow(rs.getLong("id"), rs.getString("building_no"),
                                    rs.getString("name"), rs.getString("status"))),
                    chunk.toArray());
        }
        return map;
    }

    // =====================================================================
    // 明细分页：稳定排序 + keyset 游标
    // =====================================================================

    /**
     * 钻取明细。
     *
     * @param metric 指标 key（含 4 个风险分量 key）
     * @param scope  NUMERATOR / DENOMINATOR（仅率类指标有意义；默认分母）
     */
    public DetailQueryResult details(QualityQuery q, String metric, String scope,
                                     String cursor, int size, LocalDate today) {
        return switch (metric) {
            case QualityDict.M_PLAN_ON_TIME -> planDetails(q, "NUMERATOR".equals(scope), cursor, size);
            case QualityDict.M_ORDER_DURATION -> orderDetails(q, cursor, size);
            case QualityDict.M_EVENT_REPEAT -> faultDetails(q, "NUMERATOR".equals(scope), cursor, size);
            case QualityDict.M_PART_COST -> partDetails(q, cursor, size);
            case QualityDict.M_RISK_OVERDUE_PLAN -> riskPlanDetails(q, cursor, size, today);
            case QualityDict.M_RISK_OPEN_EVENT -> riskFaultDetails(q, cursor, size, today, false);
            case QualityDict.M_RISK_REPEAT_EVENT -> riskFaultDetails(q, cursor, size, today, true);
            case QualityDict.M_RISK_INSPECT -> inspectDetails(q, cursor, size, today);
            default -> throw new IllegalArgumentException("未知的指标：" + metric);
        };
    }

    private DetailQueryResult planDetails(QualityQuery q, boolean numerator, String cursor, int size) {
        Sql sql = new Sql();
        sql.add("""
                SELECT p.id, p.plan_no, p.elevator_id, p.building_id, p.plan_date, p.status,
                       (SELECT o.order_no FROM maintenance_order o
                         WHERE o.plan_id = p.id AND o.status = 'DONE' AND o.actual_date = p.plan_date
                         ORDER BY o.id LIMIT 1) AS on_time_order_no,
                       (SELECT o.actual_date FROM maintenance_order o
                         WHERE o.plan_id = p.id AND o.status = 'DONE'
                         ORDER BY o.actual_date DESC, o.id DESC LIMIT 1) AS last_actual_date
                FROM maintenance_plan p
                WHERE p.status <> 'VOID' AND p.plan_date >= ? AND p.plan_date < ?
                """);
        appendScope(sql, q, "p");
        if (numerator) {
            sql.add("""
                    AND p.status = 'DONE' AND EXISTS (
                        SELECT 1 FROM maintenance_order o
                        WHERE o.plan_id = p.id AND o.status = 'DONE' AND o.actual_date = p.plan_date)
                    """);
        }
        appendDescKeyset(sql, cursor, "p.plan_date", "p.id");
        sql.add(" ORDER BY p.plan_date DESC, p.id DESC LIMIT ?");

        bindWindow(sql, q, false);
        if (numerator) {
            // numerator 分支不增加绑定参数（EXISTS 无参），游标参数紧随窗口参数
        }
        bindDescCursor(sql, cursor);
        sql.arg(size + 1L);

        List<RawRow> rows = new ArrayList<>();
        jdbc.query(sql.text(), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            LocalDate planDate = rs.getDate("plan_date").toLocalDate();
            String onTimeOrderNo = rs.getString("on_time_order_no");
            java.sql.Date lastActual = rs.getDate("last_actual_date");
            boolean onTime = onTimeOrderNo != null;
            String status = rs.getString("status");
            String reason;
            if (onTime) {
                reason = "关联工单 " + onTimeOrderNo + " 实际完成日 = 计划日 " + planDate
                        + "，计为准时（计入分子与分母）";
            } else if ("DONE".equals(status)) {
                reason = "计划已执行但实际完成日" + (lastActual == null ? "缺失" : "为 " + lastActual.toLocalDate())
                        + "，未落在计划日，计入分母、不计入分子";
            } else {
                reason = "计划日 " + planDate + " 到期且未作废，状态 " + status
                        + "，计入分母、不计入分子";
            }
            rows.add(RawRow.of(rs.getLong("p.id"), planDate.toString(),
                    new QualityViews.DetailRow(rs.getLong("p.id"), "PLAN", rs.getString("p.plan_no"),
                            rs.getLong("p.elevator_id"), null,
                            rs.getLong("p.building_id"), null, null, planDate,
                            1L, onTime ? 1L : 0L, null, null, null, onTime, reason)));
        }, sql.args.toArray());
        return paginate(rows, size);
    }

    private DetailQueryResult orderDetails(QualityQuery q, String cursor, int size) {
        Sql sql = new Sql();
        sql.add("""
                SELECT id, order_no, elevator_id, building_id, plan_date, actual_date,
                       status, DATEDIFF(actual_date, plan_date) AS dur
                FROM maintenance_order
                WHERE actual_date IS NOT NULL AND plan_date IS NOT NULL
                  AND status NOT IN ('VOID', 'CANCELLED')
                  AND actual_date >= ? AND actual_date < ?
                """);
        appendScope(sql, q, "maintenance_order");
        appendDescKeyset(sql, cursor, "actual_date", "id");
        sql.add(" ORDER BY actual_date DESC, id DESC LIMIT ?");
        bindWindow(sql, q, false);
        bindDescCursor(sql, cursor);
        sql.arg(size + 1L);

        List<RawRow> rows = new ArrayList<>();
        jdbc.query(sql.text(), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            long dur = rs.getLong("dur");
            LocalDate actual = rs.getDate("actual_date").toLocalDate();
            String reason = "实际完成日 " + actual + " − 计划日 " + rs.getDate("plan_date").toLocalDate()
                    + " = " + dur + " 天，计入平均执行耗时";
            rows.add(RawRow.of(rs.getLong("id"), actual.toString(),
                    new QualityViews.DetailRow(rs.getLong("id"), "MAINTENANCE_ORDER",
                            rs.getString("order_no"), rs.getLong("elevator_id"), null,
                            rs.getLong("building_id"), null, null, actual,
                            1L, dur, null, BigDecimal.valueOf(dur), null,
                            true, reason)));
        }, sql.args.toArray());
        return paginate(rows, size);
    }

    private DetailQueryResult faultDetails(QualityQuery q, boolean numerator, String cursor, int size) {
        Sql sql = new Sql();
        sql.add("""
                SELECT f.id, f.fault_no, f.elevator_id, f.building_id, f.fault_type,
                       f.report_time, f.status, f.is_repeat, f.prior_no
                FROM (
                    SELECT x.id, x.fault_no, x.elevator_id, x.building_id, x.fault_type,
                           x.report_time, x.status,
                           (COALESCE(TIMESTAMPDIFF(SECOND,
                                LAG(x.report_time) OVER w_fault, x.report_time),
                                %d) < %d) AS is_repeat,
                           LAG(x.fault_no)  OVER w_fault AS prior_no
                    FROM fault_report x
                    WHERE x.status <> 'CANCELLED'
                      AND x.report_time >= ? AND x.report_time < ?
                    WINDOW w_fault AS (
                        PARTITION BY x.elevator_id, x.fault_type ORDER BY x.report_time, x.id)
                ) f
                WHERE f.report_time >= ? AND f.report_time < ?
                """.formatted(REPEAT_GAP_SECONDS + 1, REPEAT_GAP_SECONDS));
        appendScope(sql, q, "f");
        if (numerator) {
            sql.add(" AND f.is_repeat = 1");
        }
        appendDescKeyset(sql, cursor, "f.report_time", "f.id");
        sql.add(" ORDER BY f.report_time DESC, f.id DESC LIMIT ?");
        // 内层扫描前多取 30 天的前序事件用于 LAG，外层再收回到真实窗口
        sql.arg(q.start().minusDays(QualityDict.REPEAT_WINDOW_DAYS).atStartOfDay().format(DT_FMT));
        sql.arg(q.end().plusDays(1).atStartOfDay().format(DT_FMT));
        sql.arg(q.start().atStartOfDay().format(DT_FMT));
        sql.arg(q.end().plusDays(1).atStartOfDay().format(DT_FMT));
        appendScopeArgs(sql, q);
        bindDescCursor(sql, cursor);
        sql.arg(size + 1L);

        List<RawRow> rows = new ArrayList<>();
        jdbc.query(sql.text(), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            boolean repeat = rs.getBoolean("is_repeat");
            String type = rs.getString("fault_type");
            // 直接取墙上时间字符串，不经 JVM 时区转换：游标键与 SQL 绑定字面量严格一致
            String rawTime = rs.getString("report_time");
            String cursorTime = rawTime.replace(" ", "T");
            String priorNo = rs.getString("prior_no");
            String reason = repeat
                    ? "同电梯同类型「" + type + "」事件 " + priorNo + " 在 30 天内已出现，计为重复事件（计入分子与分母）"
                    : "30 天内无同类型事件，计入分母、不计入分子";
            LocalDate day = LocalDate.parse(rawTime.substring(0, 10));
            rows.add(RawRow.of(rs.getLong("f.id"), cursorTime,
                    new QualityViews.DetailRow(rs.getLong("f.id"), "FAULT",
                            rs.getString("f.fault_no"), rs.getLong("f.elevator_id"), null,
                            rs.getLong("f.building_id"), null, null, day,
                            1L, repeat ? 1L : 0L, null, null, null,
                            repeat, reason)));
        }, sql.args.toArray());
        return paginate(rows, size);
    }

    private DetailQueryResult partDetails(QualityQuery q, String cursor, int size) {
        Sql sql = new Sql();
        sql.add("""
                SELECT id, ref_no, elevator_id, building_id, replace_date,
                       quantity, unit_price, quantity * unit_price AS amt
                FROM part_replacement
                WHERE status = 'NORMAL' AND replace_date IS NOT NULL
                  AND replace_date >= ? AND replace_date < ?
                """);
        appendScope(sql, q, "part_replacement");
        appendDescKeyset(sql, cursor, "replace_date", "id");
        sql.add(" ORDER BY replace_date DESC, id DESC LIMIT ?");
        bindWindow(sql, q, false);
        bindDescCursor(sql, cursor);
        sql.arg(size + 1L);

        List<RawRow> rows = new ArrayList<>();
        jdbc.query(sql.text(), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            long qty = rs.getLong("quantity");
            BigDecimal price = rs.getBigDecimal("unit_price");
            BigDecimal amt = rs.getBigDecimal("amt");
            LocalDate day = rs.getDate("replace_date").toLocalDate();
            String sourceNo = rs.getString("ref_no");
            String reason = "数量 " + qty + " × 单价 " + price.toPlainString() + " = " + amt.toPlainString()
                    + "，计入配件成本";
            rows.add(RawRow.of(rs.getLong("id"), day.toString(),
                    new QualityViews.DetailRow(rs.getLong("id"), "PART", sourceNo,
                            rs.getLong("elevator_id"), null,
                            rs.getLong("building_id"), null, null, day,
                            qty, null, amt, price, null,
                            true, reason)));
        }, sql.args.toArray());
        return paginate(rows, size);
    }

    private DetailQueryResult riskPlanDetails(QualityQuery q, String cursor, int size, LocalDate today) {
        Sql sql = new Sql();
        sql.add("""
                SELECT id, plan_no, elevator_id, building_id, plan_date, status,
                       DATEDIFF(?, plan_date) AS overdue_days
                FROM maintenance_plan
                WHERE plan_date < ? AND status IN ('PENDING', 'DELAYED', 'SUSPENDED')
                """);
        appendScope(sql, q, "maintenance_plan");
        appendDescKeyset(sql, cursor, "plan_date", "id");
        sql.add(" ORDER BY plan_date DESC, id DESC LIMIT ?");
        sql.arg(today.toString());
        sql.arg(today.toString());
        appendScopeArgs(sql, q);
        bindDescCursor(sql, cursor);
        sql.arg(size + 1L);

        List<RawRow> rows = new ArrayList<>();
        jdbc.query(sql.text(), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            LocalDate day = rs.getDate("plan_date").toLocalDate();
            String reason = "计划日 " + day + " 已逾期 " + rs.getLong("overdue_days")
                    + " 天且未闭环（" + rs.getString("status") + "），计入风险分量";
            rows.add(RawRow.of(rs.getLong("id"), day.toString(),
                    new QualityViews.DetailRow(rs.getLong("id"), "PLAN", rs.getString("plan_no"),
                            rs.getLong("elevator_id"), null,
                            rs.getLong("building_id"), null, null, day,
                            1L, rs.getLong("overdue_days"), null, null, null, true, reason)));
        }, sql.args.toArray());
        return paginate(rows, size);
    }

    private DetailQueryResult riskFaultDetails(QualityQuery q, String cursor, int size,
                                               LocalDate today, boolean repeatOnly) {
        Sql sql = new Sql();
        if (repeatOnly) {
            sql.add("""
                    SELECT f.id, f.fault_no, f.elevator_id, f.building_id, f.fault_type,
                           f.report_time, f.status, f.is_repeat
                    FROM (
                        SELECT x.id, x.fault_no, x.elevator_id, x.building_id, x.fault_type,
                               x.report_time, x.status,
                               (COALESCE(TIMESTAMPDIFF(SECOND,
                                    LAG(x.report_time) OVER w_fault, x.report_time),
                                    %d) < %d) AS is_repeat
                        FROM fault_report x
                        WHERE x.status <> 'CANCELLED' AND x.report_time >= ? AND x.report_time < ?
                        WINDOW w_fault AS (
                            PARTITION BY x.elevator_id, x.fault_type ORDER BY x.report_time, x.id)
                    ) f
                    WHERE f.report_time >= ? AND f.report_time < ? AND f.is_repeat = 1
                    """.formatted(REPEAT_GAP_SECONDS + 1, REPEAT_GAP_SECONDS));
        } else {
            sql.add("""
                    SELECT f.id, f.fault_no, f.elevator_id, f.building_id, f.fault_type,
                           f.report_time, f.status, 0 AS is_repeat
                    FROM fault_report f
                    WHERE f.status NOT IN ('FIXED', 'CANCELLED')
                    """);
        }
        appendScope(sql, q, "f");
        appendDescKeyset(sql, cursor, "f.report_time", "f.id");
        sql.add(" ORDER BY f.report_time DESC, f.id DESC LIMIT ?");
        if (repeatOnly) {
            sql.arg(today.minusDays(QualityDict.RISK_RECENT_DAYS - 1L + QualityDict.REPEAT_WINDOW_DAYS)
                    .atStartOfDay().format(DT_FMT));
            sql.arg(today.plusDays(1).atStartOfDay().format(DT_FMT));
            sql.arg(today.minusDays(QualityDict.RISK_RECENT_DAYS - 1L).atStartOfDay().format(DT_FMT));
            sql.arg(today.plusDays(1).atStartOfDay().format(DT_FMT));
        }
        appendScopeArgs(sql, q);
        bindDescCursor(sql, cursor);
        sql.arg(size + 1L);

        List<RawRow> rows = new ArrayList<>();
        jdbc.query(sql.text(), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            boolean repeat = rs.getBoolean("is_repeat");
            String rawTime = rs.getString("report_time");
            String cursorTime = rawTime.replace(" ", "T");
            String reason = repeatOnly
                    ? "近 30 个自然日内发生且 30 天内存在同类型事件，计入风险分量"
                    : "事件状态 " + rs.getString("f.status") + " 未闭环，计入未完成事件分量";
            LocalDate day = LocalDate.parse(rawTime.substring(0, 10));
            rows.add(RawRow.of(rs.getLong("f.id"), cursorTime,
                    new QualityViews.DetailRow(rs.getLong("f.id"), "FAULT", rs.getString("f.fault_no"),
                            rs.getLong("f.elevator_id"), null,
                            rs.getLong("f.building_id"), null, null, day,
                            1L, repeat ? 1L : 0L, null, null, null, repeat, reason)));
        }, sql.args.toArray());
        return paginate(rows, size);
    }

    private DetailQueryResult inspectDetails(QualityQuery q, String cursor, int size, LocalDate today) {
        Sql sql = new Sql();
        sql.add("""
                SELECT id, elevator_no, building_id, next_inspect_date,
                       DATEDIFF(next_inspect_date, ?) AS due_days
                FROM elevator
                WHERE next_inspect_date IS NOT NULL AND next_inspect_date >= ? AND next_inspect_date < ?
                """);
        appendElevatorScope(sql, q);
        if (cursor != null && !cursor.isBlank()) {
            sql.add(" AND (next_inspect_date > ? OR (next_inspect_date = ? AND id > ?))");
        }
        sql.add(" ORDER BY next_inspect_date ASC, id ASC LIMIT ?");
        sql.arg(today.toString());
        sql.arg(today.toString());
        sql.arg(today.plusDays(QualityDict.INSPECT_WINDOW_DAYS).toString());
        appendElevatorScopeArgs(sql, q);
        if (cursor != null && !cursor.isBlank()) {
            String[] c = decodeCursor(cursor);
            sql.arg(c[0]);
            sql.arg(c[0]);
            sql.arg(Long.parseLong(c[1]));
        }
        sql.arg(size + 1L);

        List<RawRow> rows = new ArrayList<>();
        jdbc.query(sql.text(), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            long dueDays = rs.getLong("due_days");
            LocalDate day = rs.getDate("next_inspect_date").toLocalDate();
            String no = rs.getString("elevator_no");
            String reason = "下次检验日 " + day + "，距今 " + dueDays
                    + " 天（0~30 天窗口内），计入风险分量";
            rows.add(RawRow.of(rs.getLong("id"), day.toString(),
                    new QualityViews.DetailRow(rs.getLong("id"), "ELEVATOR", no,
                            rs.getLong("id"), no,
                            rs.getLong("building_id"), null, null, day,
                            1L, dueDays, null, null, null, true, reason)));
        }, sql.args.toArray());
        return paginate(rows, size);
    }

    /**
     * 明细总数：与 {@link #details} 完全相同的 WHERE（无游标、无 LIMIT）。
     * 率类指标按 scope 区分分子/分母，分页 total 可直接与快照卡片计数核对。
     */
    public long countDetails(QualityQuery q, String metric, String scope, LocalDate today) {
        boolean numerator = "NUMERATOR".equals(scope);
        return switch (metric) {
            case QualityDict.M_PLAN_ON_TIME -> {
                Sql sql = new Sql();
                sql.add("SELECT COUNT(*) FROM maintenance_plan p WHERE p.status <> 'VOID'")
                        .add(" AND p.plan_date >= ? AND p.plan_date < ?");
                appendScope(sql, q, "p");
                if (numerator) {
                    sql.add("""
                            AND p.status = 'DONE' AND EXISTS (
                                SELECT 1 FROM maintenance_order o
                                WHERE o.plan_id = p.id AND o.status = 'DONE' AND o.actual_date = p.plan_date)
                            """);
                }
                bindWindow(sql, q, false);
                yield count(sql);
            }
            case QualityDict.M_ORDER_DURATION -> {
                Sql sql = new Sql();
                sql.add("""
                        SELECT COUNT(*) FROM maintenance_order
                        WHERE actual_date IS NOT NULL AND plan_date IS NOT NULL
                          AND status NOT IN ('VOID', 'CANCELLED')
                          AND actual_date >= ? AND actual_date < ?
                        """);
                appendScope(sql, q, "maintenance_order");
                bindWindow(sql, q, false);
                yield count(sql);
            }
            case QualityDict.M_EVENT_REPEAT -> {
                Sql sql = new Sql();
                if (numerator) {
                    sql.add("""
                            SELECT COUNT(*) FROM (
                                SELECT x.id, x.elevator_id, x.building_id, x.report_time,
                                       (COALESCE(TIMESTAMPDIFF(SECOND,
                                            LAG(x.report_time) OVER w_fault, x.report_time),
                                            %d) < %d) AS is_repeat
                                FROM fault_report x
                                WHERE x.status <> 'CANCELLED'
                                  AND x.report_time >= ? AND x.report_time < ?
                                WINDOW w_fault AS (
                                    PARTITION BY x.elevator_id, x.fault_type ORDER BY x.report_time, x.id)
                            ) f
                            WHERE f.is_repeat = 1 AND f.report_time >= ? AND f.report_time < ?
                            """.formatted(REPEAT_GAP_SECONDS + 1, REPEAT_GAP_SECONDS));
                    appendScope(sql, q, "f");
                    sql.arg(q.start().minusDays(QualityDict.REPEAT_WINDOW_DAYS).atStartOfDay().format(DT_FMT));
                    sql.arg(q.end().plusDays(1).atStartOfDay().format(DT_FMT));
                    sql.arg(q.start().atStartOfDay().format(DT_FMT));
                    sql.arg(q.end().plusDays(1).atStartOfDay().format(DT_FMT));
                    appendScopeArgs(sql, q);
                } else {
                    sql.add("SELECT COUNT(*) FROM fault_report f WHERE f.status <> 'CANCELLED'")
                            .add(" AND f.report_time >= ? AND f.report_time < ?");
                    appendScope(sql, q, "f");
                    bindWindow(sql, q, true);
                }
                yield count(sql);
            }
            case QualityDict.M_PART_COST -> {
                Sql sql = new Sql();
                sql.add("""
                        SELECT COUNT(*) FROM part_replacement
                        WHERE status = 'NORMAL' AND replace_date IS NOT NULL
                          AND replace_date >= ? AND replace_date < ?
                        """);
                appendScope(sql, q, "part_replacement");
                bindWindow(sql, q, false);
                yield count(sql);
            }
            case QualityDict.M_RISK_OVERDUE_PLAN -> {
                Sql sql = new Sql();
                sql.add("SELECT COUNT(*) FROM maintenance_plan")
                        .add(" WHERE plan_date < ? AND status IN ('PENDING', 'DELAYED', 'SUSPENDED')");
                appendScope(sql, q, "maintenance_plan");
                sql.arg(today.toString());
                appendScopeArgs(sql, q);
                yield count(sql);
            }
            case QualityDict.M_RISK_OPEN_EVENT -> {
                Sql sql = new Sql();
                sql.add("SELECT COUNT(*) FROM fault_report f")
                        .add(" WHERE f.status NOT IN ('FIXED', 'CANCELLED')");
                appendScope(sql, q, "f");
                appendScopeArgs(sql, q);
                yield count(sql);
            }
            case QualityDict.M_RISK_REPEAT_EVENT -> {
                Sql sql = new Sql();
                sql.add("""
                        SELECT COUNT(*) FROM (
                            SELECT x.id, x.elevator_id, x.building_id,
                                   (COALESCE(TIMESTAMPDIFF(SECOND,
                                        LAG(x.report_time) OVER w_fault, x.report_time),
                                        %d) < %d) AS is_repeat,
                                   x.report_time
                            FROM fault_report x
                            WHERE x.status <> 'CANCELLED' AND x.report_time >= ? AND x.report_time < ?
                            WINDOW w_fault AS (
                                PARTITION BY x.elevator_id, x.fault_type ORDER BY x.report_time, x.id)
                        ) f
                        WHERE f.report_time >= ? AND f.report_time < ? AND f.is_repeat = 1
                        """.formatted(REPEAT_GAP_SECONDS + 1, REPEAT_GAP_SECONDS));
                appendScope(sql, q, "f");
                sql.arg(today.minusDays(QualityDict.RISK_RECENT_DAYS - 1L + QualityDict.REPEAT_WINDOW_DAYS)
                        .atStartOfDay().format(DT_FMT));
                sql.arg(today.plusDays(1).atStartOfDay().format(DT_FMT));
                sql.arg(today.minusDays(QualityDict.RISK_RECENT_DAYS - 1L).atStartOfDay().format(DT_FMT));
                sql.arg(today.plusDays(1).atStartOfDay().format(DT_FMT));
                appendScopeArgs(sql, q);
                yield count(sql);
            }
            case QualityDict.M_RISK_INSPECT -> {
                Sql sql = new Sql();
                sql.add("""
                        SELECT COUNT(*) FROM elevator
                        WHERE next_inspect_date IS NOT NULL AND next_inspect_date >= ? AND next_inspect_date < ?
                        """);
                appendElevatorScope(sql, q);
                sql.arg(today.toString());
                sql.arg(today.plusDays(QualityDict.INSPECT_WINDOW_DAYS).toString());
                appendElevatorScopeArgs(sql, q);
                yield count(sql);
            }
            default -> throw new IllegalArgumentException("未知的指标：" + metric);
        };
    }

    private Long count(Sql sql) {
        Long v = jdbc.queryForObject(sql.text(), Long.class, sql.args.toArray());
        return v == null ? 0L : v;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    /** 普通业务表（building_id / elevator_id 列）的范围条件 */
    private static void appendScope(Sql sql, QualityQuery q, String alias) {
        String p = alias == null || alias.isBlank() ? "" : alias + ".";
        if (q.hasBuilding()) {
            sql.add(" AND " + p + "building_id = ?");
        }
        if (q.hasElevator()) {
            sql.add(" AND " + p + "elevator_id = ?");
        }
    }

    private static void appendScopeArgs(Sql sql, QualityQuery q) {
        if (q.hasBuilding()) {
            sql.arg(q.buildingId());
        }
        if (q.hasElevator()) {
            sql.arg(q.elevatorId());
        }
    }

    /** elevator 表自身：电梯过滤列是 id */
    private static void appendElevatorScope(Sql sql, QualityQuery q) {
        if (q.hasBuilding()) {
            sql.add(" AND building_id = ?");
        }
        if (q.hasElevator()) {
            sql.add(" AND id = ?");
        }
    }

    private static void appendElevatorScopeArgs(Sql sql, QualityQuery q) {
        if (q.hasBuilding()) {
            sql.arg(q.buildingId());
        }
        if (q.hasElevator()) {
            sql.arg(q.elevatorId());
        }
    }

    /** 窗口参数（开始包含、结束包含 → [start, end+1)） */
    private void bindWindow(Sql sql, QualityQuery q, boolean datetime) {
        if (datetime) {
            sql.arg(q.start().atStartOfDay().format(DT_FMT));
            sql.arg(q.end().plusDays(1).atStartOfDay().format(DT_FMT));
        } else {
            sql.arg(q.start().toString());
            sql.arg(q.end().plusDays(1).toString());
        }
        appendScopeArgs(sql, q);
    }

    /** 降序 keyset 文本（日期/DATETIME 列 + id），参数由 {@link #bindDescCursor} 绑定 */
    private static void appendDescKeyset(Sql sql, String cursor, String dateCol, String idCol) {
        if (cursor == null || cursor.isBlank()) {
            return;
        }
        sql.add(" AND (").add(dateCol).add(" < ? OR (").add(dateCol).add(" = ? AND ")
                .add(idCol).add(" < ?))");
    }

    private static void bindDescCursor(Sql sql, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return;
        }
        String[] c = decodeCursor(cursor);
        sql.arg(c[0]);
        sql.arg(c[0]);
        sql.arg(Long.parseLong(c[1]));
    }

    static String encodeCursor(String key, long id) {
        String raw = key + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static String[] decodeCursor(String cursor) {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        int i = raw.indexOf('|');
        return new String[] {raw.substring(0, i), raw.substring(i + 1)};
    }

    /** LIMIT size+1：多取一条判断是否有下一页，游标由最后一条可见行的稳定排序键生成 */
    private DetailQueryResult paginate(List<RawRow> fetched, int size) {
        String next = null;
        List<QualityViews.DetailRow> pageRows;
        if (fetched.size() > size) {
            RawRow last = fetched.get(size - 1);
            next = encodeCursor(last.cursorKey, last.row.id());
            pageRows = fetched.subList(0, size).stream().map(r -> r.row).toList();
        } else {
            pageRows = fetched.stream().map(r -> r.row).toList();
        }
        return new DetailQueryResult(pageRows, next);
    }

    private Map<Long, Long> groupByBuilding(Sql sql) {
        Map<Long, Long> map = new HashMap<>();
        jdbc.query(sql.text(), (org.springframework.jdbc.core.RowCallbackHandler) rs -> map.put(rs.getLong("b"), rs.getLong("c")), sql.args.toArray());
        return map;
    }

    private List<List<Long>> chunks(Collection<Long> ids) {
        List<Long> list = ids == null ? List.of() : ids.stream().distinct().sorted().toList();
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += NAME_BATCH) {
            out.add(list.subList(i, Math.min(i + NAME_BATCH, list.size())));
        }
        return out;
    }

    private static final class Sql {
        private final StringBuilder text = new StringBuilder();
        private final List<Object> args = new ArrayList<>();

        Sql add(String part) {
            text.append(part);
            return this;
        }

        Sql arg(Object value) {
            args.add(value);
            return this;
        }

        String text() {
            return text.toString();
        }
    }

    /** SQL 查询原始行（携带游标键，游标键不入参出参 DTO） */
    private record RawRow(long id, QualityViews.DetailRow row, String cursorKey) {
        static RawRow of(long id, String cursorKey, QualityViews.DetailRow row) {
            return new RawRow(id, row, cursorKey);
        }
    }

    /** 按日聚合行 */
    public record DayPlan(LocalDate day, long due, long onTime) {
    }

    public record DayOrder(LocalDate day, long count, long durationDays) {
    }

    public record DayFault(LocalDate day, long count, long repeat) {
    }

    public record DayPart(LocalDate day, long count, long quantity, BigDecimal amount) {
    }

    public record BuildingRow(Long id, String buildingNo, String name, String status) {
    }

    public record DetailQueryResult(List<QualityViews.DetailRow> rows, String nextCursor) {
    }

    // =====================================================================
    // 导出专用：会话临时表承载事件重复标记
    //
    // 全量导出若每页都对整窗重算 LAG 窗口会退化为 O(n²)；在导出事务内一次性把
    // is_repeat/prior_no 落入带索引的临时表，后续所有事件分页/计数直接走临时表。
    // 同一事务共享连接，临时表仅本连接可见，方法结束前 DROP。
    // =====================================================================

    private static final String FAULT_TEMP = "tmp_q_fault_repeat";

    /** 建临时表（下界需比统计窗口早 30 天，保证首日 LAG 能看到窗外前序） */
    public void prepareFaultTempTable(LocalDate lookbackStart) {
        jdbc.update("DROP TEMPORARY TABLE IF EXISTS " + FAULT_TEMP);
        jdbc.update("""
                CREATE TEMPORARY TABLE %s AS
                SELECT x.id, x.fault_no, x.elevator_id, x.building_id, x.fault_type,
                       x.report_time, x.status,
                       (COALESCE(TIMESTAMPDIFF(SECOND,
                            LAG(x.report_time) OVER w_fault, x.report_time),
                            %d) < %d) AS is_repeat,
                       LAG(x.fault_no) OVER w_fault AS prior_no
                FROM fault_report x
                WHERE x.status <> 'CANCELLED' AND x.report_time >= ?
                WINDOW w_fault AS (
                    PARTITION BY x.elevator_id, x.fault_type ORDER BY x.report_time, x.id)
                """.formatted(FAULT_TEMP, REPEAT_GAP_SECONDS + 1, REPEAT_GAP_SECONDS),
                lookbackStart.atStartOfDay().format(DT_FMT));
        jdbc.update("ALTER TABLE " + FAULT_TEMP
                + " ADD PRIMARY KEY (id),"
                + " ADD INDEX idx_rt (report_time, id),"
                + " ADD INDEX idx_b (building_id, elevator_id, report_time, id)");
    }

    public void dropFaultTempTable() {
        jdbc.update("DROP TEMPORARY TABLE IF EXISTS " + FAULT_TEMP);
    }

    /** 事件明细分页（临时表）：窗口 [winStart, winEnd)，可仅取重复行 */
    public DetailQueryResult faultDetailsFromTemp(QualityQuery q, boolean numerator, String cursor,
                                                  int size, LocalDate winStart, LocalDate winEnd) {
        Sql sql = new Sql();
        sql.add("SELECT f.id, f.fault_no, f.elevator_id, f.building_id, f.fault_type,")
                .add(" f.report_time, f.status, f.is_repeat, f.prior_no FROM " + FAULT_TEMP + " f")
                .add(" WHERE f.report_time >= ? AND f.report_time < ?");
        appendScope(sql, q, "f");
        if (numerator) {
            sql.add(" AND f.is_repeat = 1");
        }
        appendDescKeyset(sql, cursor, "f.report_time", "f.id");
        sql.add(" ORDER BY f.report_time DESC, f.id DESC LIMIT ?");
        sql.arg(winStart.atStartOfDay().format(DT_FMT));
        sql.arg(winEnd.atStartOfDay().format(DT_FMT));
        appendScopeArgs(sql, q);
        bindDescCursor(sql, cursor);
        sql.arg(size + 1L);
        return mapFaultRows(sql, size);
    }

    /** 事件行数（临时表），与分页同 WHERE */
    public long faultCountFromTemp(QualityQuery q, boolean numerator,
                                   LocalDate winStart, LocalDate winEnd) {
        Sql sql = new Sql();
        sql.add("SELECT COUNT(*) FROM " + FAULT_TEMP + " f")
                .add(" WHERE f.report_time >= ? AND f.report_time < ?");
        appendScope(sql, q, "f");
        if (numerator) {
            sql.add(" AND f.is_repeat = 1");
        }
        sql.arg(winStart.atStartOfDay().format(DT_FMT));
        sql.arg(winEnd.atStartOfDay().format(DT_FMT));
        appendScopeArgs(sql, q);
        return count(sql);
    }

    private DetailQueryResult mapFaultRows(Sql sql, int size) {
        List<RawRow> rows = new ArrayList<>();
        jdbc.query(sql.text(), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            boolean repeat = rs.getBoolean("is_repeat");
            String type = rs.getString("fault_type");
            // 直接取墙上时间字符串，不经 JVM 时区转换：游标键与 SQL 绑定字面量严格一致
            String rawTime = rs.getString("report_time");
            String cursorTime = rawTime.replace(" ", "T");
            String priorNo = rs.getString("prior_no");
            String reason = repeat
                    ? "同电梯同类型「" + type + "」事件 " + priorNo + " 在 30 天内已出现，计为重复事件（计入分子与分母）"
                    : "30 天内无同类型事件，计入分母、不计入分子";
            LocalDate day = LocalDate.parse(rawTime.substring(0, 10));
            rows.add(RawRow.of(rs.getLong("f.id"), cursorTime,
                    new QualityViews.DetailRow(rs.getLong("f.id"), "FAULT",
                            rs.getString("f.fault_no"), rs.getLong("f.elevator_id"), null,
                            rs.getLong("f.building_id"), null, null, day,
                            1L, repeat ? 1L : 0L, null, null, null,
                            repeat, reason)));
        }, sql.args.toArray());
        return paginate(rows, size);
    }
}
