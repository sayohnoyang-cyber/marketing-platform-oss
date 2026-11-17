<template>
  <div>
    <div class="stephead"><div><h2>开场看板</h2></div><button class="btn" :disabled="loading" @click="load">{{ loading ? '更新中…' : '刷新数据' }}</button></div>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="linkbtn" @click="load">重试</button></p>
    <p v-if="loading && !board" class="loading">正在读取开场数据…</p>
    <template v-if="board">
      <div class="flow-demo"><div><b>统计截止 · 北京时间 {{ clockLabel(board.generatedAt) }}</b></div><router-link class="btn" :to="`/preview?activity=${activity.id}`">预览用户页面</router-link></div>
      <div class="stats"><div class="card stat"><div class="statlabel">活动状态</div><div class="value state-value">{{ phaseText(displayPhase(current)) }}</div></div><div class="card stat"><div class="statlabel">投放总量</div><div class="value">{{ formatNumber(current.quantity) }}</div></div><div class="card stat"><div class="statlabel">已领取</div><div class="value">{{ formatNumber(current.claimed) }}</div></div><div class="card stat"><div class="statlabel">剩余可领取</div><div class="value">{{ formatNumber(leftOf(current)) }}</div></div></div>
      <div class="card tablecard"><div class="tabletop"><h2>各入口领取情况</h2></div><ActivityEntries :activity="current" :channels="channels" :main="main" :reports="board.channels" /></div>
    </template>
    <MessageStatus :activity-id="activity.id" />
  </div>
</template>
<script>
import MessageStatus from '../MessageStatus.vue'
import { activityDashboard } from '@/api/campaign'
import ActivityEntries from '../ActivityEntries.vue'
import { clockLabel, displayPhase, formatNumber, leftOf, phaseText } from '../format'
export default {
  components: { MessageStatus, ActivityEntries },
  props: { activity: { type: Object, required: true }, channels: { type: Array, default: () => [] }, main: { type: Object, default: () => ({}) } },
  emits: ['snapshot'],
  data() { return { board: null, loading: false, error: '', timer: 0, requestToken: 0 } },
  computed: { current() { return this.board?.activity || this.activity } },
  watch: { 'activity.id': { immediate: true, handler() { this.board = null; this.load() } } },
  mounted() { this.timer = window.setInterval(() => { if (!document.hidden && !this.loading) this.load() }, 10000); document.addEventListener('visibilitychange', this.onVisibility) },
  beforeUnmount() { window.clearInterval(this.timer); this.requestToken++; document.removeEventListener('visibilitychange', this.onVisibility) },
  methods: {
    clockLabel, displayPhase, formatNumber, leftOf, phaseText,
    onVisibility() { if (!document.hidden && !this.loading) this.load() },
    async load() { const token = ++this.requestToken; this.loading = true; this.error = ''; try { const response = await activityDashboard(this.activity.id); if (token !== this.requestToken) return; this.board = response.data; this.$emit('snapshot', this.board.activity) } catch (error) { if (token === this.requestToken) this.error = error?.message || '开场数据读取失败' } finally { if (token === this.requestToken) this.loading = false } }
  }
}
</script>
