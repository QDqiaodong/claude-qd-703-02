package com.elevator.service.quality;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 统计快照短时缓存 + 同键并发合并。
 *
 * <ul>
 *   <li>缓存键覆盖：开始/结束日、粒度、楼宇、电梯、时区、以及六张表的数据版本；</li>
 *   <li>同键并发请求只计算一次（{@link CompletableFuture} 合并），其余请求等同一结果；</li>
 *   <li>计算失败立即移除条目，绝不留下半成品缓存；</li>
 *   <li>数据变更 → post-commit 版本号递增 → 旧键不再命中，等于即时失效；TTL 兜底外部直接改库的情况。</li>
 * </ul>
 */
@Component
public class SnapshotCache {

    /** value=数据版本快照（仅头部需要，明细按行重算） */
    private record Entry(CompletableFuture<QualityViews.QualitySnapshot> future, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final StatsDataVersion dataVersion;

    public SnapshotCache(StatsDataVersion dataVersion) {
        this.dataVersion = dataVersion;
    }

    /**
     * 同键请求合并：命中直接返回缓存 Future；否则以 loader 启动一次计算并占位。
     */
    public CompletableFuture<QualityViews.QualitySnapshot> getOrLoad(QualityQuery q,
                                                                     java.util.function.Supplier<QualityViews.QualitySnapshot> loader) {
        String key = key(q);
        purgeIfNeeded();
        Entry hit = entries.get(key);
        if (hit != null && hit.expiresAt().isAfter(Instant.now()) && !hit.future().isCompletedExceptionally()) {
            return hit.future();
        }
        CompletableFuture<QualityViews.QualitySnapshot> future = new CompletableFuture<>();
        Entry candidate = new Entry(future, Instant.now().plus(Duration.ofSeconds(QualityDict.SNAPSHOT_TTL_SECONDS)));
        // compute 保证并发下只有一个占位者
        Entry winner = entries.compute(key, (k, old) -> {
            if (old != null && old.expiresAt().isAfter(Instant.now())
                    && !old.future().isCompletedExceptionally()) {
                return old;
            }
            return candidate;
        });
        if (winner != candidate) {
            return winner.future();
        }
        try {
            QualityViews.QualitySnapshot snapshot = loader.get();
            future.complete(snapshot);
        } catch (RuntimeException e) {
            entries.remove(key, candidate);
            future.completeExceptionally(e);
        }
        return future;
    }

    /** 兼容带异步 loader 的场景（当前同步计算，但接口保留异常不缓存语义） */
    public String currentKey(QualityQuery q) {
        return key(q);
    }

    private String key(QualityQuery q) {
        return q.start() + "|" + q.end()
                + "|" + q.granularity()
                + "|b=" + (q.buildingId() == null ? "" : q.buildingId())
                + "|e=" + (q.elevatorId() == null ? "" : q.elevatorId())
                + "|z=" + q.zone()
                + "|v=" + dataVersion.token();
    }

    /** 条目超上限或过期时清理 */
    private void purgeIfNeeded() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now)
                || e.getValue().future().isCompletedExceptionally());
        if (entries.size() <= QualityDict.SNAPSHOT_MAX_ENTRIES) {
            return;
        }
        entries.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().expiresAt()))
                .limit(entries.size() - QualityDict.SNAPSHOT_MAX_ENTRIES / 2)
                .forEach(e -> entries.remove(e.getKey(), e.getValue()));
    }
}
