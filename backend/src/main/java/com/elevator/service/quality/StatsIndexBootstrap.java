package com.elevator.service.quality;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 质量统计依赖的复合索引在应用启动时幂等补齐：schema.sql 已在新库带上；
 * 旧库/批量扩充库启动时这里先查 information_schema，缺失才 CREATE INDEX。
 *
 * <p>这些索引让 10 万级计划/工单/事件/配件下的窗口聚合走范围扫描，而不是全表过滤。
 */
@Component
public class StatsIndexBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StatsIndexBootstrap.class);

    /** 顺序：表名 → (索引名 → 列定义)，LinkedHashMap 保证稳定创建顺序 */
    private static final Map<String, Map<String, String>> INDEX_DDL = new LinkedHashMap<>();

    static {
        add("maintenance_plan", "idx_plan_date_build_elev",
                "(plan_date, building_id, elevator_id, status)");
        add("maintenance_order", "idx_order_actual",
                "(actual_date, building_id, elevator_id, status)");
        add("fault_report", "idx_fault_time_build_elev",
                "(report_time, building_id, elevator_id, status, fault_type)");
        add("fault_report", "idx_fault_elev_type_time",
                "(elevator_id, fault_type, report_time, status)");
        add("part_replacement", "idx_part_date_build_elev",
                "(replace_date, building_id, elevator_id, status)");
        add("elevator", "idx_elevator_build_next",
                "(building_id, next_inspect_date)");
    }

    private static void add(String table, String index, String columns) {
        INDEX_DDL.computeIfAbsent(table, k -> new LinkedHashMap<>()).put(index, columns);
    }

    private final JdbcTemplate jdbc;

    public StatsIndexBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        for (Map.Entry<String, Map<String, String>> table : INDEX_DDL.entrySet()) {
            for (Map.Entry<String, String> idx : table.getValue().entrySet()) {
                if (!indexExists(schema, table.getKey(), idx.getKey())) {
                    try {
                        jdbc.execute("CREATE INDEX " + idx.getKey() + " ON " + table.getKey()
                                + " " + idx.getValue());
                        log.info("质量统计索引已创建：{}.{}", table.getKey(), idx.getKey());
                    } catch (Exception e) {
                        // 并发多实例同时启动可能竞争建索引，已存在则忽略
                        log.debug("索引创建跳过：{}.{}（{}）", table.getKey(), idx.getKey(), e.getMessage());
                    }
                }
            }
        }
    }

    private boolean indexExists(String schema, String table, String index) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = ? AND table_name = ? AND index_name = ?",
                Integer.class, schema, table, index);
        return cnt != null && cnt > 0;
    }

    /** 供测试/文档引用 */
    public static List<String> indexNames() {
        return INDEX_DDL.values().stream().flatMap(m -> m.keySet().stream()).toList();
    }
}
