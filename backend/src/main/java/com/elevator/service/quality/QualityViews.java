package com.elevator.service.quality;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 维保服务质量分析的出参视图。
 *
 * <p>一个 {@link QualitySnapshot} 就是一次「统计快照」：总览卡片、按日/周/月趋势、
 * 楼宇排行全部由同一次聚合产生，趋势折叠回总计时必然与卡片一致。
 * 明细钻取和导出复用同一筛选与同一口径（见 {@link QualityDict}）。
 */
public final class QualityViews {

    private QualityViews() {
    }

    /** 指标口径定义 */
    public record MetricDefView(String key, String name, String windowAnchor,
                                String denominatorRule, String numeratorRule, String formula) {
    }

    /** 风险分量权重 */
    public record RiskWeightView(String key, String name, Integer weight) {
    }

    /** 快照头：参数、生成时间、数据版本、口径。导出文件原样携带。 */
    public record SnapshotHead(String snapshotId, LocalDateTime generatedAt, String dataVersion,
                               String zone, LocalDate start, LocalDate end,
                               Long buildingId, Long elevatorId, String granularity,
                               List<MetricDefView> metricDefs, List<RiskWeightView> riskWeights,
                               Long scopeBuildingCount, Long scopeElevatorCount) {
    }

    /** 单个总览卡片。率类指标同时给出 ratePercent(2位小数)，计数类给出 value 与单位。 */
    public record MetricCard(String key, String name, String unit,
                             Long numerator, Long denominator,
                             BigDecimal ratePercent, BigDecimal value,
                             String formula) {
    }

    /** 趋势桶：key=首日日期（周一/月初），label=展示文案；五个指标同桶。 */
    public record TrendBucket(String bucket, String label,
                              Long planDue, Long planOnTime,
                              Long orderCount, Long durationDays,
                              Long eventCount, Long eventRepeat,
                              Long partRecords, Long partQuantity, BigDecimal partAmount,
                              BigDecimal planRatePercent, BigDecimal eventRatePercent,
                              BigDecimal avgDurationDays) {
    }

    /** 楼宇风险排行行：按 riskScore 降序、buildingNo 升序稳定排序。 */
    public record RiskRankRow(Long buildingId, String buildingNo, String buildingName,
                              String buildingStatus, String buildingStatusName,
                              Long overduePlans, Long openEvents, Long repeatEvents,
                              Long inspectDue, Long elevatorCount,
                              Integer riskScore, String riskLevel, String riskLevelName) {
    }

    /** 整份快照 */
    public record QualitySnapshot(SnapshotHead head,
                                  List<MetricCard> cards,
                                  List<TrendBucket> trends,
                                  List<RiskRankRow> ranking,
                                  boolean empty) {
    }

    /** 明细分页游标响应 */
    public record DetailPage(String metric, String scope,
                             List<DetailRow> rows, String nextCursor,
                             Long total, Integer pageSize) {
    }

    /**
     * 通用明细行：sourceNo 来源单号、sourceDate 业务日期、reason 计入原因。
     * count1/count2/amount/value1/value2 承载各指标特有的可核对数字。
     */
    public record DetailRow(Long id, String sourceType, String sourceNo,
                            Long elevatorId, String elevatorNo,
                            Long buildingId, String buildingNo, String buildingName,
                            LocalDate sourceDate,
                            Long count1, Long count2,
                            BigDecimal amount, BigDecimal value1, BigDecimal value2,
                            boolean included, String reason) {
    }
}
