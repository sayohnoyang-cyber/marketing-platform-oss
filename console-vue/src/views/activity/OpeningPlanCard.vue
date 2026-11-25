<template>
  <div class="card opening-plan-card">
    <div class="sectionhead"><h2>预计开抢压力</h2><span class="pill" :class="{ amber: shown.capacityStatus !== 'WITHIN_CAPACITY' }">{{ statusText }}</span></div>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="linkbtn" @click="load">重试</button></p>
    <p v-else-if="loading && !plan" class="loading">正在评估预约数据…</p>
    <template v-else-if="plan">
      <div class="opening-plan-metrics">
        <div><span>有效预约</span><b>{{ formatNumber(shown.reservationCount) }}<small>人</small></b></div>
        <div><span>预计开场到场</span><b>{{ formatNumber(shown.expectedArrivals) }}<small>人</small></b></div>
        <div><span>预计峰值请求</span><b>{{ formatNumber(shown.peakQps) }}<small v-if="shown.peakQps != null">次/秒</small></b></div>
      </div>
      <div class="opening-plan-footer">
        <span>{{ dirty ? '当前估算 · 待保存' : plan.forecastNeedsRefresh ? '预约或配置待更新预测' : '已保存预测' }}<span class="plan-basis"> · {{ sourceText }} · 开场 10 秒</span></span>
        <button v-if="allowSave && (dirty || plan.forecastNeedsRefresh) && shown.peakQps != null" class="btn primary" :disabled="busy" @click="$emit('save')">保存预测</button>
        <button v-else-if="allowPrepare && plan.nextAction === 'RUN_PREPARATION'" class="btn primary" :disabled="busy" @click="$emit('prepare')">执行预热</button>
        <router-link v-else class="btn" :to="actionPath">{{ actionText }}</router-link>
      </div>
      <p class="opening-plan-note">{{ advice }}</p>
    </template>
  </div>
</template>
<script>
import { openingPlan } from '@/api/campaign'
import { formatNumber } from './format'
export default {
  props: { activityId: [String, Number], version: [String, Number], draft: Object, dirty: Boolean, allowSave: Boolean, allowPrepare: Boolean, busy: Boolean },
  emits: ['save', 'prepare'],
  data() { return { plan: null, loading: false, error: '', requestToken: 0 } },
  computed: {
    shown() { return this.draft || this.plan || {} },
    statusText() { return { NO_ACTIVE_ENTRY: '入口待启用', NO_SAMPLE: '待积累预约', UNVERIFIED: '容量待验证', OVER_CAPACITY: '需评估扩容或限流', WITHIN_CAPACITY: '预测在容量范围内' }[this.shown.capacityStatus] || '正在评估' },
    sourceText() { return { HISTORY: '历史到场率', DEFAULT: '默认到场率', SAVED: '已保存参数' }[this.plan?.source] || '情景估算' },
    advice() { return { NO_ACTIVE_ENTRY: '已有预约的入口当前未启用；先恢复推广入口，再更新开场预测。', NO_SAMPLE: '尚无有效预约样本；可先配置推广渠道并完成基础预热。', UNVERIFIED: '当前是预约驱动的估计。预热可提前准备缓存与库存，承载能力仍需实测。', OVER_CAPACITY: '预计压力超过当前安全容量；先评估扩容或入口限流，预热不能替代扩容。', WITHIN_CAPACITY: '按当前假设处于容量范围内，仍需完成缓存、库存和消息检查。' }[this.shown.capacityStatus] || '' },
    actionText() { return { COLLECT_RESERVATIONS: '配置推广渠道', SAVE_FORECAST: '更新预约预测', REVIEW_CAPACITY: '评估容量', RUN_PREPARATION: '执行预热', MONITOR_OPENING: '查看开场情况', REVIEW_ACTIVITY: '查看复盘' }[this.plan?.nextAction] || '查看预测' },
    actionPath() {
      // 按新工作台 4 视图收拢跳转目标
      const target = {
        COLLECT_RESERVATIONS: { path: `/activities/${this.activityId}/promote` },
        SAVE_FORECAST: { path: `/activities/${this.activityId}/guard`, query: { tab: 'forecast' } },
        REVIEW_CAPACITY: { path: `/activities/${this.activityId}/guard`, query: { tab: 'forecast' } },
        RUN_PREPARATION: { path: `/activities/${this.activityId}/guard`, query: { tab: 'prepare' } },
        MONITOR_OPENING: { path: `/activities/${this.activityId}/analytics` },
        REVIEW_ACTIVITY: { path: `/activities/${this.activityId}/analytics` }
      }[this.plan?.nextAction]
      return target || `/activities/${this.activityId}/guard`
    }
  },
  watch: { activityId: { immediate: true, handler() { this.plan = null; this.load() } }, version() { this.load() } },
  beforeUnmount() { this.requestToken++ },
  methods: {
    formatNumber,
    async load() { const token = ++this.requestToken; this.loading = true; this.error = ''; try { const response = await openingPlan(this.activityId); if (token === this.requestToken) this.plan = response.data } catch (error) { if (token === this.requestToken) this.error = error?.message || '开场评估读取失败' } finally { if (token === this.requestToken) this.loading = false } }
  }
}
</script>
