package com.elevator.service.quality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.elevator.service.common.BizException;
import com.elevator.service.quality.QualityStatsRepository.BuildingRow;
import com.elevator.service.quality.QualityStatsRepository.DayFault;
import com.elevator.service.quality.QualityStatsRepository.DayOrder;
import com.elevator.service.quality.QualityStatsRepository.DayPart;
import com.elevator.service.quality.QualityStatsRepository.DayPlan;
import com.elevator.service.quality.QualityStatsRepository.DetailQueryResult;

/**
 * 维保服务质量分析的快照组装：卡片 / 趋势 / 楼宇排行来自同一次聚合。
 *
 * <p>卡片数字是按日聚合行在内存里的折叠求和，周/月桶由日桶折叠而来，
 * 因此「趋势合计 = 卡片」在结构上恒成立，不存在两套计算。
 */
@Service
public class QualityStatsService {

    private static final DateTimeFormatter SNAP_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final QualityStatsRepository repo;
    private final StatsDataVersion dataVersion;
    private final SnapshotCache cache;
    private final TransactionTemplate txTemplate;
    private final ZoneId zoneId;

    public QualityStatsService(QualityStatsRepository repo,
                               StatsDataVersion dataVersion,
                               SnapshotCache cache,
                               org.springframework.transaction.PlatformTransactionManager txManager) {
        this.repo = repo;
        this.dataVersion = dataVersion;
        this.cache = cache;
        // 只读 + REPEATABLE READ：整份快照的全部聚合共享同一 Read View
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setReadOnly(true);
        this.txTemplate.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.zoneId = ZoneId.of(QualityDict.ZONE);
    }

