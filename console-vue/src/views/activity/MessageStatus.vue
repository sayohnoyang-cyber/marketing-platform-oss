<template>
  <div class="card flow-pad section-gap">
    <div class="sectionhead flush"><h2>消息处理</h2><span v-if="snapshot" class="pill" :class="{ amber: snapshot.status === 'FAILED' || snapshot.status === 'BACKLOG' }" :title="snapshot.definition">{{ statusText }}</span><button class="linkbtn" :disabled="loading" @click="load">刷新</button></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="snapshot" class="message-metrics">
      <div><span>待处理</span><b>{{ formatNumber(snapshot.backlog) }}</b></div>
      <div><span>近5分钟已处理</span><b>{{ formatNumber(snapshot.completedInWindow) }}</b></div>
      <div><span>重试中</span><b>{{ formatNumber(snapshot.retrying) }}</b></div>
      <div><span>失败</span><b :class="{ 'flow-error': Number(snapshot.dead) > 0 }">{{ formatNumber(snapshot.dead) }}</b></div>
    </div>
    <div v-if="snapshot" class="message-footer"><span>{{ snapshot.transport === 'ROCKETMQ' ? 'RocketMQ' : '本地投递' }}</span><span :title="snapshot.definition">近期平均 {{ snapshot.observedPerSecond }} 条/秒</span><span v-if="Number(snapshot.backlog) > 0">排空估算 {{ snapshot.estimatedDrainSeconds == null ? '—' : snapshot.estimatedDrainSeconds + '秒' }}</span></div>
    <p v-else-if="loading" class="loading">正在读取…</p>
  </div>
</template>
<script>
import { activityMessageStatus } from '@/api/campaign'
import { formatNumber } from './format'
export default {
  props: { activityId: { type: [String, Number], required: true } },
  data() { return { snapshot: null, loading: false, error: '', token: 0, timer: null } },
  computed: { statusText() { return { IDLE: '暂无消息', HEALTHY: '处理正常', BACKLOG: '有待处理消息', FAILED: '需要处理' }[this.snapshot?.status] || '读取中' } },
  watch: { activityId: { immediate: true, handler() { this.snapshot = null; this.load() } } },
  mounted() { this.timer = window.setInterval(() => { if (!document.hidden && !this.loading) this.load() }, 10000) },
  beforeUnmount() { window.clearInterval(this.timer); this.token++ },
  methods: {
    formatNumber,
    async load() {
      const token = ++this.token; this.loading = true; this.error = ''
      try { const response = await activityMessageStatus(this.activityId); if (token === this.token) this.snapshot = response.data }
      catch (error) { if (token === this.token) this.error = error?.message || '消息状态读取失败' }
      finally { if (token === this.token) this.loading = false }
    }
  }
}
</script>
