<template>
  <section>
    <div class="pagehead">
      <div>
        <h1>营销总览</h1>
      </div>
      <router-link class="btn primary" to="/activities/new">创建活动</router-link>
    </div>
    <p v-if="error" class="error">{{ error }} <button class="btn" type="button" @click="load">重试</button></p>
    <p v-else-if="loading" class="loading">正在读取活动…</p>
    <div v-else-if="!rows.length" class="card empty-hero"><EmptyState title="暂无活动"><router-link class="btn primary" to="/activities/new">创建活动</router-link></EmptyState></div>
    <template v-if="!loading">
      <section v-if="current" ref="hero" class="overview-hero">
        <div class="ov-aura ov-aura-1" aria-hidden="true"></div>
        <div class="ov-aura ov-aura-2" aria-hidden="true"></div>
        <div class="ov-aura ov-aura-3" aria-hidden="true"></div>
        <canvas ref="heroDots" class="ov-dots" aria-hidden="true"></canvas>

        <div class="ov-top">
          <span class="ov-label">活动速览</span>
          <div class="ov-tabs" role="tablist" aria-label="选择活动" @keydown="tabKey">
            <button
              v-for="item in rows"
              :key="item.id"
              class="ov-tab"
              type="button"
              role="tab"
              :tabindex="item.id === current.id ? 0 : -1"
              :aria-selected="item.id === current.id"
              @click="current = item"
            >
              {{ item.name }}<small>{{ phaseText(item.phase) }}</small>
            </button>
          </div>
        </div>

        <div class="ov-grid">
          <div :key="`content-${current.id}`" class="ov-left ov-swap">
            <div class="ov-pill" :class="phaseTone"><i></i><span>{{ phaseText(shownPhase) }}</span></div>
            <h2 class="ov-title">{{ current.name }}</h2>
            <div class="ov-state">{{ stateLead }}<em v-if="stateAccent" :class="phaseTone">{{ stateAccent }}</em></div>
            <p class="ov-time">{{ clockLabel(current.startTime) }} — {{ clockLabel(current.endTime) }} · 北京时间</p>
            <div class="ov-actions">
              <router-link class="btn primary" :to="activityPath(current)">{{ primaryLabel }}</router-link>
              <router-link class="btn" :to="`/preview?activity=${current.id}`">预览活动入口</router-link>
            </div>
          </div>

          <div ref="heroStage" class="ov-stage">
            <svg ref="heroLinks" class="ov-links" aria-hidden="true"></svg>
            <div ref="orbitBox" class="ov-orbit-box" aria-hidden="true">
              <div class="ov-orbit-svg">
                <svg viewBox="0 0 380 380" fill="none">
                  <g class="ov-spin" style="--d: 96s" stroke="#cbc3b2" stroke-width="1">
                    <circle cx="190" cy="190" r="82" />
                    <circle cx="190" cy="190" r="134" stroke-dasharray="1 6" stroke-linecap="round" stroke-width="1.5" />
                    <circle cx="190" cy="190" r="182" stroke-opacity=".55" />
                  </g>
                  <g class="ov-spin rev" style="--d: 72s" stroke-width="1.3" stroke-linecap="round">
                    <path d="M108 190a82 82 0 0 1 82-82" stroke="#5b7040" stroke-opacity=".5" />
                    <path d="M324 190a134 134 0 0 1-64 114" stroke="#527ea8" stroke-opacity=".5" />
                    <path d="M58 242a140 140 0 0 1 28-108" stroke="#c6613f" stroke-opacity=".45" />
                  </g>
                  <g class="ov-spin" style="--d: 18s"><circle cx="190" cy="108" r="4" fill="#c6613f" /></g>
                  <g class="ov-spin rev" style="--d: 28s"><circle cx="56" cy="190" r="3.4" fill="#527ea8" /><circle cx="56" cy="190" r="8.5" stroke="#527ea8" stroke-opacity=".3" /></g>
                  <g class="ov-spin" style="--d: 44s"><circle cx="190" cy="372" r="3.4" fill="#5b7040" /></g>
                  <g class="ov-spin rev" style="--d: 58s"><circle cx="324" cy="190" r="3" fill="#b87e18" /></g>
                </svg>
              </div>
              <div ref="seal" class="ov-seal" :class="phaseTone">序</div>
            </div>

            <div :key="`metrics-${current.id}`" class="ov-metrics ov-swap">
              <div class="ov-mcard" data-link="#c6613f" style="--tone: var(--clay)">
                <div class="ov-mhead">
                  <span class="ov-mlabel"><i></i>已投放 / 剩余</span>
                  <div class="ov-mval">{{ formatNumber(current.quantity || 0) }}<small>/ {{ formatNumber(left(current)) }} 张</small></div>
                </div>
                <div class="ov-mbar"><span :style="{ width: `${usedPercent}%` }"></span></div>
              </div>
              <div class="ov-mrow2">
                <div class="ov-mcard" data-link="#5b7040" style="--tone: #5b7040">
                  <span class="ov-mlabel"><i></i>已领取 / 发布准备</span>
                  <div class="ov-mval ov-mval-sm">{{ formatNumber(current.claimed) }}<small>张 · {{ current.checksPassed || 0 }} / {{ current.checksTotal || 0 }} 项</small></div>
                </div>
                <div class="ov-mcard" data-link="#527ea8" style="--tone: #527ea8">
                  <span class="ov-mlabel"><i></i>有效推广入口</span>
                  <div class="ov-mval ov-mval-sm">{{ current.entryCount || 0 }}<small>个</small></div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <p v-if="trafficError" class="error" role="alert">{{ trafficError }} <button class="linkbtn" @click="loadTraffic">重新读取流量</button></p>
      <div class="stats traffic-stats">
        <div class="card stat ov-stat" style="--tone: #b87e18; --tone-bg: #f8f0df">
          <div class="ov-stat-top"><div class="statlabel" :title="traffic?.definitions?.reservations">有效预约</div><span class="ov-stat-ico" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg></span></div>
          <div class="value">{{ formatNumber(traffic?.reservations) }}<small>人次</small></div><div class="statfoot"><span :class="deltaClass(traffic?.reservationChange)">{{ deltaText(traffic?.reservationChange) }}</span> {{ comparisonLabel }}</div>
        </div>
        <div class="card stat ov-stat" style="--tone: #527ea8; --tone-bg: #ebf1f6">
          <div class="ov-stat-top"><div class="statlabel" :title="traffic?.definitions?.visitors">累计触达用户</div><span class="ov-stat-ico" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 00-3-3.87M16 3.13a4 4 0 010 7.75"/></svg></span></div>
          <div class="value">{{ formatNumber(traffic?.visitors) }}<small>人</small></div><div class="statfoot"><span :class="deltaClass(traffic?.visitorChange)">{{ deltaText(traffic?.visitorChange) }}</span> {{ comparisonLabel }}</div>
        </div>
        <div class="card stat ov-stat" style="--tone: #5b7040; --tone-bg: #eef1e7">
          <div class="ov-stat-top"><div class="statlabel" :title="traffic?.definitions?.conversion">领券转化率</div><span class="ov-stat-ico" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M3 17l6-6 4 4 8-8"/><path d="M17 7h4v4"/></svg></span></div>
          <div class="value">{{ traffic?.conversionRate == null ? '—' : formatNumber(traffic.conversionRate) }}<small v-if="traffic?.conversionRate != null">%</small></div><div class="statfoot"><span :class="deltaClass(traffic?.conversionChange)">{{ deltaText(traffic?.conversionChange, ' 个百分点') }}</span> {{ comparisonLabel }}</div>
        </div>
        <div class="card stat ov-stat" style="--tone: var(--clay); --tone-bg: var(--tint)">
          <div class="ov-stat-top"><div class="statlabel">营销活动总数</div><span class="ov-stat-ico" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="5" width="16" height="15" rx="2"/><path d="M8 3v4M16 3v4M4 10h16"/></svg></span></div>
          <div class="value">{{ formatNumber(traffic?.activityCount) }}<small>个</small></div><div class="statfoot"><span class="delta-up">{{ formatNumber(traffic?.upcomingCount) }} 场</span> 活动即将开始</div>
        </div>
      </div>
      <div class="traffic-layout">
        <div class="card traffic-chart-card">
          <div class="sectionhead"><div><h2>有效访问趋势</h2></div><div class="seg"><button type="button" :class="{ active: period === 'today' }" @click="period = 'today'">今日</button><button type="button" :class="{ active: period === 'week' }" @click="period = 'week'">近 7 日</button></div></div>
          <div class="traffic-legend"><span><i></i>有效访问</span><span><i class="history"></i>历史同期</span></div>
          <TrafficTrend :points="traffic?.points || []" :period="period" :loading="trafficLoading" />
          <div v-if="Number(traffic?.visitors) > 0 && traffic?.insight" class="traffic-caption">{{ traffic.insight }}</div>
        </div>
        <div class="card opening-card">
          <div class="sectionhead"><h2>预约与开场准备</h2><span class="pill" :class="{ amber: !opening.ready }">{{ opening.ready ? '已就绪' : '待检查' }}</span></div>
          <div class="opening-body"><div class="mini">{{ opening.name || current?.name || '尚未选择活动' }} · 预计开抢压力</div>
            <div class="opening-reservations"><span>有效预约 <b>{{ formatNumber(opening.plan?.reservationCount) }}</b> 人</span><span>预计到场 <b>{{ formatNumber(opening.plan?.expectedArrivals) }}</b> 人</span></div>
            <div class="opening-forecast-label">当前预约估算 · 非实时请求量</div>
            <div class="opening-value"><span>{{ formatNumber(opening.qps) }}<small>次/秒</small></span><span class="pill">{{ opening.plan?.capacityStatus === 'NO_SAMPLE' ? '待积累预约' : '预计峰值' }}</span></div>
            <div class="meter"><span :style="{ width: capacityPercent == null ? '0%' : `${Math.min(100, capacityPercent)}%` }"></span></div>
            <div class="metertxt"><span>预计压力 / 安全容量</span><span>{{ capacityPercent == null ? '待验证' : `${capacityPercent}%` }}</span></div>
            <div class="divider"></div><div v-for="item in readinessRows" :key="item.label" class="checkrow"><span>{{ item.label }}</span><b :class="{ 'pending-text': !item.ready }" :title="item.detail">{{ item.ready ? '✓ 已就绪' : item.status }}</b></div>
            <router-link class="linkbtn opening-link" :to="current ? `/activities/${current.id}/forecast` : '/activities/new'">{{ current ? '查看预约预测与准备' : '创建活动并配置预测' }}</router-link>
          </div>
        </div>
      </div>

      <div class="card" style="margin-bottom: 22px; padding: 24px">
        <div class="sectionhead" style="padding: 0">
          <h2>待处理</h2>
          <span class="pill" :class="{ amber: pending.length }">{{ pending.length ? pending.length + ' 项' : '全部就绪' }}</span>
        </div>
        <p v-if="!pending.length" class="muted">没有需要处理的事项。</p>
        <div v-for="item in pending" :key="item.code" class="checkrow">
          <span>{{ item.reason }}</span>
          <router-link class="linkbtn" :to="activityPath(current, item.fix || 'info')">去处理</router-link>
        </div>
      </div>
      <div class="card">
        <div class="tabletop"><h2>近期活动 <span class="muted" style="margin-left: 9px">{{ rows.length }} 个活动</span></h2></div>
        <div class="tableoverflow"><table>
          <thead><tr><th>活动名称</th><th>状态</th><th>优惠券</th><th>渠道入口</th><th>触达 / 领券</th><th>发布准备度</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in rows" :key="item.id">
              <td><strong>{{ item.name }}</strong><small>{{ rangeText(item) }}</small></td>
              <td><span class="pill" :class="{ amber: item.phase === 'WARMUP' }">{{ phaseText(item.phase) }}</span></td>
              <td>{{ item.couponName || '未选券' }}</td>
              <td>{{ item.channelCount || 0 }} 个</td>
              <td>{{ formatNumber(item.visits) }} / {{ formatNumber(item.claimed) }}</td>
              <td>{{ item.checksPassed || 0 }} / {{ item.checksTotal || 0 }}</td>
              <td><router-link class="linkbtn" :to="activityPath(item)">{{ actionText(item) }}</router-link></td>
            </tr>
          </tbody>
        </table></div>
      </div>
    </template>
  </section>
