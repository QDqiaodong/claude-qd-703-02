package com.elevator.service.repository;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 服务质量分析专用查询。
 *
 * <p>所有卡片、趋势、排行和导出共用这里的数据库聚合结果；钻取只按稳定排序做 keyset 分页。
 * 日期列直接按 Asia/Shanghai 自然日比较，DATETIME 列使用 [当天 00:00, 次日 00:00)。
 */
@Repository
public class QualityStatisticsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public QualityStatisticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 兼容已初始化的数据库：建表脚本之外补齐分析索引，失败不阻断只读服务启动。 */
    @PostConstruct
    public void ensureIndexes() {
        createIndexIfMissing("maintenance_plan", "idx_q_plan_scope",
                "(building_id, elevator_id, plan_date, status)");
        createIndexIfMissing("maintenance_order", "idx_q_order_scope",
                "(building_id, elevator_id, actual_date, plan_date, status)");
        createIndexIfMissing("fault_report", "idx_q_fault_scope",
                "(building_id, elevator_id, report_time, fault_type, status)");
        createIndexIfMissing("fault_report", "idx_q_fault_repeat",
                "(elevator_id, fault_type, report_time, id)");
        createIndexIfMissing("part_replacement", "idx_q_part_scope",
                "(building_id, elevator_id, replace_date, status)");
        createIndexIfMissing("elevator", "idx_q_elevator_scope",
                "(building_id, id, next_inspect_date)");
    }

    public String currentVersion(LocalDateTime asOf) {
        String sql = """
                select greatest(
                    coalesce((select unix_timestamp(max(updated_at)) from maintenance_plan), 0),
                    coalesce((select unix_timestamp(max(updated_at)) from maintenance_order), 0),
                    coalesce((select unix_timestamp(max(updated_at)) from fault_report), 0),
                    coalesce((select unix_timestamp(max(updated_at)) from part_replacement), 0),
                    coalesce((select unix_timestamp(max(updated_at)) from elevator), 0),
                    coalesce((select unix_timestamp(max(updated_at)) from building), 0),
                    :asOfMillis
                ) as version_value
                """;
        Number value = jdbc.queryForObject(sql,
                new MapSqlParameterSource("asOfMillis", asOf.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() / 1000),
                Number.class);
        return value == null ? String.valueOf(asOf.toEpochSecond(java.time.ZoneOffset.UTC)) : String.valueOf(value.longValue());
    }

    public List<Map<String, Object>> planDaily(LocalDate start, LocalDate end, Long buildingId, Long elevatorId) {
        String sql = """
                select p.plan_date as bucket_date,
                       count(*) as denominator,
                       sum(case when p.status = 'DONE' and exists (
                           select 1 from maintenance_order o
                           where o.plan_id = p.id
                             and o.actual_date = p.plan_date
                             and coalesce(o.status, '') <> 'VOID'
                           limit 1
                       ) then 1 else 0 end) as numerator
                from maintenance_plan p
                where p.plan_date >= :start and p.plan_date < :end
                  and p.status <> 'VOID'
                """ + scope("p", buildingId, elevatorId) + """
                group by p.plan_date
                """;
        return jdbc.queryForList(sql, params(start, end, buildingId, elevatorId));
    }

    public List<Map<String, Object>> orderDurationDaily(LocalDate start, LocalDate end, Long buildingId, Long elevatorId) {
        String sql = """
                select o.actual_date as bucket_date,
                       count(*) as duration_count,
                       sum(case when o.plan_date is null then 0
                                else timestampdiff(minute, timestamp(o.plan_date), timestamp(o.actual_date))
                           end) as duration_minutes
                from maintenance_order o
                where o.actual_date >= :start and o.actual_date < :end
                  and o.actual_date is not null
                  and coalesce(o.status, '') not in ('VOID', 'CANCELLED')
                """ + scope("o", buildingId, elevatorId) + """
                group by o.actual_date
                """;
        return jdbc.queryForList(sql, params(start, end, buildingId, elevatorId));
    }

    public List<Map<String, Object>> faultDaily(LocalDate start, LocalDate end, Long buildingId, Long elevatorId) {
        String sql = """
                select date(f.report_time) as bucket_date,
                       count(*) as denominator,
                       sum(case when exists (
                           select 1 from fault_report f2
                           where f2.elevator_id = f.elevator_id
                             and f2.fault_type = f.fault_type
                             and coalesce(f2.status, '') <> 'CANCELLED'
                             and (f2.report_time < f.report_time
                                  or (f2.report_time = f.report_time and f2.id < f.id))
                             and f2.report_time >= f.report_time - interval 30 day
                           limit 1
                       ) then 1 else 0 end) as numerator
                from fault_report f
                where f.report_time >= :startTime and f.report_time < :endTime
                  and coalesce(f.status, '') <> 'CANCELLED'
                """ + scope("f", buildingId, elevatorId) + """
                group by date(f.report_time)
                """;
        return jdbc.queryForList(sql, datetimeParams(start, end, buildingId, elevatorId));
    }

    public List<Map<String, Object>> partDaily(LocalDate start, LocalDate end, Long buildingId, Long elevatorId) {
        String sql = """
                select p.replace_date as bucket_date,
                       count(*) as part_count,
                       sum(p.quantity) as part_quantity,
                       sum(p.quantity * p.unit_price) as part_amount
                from part_replacement p
                where p.replace_date >= :start and p.replace_date < :end
                  and p.replace_date is not null
                  and coalesce(p.status, 'NORMAL') = 'NORMAL'
                """ + scope("p", buildingId, elevatorId) + """
                group by p.replace_date
                """;
        return jdbc.queryForList(sql, params(start, end, buildingId, elevatorId));
    }

    public List<Map<String, Object>> buildingRisk(LocalDate start, LocalDate end, LocalDate asOf,
                                                  Long buildingId, Long elevatorId) {
        LocalDate dueEnd = asOf.plusDays(30);
        String elevatorScope = (elevatorId == null ? "" : " and e.id = :elevatorId and e.building_id = coalesce(:buildingId, e.building_id)");
        String sql = """
                select sb.id as building_id,
                       sb.building_no,
                       sb.name as building_name,
                       sb.status as building_status,
                       coalesce(op.cnt, 0) as overdue_count,
                       coalesce(oe.cnt, 0) as open_count,
                       coalesce(re.cnt, 0) as repeat_count,
                       coalesce(ie.cnt, 0) as inspect_count
                from (
                    select b.id, b.building_no, b.name, b.status
                    from building b
                    where (:buildingId is null or b.id = :buildingId)
                      and (:elevatorId is null or exists (
                          select 1 from elevator e where e.id = :elevatorId and e.building_id = b.id
                      ))
                ) sb
                left join (
                    select p.building_id, count(*) cnt
                    from maintenance_plan p
                    where p.plan_date >= :start and p.plan_date < :end
                      and p.plan_date <= :asOf
                      and p.status in ('PENDING', 'DELAYED', 'SUSPENDED')
                    """ + optionalScope("p", buildingId, elevatorId) + """
                    group by p.building_id
                ) op on op.building_id = sb.id
                left join (
                    select f.building_id, count(*) cnt
                    from fault_report f
                    where f.report_time >= :startTime and f.report_time < :endTime
                      and coalesce(f.status, '') not in ('FIXED', 'CANCELLED')
                    """ + optionalScope("f", buildingId, elevatorId) + """
                    group by f.building_id
                ) oe on oe.building_id = sb.id
                left join (
                    select f.building_id, count(*) cnt
                    from fault_report f
                    where f.report_time >= :startTime and f.report_time < :endTime
                      and coalesce(f.status, '') <> 'CANCELLED'
                      and exists (
                          select 1 from fault_report f2
                          where f2.elevator_id = f.elevator_id
                            and f2.fault_type = f.fault_type
                            and coalesce(f2.status, '') <> 'CANCELLED'
                            and (f2.report_time < f.report_time
                                 or (f2.report_time = f.report_time and f2.id < f.id))
                            and f2.report_time >= f.report_time - interval 30 day
                          limit 1
                      )
                    """ + optionalScope("f", buildingId, elevatorId) + """
                    group by f.building_id
                ) re on re.building_id = sb.id
                left join (
                    select e.building_id, count(*) cnt
                    from elevator e
                    where e.next_inspect_date > :asOf and e.next_inspect_date <= :dueEnd
                    """ + elevatorScope + """
                    """ + (buildingId == null ? "" : " and e.building_id = :buildingId") + """
                    group by e.building_id
                ) ie on ie.building_id = sb.id
                order by sb.building_no asc
                """;
        MapSqlParameterSource p = datetimeParams(start, end, buildingId, elevatorId)
                .addValue("asOf", asOf)
                .addValue("dueEnd", dueEnd);
        return jdbc.queryForList(sql, p);
    }

    public List<Map<String, Object>> detailRows(String metric, String component, LocalDate start, LocalDate end,
                                                LocalDate asOf, Long buildingId, Long elevatorId, Long drillBuildingId,
                                                String cursor, int limit) {
        Long effectiveBuilding = drillBuildingId == null ? buildingId : drillBuildingId;
        MapSqlParameterSource p = datetimeParams(start, end, effectiveBuilding, elevatorId);
        String whereCursor = buildCursorClause(metric, cursor, p, limit);
        String sql = switch (metric) {
            case "PLAN" -> planDetailSql(component, whereCursor, limit, p);
            case "DURATION" -> orderDetailSql(whereCursor, limit);
            case "REPEAT" -> faultDetailSql(component, whereCursor, limit, p);
            case "PART" -> partDetailSql(whereCursor, limit);
            case "RISK" -> riskDetailSql(component, start, end, asOf,
                    effectiveBuilding, elevatorId, whereCursor, p, limit);
            default -> throw new IllegalArgumentException("未知指标：" + metric);
        };
        return jdbc.queryForList(sql, p);
    }

    public Long countByBuilding(String metric, String component, LocalDate start, LocalDate end, LocalDate asOf,
                                Long buildingId, Long elevatorId) {
        MapSqlParameterSource params = datetimeParams(start, end, buildingId, elevatorId)
                .addValue("asOf", asOf)
                .addValue("dueEnd", asOf.plusDays(30));
        List<String> predicates = countPredicates(metric, component, params);
        String sql = """
                select count(*)
                from (
                """ + String.join(" union all ", predicates) + """
                ) y
                where y.building_id = :countBuildingId
                  and (:elevatorId is null or y.elevator_id = :elevatorId)
                """;
        params.addValue("countBuildingId", buildingId);
        Number value = jdbc.queryForObject(sql, params, Number.class);
        return value == null ? 0L : value.longValue();
    }

    private List<String> countPredicates(String metric, String component, MapSqlParameterSource params) {
        if ("RISK".equals(metric)) {
            List<String> list = new ArrayList<>();
            if (List.of("ALL", "RISK_OVERDUE").contains(component)) list.add(riskOverdueUnion());
            if (List.of("ALL", "RISK_OPEN").contains(component)) list.add(riskOpenUnion());
            if (List.of("ALL", "RISK_REPEAT").contains(component)) list.add(riskRepeatUnion());
            if (List.of("ALL", "RISK_INSPECTION").contains(component)) list.add(riskInspectionUnion());
            return list;
        }
        if ("PLAN".equals(metric)) {
            params.addValue("component", component);
            String numerator = """
                    and p.status = 'DONE' and exists (
                        select 1 from maintenance_order o
                        where o.plan_id = p.id and o.actual_date = p.plan_date
                          and coalesce(o.status, '') <> 'VOID' limit 1)
                    """;
            return List.of("""
                    select p.building_id building_id, p.elevator_id elevator_id
                    from maintenance_plan p
                    where p.plan_date >= :start and p.plan_date < :end and p.status <> 'VOID'
                    """ + optionalScope("p", null, null)
                    + ("NUMERATOR".equals(component) ? numerator : ""));
        }
        if ("DURATION".equals(metric)) {
            return List.of("""
                    select o.building_id building_id, o.elevator_id elevator_id
                    from maintenance_order o
                    where o.actual_date >= :start and o.actual_date < :end and o.actual_date is not null
                      and coalesce(o.status, '') not in ('VOID', 'CANCELLED')
                    """ + optionalScope("o", null, null));
        }
        if ("PART".equals(metric)) {
            return List.of("""
                    select p.building_id building_id, p.elevator_id elevator_id
                    from part_replacement p
                    where p.replace_date >= :start and p.replace_date < :end and p.replace_date is not null
                      and coalesce(p.status, 'NORMAL') = 'NORMAL'
                    """ + optionalScope("p", null, null));
        }
        params.addValue("component", component);
        String repeatCondition = """
                exists (
                    select 1 from fault_report f2
                    where f2.elevator_id = f.elevator_id and f2.fault_type = f.fault_type
                      and coalesce(f2.status, '') <> 'CANCELLED'
                      and (f2.report_time < f.report_time or (f2.report_time = f.report_time and f2.id < f.id))
                      and f2.report_time >= f.report_time - interval 30 day limit 1)
                """;
        String filter = switch (component) {
            case "NUMERATOR" -> " and " + repeatCondition;
            case "NON_REPEAT" -> " and not " + repeatCondition;
            default -> "";
        };
        return List.of("""
                select f.building_id building_id, f.elevator_id elevator_id
                from fault_report f
                where f.report_time >= :startTime and f.report_time < :endTime
                  and coalesce(f.status, '') <> 'CANCELLED'
                """ + optionalScope("f", null, null) + filter);
    }

    private String planDetailSql(String component, String cursorClause, int limit, MapSqlParameterSource params) {
        params.addValue("component", component);
        String componentFilter = "REPEAT".equals(component) || "EVENT".equals(component) ? "" : switch (component) {
            case "NUMERATOR" -> """
                    and p.status = 'DONE' and exists (
                        select 1 from maintenance_order o
                        where o.plan_id = p.id and o.actual_date = p.plan_date
                          and coalesce(o.status, '') <> 'VOID' limit 1)
                    """;
            default -> "";
        };
        return """
                select 'PLAN' as metric, :component as component, p.id, p.plan_no as source_no, 'PLAN' as source_type,
                       p.building_id, b.building_no, b.name as building_name,
                       p.elevator_id, e.elevator_no, p.plan_date as metric_date, p.plan_date as biz_date,
                       cast(null as datetime) as event_time, p.status,
                       cast(null as decimal(18,4)) as quantity, cast(null as decimal(12,2)) as unit_price,
                       cast(null as decimal(12,2)) as amount, cast(null as signed) as duration_minutes,
                       case when p.status = 'DONE' and exists (
                           select 1 from maintenance_order o
                           where o.plan_id = p.id and o.actual_date = p.plan_date
                             and coalesce(o.status, '') <> 'VOID' limit 1
                       ) then 1 else 0 end as counted,
                       case when p.status = 'DONE' and exists (
                           select 1 from maintenance_order o
                           where o.plan_id = p.id and o.actual_date = p.plan_date
                             and coalesce(o.status, '') <> 'VOID' limit 1
                       ) then '窗口内到期、非作废，且关联维保记录实际完成日=计划日'
                            else '窗口内到期且非作废，但未按计划日完成' end as include_reason,
                       (select o.order_no from maintenance_order o
                         where o.plan_id = p.id and o.actual_date = p.plan_date
                           and coalesce(o.status, '') <> 'VOID'
                         order by o.actual_date desc, o.id desc limit 1) as related_source_no,
                       p.plan_no as title
                from maintenance_plan p
                join elevator e on e.id = p.elevator_id
                join building b on b.id = p.building_id
                where p.plan_date >= :start and p.plan_date < :end and p.status <> 'VOID'
                """ + optionalScope("p", null, null) + componentFilter + " " + cursorClause + """
                order by p.plan_date desc, p.id desc
                limit :limit
                """;
    }

    private String orderDetailSql(String cursorClause, int limit) {
        return """
                select 'DURATION' as metric, 'COUNTED' as component, o.id, o.order_no as source_no, 'ORDER' as source_type,
                       o.building_id, b.building_no, b.name as building_name,
                       o.elevator_id, e.elevator_no, o.actual_date as metric_date, o.actual_date as biz_date,
                       cast(null as datetime) as event_time, o.status,
                       cast(null as decimal(18,4)) as quantity, cast(null as decimal(12,2)) as unit_price,
                       cast(null as decimal(12,2)) as amount,
                       case when o.plan_date is null then 0
                            else timestampdiff(minute, timestamp(o.plan_date), timestamp(o.actual_date))
                       end as duration_minutes,
                       1 as counted,
                       case when o.plan_date is null then '已有实际完成日；无计划日，耗时按 0 分钟计入'
                            else concat('已有实际完成日；实际完成日-计划日=',
                                 timestampdiff(minute, timestamp(o.plan_date), timestamp(o.actual_date)), ' 分钟')
                       end as include_reason,
                       mp.plan_no as related_source_no, o.order_no as title
                from maintenance_order o
                join elevator e on e.id = o.elevator_id
                join building b on b.id = o.building_id
                left join maintenance_plan mp on mp.id = o.plan_id
                where o.actual_date >= :start and o.actual_date < :end and o.actual_date is not null
                  and coalesce(o.status, '') not in ('VOID', 'CANCELLED')
                """ + optionalScope("o", null, null) + " " + cursorClause + """
                order by o.actual_date desc, o.id desc
                limit :limit
                """;
    }

    private String faultDetailSql(String component, String cursorClause, int limit, MapSqlParameterSource params) {
        params.addValue("component", component);
        String repeatCondition = """
                exists (
                    select 1 from fault_report f2
                    where f2.elevator_id = f.elevator_id and f2.fault_type = f.fault_type
                      and coalesce(f2.status, '') <> 'CANCELLED'
                      and (f2.report_time < f.report_time or (f2.report_time = f.report_time and f2.id < f.id))
                      and f2.report_time >= f.report_time - interval 30 day limit 1)
                """;
        String componentFilter = switch (component) {
            case "NUMERATOR" -> " and " + repeatCondition;
            case "NON_REPEAT" -> " and not " + repeatCondition;
            default -> "";
        };
        return """
                select 'REPEAT' as metric, :component as component, f.id, f.fault_no as source_no, 'FAULT' as source_type,
                       f.building_id, b.building_no, b.name as building_name,
                       f.elevator_id, e.elevator_no, date(f.report_time) as metric_date,
                       cast(null as date) as biz_date, f.report_time as event_time, f.status,
                       cast(null as decimal(18,4)) as quantity, cast(null as decimal(12,2)) as unit_price,
                       cast(null as decimal(12,2)) as amount, cast(null as signed) as duration_minutes,
                       case when """ + repeatCondition + " then 1 else 0 end as counted, " + """
                       case when """ + repeatCondition + """
                            then '同电梯同事件类型在前序 30 天内已出现'
                            else '窗口内有效事件；前序 30 天无同电梯同类型事件'
                       end as include_reason,
                       f.order_no as related_source_no, concat(f.fault_no, ' ', f.fault_type) as title
                from fault_report f
                join elevator e on e.id = f.elevator_id
                join building b on b.id = f.building_id
                where f.report_time >= :startTime and f.report_time < :endTime
                  and coalesce(f.status, '') <> 'CANCELLED'
                """ + optionalScope("f", null, null) + componentFilter + " " + cursorClause + """
                order by f.report_time desc, f.id desc
                limit :limit
                """;
    }

    private String partDetailSql(String cursorClause, int limit) {
        return """
                select 'PART' as metric, 'COUNTED' as component, p.id,
                       concat('PART-', lpad(p.id, 8, '0')) as source_no,
                       coalesce(nullif(p.ref_type, ''), 'PART') as source_type,
                       p.building_id, b.building_no, b.name as building_name,
                       p.elevator_id, e.elevator_no, p.replace_date as metric_date, p.replace_date as biz_date,
                       cast(null as datetime) as event_time, p.status,
                       cast(p.quantity as decimal(18,4)) as quantity, p.unit_price,
                       cast(p.quantity * p.unit_price as decimal(12,2)) as amount,
                       cast(null as signed) as duration_minutes, 1 as counted,
                       concat('更换日在窗口内且未作废；', p.quantity, ' × ¥', p.unit_price,
                              ' = ¥', format(p.quantity * p.unit_price, 2)) as include_reason,
                       p.ref_no as related_source_no, p.part_name as title
                from part_replacement p
                join elevator e on e.id = p.elevator_id
                join building b on b.id = p.building_id
                where p.replace_date >= :start and p.replace_date < :end and p.replace_date is not null
                  and coalesce(p.status, 'NORMAL') = 'NORMAL'
                """ + optionalScope("p", null, null) + " " + cursorClause + """
                order by p.replace_date desc, p.id desc
                limit :limit
                """;
    }

    private String riskDetailSql(String component, LocalDate start, LocalDate end, LocalDate asOf,
                                 Long buildingId, Long elevatorId,
                                 String cursorClause, MapSqlParameterSource p, int limit) {
        p.addValue("buildingId", buildingId);
        p.addValue("elevatorId", elevatorId);
        p.addValue("asOf", asOf);
        p.addValue("dueEnd", asOf.plusDays(30));
        String scopeFragment = literalScope("x", buildingId, elevatorId);
        List<String> unions = new ArrayList<>();
        if (!"RISK_OPEN".equals(component) && !"RISK_REPEAT".equals(component) && !"RISK_INSPECTION".equals(component)) {
            unions.add(riskOverdueUnion());
        }
        if (!"RISK_OVERDUE".equals(component) && !"RISK_REPEAT".equals(component) && !"RISK_INSPECTION".equals(component)) {
            unions.add(riskOpenUnion());
        }
        if (!"RISK_OVERDUE".equals(component) && !"RISK_OPEN".equals(component) && !"RISK_INSPECTION".equals(component)) {
            unions.add(riskRepeatUnion());
        }
        if (!"RISK_OVERDUE".equals(component) && !"RISK_OPEN".equals(component) && !"RISK_REPEAT".equals(component)) {
            unions.add(riskInspectionUnion());
        }
        return """
                select * from (
                """ + String.join(" union all ", unions) + """
                ) x
                where 1=1
                """ + scopeFragment + " " + cursorClause + """
                order by x.sort_date desc, x.sort_no desc, x.sort_id desc
                limit :limit
                """;
    }

    private String riskOverdueUnion() {
        return """
                select 'RISK' metric, 'RISK_OVERDUE' component, p.id, p.plan_no source_no, 'PLAN' source_type,
                       p.building_id, b.building_no, b.name building_name, p.elevator_id, e.elevator_no,
                       p.plan_date metric_date, p.plan_date biz_date, cast(null as datetime) event_time, p.status,
                       cast(null as decimal(18,4)) quantity, cast(null as decimal(12,2)) unit_price,
                       cast(null as decimal(12,2)) amount, cast(null as signed) duration_minutes, 1 counted,
                       concat('逾期计划：窗口内到期且截至快照日仍为', p.status) include_reason,
                       cast(null as char(24)) related_source_no, p.plan_no title,
                       p.plan_date sort_date, p.plan_no sort_no, p.id sort_id
                from maintenance_plan p
                join elevator e on e.id=p.elevator_id join building b on b.id=p.building_id
                where p.plan_date >= :start and p.plan_date < :end and p.plan_date <= :asOf
                  and p.status in ('PENDING','DELAYED','SUSPENDED')
                """ + optionalScope("p", null, null);
    }

    private String riskOpenUnion() {
        return """
                select 'RISK', 'RISK_OPEN', f.id, f.fault_no, 'FAULT',
                       f.building_id, b.building_no, b.name, f.elevator_id, e.elevator_no,
                       date(f.report_time), cast(null as date), f.report_time, f.status,
                       cast(null as decimal(18,4)), cast(null as decimal(12,2)), cast(null as decimal(12,2)),
                       cast(null as signed), 1, concat('未完成事件：窗口内报修且状态为', f.status),
                       f.order_no, concat(f.fault_no,' ',f.fault_type),
                       date(f.report_time), f.fault_no, f.id
                from fault_report f
                join elevator e on e.id=f.elevator_id join building b on b.id=f.building_id
                where f.report_time >= :startTime and f.report_time < :endTime
                  and coalesce(f.status,'') not in ('FIXED','CANCELLED')
                """ + optionalScope("f", null, null);
    }

    private String riskRepeatUnion() {
        return """
                select 'RISK', 'RISK_REPEAT', f.id, f.fault_no, 'FAULT',
                       f.building_id, b.building_no, b.name, f.elevator_id, e.elevator_no,
                       date(f.report_time), cast(null as date), f.report_time, f.status,
                       cast(null as decimal(18,4)), cast(null as decimal(12,2)), cast(null as decimal(12,2)),
                       cast(null as signed), 1, '重复事件：同电梯同类型 30 天内再次出现',
                       f.order_no, concat(f.fault_no,' ',f.fault_type),
                       date(f.report_time), f.fault_no, f.id
                from fault_report f
                join elevator e on e.id=f.elevator_id join building b on b.id=f.building_id
                where f.report_time >= :startTime and f.report_time < :endTime
                  and coalesce(f.status,'') <> 'CANCELLED'
                  and exists (
                      select 1 from fault_report f2 where f2.elevator_id=f.elevator_id
                        and f2.fault_type=f.fault_type and coalesce(f2.status,'') <> 'CANCELLED'
                        and (f2.report_time < f.report_time or (f2.report_time=f.report_time and f2.id<f.id))
                        and f2.report_time >= f.report_time - interval 30 day limit 1)
                """ + optionalScope("f", null, null);
    }

    private String riskInspectionUnion() {
        return """
                select 'RISK', 'RISK_INSPECTION', e.id, e.elevator_no, 'ELEVATOR',
                       e.building_id, b.building_no, b.name, e.id, e.elevator_no,
                       e.next_inspect_date, cast(null as date), cast(null as datetime), e.status,
                       cast(null as decimal(18,4)), cast(null as decimal(12,2)), cast(null as decimal(12,2)),
                       cast(null as signed), 1, '未来 30 天检验到期',
                       cast(null as char(24)), concat(e.elevator_no,' 检验到期'),
                       e.next_inspect_date, e.elevator_no, e.id
                from elevator e join building b on b.id=e.building_id
                where e.next_inspect_date > :asOf and e.next_inspect_date <= :dueEnd
                """ + optionalScope("e", null, null);
    }

    private String buildCursorClause(String metric, String cursor, MapSqlParameterSource p, int requestedLimit) {
        p.addValue("limit", pageLimit(requestedLimit));
        if (cursor == null || cursor.isBlank()) {
            return "";
        }
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String[] parts = decoded.split("\\|", -1);
        return switch (metric) {
            case "PLAN", "DURATION", "PART" -> {
                p.addValue("cursorDate", LocalDate.parse(parts[0]));
                p.addValue("cursorId", Long.parseLong(parts[1]));
                yield switch (metric) {
                    case "PLAN" -> " and (p.plan_date < :cursorDate or (p.plan_date = :cursorDate and p.id < :cursorId))";
                    case "DURATION" -> " and (o.actual_date < :cursorDate or (o.actual_date = :cursorDate and o.id < :cursorId))";
                    default -> " and (p.replace_date < :cursorDate or (p.replace_date = :cursorDate and p.id < :cursorId))";
                };
            }
            case "REPEAT" -> {
                p.addValue("cursorTime", LocalDateTime.parse(parts[0]));
                p.addValue("cursorId", Long.parseLong(parts[1]));
                yield " and (f.report_time < :cursorTime or (f.report_time = :cursorTime and f.id < :cursorId))";
            }
            case "RISK" -> {
                p.addValue("cursorDate", LocalDate.parse(parts[0]));
                p.addValue("cursorNo", parts[1]);
                p.addValue("cursorId", Long.parseLong(parts[2]));
                yield " and (x.sort_date < :cursorDate or (x.sort_date = :cursorDate and "
                        + "(x.sort_no < :cursorNo or (x.sort_no = :cursorNo and x.sort_id < :cursorId))))";
            }
            default -> "";
        };
    }

    public static String encodeCursor(Object... values) {
        List<String> parts = new ArrayList<>();
        for (Object value : values) {
            parts.add(String.valueOf(value));
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
    }

    private int pageLimit(int requested) {
        return Math.min(Math.max(requested, 1), 200);
    }

    private void createIndexIfMissing(String table, String index, String definition) {
        try {
            Integer count = jdbc.getJdbcOperations().queryForObject("""
                    select count(*) from information_schema.statistics
                    where table_schema = database() and table_name = ? and index_name = ?
                    """, Integer.class, table, index);
            if (count != null && count == 0) {
                jdbc.getJdbcOperations().execute("alter table " + table + " add index " + index + " " + definition);
            }
        } catch (Exception ignored) {
            // 索引是性能增强；缺权限或旧库时查询仍可执行。
        }
    }

    private String scope(String alias, Long buildingId, Long elevatorId) {
        // 快照路径的参数一定存在；:buildingId / :elevatorId 为 null 时数据库走 is null 判断。
        return optionalScope(alias, buildingId, elevatorId);
    }

    private String literalScope(String alias, Long buildingId, Long elevatorId) {
        StringBuilder sb = new StringBuilder();
        if (buildingId != null) {
            sb.append(" and ").append(alias).append(".building_id = :buildingId");
        }
        if (elevatorId != null) {
            sb.append(" and ").append(alias).append(".elevator_id = :elevatorId");
        }
        return sb.toString();
    }

    private String optionalScope(String alias, Long buildingId, Long elevatorId) {
        StringBuilder sb = new StringBuilder(" and (:buildingId is null");
        if (buildingId != null) {
            sb.setLength(0);
            sb.append(" and ").append(alias).append(".building_id = :buildingId");
        } else {
            sb.append(" or ").append(alias).append(".building_id = :buildingId)");
        }
        if (elevatorId != null) {
            sb.append(" and ").append(alias).append(".elevator_id = :elevatorId");
        } else {
            sb.append(" and (:elevatorId is null or ").append(alias).append(".elevator_id = :elevatorId)");
        }
        return sb.toString();
    }

    private MapSqlParameterSource params(LocalDate start, LocalDate end, Long buildingId, Long elevatorId) {
        return new MapSqlParameterSource()
                .addValue("start", Date.valueOf(start))
                .addValue("end", Date.valueOf(end))
                .addValue("buildingId", buildingId)
                .addValue("elevatorId", elevatorId);
    }

    private MapSqlParameterSource datetimeParams(LocalDate start, LocalDate end, Long buildingId, Long elevatorId) {
        return params(start, end, buildingId, elevatorId)
                .addValue("startTime", Timestamp.valueOf(start.atStartOfDay()))
                .addValue("endTime", Timestamp.valueOf(end.atStartOfDay()));
    }
}
