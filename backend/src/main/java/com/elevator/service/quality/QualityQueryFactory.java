package com.elevator.service.quality;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

import com.elevator.service.common.BizException;

/**
 * 把前端参数解析为 {@link QualityQuery}：
 * <ul>
 *   <li>日期只接受 {@code yyyy-MM-dd}（Java LocalDate，无时区偏移），按 Asia/Shanghai 自然日解释；</li>
 *   <li>开始日包含、结束日包含，SQL 内换算为左闭右开；</li>
 *   <li>粒度只允许 DAY/WEEK/MONTH；时区键必须覆盖且当前固定 Asia/Shanghai（未来扩展不破坏缓存键）。</li>
 * </ul>
 */
@Component
public class QualityQueryFactory {

    private static final ZoneId ZONE = ZoneId.of(QualityDict.ZONE);

    public QualityQuery parse(String start, String end, Long buildingId, Long elevatorId,
                              String granularity, String zone) {
        LocalDate s = parseDate(start, "开始日期");
        LocalDate e = parseDate(end, "结束日期");
        if (e.isBefore(s)) {
            throw new BizException("结束日期不能早于开始日期：" + s + " ~ " + e);
        }
        long span = e.toEpochDay() - s.toEpochDay() + 1;
        if (span > QualityDict.MAX_WINDOW_DAYS) {
            throw new BizException("日期范围最大支持 " + QualityDict.MAX_WINDOW_DAYS + " 天，请缩小范围");
        }
        String g = granularity == null || granularity.isBlank() ? "DAY" : granularity.trim().toUpperCase();
        if (!g.equals("DAY") && !g.equals("WEEK") && !g.equals("MONTH")) {
            throw new BizException("趋势粒度只能是 DAY/WEEK/MONTH，收到的是：" + granularity);
        }
        String z = zone == null || zone.isBlank() ? QualityDict.ZONE : zone.trim();
        if (!QualityDict.ZONE.equals(z)) {
            throw new BizException("当前仅支持 Asia/Shanghai 自然日口径，收到的时区是：" + z);
        }
        if (buildingId != null && buildingId <= 0) {
            buildingId = null;
        }
        if (elevatorId != null && elevatorId <= 0) {
            elevatorId = null;
        }
        return new QualityQuery(s, e, buildingId, elevatorId, g, z);
    }

    /** 今天（Asia/Shanghai） */
    public LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private LocalDate parseDate(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BizException(label + "不能为空（格式 yyyy-MM-dd）");
        }
        String v = value.trim();
        // 严格 yyyy-MM-dd，禁止传入带时间/时区的表达，杜绝浏览器时区歧义
        if (!v.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new BizException(label + "格式必须是 yyyy-MM-dd，收到的是：" + value);
        }
        try {
            return LocalDate.parse(v);
        } catch (Exception ex) {
            throw new BizException(label + "不是合法日期：" + value);
        }
    }
}