    /** 快照（短时缓存 + 同键并发合并；一次计算整体包在单个 REPEATABLE READ 事务内） */
    public QualityViews.QualitySnapshot snapshot(QualityQuery q) {
        try {
            return cache.getOrLoad(q,
                    () -> txTemplate.execute(status -> buildSnapshot(q, LocalDate.now(zoneId)))).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("统计计算被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new BizException("统计计算失败：" + (cause == null ? e.getMessage() : cause.getMessage()));
        }
    }

    /** 导出用：在调用方导出事务内重算快照（与明细共享同一 REPEATABLE READ 快照，不经缓存） */
    public QualityViews.QualitySnapshot snapshotForExport(QualityQuery q) {
        return buildSnapshot(q, LocalDate.now(zoneId));
    }

    /** 名称补齐后的明细分页（total 与卡片计数同 WHERE） */
    @Transactional(readOnly = true)
    public QualityViews.DetailPage details(QualityQuery q, String metric, String scope,
                                           String cursor, int size) {
        validateMetric(metric);
        String useScope = scope == null || scope.isBlank() ? "DENOMINATOR" : scope.toUpperCase();
        LocalDate today = LocalDate.now(zoneId);
        DetailQueryResult result = repo.details(q, metric, useScope, cursor, size, today);
        long total = repo.countDetails(q, metric, useScope, today);
        return new QualityViews.DetailPage(metric, useScope,
                enrich(result.rows()), result.nextCursor(), total, size);
    }

    /**
     * 导出名次/明细枚举（导出事务内逐页取数）。游标在 export 服务里持有。
     */
    @Transactional(readOnly = true)
    public DetailQueryResult detailPage(QualityQuery q, String metric, String scope,
                                        String cursor, int size, LocalDate today) {
        return repo.details(q, metric, scope, cursor, size, today);
    }

    /** 导出前一次性预载名称（事务内） */
    public Map<Long, String> loadAllElevatorNos() {
        return repo.allElevatorNos();
    }

    public Map<Long, BuildingRow> loadAllBuildings() {
        return repo.allBuildingRows();
    }

    public List<QualityViews.DetailRow> enrich(List<QualityViews.DetailRow> rows) {
        return enrich(rows, null, null);
    }

    /** 导出全量明细时可传入预载的全量名称，避免逐页查询 */
    public List<QualityViews.DetailRow> enrich(List<QualityViews.DetailRow> rows,
                                               Map<Long, String> elevatorNameCache,
                                               Map<Long, BuildingRow> buildingCache) {
        if (rows.isEmpty()) {
            return rows;
        }
        Set<Long> elevatorIds = new HashSet<>();
        Set<Long> buildingIds = new HashSet<>();
        for (QualityViews.DetailRow r : rows) {
            if (r.elevatorId() != null) {
                elevatorIds.add(r.elevatorId());
            }
            if (r.buildingId() != null) {
                buildingIds.add(r.buildingId());
            }
        }
        Map<Long, String> elevators = elevatorNameCache == null
                ? repo.elevatorNos(elevatorIds) : elevatorNameCache;
        Map<Long, BuildingRow> buildings = buildingCache == null
                ? repo.buildingRows(buildingIds) : buildingCache;
        List<QualityViews.DetailRow> out = new ArrayList<>(rows.size());
        for (QualityViews.DetailRow r : rows) {
            String elevatorNo = r.elevatorNo() != null ? r.elevatorNo()
                    : elevators.getOrDefault(r.elevatorId(), "");
            BuildingRow b = buildings.get(r.buildingId());
            out.add(new QualityViews.DetailRow(r.id(), r.sourceType(), r.sourceNo(),
                    r.elevatorId(), elevatorNo,
                    r.buildingId(), b == null ? null : b.buildingNo(), b == null ? null : b.name(),
                    r.sourceDate(), r.count1(), r.count2(), r.amount(), r.value1(), r.value2(),
                    r.included(), r.reason()));
        }
        return out;
    }

    // =====================================================================
    // 快照组装
    // =====================================================================

    private QualityViews.QualitySnapshot buildSnapshot(QualityQuery q, LocalDate today) {
        // 1) 四条按日聚合（全部在 DB 内 GROUP BY）
        Map<LocalDate, DayPlan> planByDay = index(repo.planDaily(q), DayPlan::day);
        Map<LocalDate, DayOrder> orderByDay = index(repo.orderDaily(q), DayOrder::day);
        Map<LocalDate, DayFault> faultByDay = index(repo.faultDaily(q), DayFault::day);
        Map<LocalDate, DayPart> partByDay = index(repo.partDaily(q), DayPart::day);

        // 2) 卡片 = 日聚合折叠求和（与趋势同一数据源）
        long planDue = sum(planByDay.values(), DayPlan::due);
        long planOnTime = sum(planByDay.values(), DayPlan::onTime);
        long orderCount = sum(orderByDay.values(), DayOrder::count);
        long durationDays = sum(orderByDay.values(), DayOrder::durationDays);
        long eventCount = sum(faultByDay.values(), DayFault::count);
        long eventRepeat = sum(faultByDay.values(), DayFault::repeat);
        long partRecords = sum(partByDay.values(), DayPart::count);
        long partQuantity = sum(partByDay.values(), DayPart::quantity);
        BigDecimal partAmount = QualityDict.scale2(
                partByDay.values().stream().map(DayPart::amount).reduce(BigDecimal.ZERO, BigDecimal::add));

        // 3) 趋势桶：先算每个自然日的五项，再按粒度折叠；空桶补零
        List<BucketAcc> buckets = buildBuckets(q, planByDay, orderByDay, faultByDay, partByDay);
        List<QualityViews.TrendBucket> trend = buckets.stream().map(BucketAcc::toView).toList();

        // 4) 楼宇风险排行：四条按楼宇 GROUP BY + 楼宇/电梯数一次取齐
        Map<Long, Long> overdue = repo.riskOverduePlans(q, today);
        Map<Long, Long> openEvents = repo.riskOpenEvents(q);
        Map<Long, Long> repeatEvents = repo.riskRepeatEvents(q, today);
        Map<Long, Long> inspectDue = repo.riskInspectDue(q, today);
        Map<Long, Long> elevatorCounts = repo.elevatorCounts(q);
        List<BuildingRow> buildings = repo.buildings();
        List<QualityViews.RiskRankRow> ranking = new ArrayList<>();
        for (BuildingRow b : buildings) {
            // 选了单栋/单台时只保留该范围；无范围时只要有电梯或任一分量非零就入榜（含已撤场）
            if (q.hasBuilding() && !q.buildingId().equals(b.id())) {
                continue;
            }
            long o = overdue.getOrDefault(b.id(), 0L);
            long e = openEvents.getOrDefault(b.id(), 0L);
            long r = repeatEvents.getOrDefault(b.id(), 0L);
            long i = inspectDue.getOrDefault(b.id(), 0L);
            long cnt = elevatorCounts.getOrDefault(b.id(), 0L);
            if (!q.hasBuilding() && cnt == 0 && o == 0 && e == 0 && r == 0 && i == 0) {
                continue;
            }
            int score = QualityDict.W_OVERDUE_PLAN * (int) o
                    + QualityDict.W_OPEN_EVENT * (int) e
                    + QualityDict.W_REPEAT_EVENT * (int) r
                    + QualityDict.W_INSPECT * (int) i;
            String level = QualityDict.riskLevel(score);
            ranking.add(new QualityViews.RiskRankRow(b.id(), b.buildingNo(), b.name(), b.status(),
                    com.elevator.service.domain.ElevatorDict.archiveStatusName(b.status()),
                    o, e, r, i, cnt, score, level, QualityDict.riskLevelName(level)));
        }
        // 分值降序、楼宇编号升序稳定排序
        ranking.sort((x, y) -> {
            int c = Integer.compare(y.riskScore(), x.riskScore());
            return c != 0 ? c : x.buildingNo().compareTo(y.buildingNo());
        });

        // 5) 范围元信息
        Long scopeBuildingCount = (long) buildings.size();
        if (q.hasBuilding()) {
            scopeBuildingCount = buildings.stream().filter(b -> b.id().equals(q.buildingId())).count();
        }
        Long scopeElevatorCount = elevatorCounts.values().stream().mapToLong(Long::longValue).sum();

        List<QualityViews.MetricDefView> defs = QualityDict.metricDefs().stream()
                .map(d -> new QualityViews.MetricDefView(d.key(), d.name(), d.windowAnchor(),
                        d.denominatorRule(), d.numeratorRule(), d.formula()))
                .toList();
        List<QualityViews.RiskWeightView> weights = QualityDict.riskWeights().stream()
                .map(w -> new QualityViews.RiskWeightView(w.key(), w.name(), w.weight()))
                .toList();

        LocalDateTime now = LocalDateTime.now(zoneId);
        String snapshotId = "QS-" + now.format(SNAP_FMT) + "-" + Long.toHexString(keyHash(q, dataVersion.token()));
        QualityViews.SnapshotHead head = new QualityViews.SnapshotHead(
                snapshotId, now, dataVersion.token(), q.zone(),
                q.start(), q.end(), q.buildingId(), q.elevatorId(), q.granularity(),
                defs, weights, scopeBuildingCount, scopeElevatorCount);

        BigDecimal planRate = rate(planOnTime, planDue);
        BigDecimal eventRate = rate(eventRepeat, eventCount);
        BigDecimal avgDuration = orderCount == 0 ? null
                : QualityDict.scale2(BigDecimal.valueOf(durationDays)
                        .divide(BigDecimal.valueOf(orderCount), 4, RoundingMode.HALF_UP));
        BigDecimal partValue = partAmount;

        QualityDict.MetricDef planDef = def(QualityDict.M_PLAN_ON_TIME);
        QualityDict.MetricDef orderDef = def(QualityDict.M_ORDER_DURATION);
        QualityDict.MetricDef eventDef = def(QualityDict.M_EVENT_REPEAT);
        QualityDict.MetricDef partDef = def(QualityDict.M_PART_COST);
        QualityDict.MetricDef riskDef = def(QualityDict.M_RISK_RANK);

        List<QualityViews.MetricCard> cards = List.of(
                new QualityViews.MetricCard(planDef.key(), planDef.name(), "%",
                        planOnTime, planDue, planRate, planRate, planDef.formula()),
                new QualityViews.MetricCard(orderDef.key(), orderDef.name(), "天",
                        orderCount, orderCount, null, avgDuration, orderDef.formula()),
                new QualityViews.MetricCard(eventDef.key(), eventDef.name(), "%",
                        eventRepeat, eventCount, eventRate, eventRate, eventDef.formula()),
                new QualityViews.MetricCard(partDef.key(), partDef.name(), "元",
                        partRecords, null, null, partValue, partDef.formula()),
                new QualityViews.MetricCard(riskDef.key(), riskDef.name(), "分",
                        ranking.stream().filter(x -> !"LOW".equals(x.riskLevel())).count(),
                        (long) ranking.size(), null,
                        ranking.isEmpty() ? null : BigDecimal.valueOf(ranking.get(0).riskScore()),
                        riskDef.formula()));

        boolean empty = planDue == 0 && orderCount == 0 && eventCount == 0
                && partRecords == 0 && ranking.isEmpty();
        return new QualityViews.QualitySnapshot(head, cards, trend, ranking, empty);
    }

    private List<BucketAcc> buildBuckets(QualityQuery q,
                                         Map<LocalDate, DayPlan> planByDay,
                                         Map<LocalDate, DayOrder> orderByDay,
                                         Map<LocalDate, DayFault> faultByDay,
                                         Map<LocalDate, DayPart> partByDay) {
        // 先生成有序的日桶（空日补零），再按粒度折叠，保证周/月合计与日合计一致
        Map<LocalDate, BucketAcc> dayBuckets = new java.util.TreeMap<>();
        for (LocalDate d = q.start(); !d.isAfter(q.end()); d = d.plusDays(1)) {
            DayPlan p = planByDay.get(d);
            DayOrder o = orderByDay.get(d);
            DayFault f = faultByDay.get(d);
            DayPart r = partByDay.get(d);
            BucketAcc acc = new BucketAcc(d, d);
            acc.add(p == null ? 0 : p.due(), p == null ? 0 : p.onTime(),
                    o == null ? 0 : o.count(), o == null ? 0 : o.durationDays(),
                    f == null ? 0 : f.count(), f == null ? 0 : f.repeat(),
                    r == null ? 0 : r.count(), r == null ? 0 : r.quantity(),
                    r == null ? BigDecimal.ZERO : r.amount());
            dayBuckets.put(d, acc);
        }
        String g = q.granularity() == null ? "DAY" : q.granularity().toUpperCase();
        Map<LocalDate, BucketAcc> grouped = new java.util.TreeMap<>();
        for (BucketAcc day : dayBuckets.values()) {
            LocalDate key = switch (g) {
                case "WEEK" -> day.first.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                case "MONTH" -> day.first.withDayOfMonth(1);
                default -> day.first;
            };
            grouped.computeIfAbsent(key, k -> new BucketAcc(k, day.first)).merge(day);
        }
        return new ArrayList<>(grouped.values());
    }

    private static QualityDict.MetricDef def(String key) {
        return QualityDict.metricDefs().stream().filter(d -> d.key().equals(key)).findFirst().orElseThrow();
    }

    private static void validateMetric(String metric) {
        boolean known = QualityDict.metricDefs().stream().anyMatch(d -> d.key().equals(metric))
                || metric.equals(QualityDict.M_RISK_OVERDUE_PLAN)
                || metric.equals(QualityDict.M_RISK_OPEN_EVENT)
                || metric.equals(QualityDict.M_RISK_REPEAT_EVENT)
                || metric.equals(QualityDict.M_RISK_INSPECT);
        if (!known) {
            throw new BizException("未知的指标 key：" + metric);
        }
    }

    private static BigDecimal rate(long num, long den) {
        if (den == 0) {
            return null;
        }
        return QualityDict.scale2(BigDecimal.valueOf(num)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(den), 4, RoundingMode.HALF_UP));
    }

