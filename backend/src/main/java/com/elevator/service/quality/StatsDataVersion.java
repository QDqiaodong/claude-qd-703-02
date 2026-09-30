package com.elevator.service.quality;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 质量统计的数据版本号：任一参与统计的表（楼宇/电梯/计划/工单/事件/配件）发生
 * insert/update/delete 并【事务提交后】递增，单调不降。
 *
 * <p>缓存键携带版本号，变更后旧键自然失效；计算失败不递增也不写缓存。
 * 监听器见 {@link StatsInvalidationIntegrator}（post-commit 事件，回滚不递增）。
 */
@Component
public class StatsDataVersion {

    /** 各表独立版本，缓存键把 6 个版本拼起来，互不干扰 */
    private final AtomicLong building = new AtomicLong();
    private final AtomicLong elevator = new AtomicLong();
    private final AtomicLong plan = new AtomicLong();
    private final AtomicLong order = new AtomicLong();
    private final AtomicLong fault = new AtomicLong();
    private final AtomicLong part = new AtomicLong();

    public String token() {
        return "b" + building.get()
                + ".e" + elevator.get()
                + ".p" + plan.get()
                + ".o" + order.get()
                + ".f" + fault.get()
                + ".r" + part.get();
    }

    public void bump(String entityName) {
        if (entityName == null) {
            return;
        }
        switch (entityName) {
            case "com.elevator.service.domain.Building" -> building.incrementAndGet();
            case "com.elevator.service.domain.Elevator" -> elevator.incrementAndGet();
            case "com.elevator.service.domain.MaintenancePlan" -> plan.incrementAndGet();
            case "com.elevator.service.domain.MaintenanceOrder" -> order.incrementAndGet();
            case "com.elevator.service.domain.FaultReport" -> fault.incrementAndGet();
            case "com.elevator.service.domain.PartReplacement" -> part.incrementAndGet();
            default -> { }
        }
    }
}
