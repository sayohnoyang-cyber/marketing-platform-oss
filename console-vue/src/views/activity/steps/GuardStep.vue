<template>
  <!-- 高级保障收拢页：将压测预测、限流策略、缓存预热及 Outbox 消息监控收纳到运维保障标签下，不打扰常规活动运营。 -->
  <div>
    <p class="flow-message guard-intro">常规小规模活动可直接跳过本页；若预计大促高并发抢券，可在此做峰值估算、Redis 限流与缓存预热。</p>
    <div class="guard-bar">
      <div class="seg" role="tablist" aria-label="高级保障子视图">
        <button type="button" role="tab" :aria-selected="tab === 'forecast'" :class="{ active: tab === 'forecast' }" @click="switchTab('forecast')">压力预测与限流</button>
        <button type="button" role="tab" :aria-selected="tab === 'prepare'" :class="{ active: tab === 'prepare' }" @click="switchTab('prepare')">开场预热与消息保障</button>
      </div>
    </div>
    <ForecastStep v-if="tab === 'forecast'" :activity="activity" @forecast-saved="$emit('forecast-saved', $event)" />
    <PrepareStep v-else :activity="activity" @preparation-completed="$emit('preparation-completed', $event)" />
  </div>
</template>

<script>
import ForecastStep from './ForecastStep.vue'
import PrepareStep from './PrepareStep.vue'

export default {
  components: { ForecastStep, PrepareStep },
  props: { activity: { type: Object, required: true } },
  emits: ['forecast-saved', 'preparation-completed'],
  computed: {
    tab() {
      return this.$route.query.tab === 'prepare' ? 'prepare' : 'forecast'
    }
  },
  methods: {
    switchTab(next) {
      if (this.tab === next) return
      this.$router.replace({ query: { ...this.$route.query, tab: next } })
    }
  }
}
</script>

<style scoped>
.guard-intro { margin-bottom: 14px; }
.guard-bar { margin-bottom: 18px; }
</style>
