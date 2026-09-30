<template>
  <div class="quality-page">
    <section class="panel q-filter-panel">
      <div class="panel-head">
        <h3>维保服务质量分析</h3>
        <span class="panel-sub">同一筛选、同一快照口径：总览、趋势、楼宇排行、钻取与导出</span>
      </div>
      <div class="q-filters">
        <el-date-picker
          v-model="filters.range"
          type="daterange"
          unlink-panels
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日（含）"
          end-placeholder="结束日（含）"
          size="default"
        />
        <el-select v-model="filters.buildingId" clearable filterable placeholder="全部楼宇（含已撤场）" style="width: 220px" @change="onBuildingChange">
          <el-option
            v-for="b in buildings"
            :key="b.id"
            :label="`${b.buildingNo} · ${b.name}${b.status === 'ARCHIVED' ? '（已撤场）' : ''}`"
            :value="b.id"
          />
        </el-select>
        <el-select v-model="filters.elevatorId" clearable filterable placeholder="全部电梯" style="width: 220px">
          <el-option
            v-for="e in elevatorOptions"
            :key="e.id"
            :label="`${e.elevatorNo} · ${e.brand}${e.model ? ' ' + e.model : ''}`"
            :value="e.id"
          />
        </el-select>
        <el-radio-group v-model="filters.granularity">
          <el-radio-button label="day">按天</el-radio-button>
          <el-radio-button label="week">按周</el-radio-button>
          <el-radio-button label="month">按月</el-radio-button>
        </el-radio-group>
        <el-button type="primary" :loading="loading" @click="loadSnapshot">应用筛选</el-button>
        <el-button :disabled="!snapshot" @click="exportSnapshot">导出 CSV</el-button>
      </div>
      <div class="snapshot-line" v-if="snapshot">
        <span>快照：<b>{{ snapshot.snapshotId }}</b></span>
        <span>生成：{{ formatTime(snapshot.generatedAt) }}</span>
        <span>数据版本：{{ snapshot.dataVersion }}</span>
        <span>后端区间：[{{ snapshot.filter.startInclusive }}, {{ snapshot.filter.endExclusive }})</span>
        <span>时区：Asia/Shanghai（开始日含、结束日含）</span>
      </div>
    </section>

    <el-alert
      v-if="snapshot && hasNoData"
      title="当前筛选范围内没有可统计数据"
      type="info"
      :closable="false"
      description="跨年、已撤场楼宇或仅选择单台电梯时也会返回稳定空结果；可调整日期或清空电梯筛选。"
      style="margin: 12px 0"
    />

    <section class="q-cards">
      <el-card
        v-for="card in cards"
        :key="card.key"
        class="q-card"
        shadow="hover"
        :class="`is-${card.theme}`"
        @click="openMetric(card)"
      >
        <div class="q-card-name">{{ card.name }}</div>
        <div class="q-card-value">{{ card.summary?.displayValue || '—' }}</div>
        <div class="q-card-sub">{{ card.sub }}</div>
        <div class="q-card-foot">{{ card.foot }}</div>
      </el-card>
    </section>

    <div class="q-grid">
      <section class="panel">
        <div class="panel-head">
          <h3>按{{ granularityName }}趋势</h3>
          <span class="panel-sub">各粒度合计可与上方卡片逐项核对</span>
          <div class="panel-extra">
            <el-radio-group v-model="trendMetric" size="small">
              <el-radio-button label="PLAN_ON_TIME">准时率</el-radio-button>
              <el-radio-button label="AVG_DURATION">耗时</el-radio-button>
              <el-radio-button label="REPEAT_RATE">重复率</el-radio-button>
              <el-radio-button label="PART_COST">成本</el-radio-button>
            </el-radio-group>
          </div>
        </div>
        <el-table :data="snapshot?.trends || []" size="small" border stripe height="330">
          <el-table-column prop="label" label="区间" min-width="130" fixed />
          <template v-if="trendMetric === 'PLAN_ON_TIME'">
            <el-table-column label="准时/到期" width="110" align="right">
              <template #default="{ row }">{{ row.onTimeNumerator }}/{{ row.onTimeDenominator }}</template>
            </el-table-column>
            <el-table-column label="准时率" min-width="170">
              <template #default="{ row }">
                <div class="bar-cell"><span>{{ formatRate(row.onTimeRate) }}</span><div class="bar"><i :style="{ width: pct(row.onTimeRate) }"></i></div></div>
              </template>
            </el-table-column>
          </template>
          <template v-else-if="trendMetric === 'AVG_DURATION'">
            <el-table-column prop="durationCount" label="维保记录" width="90" align="right" />
            <el-table-column label="平均耗时（小时）" min-width="170">
              <template #default="{ row }">{{ row.averageDurationHours ?? '—' }}</template>
            </el-table-column>
          </template>
          <template v-else-if="trendMetric === 'REPEAT_RATE'">
            <el-table-column label="重复/事件" width="110" align="right">
              <template #default="{ row }">{{ row.repeatNumerator }}/{{ row.repeatDenominator }}</template>
            </el-table-column>
            <el-table-column label="重复事件率" min-width="170">
              <template #default="{ row }">
                <div class="bar-cell"><span>{{ formatRate(row.repeatRate) }}</span><div class="bar danger"><i :style="{ width: pct(row.repeatRate) }"></i></div></div>
              </template>
            </el-table-column>
          </template>
          <template v-else>
            <el-table-column prop="partCount" label="配件记录" width="90" align="right" />
            <el-table-column prop="partQuantity" label="数量" width="90" align="right" />
            <el-table-column label="金额" min-width="150" align="right">
              <template #default="{ row }">¥ {{ row.partAmount }}</template>
            </el-table-column>
          </template>
        </el-table>
      </section>

      <section class="panel">
        <div class="panel-head">
          <h3>楼宇风险排行</h3>
          <span class="panel-sub">权重与后端快照一致；并列按楼宇编号升序</span>
        </div>
        <el-table :data="snapshot?.buildingRanks || []" size="small" border stripe height="330">
          <el-table-column type="index" label="#" width="42" />
          <el-table-column label="楼宇" min-width="150">
            <template #default="{ row }">
              <el-link type="primary" @click="openBuildingRisk(row)">{{ row.buildingNo }} · {{ row.buildingName }}</el-link>
              <el-tag v-if="row.buildingStatus === 'ARCHIVED'" size="small" type="info" style="margin-left: 6px">已撤场</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="overduePlanCount" label="逾期" width="58" align="right" />
          <el-table-column prop="openEventCount" label="未完成" width="66" align="right" />
          <el-table-column prop="repeatEventCount" label="重复" width="58" align="right" />
          <el-table-column prop="inspectDue30dCount" label="检验" width="58" align="right" />
          <el-table-column prop="riskScore" label="风险分" width="72" align="right">
            <template #default="{ row }"><b :class="riskClass(row.riskScore)">{{ row.riskScore }}</b></template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <section class="panel">
      <div class="panel-head">
        <h3>指标口径（可审计）</h3>
        <span class="panel-sub">五个指标使用同一筛选条件；导出文件复用同一 snapshotId 和同一查询实现</span>
      </div>
      <el-table :data="snapshot?.definitions || []" size="small" border>
        <el-table-column prop="name" label="指标" width="130" />
        <el-table-column prop="numerator" label="分子/取值" min-width="260" />
        <el-table-column prop="denominator" label="分母/范围" min-width="220" />
        <el-table-column prop="dateField" label="日期字段" width="210" />
        <el-table-column prop="includedReason" label="说明" min-width="260" />
      </el-table>
      <div class="weights">
        风险权重：逾期计划 {{ snapshot?.weights?.overduePlan }}，未完成事件 {{ snapshot?.weights?.openEvent }}，
        重复事件 {{ snapshot?.weights?.repeatEvent }}，未来 30 天检验 {{ snapshot?.weights?.inspectDue30d }}
      </div>
    </section>

    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="82%" destroy-on-close>
      <div class="drawer-toolbar">
        <el-radio-group v-if="detail.components.length > 1" v-model="detail.component" size="small" @change="loadDetails(true)">
          <el-radio-button v-for="c in detail.components" :key="c.value" :label="c.value">{{ c.label }}</el-radio-button>
        </el-radio-group>
        <span class="muted">总计：{{ detail.total == null ? '—' : detail.total }} 条 · 稳定游标分页，按来源日期倒序</span>
      </div>
      <el-table :data="detail.rows" size="small" border stripe v-loading="detail.loading">
        <el-table-column prop="sourceNo" label="来源单号" width="150" />
        <el-table-column prop="sourceTypeName" label="来源类型" width="100" />
        <el-table-column prop="buildingNo" label="楼宇" width="95" />
        <el-table-column prop="buildingName" label="楼宇名称" min-width="150" />
        <el-table-column prop="elevatorNo" label="电梯" width="120" />
        <el-table-column prop="metricDate" label="日期" width="110" />
        <el-table-column prop="statusName" label="状态" width="90" />
        <el-table-column v-if="detail.metric === 'PART'" label="数量 × 单价" width="130" align="right">
          <template #default="{ row }">{{ row.quantity }} × ¥{{ row.unitPrice }}</template>
        </el-table-column>
        <el-table-column v-if="detail.metric === 'PART'" prop="amount" label="金额" width="100" align="right">
          <template #default="{ row }">¥{{ row.amount }}</template>
        </el-table-column>
        <el-table-column v-if="detail.metric === 'DURATION'" prop="durationMinutes" label="耗时（分钟）" width="110" align="right" />
        <el-table-column prop="relatedSourceNo" label="关联单号" width="150" />
        <el-table-column label="计入" width="70" align="center">
          <template #default="{ row }">
            <el-tag :type="row.counted ? 'success' : 'info'" size="small">{{ row.counted ? '是' : '否' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="includeReason" label="计入原因" min-width="280" />
      </el-table>
      <div class="pager">
        <el-button size="small" :disabled="!detail.cursor || detail.loading" @click="loadDetails(false)">加载下一页</el-button>
        <span v-if="!detail.nextCursor && detail.rows.length" class="muted">已加载全部</span>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import { buildingApi, qualityApi } from '../api'

const es = inject('es')
const buildings = ref([])
const snapshot = ref(null)
const loading = ref(false)
const exporting = ref(false)
const trendMetric = ref('PLAN_ON_TIME')
const drawerVisible = ref(false)
let requestSeq = 0

const shanghaiToday = () => {
  const now = new Date()
  const shifted = new Date(now.getTime() + 8 * 3600 * 1000)
  return shifted.toISOString().slice(0, 10)
}

const defaultRange = () => {
  const end = shanghaiToday()
  const d = new Date(end + 'T00:00:00Z')
  d.setUTCDate(d.getUTCDate() - 89)
  return [d.toISOString().slice(0, 10), end]
}

const filters = reactive({
  range: defaultRange(),
  buildingId: null,
  elevatorId: null,
  granularity: 'day'
})

const elevatorOptions = computed(() => {
  const b = buildings.value.find((item) => item.id === filters.buildingId)
  if (!b) {
    return buildings.value.flatMap((building) =>
      (building.units || []).flatMap((unit) =>
        (unit.elevators || []).map((e) => ({ ...e, buildingId: building.id }))
      )
    )
  }
  return (b.units || []).flatMap((unit) =>
    (unit.elevators || []).map((e) => ({ ...e, buildingId: b.id }))
  )
})

const cards = computed(() => {
  const s = snapshot.value?.summaries || {}
  return [
    { key: 'PLAN_ON_TIME', name: '计划准时率', theme: 'ok', summary: s.PLAN_ON_TIME, metric: 'PLAN', component: 'DENOMINATOR',
      sub: '按计划日完成 / 到期非作废计划', foot: `分子 ${s.PLAN_ON_TIME?.numerator || 0}，分母 ${s.PLAN_ON_TIME?.denominator || 0}` },
    { key: 'AVG_DURATION', name: '平均执行耗时', theme: 'blue', summary: s.AVG_DURATION, metric: 'DURATION', component: 'COUNTED',
      sub: '仅统计已有实际完成日记录', foot: `${s.AVG_DURATION?.count || 0} 条维保记录` },
    { key: 'REPEAT_RATE', name: '重复事件率', theme: 'danger', summary: s.REPEAT_RATE, metric: 'REPEAT', component: 'DENOMINATOR',
      sub: '同电梯同类型 30 天内再次出现', foot: `重复 ${s.REPEAT_RATE?.numerator || 0} / 事件 ${s.REPEAT_RATE?.denominator || 0}` },
    { key: 'PART_COST', name: '配件成本', theme: 'orange', summary: s.PART_COST, metric: 'PART', component: 'COUNTED',
      sub: '数量 × 单价，按更换日期汇总', foot: `${s.PART_COST?.count || 0} 条配件记录` },
    { key: 'BUILDING_RISK', name: '楼宇风险因子', theme: 'purple', summary: s.BUILDING_RISK, metric: 'RISK', component: 'ALL',
      sub: '逾期/未完成/重复/检验加权', foot: '点击查看四类风险明细' }
  ]
})

const hasNoData = computed(() => {
  const s = snapshot.value
  if (!s) return false
  return Object.values(s.detailCounts).every((v) => !v)
})

const granularityName = computed(() => ({ day: '天', week: '周', month: '月' }[filters.granularity]))

const detail = reactive({
  title: '',
  metric: 'PLAN',
  component: 'ALL',
  components: [],
  buildingId: null,
  rows: [],
  cursor: null,
  nextCursor: null,
  total: 0,
  loading: false
})

async function loadBuildings () {
  buildings.value = await buildingApi.tree()
}

function onBuildingChange () {
  filters.elevatorId = null
}

async function loadSnapshot () {
  if (!filters.range || filters.range.length !== 2) return
  const seq = ++requestSeq
  loading.value = true
  const params = {
    startDate: filters.range[0],
    endDate: filters.range[1],
    granularity: filters.granularity,
    buildingId: filters.buildingId || undefined,
    elevatorId: filters.elevatorId || undefined,
    timezone: 'Asia/Shanghai'
  }
  try {
    const data = await qualityApi.snapshot(params)
    if (seq !== requestSeq) return
    snapshot.value = data
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function metricComponents (metric) {
  return {
    PLAN: [
      { value: 'DENOMINATOR', label: '全部到期计划' },
      { value: 'NUMERATOR', label: '仅准时完成' }
    ],
    DURATION: [{ value: 'COUNTED', label: '计入耗时记录' }],
    REPEAT: [
      { value: 'DENOMINATOR', label: '全部有效事件' },
      { value: 'NUMERATOR', label: '仅重复事件' },
      { value: 'NON_REPEAT', label: '非重复事件' }
    ],
    PART: [{ value: 'COUNTED', label: '计入配件' }],
    RISK: [
      { value: 'ALL', label: '全部风险' },
      { value: 'RISK_OVERDUE', label: '逾期计划' },
      { value: 'RISK_OPEN', label: '未完成事件' },
      { value: 'RISK_REPEAT', label: '重复事件' },
      { value: 'RISK_INSPECTION', label: '检验到期' }
    ]
  }[metric]
}

async function openMetric (card) {
  openDrawer(card.name, card.metric, card.component, null)
}

async function openBuildingRisk (row) {
  openDrawer(`楼宇风险明细 · ${row.buildingNo}`, 'RISK', 'ALL', row.id)
}

async function openDrawer (title, metric, component, buildingId) {
  detail.title = title
  detail.metric = metric
  detail.component = component
  detail.components = metricComponents(metric)
  detail.buildingId = buildingId
  drawerVisible.value = true
  await loadDetails(true)
}

async function loadDetails (reset) {
  if (!snapshot.value) return
  detail.loading = true
  try {
    if (reset) {
      detail.rows = []
      detail.cursor = null
      detail.total = 0
    }
    const data = await qualityApi.details({
      snapshotId: snapshot.value.snapshotId,
      metric: detail.metric,
      component: detail.component,
      cursor: detail.cursor || undefined,
      size: 50,
      buildingId: detail.buildingId || undefined
    })
    detail.rows = reset ? data.rows : detail.rows.concat(data.rows)
    detail.nextCursor = data.nextCursor
    detail.cursor = data.nextCursor
    detail.total = data.total
  } finally {
    detail.loading = false
  }
}

async function exportSnapshot () {
  if (!snapshot.value || exporting.value) return
  exporting.value = true
  try {
    const blob = await qualityApi.exportSnapshot(snapshot.value.snapshotId)
    const url = URL.createObjectURL(new Blob([blob], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `service-quality-${snapshot.value.generatedAt.slice(0, 19).replace(/[-:T ]/g, '')}.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } finally {
    exporting.value = false
  }
}

function pct (v) {
  return `${Math.max(0, Math.min(100, Number(v || 0) * 100))}%`
}

function formatRate (v) {
  return v == null ? '—' : `${(Number(v) * 100).toFixed(2)}%`
}

function riskClass (score) {
  const n = Number(score)
  return n >= 3 ? 'risk-high' : n >= 1 ? 'risk-mid' : 'risk-low'
}

function formatTime (value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : ''
}

watch(() => es.buildingId.value, (value) => {
  filters.buildingId = value || null
  filters.elevatorId = null
}, { immediate: false })

watch(() => es.elevatorId.value, (value) => {
  filters.elevatorId = value || null
}, { immediate: false })

onMounted(async () => {
  await loadBuildings()
  filters.buildingId = es.buildingId.value || null
  filters.elevatorId = es.elevatorId.value || null
  await loadSnapshot()
})
</script>

<style scoped>
.quality-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.q-filter-panel .q-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}
.snapshot-line {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fff8f1;
  color: #7d6b5a;
  font-size: 12px;
}
.q-cards {
  display: grid;
  grid-template-columns: repeat(5, minmax(170px, 1fr));
  gap: 12px;
}
.q-card {
  cursor: pointer;
  border-top: 3px solid var(--es-primary);
}
.q-card.is-ok { border-top-color: var(--es-ok); }
.q-card.is-blue { border-top-color: var(--es-info); }
.q-card.is-danger { border-top-color: var(--es-danger); }
.q-card.is-orange { border-top-color: var(--es-primary); }
.q-card.is-purple { border-top-color: #6a1b9a; }
.q-card-name { color: #7d6b5a; font-size: 13px; }
.q-card-value { margin: 8px 0 4px; font-size: 24px; font-weight: 800; color: #2c2118; }
.q-card-sub { font-size: 12px; color: #7d6b5a; min-height: 34px; }
.q-card-foot { margin-top: 8px; font-size: 12px; color: #b8500a; }
.q-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(0, 0.92fr);
  gap: 12px;
}
.bar-cell { display: flex; align-items: center; gap: 8px; }
.bar-cell span { width: 58px; text-align: right; }
.bar { flex: 1; height: 8px; border-radius: 999px; background: #f1ddc8; overflow: hidden; }
.bar i { display: block; height: 100%; background: var(--es-primary); }
.bar.danger i { background: var(--es-danger); }
.weights { margin-top: 10px; color: #7d6b5a; font-size: 12px; }
.risk-high { color: var(--es-danger); }
.risk-mid { color: #b8500a; }
.risk-low { color: var(--es-ok); }
.drawer-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 10px; }
.muted { color: #8a7969; font-size: 12px; }
.pager { display: flex; align-items: center; gap: 12px; margin-top: 12px; }
@media (max-width: 1400px) {
  .q-cards { grid-template-columns: repeat(2, minmax(180px, 1fr)); }
  .q-grid { grid-template-columns: 1fr; }
}
</style>
