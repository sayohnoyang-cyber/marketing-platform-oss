<template>
  <div class="traffic-plot" :aria-busy="loading">
    <svg ref="chart" viewBox="0 0 640 245" role="group" :aria-label="`${period === 'today' ? '今日每小时' : '近7日每天'}有效访问与历史同期对比`" @pointerleave="activeIndex = null">
      <g v-for="tick in ticks" :key="tick.value"><line x1="52" :y1="tick.y" x2="618" :y2="tick.y" class="traffic-gridline" /><text x="42" :y="tick.y + 4" text-anchor="end">{{ tick.value }}</text></g>
      <path v-if="previousPath" :d="previousPath" class="traffic-previous" />
      <path v-if="currentPath" :d="currentPath" class="traffic-current" />
      <circle v-for="(point,index) in points" :key="index" v-show="point.current != null && Number(point.current) > 0" :cx="x(index)" :cy="y(point.current)" r="3.5" class="traffic-dot"><title>{{ point.label }} · {{ point.current }} 位有效访客</title></circle>
      <text v-for="(point,index) in points" :key="`label-${index}`" v-show="showLabel(index)" :x="x(index)" y="229" text-anchor="middle">{{ point.label }}</text>
      <g v-if="activePoint" class="traffic-highlight" aria-hidden="true">
        <line :x1="x(activeIndex)" y1="25" :x2="x(activeIndex)" y2="195" />
        <circle v-if="activePoint.previous != null" :cx="x(activeIndex)" :cy="y(activePoint.previous)" r="5" class="history" />
        <circle v-if="activePoint.current != null" :cx="x(activeIndex)" :cy="y(activePoint.current)" r="5" />
      </g>
      <rect v-if="points.length && !loading" class="traffic-hit-area" x="52" y="25" width="566" height="170" tabindex="0" role="slider" aria-label="查看访问数据" aria-valuemin="0" :aria-valuemax="points.length - 1" :aria-valuenow="activeIndex ?? 0" :aria-valuetext="activeDescription" @pointermove="selectPoint" @pointerdown="selectPoint" @focus="activeIndex = activeIndex ?? 0" @blur="activeIndex = null" @keydown="selectWithKey" />
    </svg>
    <div v-if="activePoint" class="traffic-tooltip" role="tooltip" :style="{ left: `${Math.max(20, Math.min(80, x(activeIndex) / 640 * 100))}%` }">
      <strong>{{ activePoint.label }}</strong>
      <div><span><i></i>有效访问</span><b>{{ valueText(activePoint.current) }}</b></div>
      <div><span><i class="history"></i>历史同期</span><b>{{ valueText(activePoint.previous) }}</b></div>
    </div>
    <div v-if="loading" class="traffic-plot-message">正在读取访问趋势…</div>
    <div v-else-if="!points.some(point => Number(point.current) > 0 || Number(point.previous) > 0)" class="traffic-plot-message">当前范围暂无访问数据</div>
  </div>
</template>

<script>
export default {
  props: { points: { type: Array, default: () => [] }, period: String, loading: Boolean },
  data() { return { activeIndex: null } },
  watch: {
    points() { this.activeIndex = null },
    period() { this.activeIndex = null },
    loading() { this.activeIndex = null }
  },
  computed: {
    activePoint() { return !this.loading && this.activeIndex != null ? this.points[this.activeIndex] : null },
    activeDescription() {
      const point = this.activePoint || this.points[0]
      return point ? `${point.label}，有效访问 ${this.valueText(point.current)}，历史同期 ${this.valueText(point.previous)}` : ''
    },
    ceiling() {
      const maximum = Math.max(1, ...this.points.flatMap(point => [Number(point.current) || 0, Number(point.previous) || 0]))
      const raw = maximum / 4, base = 10 ** Math.floor(Math.log10(raw))
      const step = Math.max(1, [1,2,2.5,5,10].find(factor => factor * base >= raw) * base)
      return step * 4
    },
    ticks() { return Array.from({length:5}, (_,index) => { const value = this.ceiling * index / 4; return { value: Number(value.toFixed(2)), y: this.y(value) } }) },
    currentPath() { return this.path('current') },
    previousPath() { return this.path('previous') }
  },
  methods: {
    valueText(value) { return value == null ? '—' : `${Number(value).toLocaleString('zh-CN')} 人` },
    selectPoint(event) {
      const svg = this.$refs.chart, matrix = svg.getScreenCTM()
      if (!matrix || !this.points.length) return
      const pointer = svg.createSVGPoint()
      pointer.x = event.clientX; pointer.y = event.clientY
      const local = pointer.matrixTransform(matrix.inverse())
      this.activeIndex = Math.max(0, Math.min(this.points.length - 1, Math.round((local.x - 52) / 566 * (this.points.length - 1))))
    },
    selectWithKey(event) {
      if (event.key === 'Escape') { this.activeIndex = null; return }
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
      event.preventDefault()
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? this.points.length - 1 : (this.activeIndex ?? 0) + (event.key === 'ArrowRight' ? 1 : -1)
      this.activeIndex = Math.max(0, Math.min(this.points.length - 1, next))
    },
    x(index) { return 52 + index * 566 / Math.max(1,this.points.length - 1) },
    y(value) { return 195 - Number(value || 0) * 170 / this.ceiling },
    showLabel(index) { return this.period === 'week' || index % 4 === 0 || index === this.points.length - 1 },
    path(key) {
      let result = '', previous = null
      this.points.forEach((point,index) => {
        if (point[key] == null) { previous = null; return }
        const next = { x:this.x(index), y:this.y(point[key]) }
        if (!previous) result += `M${next.x} ${next.y}`
        else { const middle = (previous.x + next.x) / 2; result += ` C${middle} ${previous.y},${middle} ${next.y},${next.x} ${next.y}` }
        previous = next
      })
      return result
    }
  }
}
</script>

<style scoped>
.traffic-hit-area { fill: transparent; cursor: crosshair; }
.traffic-hit-area:focus-visible { outline: 2px solid var(--clay); outline-offset: 5px; }
.traffic-highlight { pointer-events: none; }
.traffic-highlight line { stroke: #b9b5a8; stroke-width: 1; stroke-dasharray: 4 4; }
.traffic-highlight circle { fill: var(--clay); stroke: white; stroke-width: 2; }
.traffic-highlight circle.history { fill: #b9b5a8; }
.traffic-tooltip { position: absolute; top: 0; z-index: 2; transform: translateX(-50%); width: 190px; max-width: 85%; box-sizing: border-box; padding: 13px 16px; border: 1px solid var(--line, #e8e5dc); border-radius: 12px; background: white; box-shadow: 0 6px 24px #25201814; pointer-events: none; font-size: 13px; }
.traffic-tooltip strong { display: block; margin-bottom: 9px; }
.traffic-tooltip div { display: flex; justify-content: space-between; gap: 16px; margin-top: 6px; }
.traffic-tooltip span { color: var(--muted); white-space: nowrap; }
.traffic-tooltip b { font-variant-numeric: tabular-nums; white-space: nowrap; }
.traffic-tooltip i { display: inline-block; width: 7px; height: 7px; border-radius: 50%; margin-right: 7px; background: var(--clay); }
.traffic-tooltip i.history { background: #b9b5a8; }
</style>
