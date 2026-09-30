package com.elevator.service.quality;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 维保服务质量分析的口径字典：五个指标的统计规则、楼宇风险权重、各类窗口常量集中定义在此。
 *
 * <p>页面与导出都只展示 {@link #metricDefs()} / {@link #riskWeights()} 返回的同一份文案与权重，
 * 不允许前端或导出器另写一套口径。
 */
public final class QualityDict {

    private QualityDict() {
    }

    /** 固定业务时区：所有自然日按 Asia/Shanghai 解释 */
    public static final String ZONE = "Asia/Shanghai";

    /** 重复事件判定窗口：同一台电梯、同一事件类型，30 个自然日内再次出现 */
    public static final int REPEAT_WINDOW_DAYS = 30;

    /** 楼宇风险：以「今天」为基准，未来 30 个自然日（含今日，左闭右开 [今天, 今天+30)）检验到期 */
    public static final int INSPECT_WINDOW_DAYS = 30;

    /** 楼宇风险：近 30 个自然日（含今日，[今天-29, 明天)）内的重复事件数 */
    public static final int RISK_RECENT_DAYS = 30;

    /** 快照短时缓存秒数：相同筛选并发合并、命中后直接复用 */
    public static final long SNAPSHOT_TTL_SECONDS = 30;

    /** 快照缓存最大条目，超出时先清理过期项 */
    public static final int SNAPSHOT_MAX_ENTRIES = 200;

    /** 最大允许的窗口跨度（天），防止误传超大范围拖垮聚合 */
    public static final int MAX_WINDOW_DAYS = 366 * 3 + 1;

    // ---------------- 指标 key（前后端协议，勿随意改） ----------------
    public static final String M_PLAN_ON_TIME = "PLAN_ON_TIME";
    public static final String M_ORDER_DURATION = "ORDER_DURATION";
    public static final String M_EVENT_REPEAT = "EVENT_REPEAT";
    public static final String M_PART_COST = "PART_COST";
    public static final String M_RISK_RANK = "RISK_RANK";
    public static final String M_RISK_OVERDUE_PLAN = "RISK_OVERDUE_PLAN";
    public static final String M_RISK_OPEN_EVENT = "RISK_OPEN_EVENT";
    public static final String M_RISK_REPEAT_EVENT = "RISK_REPEAT_EVENT";
    public static final String M_RISK_INSPECT = "RISK_INSPECT";

    // ---------------- 楼宇风险综合权重（加权计数，分值越大风险越高） ----------------
    /** 逾期计划：每条 40 分 */
    public static final int W_OVERDUE_PLAN = 40;
    /** 未完成事件：每条 30 分 */
    public static final int W_OPEN_EVENT = 30;
    /** 近 30 天重复事件：每条 20 分 */
    public static final int W_REPEAT_EVENT = 20;
    /** 未来 30 天检验到期：每台 10 分 */
    public static final int W_INSPECT = 10;

    /** 风险分层阈值（含），分值 ≥ 高分阈值为 HIGH，否则 ≥ 中分阈值为 MID，其余 LOW */
    public static final int RISK_HIGH_SCORE = 100;
    public static final int RISK_MID_SCORE = 40;

    /**
     * 五个指标的可审计口径定义。窗口锚点列与 WHERE 条件在
     * {@link QualityStatsRepository} 的 SQL 中严格按这里的文案实现。
     */
    public static List<MetricDef> metricDefs() {
        return List.of(
                new MetricDef(
                        M_PLAN_ON_TIME, "计划准时率",
                        "计划执行日 maintenance_plan.plan_date",
                        "窗口内【计划日到期】且状态非作废（status <> 'VOID'）的维保计划条数",
                        "其中状态为已执行（DONE）且存在关联已完成工单（order.status='DONE'）"
                                + "其实际完成日 actual_date 恰好等于计划日 plan_date 的计划条数",
                        "准时率 = 按计划日完成条数 ÷ 到期非作废计划条数 × 100%；"
                                + "待执行/已延期/应急暂停均计入分母但不计入分子，作废计划不计入。"),
                new MetricDef(
                        M_ORDER_DURATION, "平均执行耗时",
                        "实际完成日 maintenance_order.actual_date",
                        "窗口内【已有实际完成日期】、同时具备计划日期、且状态非作废/已撤销"
                                + "（actual_date IS NOT NULL 且 plan_date IS NOT NULL 且 status NOT IN ('VOID','CANCELLED')）的维保记录条数",
                        "每条计入记录贡献耗时 = DATEDIFF(actual_date, plan_date) 天（可为负，表示提前完成）",
                        "平均执行耗时 = Σ(实际完成日 − 计划日) ÷ 计入记录条数；单位：天，保留 2 位小数；"
                                + "应急抢修单（EX）有实际完成日也计入；无实际完成日的在途单不统计。"),
                new MetricDef(
                        M_EVENT_REPEAT, "重复事件率",
                        "报修时间 fault_report.report_time（按 Asia/Shanghai 自然日）",
                        "窗口内非误报撤销（status <> 'CANCELLED'）的故障报修事件条数",
                        "同一台电梯、同一故障类型，在该事件之前 30 天内（含同日，按报修时间与 id 稳定排序）"
                                + "已出现过另一条非撤销事件的事件条数",
                        "重复事件率 = 重复事件条数 ÷ 事件总条数 × 100%；"
                                + "连续第三次出现时相对第二条仍在 30 天内，照样计为重复事件。"),
                new MetricDef(
                        M_PART_COST, "配件成本",
                        "更换日期 part_replacement.replace_date",
                        "窗口内状态为已登记（status='NORMAL'）的配件更换记录（作废记录不计入）",
                        "每条计入记录贡献金额 = quantity × unit_price（= amount）",
                        "配件成本 = Σ(数量 × 单价)，按更换日期汇总；金额保留 2 位小数。"),
                new MetricDef(
                        M_RISK_RANK, "楼宇风险排行",
                        "存量口径，以 Asia/Shanghai 今天为基准（不受日期窗口影响，与前四项共用楼宇/电梯筛选）",
                        "参与排行的楼宇：筛选范围内有电梯或任一风险分量大于 0 的楼宇（含已撤场楼宇）",
                        "riskScore = 40×逾期计划 + 30×未完成事件 + 20×近30天重复事件 + 10×未来30天检验到期",
                        "逾期计划=计划日早于今天且状态在 PENDING/DELAYED/SUSPENDED 的计划；"
                                + "未完成事件=status NOT IN ('FIXED','CANCELLED') 的报修；"
                                + "重复事件=近 30 个自然日（含今日）内同电梯同类型 30 天内再现的事件；"
                                + "检验到期=电梯 next_inspect_date 落在 [今天, 今天+30)。"
                                + "并列时按楼宇编号升序稳定排序。"));
    }

    /** 楼宇风险权重，后端按此计分并原样返回给前端/导出，保证显示一致。 */
    public static List<RiskWeight> riskWeights() {
        return List.of(
                new RiskWeight(M_RISK_OVERDUE_PLAN, "逾期计划", W_OVERDUE_PLAN),
                new RiskWeight(M_RISK_OPEN_EVENT, "未完成事件", W_OPEN_EVENT),
                new RiskWeight(M_RISK_REPEAT_EVENT, "近30天重复事件", W_REPEAT_EVENT),
                new RiskWeight(M_RISK_INSPECT, "未来30天检验到期", W_INSPECT));
    }

    public static String riskLevel(int score) {
        if (score >= RISK_HIGH_SCORE) {
            return "HIGH";
        }
        if (score >= RISK_MID_SCORE) {
            return "MID";
        }
        return "LOW";
    }

    public static String riskLevelName(String level) {
        return switch (level) {
            case "HIGH" -> "高风险";
            case "MID" -> "中风险";
            default -> "低风险";
        };
    }

    /** 金额/比率统一舍入 */
    public static BigDecimal scale2(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public static BigDecimal scale1(BigDecimal v) {
        return v.setScale(1, java.math.RoundingMode.HALF_UP);
    }

    /** 指标口径定义（快照与导出共用） */
    public record MetricDef(String key, String name, String windowAnchor,
                            String denominatorRule, String numeratorRule, String formula) {
    }

    /** 风险分量权重（快照与导出共用） */
    public record RiskWeight(String key, String name, Integer weight) {
    }

    /** 各指标占位列的含义，导出/明细复用 */
    public static final Map<String, String> METRIC_NAMES = Map.of(
            M_PLAN_ON_TIME, "计划准时率",
            M_ORDER_DURATION, "平均执行耗时",
            M_EVENT_REPEAT, "重复事件率",
            M_PART_COST, "配件成本",
            M_RISK_RANK, "楼宇风险排行",
            M_RISK_OVERDUE_PLAN, "风险分量·逾期计划",
            M_RISK_OPEN_EVENT, "风险分量·未完成事件",
            M_RISK_REPEAT_EVENT, "风险分量·近30天重复事件",
            M_RISK_INSPECT, "风险分量·检验到期");
}
