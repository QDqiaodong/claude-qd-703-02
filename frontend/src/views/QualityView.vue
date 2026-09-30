<template>
  <div class="quality-page">
    <!-- 筛选条：五指标共用 -->
    <section class="panel q-filter">
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        size="small"
        range-separator="至"
        start-placeholder="开始日（含）"
        end-placeholder="结束日（含）"
        value-format="YYYY-MM-DD"
        :clearable="false"
        style="width: 260px"
      />
      <el-select
        v-model="filters.buildingId"
        size="small"
        style="width: 210px"
        placeholder="全部楼宇（含已撤场）"
        clearable
        @change="onBuildingChange"
      >
        <el-option
          v-for="b in buildingOptions"
          :key="b.id"
          :label="b.buildingNo + ' ' + b.name + (b.status === 'ARCHIVED' ? '（已撤场）' : '')"
          :value="b.id"
        />
      </el-select>
      <el-select
        v-model="filters.elevatorId"
        size="small"
        style="width: 190px"
        placeholder="全部电梯"
        clearable
      >
        <el-option
          v-for="e in elevatorOptions"
          :key="e.id"
          :label="e.elevatorNo"
          :value="e.id"
        />
      </el-select>
      <el-radio-group v-model="filters.granularity" size="small">
        <el-radio-button label="DAY">按天</el-radio-button>
        <el-radio-button label="WEEK">按周</el-radio-button>
        <el-radio-button label="MONTH">按月</el-radio-button>
      </el-radio-group>
      <el-button size="small" type="primary" :loading="loading" @click="reload">查询</el-button>
      <el-button size="small" @click="resetRange">近 30 天</el-button>
      <el-button size="small" @click="resetRange(180)">近 180 天</el-button>
      <el-button size="small" type="success" plain :loading="exporting" @click="doExport">
        导出 CSV（复用当前筛选与口径）
      </el-button>
      <div class="q-snap" v-if="snapshot">
        快照 {{ snapshot.head.snapshotId }} · 生成于 {{ fmtTime(snapshot.head.generatedAt) }}
        · 数据版本 {{ snapshot.head.dataVersion }} · {{ snapshot.head.zone }}
        · 范围楼宇 {{ snapshot.head.scopeBuildingCount }} 栋 / 电梯 {{ snapshot.head.scopeElevatorCount }} 台
      </div>
    </section>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      style="margin-bottom: 10px"
    />

    <!-- 总览卡片 -->
    <section class="q-cards">
      <div
        v-for="card in cards"
        :key="card.key"
        class="q-card"
        :class="{ 'is-clickable': card.key !== 'RISK_RANK' }"
        @click="drillCard(card)"
      >
        <div class="q-card-head">
          <span>{{ card.name }}</span>
          <el-tooltip placement="top" effect="light">
            <template #content>
              <div style="max-width: 420px; line-height: 1.6">
                <div>分母：{{ defOf(card.key)?.denominatorRule }}</div>
                <div>分子：{{ defOf(card.key)?.numeratorRule }}</div>
                <div>公式：{{ defOf(card.key)?.formula }}</div>
              </div>
            </template>
            <span class="q-info">ⓘ</span>
          </el-tooltip>
        </div>
        <div class="q-card-value">
          <template v-if="card.key === 'PLAN_ON_TIME' || card.key === 'EVENT_REPEAT'">
            <b>{{ card.ratePercent == null ? '—' : card.ratePercent }}</b><i>%</i>
            <span class="q-card-sub">{{ card.numerator }} / {{ card.denominator }}</span>
          </template>
          <template v-else-if="card.key === 'ORDER_DURATION'">
            <b>{{ card.value == null ? '—' : card.value }}</b><i>天</i>
            <span class="q-card-sub">{{ card.numerator }} 条记录</span>
          </template>
          <template v-else-if="card.key === 'PART_COST'">
            <b>¥{{ card.value == null ? 0 : card.value }}</b>
            <span class="q-card-sub">{{ card.numerator || 0 }} 条配件记录</span>
          </template>
          <template v-else>
            <b>{{ card.value == null ? '—' : card.value }}</b><i>分</i>
            <span class="q-card-sub">最高风险楼；非低风险 {{ card.numerator }}/{{ card.denominator }} 栋，点分量钻取</span>
          </template>
        </div>
      </div>
    </section>

    <el-empty v-if="snapshot && snapshot.empty" description="当前筛选范围内没有任何计划、维保记录、事件、配件或风险分量" />

    <template v-if="snapshot && !snapshot.empty">
      <!-- 趋势 -->
      <section class="panel">
        <div class="panel-head">
          <h3>按{{ granName }}趋势（卡片数字 = 趋势合计）</h3>
          <el-radio-group v-model="trendMetric" size="small">
            <el-radio-button label="PLAN_ON_TIME">准时率</el-radio-button>
            <el-radio-button label="ORDER_DURATION">平均耗时</el-radio-button>
            <el-radio-button label="EVENT_REPEAT">重复事件率</el-radio-button>
            <el-radio-button label="PART_COST">配件成本</el-radio-button>
          </el-radio-group>
        </div>

        <TrendChart :data="snapshot.trends" :metric="trendMetric" @pick="openMetricTrend" />

        <div class="q-trend-total">
          趋势合计：
          <template v-if="trendMetric === 'PLAN_ON_TIME'">
            到期计划 <b>{{ trendSum('planDue') }}</b> · 准时 <b>{{ trendSum('planOnTime') }}</b>
            · 准时率 <b>{{ pct(trendSum('planOnTime'), trendSum('planDue')) }}%</b>
            <span class="muted">（应与卡片 {{ cards[0].numerator }}/{{ cards[0].denominator }} 一致）</span>
          </template>
          <template v-else-if="trendMetric === 'ORDER_DURATION'">
            完成记录 <b>{{ trendSum('orderCount') }}</b> · 耗时合计 <b>{{ trendSum('durationDays') }}</b> 天
            · 平均 <b>{{ pct2(trendSum('durationDays'), trendSum('orderCount')) }}</b> 天
          </template>
          <template v-else-if="trendMetric === 'EVENT_REPEAT'">
            事件 <b>{{ trendSum('eventCount') }}</b> · 重复 <b>{{ trendSum('eventRepeat') }}</b>
            · 重复率 <b>{{ pct(trendSum('eventRepeat'), trendSum('eventCount')) }}%</b>
          </template>
          <template v-else>
            配件记录 <b>{{ trendSum('partRecords') }}</b> · 数量 <b>{{ trendSum('partQuantity') }}</b>
            · 金额 <b>¥{{ amountSum() }}</b>
          </template>
        </div>
      </section>

      <!-- 楼宇风险排行 -->
      <section class="panel">
        <div class="panel-head">
          <h3>楼宇风险排行</h3>
          <span class="panel-sub">
            综合分 =
            <template v-for="w in snapshot.head.riskWeights" :key="w.key">
              {{ w.name }}×{{ w.weight }}{{ w.key === 'RISK_INSPECT' ? '' : ' + ' }}
            </template>
            ；分值降序、楼宇编号升序；含已撤场楼宇。点数字钻取。
          </span>
        </div>
        <el-table :data="snapshot.ranking" size="small" border stripe max-height="420">
          <el-table-column type="index" label="排名" width="60" />
          <el-table-column prop="buildingNo" label="楼宇编号" width="100" />
          <el-table-column prop="buildingName" label="楼宇名称" min-width="170">
            <template #default="{ row }">
              {{ row.buildingName }}
              <el-tag v-if="row.buildingStatus === 'ARCHIVED'" size="small" type="info">已撤场</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="逾期计划" width="100" align="right">
            <template #default="{ row }">
              <el-button link type="danger" @click="drillRisk(row, 'RISK_OVERDUE_PLAN')">
                {{ row.overduePlans }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="未完成事件" width="100" align="right">
            <template #default="{ row }">
              <el-button link type="warning" @click="drillRisk(row, 'RISK_OPEN_EVENT')">
                {{ row.openEvents }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="近30天重复" width="110" align="right">
            <template #default="{ row }">
              <el-button link type="warning" @click="drillRisk(row, 'RISK_REPEAT_EVENT')">
                {{ row.repeatEvents }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="检验到期" width="90" align="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="drillRisk(row, 'RISK_INSPECT')">
                {{ row.inspectDue }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column prop="elevatorCount" label="电梯数" width="80" align="right" />
          <el-table-column label="综合分" width="90" align="right">
            <template #default="{ row }"><b>{{ row.riskScore }}</b></template>
          </el-table-column>
          <el-table-column label="等级" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.riskLevel === 'HIGH' ? 'danger' : row.riskLevel === 'MID' ? 'warning' : 'success'">
                {{ row.riskLevelName }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </template>

    <!-- 钻取抽屉 -->
    <el-drawer v-model="drawer.open" :title="drawerTitle" size="72%" destroy-on-close>
      <div class="q-drawer-bar">
        <el-radio-group
          v-if="drawer.scoped"
          v-model="drawer.scope"
          size="small"
          @change="loadDetails(true)"
        >
          <el-radio-button label="DENOMINATOR">分母全集</el-radio-button>
          <el-radio-button label="NUMERATOR">仅计入分子</el-radio-button>
        </el-radio-group>
        <span class="muted">{{ drawer.reasonHint }}</span>
        <span style="margin-left: auto">共 {{ drawer.total }} 条</span>
      </div>

      <el-table :data="drawer.rows" size="small" border stripe v-loading="drawer.loading" max-height="60vh">
        <el-table-column prop="sourceType" label="类型" width="120" :formatter="sourceTypeName" />
        <el-table-column prop="sourceNo" label="来源单号" width="150">
          <template #default="{ row }">{{ row.sourceNo || '—' }}</template>
        </el-table-column>
        <el-table-column prop="elevatorNo" label="电梯" width="130" />
        <el-table-column prop="buildingNo" label="楼宇编号" width="100" />
        <el-table-column prop="buildingName" label="楼宇" min-width="150" />
        <el-table-column prop="sourceDate" label="业务日期" width="115" />
        <el-table-column label="数值" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.count1 != null">计数 {{ row.count1 }}<span v-if="row.count2 != null">/{{ row.count2 }}</span></span>
            <span v-if="row.amount != null">¥{{ row.amount }}</span>
            <span v-if="row.value1 != null">{{ row.value1 }} 天</span>
          </template>
        </el-table-column>
        <el-table-column label="计入" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.included ? 'success' : 'info'">{{ row.included ? '是' : '否' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="计入原因" min-width="280" show-overflow-tooltip />
      </el-table>

      <div class="q-pager">
        <el-button size="small" :disabled="drawer.pageIndex === 0 || drawer.loading" @click="prevPage">上一页</el-button>
        <span>第 {{ drawer.pageIndex + 1 }} 页</span>
        <el-button size="small" :disabled="!drawer.cursor || drawer.loading" @click="nextPage">下一页</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref, watch, h } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { qualityApi } from '../api'

const es = inject('es')

const METRIC_LABEL = {
  PLAN_ON_TIME: '计划准时率',
  ORDER_DURATION: '平均执行耗时',
  EVENT_REPEAT: '重复事件率',
  PART_COST: '配件成本',
  RISK_RANK: '楼宇风险排行',
  RISK_OVERDUE_PLAN: '逾期计划',
  RISK_OPEN_EVENT: '未完成事件',
  RISK_REPEAT_EVENT: '近30天重复事件',
  RISK_INSPECT: '未来30天检验到期'
}

const SOURCE_LABEL = {
  PLAN: '维保计划',
  MAINTENANCE_ORDER: '维保记录',
  FAULT: '故障事件',
  PART: '配件更换',
  ELEVATOR: '电梯台账'
}

function iso (d) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function defaultRange (days = 30) {
  const end = new Date()
  const start = new Date()
  start.setDate(end.getDate() - (days - 1))
  return [iso(start), iso(end)]
}

const dateRange = ref(defaultRange(30))
const filters = reactive({ buildingId: null, elevatorId: null, granularity: 'DAY' })
const trendMetric = ref('PLAN_ON_TIME')
const snapshot = ref(null)
const loading = ref(false)
const exporting = ref(false)
const error = ref('')
let reqSeq = 0

const buildingOptions = computed(() => es.tree.value || [])
const elevatorOptions = computed(() => {
  const out = []
  for (const b of es.tree.value || []) {
    if (filters.buildingId && b.id !== filters.buildingId) continue
    for (const u of b.units || []) {
      for (const e of u.elevators || []) out.push(e)
    }
  }
  return out
})

const cards = computed(() => snapshot.value?.cards || [])
function defOf (key) {
  return (snapshot.value?.head.metricDefs || []).find((d) => d.key === key)
}
function granName () {
  return { DAY: '日', WEEK: '周', MONTH: '月' }[filters.granularity] || '日'
}
function fmtTime (t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : ''
}

function onBuildingChange () {
  // 切楼宇后若电梯不在该楼内则清空
  if (filters.elevatorId && !elevatorOptions.value.some((e) => e.id === filters.elevatorId)) {
    filters.elevatorId = null
  }
}

function resetRange (days = 30) {
  dateRange.value = defaultRange(days)
  reload()
}

function currentParams () {
  return {
    startDate: dateRange.value[0],
    endDate: dateRange.value[1],
    buildingId: filters.buildingId || undefined,
    elevatorId: filters.elevatorId || undefined,
    granularity: filters.granularity
  }
}

async function reload () {
  error.value = ''
  loading.value = true
  const seq = ++reqSeq
  try {
    const data = await qualityApi.snapshot(currentParams())
    // 快速切换筛选：旧请求晚返回不得覆盖新条件结果
    if (seq !== reqSeq) return
    snapshot.value = data
  } catch (e) {
    if (seq === reqSeq) error.value = e.message || '统计加载失败'
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

async function doExport () {
  exporting.value = true
  try {
    const res = await axios.get(qualityApi.exportUrl(currentParams()), { responseType: 'blob' })
    const cd = res.headers['content-disposition'] || ''
    let name = 'service-quality.csv'
    const m = /filename\*=UTF-8''([^;]+)/.exec(cd) || /filename="([^"]+)"/.exec(cd)
    if (m) name = decodeURIComponent(m[1])
    const url = URL.createObjectURL(new Blob([res.data], { type: 'text/csv;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出完成：文件内参数、汇总、排行、明细使用导出开始时的同一快照')
  } catch (e) {
    ElMessage.error('导出失败：' + (e.message || ''))
  } finally {
    exporting.value = false
  }
}

// ---------------- 钻取 ----------------
const drawer = reactive({
  open: false,
  metric: '',
  scope: 'DENOMINATOR',
  scoped: false,
  rows: [],
  cursor: null,
  pageIndex: 0,
  stack: [],
  total: 0,
  loading: false,
  reasonHint: '',
  buildingId: undefined
})

const drawerTitle = computed(() => `${METRIC_LABEL[drawer.metric] || '明细'} · 钻取`)

function drillCard (card) {
  if (card.key === 'RISK_RANK') {
    ElMessage.info('楼宇风险排行请在下方表格点各分量数字钻取；卡片展示最高风险楼分值')
    return
  }
  openMetric(card.key)
}

function openMetric (metric, buildingId) {
  drawer.metric = metric
  drawer.buildingId = buildingId
  drawer.scope = 'DENOMINATOR'
  drawer.scoped = metric === 'PLAN_ON_TIME' || metric === 'EVENT_REPEAT'
  drawer.open = true
  loadDetails(true)
}

function drillRisk (row, metric) {
  openMetric(metric, row.buildingId)
}

function openMetricTrend (metric) {
  openMetric(metric)
}

async function loadDetails (reset) {
  drawer.loading = true
  try {
    if (reset) {
      drawer.pageIndex = 0
      drawer.stack = []
      drawer.cursor = null
    }
    const p = {
      ...currentParams(),
      metric: drawer.metric,
      scope: drawer.scope,
      size: 50,
      cursor: drawer.cursor || undefined
    }
    if (drawer.buildingId) p.buildingId = drawer.buildingId
    const data = await qualityApi.details(p)
    drawer.rows = data.rows
    drawer.total = data.total
    drawer.nextCursor = data.nextCursor
    drawer.reasonHint = reasonHintFor(drawer.metric)
  } finally {
    drawer.loading = false
  }
}

function nextPage () {
  drawer.stack.push(drawer.cursor)
  drawer.cursor = drawer.nextCursor
  drawer.pageIndex++
  loadDetails(false)
}

function prevPage () {
  drawer.cursor = drawer.stack.pop() || null
  drawer.pageIndex = Math.max(0, drawer.pageIndex - 1)
  loadDetails(false)
}

function reasonHintFor (metric) {
  const d = defOf(metric)
  if (!d) return ''
  return '口径：' + d.formula
}

function sourceTypeName ({ row }) {
  return SOURCE_LABEL[row.sourceType] || row.sourceType
}

function trendSum (field) {
  return (snapshot.value?.trends || []).reduce((s, t) => s + (t[field] || 0), 0)
}
function amountSum () {
  const v = (snapshot.value?.trends || [])
    .reduce((s, t) => s + Number(t.partAmount || 0), 0)
  return v.toFixed(2)
}
function pct (n, d) {
  return d === 0 ? '—' : ((n * 100) / d).toFixed(2)
}
function pct2 (sum, d) {
  return d === 0 ? '—' : (sum / d).toFixed(2)
}

// ---------------- 内嵌趋势图（SVG，无新依赖） ----------------
const TrendChart = {
  props: ['data', 'metric'],
  emits: ['pick'],
  setup (props, { emit }) {
    return () => {
      const data = props.data || []
      if (!data.length) return h('div', { class: 'muted' }, '窗口内无趋势数据')
      const W = 960
      const H = 220
      const pad = 34
      const bw = (W - pad * 2) / data.length
      const barKey = {
        PLAN_ON_TIME: 'planDue',
        ORDER_DURATION: 'orderCount',
        EVENT_REPEAT: 'eventCount',
        PART_COST: 'partRecords'
      }[props.metric]
      const lineKey = {
        PLAN_ON_TIME: 'planRatePercent',
        ORDER_DURATION: 'avgDurationDays',
        EVENT_REPEAT: 'eventRatePercent',
        PART_COST: 'partAmount'
      }[props.metric]
      const maxBar = Math.max(1, ...data.map((d) => Number(d[barKey]) || 0))
      const lineVals = data.map((d) => Number(d[lineKey])).filter((v) => !Number.isNaN(v))
      const maxLine = Math.max(1, ...lineVals, 0.01)
      const bars = []
      const pts = []
      data.forEach((d, i) => {
        const x = pad + i * bw
        const bh = ((Number(d[barKey]) || 0) / maxBar) * (H - pad * 2)
        bars.push(h('rect', {
          key: 'b' + i,
          x: x + 1, y: H - pad - bh, width: Math.max(1, bw - 2), height: bh,
          fill: '#93bffc', style: 'cursor:pointer',
          onClick: () => emit('pick', props.metric)
        }))
        const v = Number(d[lineKey])
        if (!Number.isNaN(v)) {
          const y = H - pad - (v / maxLine) * (H - pad * 2)
          pts.push(`${x + bw / 2},${y}`)
        }
      })
      const labels = data.map((d, i) => h('text', {
        key: 'l' + i,
        x: pad + i * bw + bw / 2, y: H - 10,
        'font-size': 9, 'text-anchor': 'middle', fill: '#888'
      }, data.length <= 31 || i % Math.ceil(data.length / 15) === 0 ? d.label : ''))
      return h('svg', { viewBox: `0 0 ${W} ${H}`, width: '100%', height: H }, [
        h('line', { x1: pad, y1: H - pad, x2: W - pad, y2: H - pad, stroke: '#ddd' }),
        h('line', { x1: pad, y1: pad, x2: pad, y2: H - pad, stroke: '#ddd' }),
        ...bars,
        h('polyline', {
          points: pts.join(' '), fill: 'none', stroke: '#d9480f', 'stroke-width': 2
        }),
        ...labels
      ])
    }
  }
}

watch(() => es.buildingId.value, (v) => { filters.buildingId = v }, { immediate: false })
watch(() => es.elevatorId.value, (v) => { filters.elevatorId = v }, { immediate: false })
watch([dateRange, () => filters.granularity], reload)
// 楼宇/电梯手动切换后也自动查询
watch([() => filters.buildingId, () => filters.elevatorId], reload)

onMounted(() => {
  filters.buildingId = es.buildingId.value || null
  filters.elevatorId = es.elevatorId.value || null
  reload()
})
</script>

<style scoped>
.quality-page .panel { margin-bottom: 12px; }
.q-filter { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.q-snap { width: 100%; font-size: 12px; color: #888; margin-top: 4px; }
.q-cards { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; margin-bottom: 12px; }
.q-card { background: #fff; border: 1px solid #e8e8e8; border-radius: 8px; padding: 12px 14px; }
.q-card.is-clickable { cursor: pointer; }
.q-card.is-clickable:hover { border-color: #409eff; box-shadow: 0 2px 10px rgba(64,158,255,.15); }
.q-card-head { display: flex; justify-content: space-between; color: #666; font-size: 13px; }
.q-info { color: #bbb; cursor: help; }
.q-card-value b { font-size: 26px; color: #1f5fbf; }
.q-card-value i { font-style: normal; color: #999; margin-left: 4px; }
.q-card-sub { display: block; color: #999; font-size: 12px; margin-top: 4px; }
.q-trend-total { margin-top: 8px; font-size: 13px; color: #555; }
.muted { color: #999; }
.q-drawer-bar { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; font-size: 13px; }
.q-pager { display: flex; gap: 10px; justify-content: center; align-items: center; margin-top: 12px; }
</style>