</template>

<script>
import EmptyState from '@/components/EmptyState.vue'
import TrafficTrend from './TrafficTrend.vue'
import { trafficOverview, listActivities } from '@/api/campaign'
import { activityPath, clockLabel, dayRange, displayPhase, formatNumber, leftOf, phaseText, stepFor } from './format'

const DOT_GAP = 20
const WAVE_PERIOD = 3.6
const BURST_SPEED = 260

export default {
  components: { EmptyState, TrafficTrend },
  data() {
    return {
      period: 'today',
      traffic: null,
      trafficLoading: false,
      trafficError: '',
      trafficRequestToken: 0,
      rows: [],
      current: null,
      loading: false,
      error: '',
      now: Date.now(),
      timer: 0
    }
  },
  computed: {
    comparisonLabel() { return this.period === 'today' ? '较昨日同期' : '较前7日同期' },
    opening() { return this.traffic?.opening || {} },
    scenarioLabel() { return { low: '保守场景预测', normal: '常规场景预测', high: '集中场景预测' }[this.opening.scenario] || '尚未生成预测' },
    capacityPercent() { return this.opening.qps != null && Number(this.opening.safeCapacity) > 0 ? Math.round(Number(this.opening.qps) / Number(this.opening.safeCapacity) * 100) : null },
    readinessRows() {
      const tasks = this.opening.tasks || [], stale = this.opening.stale
      return [{ label: '活动与模板缓存', codes: ['page', 'offer'] }, { label: '热点读缓存', codes: ['page'] }, { label: '消息消费容量', codes: ['capacity'] }].map(row => {
        const matched = tasks.filter(task => row.codes.includes(task.code))
        const ready = !stale && matched.length === row.codes.length && matched.every(task => task.status === 'SUCCEEDED')
        return { label: row.label, ready, status: stale ? '需重新检查' : matched.some(task => task.status === 'FAILED') ? '未通过' : matched.some(task => task.status === 'UNVERIFIED') ? '未验证' : '待检查', detail: matched.map(task => task.detail).filter(Boolean).join('；') }
      })
    },
    liveCount() { return this.rows.filter((item) => item.phase === 'LIVE' || displayPhase(item) === 'SOLDOUT').length },
    totalClaims() { return this.rows.reduce((sum, item) => sum + (item.claimed == null ? 0 : Number(item.claimed)), 0) },
    pending() { return this.current?.pending || [] },
    shownPhase() { return displayPhase(this.current) },
    isActivePhase() { return ['LIVE', 'WARMUP'].includes(this.shownPhase) },
    phaseTone() {
      if (this.shownPhase === 'LIVE') return 'live'
      if (this.shownPhase === 'WARMUP') return 'warmup'
      return 'muted'
    },
    stateText() {
      const phase = this.shownPhase
      if (phase === 'WARMUP') return '等待开场'
      if (phase === 'LIVE') return '正在发券'
      if (phase === 'SOLDOUT') return '本场已领完'
      if (phase === 'DRAFT') return '完善配置'
      if (phase === 'OFFLINE') return '已下线'
      return phaseText(phase)
    },
    stateLead() {
      const t = this.stateText || ''
      return t.length > 2 ? t.slice(0, -2) : ''
    },
    stateAccent() {
      const t = this.stateText || ''
      return t.length > 2 ? t.slice(-2) : t
    },
    usedPercent() {
      const total = Number(this.current?.quantity || 0)
      if (!total) return 0
      const remain = Number(this.left(this.current) || 0)
      return Math.max(0, Math.min(100, Math.round(((total - remain) / total) * 100)))
    },
    primaryLabel() {
      return this.actionText(this.current)
    }
  },
  created() {
    this.reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    this.heroStart = performance.now()
    this.heroBursts = []
    this.heroPointer = { x: -999, y: -999 }
    this.heroCenter = { x: 0, y: 0 }
    this.load()
  },
  beforeUnmount() {
    window.clearInterval(this.timer)
    this.trafficRequestToken++
    this.teardownHeroMotion()
  },
  watch: {
    current(value, previous) {
      this.$emit('spotlight', value)
      this.loadTraffic()
      this.$nextTick(() => {
        if (!value) {
          this.teardownHeroMotion()
          return
        }
        if (!this.heroInitialized) {
          this.initHeroMotion()
        } else {
          this.drawHeroLinks()
          if (previous) this.triggerHeroRipple()
        }
      })
    },
    period() { this.loadTraffic() }
  },
  methods: {
    deltaClass(value) { return value == null ? '' : Number(value) < 0 ? 'delta-down' : 'delta-up' },
    deltaText(value, unit = '%') { return value == null ? '暂无可比数据' : Number(value) === 0 ? '持平' : `${Number(value) > 0 ? '↑' : '↓'} ${Math.abs(Number(value))}${unit}` },
    async loadTraffic() {
      const token = ++this.trafficRequestToken
      this.trafficLoading = true; this.trafficError = ''; this.traffic = null
      try { const response = await trafficOverview(this.period, this.current?.id); if (token === this.trafficRequestToken) this.traffic = response.data }
      catch (error) { if (token === this.trafficRequestToken) this.trafficError = error?.message || '流量数据读取失败' }
      finally { if (token === this.trafficRequestToken) this.trafficLoading = false }
    },
    tabKey(event) {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
      event.preventDefault()
      const index = this.rows.findIndex(item => item.id === this.current.id)
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? this.rows.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + this.rows.length) % this.rows.length
      this.current = this.rows[next]
      this.$nextTick(() => this.$el.querySelector('.ov-tab[aria-selected="true"]')?.focus())
    },
    phaseText, left: leftOf, formatNumber, clockLabel, activityPath,
    actionText(item) {
      const step = stepFor(item)
      if (step === 'pick') return '继续配置'
      if (step === 'live') return '查看领取情况'
      if (step === 'review') return '查看复盘'
      return '开场检查'
    },
    rangeText: dayRange,
    countdown(value) {
      const end = new Date(String(value).replace(' ', 'T')).getTime()
      const diff = Math.max(0, end - this.now)
      const hours = Math.floor(diff / 3600000)
      const minutes = Math.floor(diff / 60000) % 60
      const seconds = Math.floor(diff / 1000) % 60
      const pad = (n) => String(n).padStart(2, '0')
      return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
    },
    async load() {
      this.loading = true
      this.error = ''
      try {
        const response = await listActivities()
        this.rows = response.data || []
        const rank = { LIVE: 0, WARMUP: 1, DRAFT: 2, PAUSED: 3 }
        this.current = [...this.rows].sort((a, b) => (rank[a.phase] ?? 9) - (rank[b.phase] ?? 9))[0] || null
        if (!this.current) this.loadTraffic()
      } catch (error) {
        this.rows = []
        this.current = null
        this.error = error?.message || '活动读取失败'
      } finally {
        this.loading = false
      }
    },

    /* ---------- Hero 点阵、波纹与轨道连线 ---------- */
    initHeroMotion() {
      const hero = this.$refs.hero
      if (!hero || this.heroInitialized) return
      this.heroInitialized = true
      this.onHeroMove = event => {
        const box = hero.getBoundingClientRect()
        this.heroPointer = { x: event.clientX - box.left, y: event.clientY - box.top }
      }
      this.onHeroLeave = () => { this.heroPointer = { x: -999, y: -999 } }
      hero.addEventListener('pointermove', this.onHeroMove)
      hero.addEventListener('pointerleave', this.onHeroLeave)
      this.heroObserver = new ResizeObserver(() => this.resizeHero())
      this.heroObserver.observe(hero)
      this.resizeHero()
      const loop = now => {
        if (!this.reducedMotion && !document.hidden) this.drawHeroDots((now - this.heroStart) / 1000)
        this.heroFrame = requestAnimationFrame(loop)
      }
      this.heroFrame = requestAnimationFrame(loop)
    },
    teardownHeroMotion() {
      if (!this.heroInitialized) return
      this.heroInitialized = false
      cancelAnimationFrame(this.heroFrame)
      if (this.heroObserver) this.heroObserver.disconnect()
      const hero = this.$refs.hero
      if (hero && this.onHeroMove) {
        hero.removeEventListener('pointermove', this.onHeroMove)
        hero.removeEventListener('pointerleave', this.onHeroLeave)
      }
    },
    resizeHero() {
      const hero = this.$refs.hero
      const canvas = this.$refs.heroDots
      const orbitBox = this.$refs.orbitBox
      if (!hero || !canvas || !orbitBox) return
      const ratio = Math.min(window.devicePixelRatio || 1, 2)
      const box = hero.getBoundingClientRect()
      this.heroSize = { w: box.width, h: box.height }
      canvas.width = Math.round(box.width * ratio)
      canvas.height = Math.round(box.height * ratio)
      const ctx = canvas.getContext('2d')
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
      const ob = orbitBox.getBoundingClientRect()
      this.heroCenter = { x: ob.left - box.left, y: ob.top - box.top }
      this.drawHeroLinks()
      this.drawHeroDots(this.reducedMotion ? 1.1 : (performance.now() - this.heroStart) / 1000)
    },
    drawHeroDots(time) {
      const canvas = this.$refs.heroDots
      if (!canvas || !this.heroSize?.w || !this.heroSize?.h) return
      const { w, h } = this.heroSize
      const { x: cx, y: cy } = this.heroCenter
      const { x: px, y: py } = this.heroPointer
      const ctx = canvas.getContext('2d')
      this.heroBursts = this.heroBursts.filter(b => (time - b.t) * BURST_SPEED <= b.size)
      const pal = {
        live: [[91, 112, 64], [198, 97, 63]],
        warmup: [[184, 126, 24], [82, 126, 168]],
        muted: [[124, 120, 112], [82, 126, 168]]
      }[this.phaseTone] || [[91, 112, 64], [198, 97, 63]]
      ctx.clearRect(0, 0, w, h)
      const maxR = Math.hypot(w, h) * 0.72
      for (let y = DOT_GAP / 2; y < h; y += DOT_GAP) {
        for (let x = DOT_GAP / 2; x < w; x += DOT_GAP) {
          const d = Math.hypot(x - cx, y - cy)
          const r1 = ((time / WAVE_PERIOD) % 1) * maxR
          const r2 = ((time / WAVE_PERIOD + 0.5) % 1) * maxR
          let i1 = Math.exp(-(((d - r1) / 24) ** 2)) * (1 - r1 / maxR)
          let i2 = Math.exp(-(((d - r2) / 24) ** 2)) * (1 - r2 / maxR)
          for (const b of this.heroBursts) {
            const r = (time - b.t) * BURST_SPEED
            if (r >= 0) i1 += Math.exp(-(((Math.hypot(x - b.x, y - b.y) - r) / 22) ** 2)) * Math.max(0, 1 - r / b.size) * b.power
          }
          const mouse = Math.exp(-((Math.hypot(x - px, y - py) / 76) ** 2)) * 0.85
          i1 = Math.min(1, i1 + mouse * 0.6)
          i2 = Math.min(1, i2 + mouse * 0.4)
          const intensity = Math.min(1, i1 + i2)
          const w1 = intensity > 0 ? i1 / (i1 + i2) : 0.5
          const tr = pal[0][0] * w1 + pal[1][0] * (1 - w1)
          const tg = pal[0][1] * w1 + pal[1][1] * (1 - w1)
          const tb = pal[0][2] * w1 + pal[1][2] * (1 - w1)
          const r = 202 + (tr - 202) * intensity
          const g = 196 + (tg - 196) * intensity
          const b = 182 + (tb - 182) * intensity
          ctx.fillStyle = `rgba(${r | 0},${g | 0},${b | 0},${0.42 + intensity * 0.54})`
          ctx.beginPath()
          ctx.arc(x, y, 1 + intensity * 1.7, 0, Math.PI * 2)
          ctx.fill()
        }
      }
    },
    drawHeroLinks() {
      const svg = this.$refs.heroLinks
      const stage = this.$refs.heroStage
      const orbitBox = this.$refs.orbitBox
      if (!svg || !stage || !orbitBox) return
      const stageBox = stage.getBoundingClientRect()
      const ob = orbitBox.getBoundingClientRect()
      const ox = ob.left - stageBox.left
      const oy = ob.top - stageBox.top
      svg.setAttribute('viewBox', `0 0 ${stageBox.width} ${stageBox.height}`)
      svg.innerHTML = [...stage.querySelectorAll('[data-link]')].map(el => {
        const b = el.getBoundingClientRect()
        const x = b.left - stageBox.left + 8
        const y = b.top - stageBox.top + b.height / 2
        const color = el.dataset.link || '#c6613f'
        return `<path stroke="${color}" d="M${ox} ${oy} Q${(ox + x) / 2} ${(oy + y) / 2 - 14} ${x} ${y}" />`
      }).join('')
    },
    triggerHeroRipple() {
      if (this.reducedMotion) return
      this.heroBursts.push({
        x: this.heroCenter.x,
        y: this.heroCenter.y,
        t: (performance.now() - this.heroStart) / 1000,
        power: 1,
        size: 900
      })
      const seal = this.$refs.seal
      if (seal) {
        seal.classList.remove('beat')
        void seal.offsetWidth
        seal.classList.add('beat')
      }
    }
  }
}
</script>

