<template>
  <div>
    <div class="stephead"><div><h2>开场预热与检查</h2></div><div class="actions"><button class="btn" :disabled="loading || running" @click="load">重新读取</button><button class="btn primary" :disabled="running || loading" @click="prepare">{{ running ? '执行中…' : '执行预热与检查' }}</button></div></div>
    <OpeningPlanCard ref="openingPlan" :activity-id="activity.id" :version="activity.version" allow-prepare :busy="running || loading" @prepare="prepare" />
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="selectedRun" class="flow-message"><span class="pill" :class="{ amber: selectedRun.stale || selectedRun.status !== 'SUCCEEDED' }">{{ selectedRun.stale ? '活动配置已变化，请重新执行' : statusLabel(selectedRun.status) }}</span><span v-if="selectedRun.data?.warmLevel" class="pill" :class="{ amber: selectedRun.data.warmLevel === 'PEAK_PROTECT' }">{{ warmLabel(selectedRun.data.warmLevel) }}</span><span v-if="selectedRun.data?.totalVisits != null" class="pill">有效访问 {{ selectedRun.data.totalVisits }}</span><span v-if="selectedRun.data?.warmLevel === 'PEAK_PROTECT' && !selectedRun.data?.rateLimitActive"><router-link class="linkbtn" :to="`/activities/${activity.id}/guard?tab=forecast`">去开启限流保护 →</router-link></span></div>
    <div v-if="selectedRun?.data?.recommendations?.length" class="card flow-pad"><div v-for="(tip, idx) in selectedRun.data.recommendations" :key="idx" class="checkrow"><span>{{ tip }}</span></div></div>
    <div class="card flow-pad"><h2>发布准备</h2><p v-if="loading" class="loading">正在读取检查结果…</p><div v-for="item in blocking" :key="item.code" class="checkrow"><span>{{ item.passed ? '✓' : '○' }} {{ label(item.code) }}<small v-if="!item.passed" class="flow-sub">{{ item.reason }}</small></span><b :class="{ 'flow-error': !item.passed }">{{ item.passed ? '已就绪' : '待处理' }}</b></div><p v-if="!loading && !blocking.length" class="note">尚未取得发布检查结果。</p><div v-for="item in risks" :key="item.code" class="flow-message"><span class="pill amber">风险提示</span><p class="note">{{ item.reason }}</p></div></div>
    <div class="card tablecard section-gap"><div class="tabletop"><h2>应用预热任务</h2><span>{{ selectedRun ? statusLabel(selectedRun.status) : '尚未执行' }}</span></div><div v-if="selectedRun?.data.tasks?.length" class="preparation-list"><div v-for="item in selectedRun.data.tasks" :key="item.code" class="preparation-task"><span class="task-marker" :class="{ done: item.status === 'SUCCEEDED' }">{{ item.status === 'SUCCEEDED' ? '✓' : '○' }}</span><div><strong :title="item.detail">{{ item.title }}</strong><p v-if="item.status === 'FAILED'">{{ item.detail }}</p></div><span class="pill" :class="{ amber: item.status !== 'SUCCEEDED' }">{{ statusLabel(item.status) }}</span></div></div><p v-else class="empty">尚未执行</p></div>
    <label v-if="runs.length" class="field">历史执行记录<select :value="selectedRun?.id" @change="chooseRun($event.target.value)"><option v-for="item in runs" :key="item.id" :value="item.id">{{ clockLabel(item.createdAt) }} · {{ statusLabel(item.status) }}{{ item.stale ? ' · 配置已过期' : '' }}</option></select></label>
    <details class="section-gap"><summary class="linkbtn">消息处理与运维状态</summary><MessageStatus :activity-id="activity.id" /></details>
  </div>
</template>
<script>
import MessageStatus from '../MessageStatus.vue'
import OpeningPlanCard from '../OpeningPlanCard.vue'
import { activityChecks, runPreparation, listPreparationRuns } from '@/api/campaign'
import { clockLabel } from '../format'
const labels = { time: '领取时间已设置', template: '优惠券规则可读取', allocation: '投放库存已分配', 'main-link': '推广入口可识别活动和渠道', budget: '最大让利在预算内' }
export default {
  components: { MessageStatus, OpeningPlanCard },
  props: { activity: {type:Object, required:true} },
  emits: ['preparation-completed'],
  data() { return { items: [], runs: [], selectedRun: null, loading: false, running: false, error: '', pendingKey: '', pendingVersion: '', requestToken: 0 } },
  computed: { blocking() { return this.items.filter(item => item.blocking !== false && item.level !== 'risk') }, risks() { return this.items.filter(item => item.level === 'risk' || item.blocking === false) } },
  watch: { 'activity.version': { immediate:true, handler() { this.load() } } },
  methods: {
    clockLabel,
    label(code) { return labels[code] || code },
    statusLabel(status) { return { SAVED:'已保存', RUNNING:'执行中', SUCCEEDED:'已就绪', FAILED:'未通过', PARTIAL:'部分完成', UNVERIFIED:'未验证', SKIPPED:'未执行', STALE:'配置已过期' }[status] || status },
    warmLabel(level) { return { STANDARD: '常规预热', ELEVATED: '加强预热', PEAK_PROTECT: '高峰保护' }[level] || level },
    chooseRun(id) { this.selectedRun = this.runs.find(item => String(item.id) === String(id)) },
    async load() { const token = ++this.requestToken; this.loading = true; this.error = ''; this.items = []; try { const [checks,runs] = await Promise.all([activityChecks(this.activity.id),listPreparationRuns(this.activity.id)]); if (token === this.requestToken) { this.items = checks.data || []; this.runs = runs.data || []; this.selectedRun = this.runs[0] || null } } catch (error) { if (token === this.requestToken) this.error = error?.message || '检查读取失败' } finally { if (token === this.requestToken) this.loading = false } },
    async prepare() {
      if (this.running) return
      this.running = true; this.error = ''
      const signature = `${this.activity.id}:${this.activity.version}`
      if (!this.pendingKey || this.pendingVersion !== signature) { this.pendingKey = crypto.randomUUID(); this.pendingVersion = signature }
      try { const response = await runPreparation(this.activity.id, this.pendingKey); this.pendingKey = ''; this.selectedRun = response.data; this.runs = [response.data,...this.runs.filter(row => row.id !== response.data.id)].slice(0,20); this.items = (await activityChecks(this.activity.id)).data || []; this.$refs.openingPlan?.load(); this.$emit('preparation-completed', response.data) }
      catch (error) { if (error?.code === 'A000005') this.pendingKey = ''; this.error = error?.message || '预热执行失败' }
      finally { this.running = false }
    }
  }
}
</script>