    private static <T> Map<LocalDate, T> index(List<T> list, java.util.function.Function<T, LocalDate> key) {
        Map<LocalDate, T> map = new HashMap<>(list.size() * 2);
        for (T t : list) {
            map.put(key.apply(t), t);
        }
        return map;
    }

    private static <T> long sum(Iterable<T> items, java.util.function.ToLongFunction<T> fn) {
        long s = 0;
        for (T t : items) {
            s += fn.applyAsLong(t);
        }
        return s;
    }

    private static int keyHash(QualityQuery q, String version) {
        return (q.toString() + "|" + version).hashCode();
    }

    /** 趋势桶累加器 */
    private static final class BucketAcc {
        private final LocalDate first;
        private LocalDate last;
        private long planDue;
        private long planOnTime;
        private long orderCount;
        private long durationDays;
        private long eventCount;
        private long eventRepeat;
        private long partRecords;
        private long partQuantity;
        private BigDecimal partAmount = BigDecimal.ZERO;

        BucketAcc(LocalDate first, LocalDate anyDay) {
            this.first = first;
            this.last = anyDay;
        }

        void add(long pd, long po, long oc, long dd, long ec, long er, long pr, long pq, BigDecimal pa) {
            this.planDue += pd;
            this.planOnTime += po;
            this.orderCount += oc;
            this.durationDays += dd;
            this.eventCount += ec;
            this.eventRepeat += er;
            this.partRecords += pr;
            this.partQuantity += pq;
            this.partAmount = this.partAmount.add(pa);
        }

