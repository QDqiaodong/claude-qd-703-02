package com.elevator.service.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.elevator.service.common.ApiResponse;
import com.elevator.service.quality.QualityExportService;
import com.elevator.service.quality.QualityQuery;
import com.elevator.service.quality.QualityQueryFactory;
import com.elevator.service.quality.QualityStatsService;
import com.elevator.service.quality.QualityViews;

/**
 * 维保服务质量分析：快照（卡片/趋势/排行同源）、指标钻取明细、口径复用导出。
 */
@RestController
@RequestMapping("/api/quality")
public class QualityController {

    private final QualityQueryFactory queryFactory;
    private final QualityStatsService statsService;
    private final QualityExportService exportService;

    public QualityController(QualityQueryFactory queryFactory,
                             QualityStatsService statsService,
                             QualityExportService exportService) {
        this.queryFactory = queryFactory;
        this.statsService = statsService;
        this.exportService = exportService;
    }

    /** 总览卡片 + 趋势（day/week/month）+ 楼宇风险排行：同一份快照 */
    @GetMapping("/snapshot")
    public ApiResponse<QualityViews.QualitySnapshot> snapshot(
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            @RequestParam(value = "buildingId", required = false) Long buildingId,
            @RequestParam(value = "elevatorId", required = false) Long elevatorId,
            @RequestParam(value = "granularity", required = false, defaultValue = "DAY") String granularity,
            @RequestParam(value = "zone", required = false) String zone) {
        QualityQuery q = queryFactory.parse(startDate, endDate, buildingId, elevatorId, granularity, zone);
        return ApiResponse.success(statsService.snapshot(q));
    }

    /**
     * 指标钻取：构成卡片数字的计划/维保记录/事件/配件/风险分量明细，游标分页。
     *
     * @param scope DENOMINATOR（默认，分母全集）/ NUMERATOR（仅计入分子的行）
     */
    @GetMapping("/details")
    public ApiResponse<QualityViews.DetailPage> details(
            @RequestParam("metric") String metric,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            @RequestParam(value = "buildingId", required = false) Long buildingId,
            @RequestParam(value = "elevatorId", required = false) Long elevatorId,
            @RequestParam(value = "granularity", required = false, defaultValue = "DAY") String granularity,
            @RequestParam(value = "zone", required = false) String zone,
            @RequestParam(value = "scope", required = false, defaultValue = "DENOMINATOR") String scope,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "size", required = false, defaultValue = "50") Integer size) {
        QualityQuery q = queryFactory.parse(startDate, endDate, buildingId, elevatorId, granularity, zone);
        int pageSize = size == null || size <= 0 ? 50 : Math.min(size, 500);
        return ApiResponse.success(statsService.details(q, metric, scope, cursor, pageSize));
    }

    /** 导出：复用当前筛选与同一口径，文件内含参数/生成时间/快照号/汇总/排行/全部明细 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate,
            @RequestParam(value = "buildingId", required = false) Long buildingId,
            @RequestParam(value = "elevatorId", required = false) Long elevatorId,
            @RequestParam(value = "granularity", required = false, defaultValue = "DAY") String granularity,
            @RequestParam(value = "zone", required = false) String zone) {
        QualityQuery q = queryFactory.parse(startDate, endDate, buildingId, elevatorId, granularity, zone);
        QualityExportService.ExportFile file = exportService.export(q);
        String encoded = URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.filename() + "\"; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(file.content());
    }
}