<style scoped>
.overview-hero {
  position: relative;
  overflow: hidden;
  border-radius: 22px;
  background: linear-gradient(125deg, #f3efe6 0%, #f0eee6 48%, #edf1ea 100%);
  border: 1px solid var(--line);
  padding: 26px 32px 30px;
  margin-bottom: 20px;
  isolation: isolate;
  box-shadow: 0 20px 50px -36px #5a432b40;
}
.ov-aura { position: absolute; border-radius: 50%; filter: blur(46px); z-index: -4; pointer-events: none; opacity: .55; }
.ov-aura-1 { width: 380px; height: 320px; left: -60px; bottom: -120px; background: radial-gradient(circle, #6a9bcc36, transparent 70%); }
.ov-aura-2 { width: 420px; height: 340px; right: 12%; top: -110px; background: radial-gradient(circle, #c6613f2b, transparent 70%); }
.ov-aura-3 { width: 340px; height: 300px; right: -40px; bottom: -100px; background: radial-gradient(circle, #788c5d36, transparent 70%); }
.ov-dots { position: absolute; inset: 0; width: 100%; height: 100%; z-index: -3; }
.overview-hero::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -2;
  pointer-events: none;
  background: linear-gradient(90deg, #f3efe6d8 0%, #f0eee670 45%, transparent 78%);
}

.ov-top { position: relative; z-index: 2; display: flex; align-items: center; gap: 14px; margin-bottom: 24px; flex-wrap: wrap; }
.ov-label { font-size: 12px; color: var(--muted); font-weight: 500; flex-shrink: 0; }
.ov-tabs { display: flex; gap: 8px; flex-wrap: wrap; }
.ov-tab {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 7px 14px; border-radius: 999px; font-size: 13px; font-weight: 500;
  color: var(--ink2); background: #ffffff8c; border: 1px solid var(--line);
  backdrop-filter: blur(6px); transition: all .2s;
}
.ov-tab:hover { background: #fff; border-color: #d6d0c2; }
.ov-tab small { font-size: 11px; color: var(--muted); font-weight: 400; }
.ov-tab[aria-selected="true"] {
  background: var(--ink); color: var(--bg); border-color: var(--ink);
  box-shadow: 0 8px 18px -10px #14141388;
}
.ov-tab[aria-selected="true"] small { color: #d5d1c6; }

.ov-grid { position: relative; z-index: 2; display: grid; grid-template-columns: minmax(0, 1.05fr) minmax(0, 1.15fr); gap: 28px; align-items: center; }

.ov-pill { display: inline-flex; align-items: center; gap: 7px; padding: 4px 11px; border-radius: 999px; font-size: 12px; font-weight: 500; margin-bottom: 12px; }
.ov-pill.live { background: #eef1e7; color: var(--olive); }
.ov-pill.live i { width: 7px; height: 7px; border-radius: 50%; background: var(--olive); animation: ov-blink 1.6s ease-in-out infinite; }
.ov-pill.warmup { background: var(--tint); color: var(--clayd); }
.ov-pill.warmup i { width: 7px; height: 7px; border-radius: 50%; background: var(--clay); }
.ov-pill.muted { background: #e8e5db; color: var(--muted); }
.ov-pill.muted i { width: 7px; height: 7px; border-radius: 50%; background: var(--muted); }

.ov-title { margin: 0 0 6px; font-size: 18px; font-weight: 600; color: var(--ink2); }
.ov-state { margin: 0; font-family: var(--serif); font-size: clamp(38px, 4vw, 52px); line-height: 1.15; letter-spacing: .01em; color: var(--ink); }
.ov-state em { font-style: normal; color: var(--clay); transition: color .3s; }
.ov-state em.live { color: #5b7040; }
.ov-state em.warmup { color: #b87e18; }
.ov-time { margin: 12px 0 22px; color: var(--muted); font-size: 13px; }
.ov-actions { display: flex; gap: 10px; flex-wrap: wrap; }

.ov-stage { position: relative; min-height: 220px; display: flex; align-items: center; justify-content: flex-end; }
.ov-orbit-box { position: absolute; left: 24%; top: 50%; width: 0; height: 0; pointer-events: none; z-index: 0; }
.ov-orbit-svg { position: absolute; width: 380px; height: 380px; left: -190px; top: -190px; }
.ov-orbit-svg svg { width: 100%; height: 100%; overflow: visible; }
.ov-spin { transform-origin: 190px 190px; animation: ov-turn var(--d, 60s) linear infinite; }
.ov-spin.rev { animation-direction: reverse; }

.ov-seal {
  position: absolute; left: -27px; top: -27px; width: 54px; height: 54px; border-radius: 16px;
  background: linear-gradient(135deg, #d16f4c, var(--clay)); color: #fff; display: grid; place-items: center;
  font-family: var(--serif); font-size: 28px;
  box-shadow: 0 14px 30px -10px #b0502f88, inset 0 1px 0 #ffffff40;
  transition: all .3s;
}
.ov-seal.live { background: linear-gradient(135deg, #6c844d, #5b7040); box-shadow: 0 14px 30px -10px #5b704088; }
.ov-seal.warmup { background: linear-gradient(135deg, #d39b33, #b87e18); box-shadow: 0 14px 30px -10px #b87e1888; }
.ov-seal.muted { background: #7f7b72; box-shadow: 0 10px 24px -12px #14141355; }
.ov-seal::before, .ov-seal::after {
  content: ''; position: absolute; inset: -1px; border-radius: inherit;
  border: 1.5px solid currentColor; color: var(--clay); animation: ov-halo 3.2s ease-out infinite;
}
.ov-seal.live::before, .ov-seal.live::after { color: #5b7040; }
.ov-seal.warmup::before, .ov-seal.warmup::after { color: #b87e18; }
.ov-seal::after { animation-delay: 1.6s; }
.ov-seal.muted::before, .ov-seal.muted::after { animation: none; opacity: 0; }
.ov-seal.beat { animation: ov-beat .6s cubic-bezier(.2, .7, .2, 1); }

.ov-links { position: absolute; inset: 0; width: 100%; height: 100%; z-index: 1; pointer-events: none; }
.ov-links :deep(path) { fill: none; stroke-opacity: .38; stroke-width: 1.2; stroke-dasharray: 3 5; animation: ov-flow 1.8s linear infinite; }

.ov-metrics { position: relative; z-index: 2; display: grid; gap: 12px; width: min(360px, 100%); }
.ov-mcard {
  position: relative; overflow: hidden;
  background: #ffffffeb; backdrop-filter: blur(8px);
  border: 1px solid var(--line); border-radius: 14px; padding: 13px 16px;
  box-shadow: 0 12px 28px -20px #5a432b45;
  transition: transform .25s, border-color .25s;
}
.ov-mcard::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3.5px; background: var(--tone, var(--clay)); }
.ov-mcard:hover { transform: translateY(-2px); border-color: #d6cfc0; }
.ov-mhead { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; }
.ov-mlabel { font-size: 12px; color: var(--muted); display: inline-flex; align-items: center; gap: 6px; }
.ov-mlabel i { width: 6px; height: 6px; border-radius: 50%; background: var(--tone, var(--clay)); }
.ov-stat { position: relative; overflow: hidden; transition: transform .2s, box-shadow .2s, border-color .2s; }
.ov-stat::before { content: ''; position: absolute; left: 0; right: 0; top: 0; height: 3px; background: var(--tone, var(--clay)); opacity: .85; }
.ov-stat:hover { transform: translateY(-2px); border-color: #d7d0c2; box-shadow: 0 12px 26px -22px #5a432b45; }
.ov-stat-top { display: flex; justify-content: space-between; align-items: center; }
.ov-stat-ico { width: 26px; height: 26px; border-radius: 8px; background: var(--tone-bg, var(--tint)); color: var(--tone, var(--clay)); display: grid; place-items: center; }
.ov-stat-ico svg { width: 14px; height: 14px; }
.ov-mval { font-family: var(--serif); font-size: 25px; font-weight: 500; line-height: 1.15; font-variant-numeric: tabular-nums; }
.ov-mval-sm { margin-top: 4px; }
.ov-mval small { font-family: var(--font); font-size: 12px; color: var(--muted); margin-left: 4px; font-weight: 400; }
.ov-mbar { height: 5px; border-radius: 5px; background: var(--cloud); margin-top: 9px; overflow: hidden; }
.ov-mbar span { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #e5a587, var(--clay)); transition: width .9s cubic-bezier(.2, .7, .2, 1); }
.ov-mrow2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }

.ov-swap { animation: ov-swap .42s cubic-bezier(.2, .7, .2, 1); }

@keyframes ov-turn { to { transform: rotate(360deg); } }
@keyframes ov-halo { from { transform: scale(1); opacity: .55; } to { transform: scale(1.95); opacity: 0; } }
@keyframes ov-beat { 40% { transform: scale(1.12); } }
@keyframes ov-flow { to { stroke-dashoffset: -16; } }
@keyframes ov-blink { 50% { opacity: .3; } }
@keyframes ov-swap { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }

@media (max-width: 1100px) {
  .ov-grid { grid-template-columns: 1fr; }
  .ov-orbit-box { left: 16%; }
}
@media (max-width: 720px) {
  .overview-hero { padding: 20px 18px 24px; border-radius: 18px; }
  .ov-orbit-box, .ov-links { display: none; }
  .ov-stage { min-height: 0; justify-content: stretch; }
  .ov-metrics { width: 100%; }
}
@media (prefers-reduced-motion: reduce) {
  .ov-spin, .ov-seal::before, .ov-seal::after, .ov-seal.beat, .ov-links :deep(path), .ov-pill.live i, .ov-swap { animation: none !important; }
  .ov-mcard, .ov-mbar span, .ov-tab { transition: none !important; }
}
</style>
