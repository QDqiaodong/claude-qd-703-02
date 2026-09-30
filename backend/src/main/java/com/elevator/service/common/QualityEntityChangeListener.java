package com.elevator.service.common;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/**
 * 质量统计源实体监听器。JPA 实体监听器不由 Spring 管理，使用静态桥在应用启动时注入发布器。
 */
public class QualityEntityChangeListener {

    public interface Publisher {
        void publishQualityDataChanged();
    }

    private static Publisher publisher;

    private QualityEntityChangeListener() {
    }

    public static void bind(Publisher value) {
        publisher = value;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void afterChange(Object entity) {
        Publisher current = publisher;
        if (current != null) {
            current.publishQualityDataChanged();
        }
    }
}
