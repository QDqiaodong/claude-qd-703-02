package com.elevator.service.quality;

import java.util.List;
import java.util.Map;

import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostCommitDeleteEventListener;
import org.hibernate.event.spi.PostCommitInsertEventListener;
import org.hibernate.event.spi.PostCommitUpdateEventListener;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/**
 * 统计失效链路：作为 Hibernate Integrator 注册 post-commit 事件监听。
 *
 * <p>选择 post-commit（而非 @PostPersist/@PostUpdate 或事务前置回调）的原因：
 * <ul>
 *   <li>只有事务<b>提交成功后</b>才递增版本——事务回滚不产生失效噪声，也不会留下半成品缓存；</li>
 *   <li>不需要改动任何既有实体/服务，任何写入路径（计划执行、事件闭环、配件登记……）统一覆盖；</li>
 *   <li>使用专用的 PostCommit*EventListener，语义由 Hibernate 保证。</li>
 * </ul>
 *
 * <p>本 Bean 通过 {@link HibernatePropertiesCustomizer} 挂进 Spring Boot 自动配置的
 * SessionFactory，不依赖 ServiceLoader 配置文件。
 */
@Component
public class StatsInvalidationIntegrator
        implements Integrator, HibernatePropertiesCustomizer, IntegratorProvider,
        PostCommitInsertEventListener, PostCommitUpdateEventListener, PostCommitDeleteEventListener {

    private final StatsDataVersion version;

    public StatsInvalidationIntegrator(StatsDataVersion version) {
        this.version = version;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put("hibernate.integrator_provider", this);
    }

    @Override
    public List<Integrator> getIntegrators() {
        return List.of(this);
    }

    @Override
    public void integrate(Metadata metadata, SessionFactoryImplementor sessionFactory,
                          SessionFactoryServiceRegistry serviceRegistry) {
        EventListenerRegistry registry = serviceRegistry.getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_COMMIT_INSERT, this);
        registry.appendListeners(EventType.POST_COMMIT_UPDATE, this);
        registry.appendListeners(EventType.POST_COMMIT_DELETE, this);
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory,
                             SessionFactoryServiceRegistry serviceRegistry) {
        // 无需处理
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        version.bump(event.getPersister().getEntityName());
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        version.bump(event.getPersister().getEntityName());
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        version.bump(event.getPersister().getEntityName());
    }

    // ---- 提交失败（事务回滚）时不递增版本：保持空实现 ----

    @Override
    public void onPostInsertCommitFailed(PostInsertEvent event) {
        // no-op：回滚不失效缓存
    }

    @Override
    public void onPostUpdateCommitFailed(PostUpdateEvent event) {
        // no-op：回滚不失效缓存
    }

    @Override
    public void onPostDeleteCommitFailed(PostDeleteEvent event) {
        // no-op：回滚不失效缓存
    }

    /** 明确要求 post-commit 分发：回滚不触发失效 */
    @Override
    public boolean requiresPostCommitHandling(
            org.hibernate.persister.entity.EntityPersister persister) {
        return true;
    }
}
