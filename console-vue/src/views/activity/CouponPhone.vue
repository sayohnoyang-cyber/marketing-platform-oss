<template>
  <div class="device" :class="{ 'public-device': publicPage }">
    <div class="phonebar"><b>9:41</b><span aria-hidden="true">●●● ▰</span></div>
    <DotStage ref="heroStage" class="phonehero phonehero-motion" :offset-right="28" :offset-top="32" :rings="2" :palette="palette">
      <div class="pb-orbit" aria-hidden="true">
        <svg viewBox="0 0 148 148" fill="none">
          <g class="pb-spin"><circle cx="74" cy="74" r="44" stroke="#bcc7b6" /><circle cx="74" cy="30" r="2.8" fill="#5b7040" /></g>
          <g class="pb-spin rev"><circle cx="74" cy="74" r="64" stroke="#527ea8" stroke-opacity=".42" stroke-dasharray="2 5" /><circle cx="74" cy="10" r="2.4" fill="#c6613f" /></g>
        </svg>
      </div>
      <span>{{ phase }}</span><h2>{{ name || '请选择活动' }}</h2>
      <p>{{ rule }}<template v-if="limit"> · 每人限领 {{ limit }} 张</template></p>
      <p>{{ timeText }}</p>
    </DotStage>
    <p class="device-label">本场优惠券</p>
    <div class="coupon">
      <strong :class="{ compact: String(face || '').replace(/[¥￥折\s]/g, '').length > 4 }"><CouponFace :value="face" /></strong>
      <div><h3>{{ couponName || '尚未选券' }}</h3><p>{{ rule }}</p></div>
    </div>
    <div class="actionwrap">
      <button class="btn primary" type="button" :disabled="disabled || busy" @click="triggerAction">{{ busy ? '正在处理…' : actionText }}</button>
      <p v-if="note" class="phone-note" role="status">{{ note }}</p>
      <slot />
    </div>
  </div>
</template>

<script>
import CouponFace from '@/components/CouponFace.vue'
import DotStage from '@/components/DotStage.vue'

export default {
  components: { CouponFace, DotStage },
  props: {
    name: String, phase: String, rule: String, limit: [Number, String], timeText: String,
    face: String, couponName: String, actionText: String, note: String,
    disabled: Boolean, busy: Boolean, publicPage: Boolean,
    palette: { type: Array, default: () => [[91, 112, 64], [82, 126, 168]] }
  },
  emits: ['action'],
  methods: {
    triggerAction() {
      this.$refs.heroStage?.burst()
      this.$emit('action')
    },
    pulse() {
      this.$refs.heroStage?.burst()
    }
  }
}
</script>

<style scoped>
.phonehero-motion {
  border: 1px solid #e1dcd0;
  background: linear-gradient(135deg, #eef2eb 0%, #ede8dc 60%, #f3eae3 100%);
}
/* 仅提升插槽内文本与交互层层级，避免覆盖 DotStage 内部绝对定位的 canvas 导致高度循环撑开 */
.phonehero-motion > *:not(.pb-orbit):not(.dot-stage-canvas) {
  position: relative;
  z-index: 1;
}
.pb-orbit {
  position: absolute;
  right: -44px;
  top: -36px;
  width: 148px;
  height: 148px;
  pointer-events: none;
  z-index: -1;
}
.pb-orbit svg {
  width: 100%;
  height: 100%;
  overflow: visible;
}
.pb-spin {
  transform-origin: 74px 74px;
  animation: pb-turn 34s linear infinite;
}
.pb-spin.rev {
  animation-direction: reverse;
  animation-duration: 24s;
}
@keyframes pb-turn {
  to { transform: rotate(360deg); }
}
@media (prefers-reduced-motion: reduce) {
  .pb-spin { animation: none; }
}
</style>
