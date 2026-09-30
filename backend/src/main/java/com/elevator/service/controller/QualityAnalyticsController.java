package com.elevator.service.controller;

import java.time.LocalDate;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.elevator.service.common.ApiResponse;
import com.elevator.service.dto.QualityDtos;
import com.elevator.service.service.QualityAnalysisService;

/** 维保服务质量分析：快照、指标钻取和同快照导出。 */
@RestController
@RequestMapping("/api/quality-analytics")
public class QualityAnalyticsController {

    private final QualityAnalysisService service;

    public QualityAnalyticsController(QualityAnalysisService service) {
        this.service = service;
    }

    @GetMapping("/snapshot")
    public ApiResponse<QualityDtos.QualitySnapshot> snapshot(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "day") String granularity,
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) Long elevatorId,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone) {
        return ApiResponse.success(service.getSnapshot(
                new QualityDtos.SnapshotRequest(startDate, endDate, granularity, buildingId, elevatorId, timezone)));
    }

    @GetMapping("/details")
    public ApiResponse<QualityDtos.DetailPage> details(
            @RequestParam String snapshotId,
            @RequestParam String metric,
            @RequestParam(defaultValue = "ALL") String component,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Long buildingId) {
        return ApiResponse.success(service.drill(snapshotId, metric, component, cursor, size, buildingId));
    }

    @GetMapping("/export")
    public void export(@RequestParam String snapshotId, HttpServletResponse response) {
        service.exportSnapshot(snapshotId, response);
    }
}
