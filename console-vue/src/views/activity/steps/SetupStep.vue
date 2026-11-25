<template>
  <!-- 活动配置整合页：同屏完成基本信息设置与选券投放配额，减少草稿阶段频繁切页。 -->
  <div class="setup-step">
    <InfoStep ref="infoStep" :activity="activity" @reload="$emit('reload')" />
    <div class="section-gap">
      <PickStep ref="pickStep" :activity="activity" @reload="$emit('reload')" />
    </div>
  </div>
</template>

<script>
import InfoStep from './InfoStep.vue'
import PickStep from './PickStep.vue'

export default {
  components: { InfoStep, PickStep },
  props: { activity: { type: Object, required: true } },
  emits: ['reload'],
  beforeRouteUpdate() { return this.confirmLeave() },
  beforeRouteLeave() { return this.confirmLeave() },
  methods: {
    confirmLeave() {
      if (this.$refs.infoStep?.hasUnsavedChanges() || this.$refs.pickStep?.hasUnsavedChanges()) {
        return window.confirm('活动配置或投放草稿尚未保存，确定离开？')
      }
      return true
    }
  }
}
</script>
