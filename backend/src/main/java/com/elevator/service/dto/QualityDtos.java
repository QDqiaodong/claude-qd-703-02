package com.elevator.service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 维保服务质量分析的请求、快照与钻取 DTO。 */
public final class QualityDtos {

    private QualityDtos() {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SnapshotRequest {
        private LocalDate startDate;
        private LocalDate endDate;
        /** day / week / month */
        private String granularity;
        private Long buildingId;
        private Long elevatorId;
        /** 固定允许 Asia/Shanghai，显式进入缓存键 */
        private String timezone;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterMeta {
        private LocalDate startDate;
        private LocalDate endDate;
        /** 后端实际查询区间：[startDate 00:00, endDate+1d 00:00) */
        private LocalDateTime startInclusive;
        private LocalDateTime endExclusive;
        private String granularity;
        private Long buildingId;
        private Long elevatorId;
        private String timezone;
        private LocalDate asOfDate;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricDefinition {
        private String key;
        private String name;
        private String numerator;
        private String denominator;
        private String unit;
        private String dateField;
        private String includedReason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricSummary {
        private String key;
        private String name;
        private BigDecimal value;
        private String displayValue;
        private String unit;
        private Long numerator;
        private Long denominator;
        private BigDecimal amount;
        private Long count;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendBucket {
        private String key;
        private String label;
        private LocalDate startDate;
        private LocalDate endDate;
        private Long onTimeNumerator;
        private Long onTimeDenominator;
        private BigDecimal onTimeRate;
        private Long durationTotalMinutes;
        private Long durationCount;
        private BigDecimal averageDurationHours;
        private Long repeatNumerator;
        private Long repeatDenominator;
        private BigDecimal repeatRate;
        private Long partCount;
        private Long partQuantity;
        private BigDecimal partAmount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BuildingRank {
        private Long buildingId;
        private String buildingNo;
        private String buildingName;
        private String buildingStatus;
        private String buildingStatusName;
        private Long overduePlanCount;
        private Long openEventCount;
        private Long repeatEventCount;
        private Long inspectDue30dCount;
        private BigDecimal riskScore;
        private String riskLevel;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetailRow {
        private String metric;
        private String component;
        private Long id;
        private String sourceNo;
        private String sourceType;
        private String sourceTypeName;
        private Long buildingId;
        private String buildingNo;
        private String buildingName;
        private Long elevatorId;
        private String elevatorNo;
        private LocalDate date;
        private LocalDateTime eventTime;
        private String status;
        private String statusName;
        private String metricDate;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal amount;
        private Long durationMinutes;
        private Boolean counted;
        private String includeReason;
        private String relatedSourceNo;
        private String title;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetailPage {
        private List<DetailRow> rows;
        private String nextCursor;
        private Long total;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualitySnapshot {
        private String snapshotId;
        private LocalDateTime generatedAt;
        private String dataVersion;
        private FilterMeta filter;
        private List<MetricDefinition> definitions;
        private Map<String, MetricSummary> summaries;
        private List<TrendBucket> trends;
        private List<BuildingRank> buildingRanks;
        private Map<String, Long> detailCounts;
        private Map<String, String> weights;
    }
}
