package com.elevator.service.quality;

import java.time.LocalDate;

/**
 * 质量分析的统一筛选条件（五个指标共用同一套）。
 *
 * <p>日期始终以 Asia/Shanghai 的「自然日」表示：开始日包含、结束日包含；
 * 落到 SQL 的 DATETIME 列时换算为左闭右开 {@code [start 00:00, end+1 00:00)}，
 * 不经过浏览器/会话时区，也不存在夏令时跨日问题（上海时区无 DST）。
 */
public record QualityQuery(LocalDate start, LocalDate end, Long buildingId, Long elevatorId,
                           String granularity, String zone) {

    public boolean hasBuilding() {
        return buildingId != null;
    }

    public boolean hasElevator() {
        return elevatorId != null;
    }
}