        void merge(BucketAcc other) {
            add(other.planDue, other.planOnTime, other.orderCount, other.durationDays,
                    other.eventCount, other.eventRepeat, other.partRecords, other.partQuantity,
                    other.partAmount);
            if (other.last != null && (last == null || other.last.isAfter(last))) {
                last = other.last;
            }
        }

        QualityViews.TrendBucket toView() {
            String label;
            String key = first.toString();
            if (first.equals(last)) {
                label = first.toString();
            } else if (first.getMonth() == last.getMonth() && first.getYear() == last.getYear()
                    && first.getDayOfMonth() == 1
                    && first.plusMonths(1).minusDays(1).equals(last)) {
                label = first.getYear() + "-" + String.format("%02d", first.getMonthValue());
            } else {
                label = first + " ~ " + last;
            }
            return new QualityViews.TrendBucket(key, label,
                    planDue, planOnTime, orderCount, durationDays,
                    eventCount, eventRepeat, partRecords, partQuantity,
                    QualityDict.scale2(partAmount),
                    rate(planOnTime, planDue), rate(eventRepeat, eventCount),
                    orderCount == 0 ? null
                            : QualityDict.scale2(BigDecimal.valueOf(durationDays)
                            .divide(BigDecimal.valueOf(orderCount), 4, RoundingMode.HALF_UP)));
        }
    }
}
