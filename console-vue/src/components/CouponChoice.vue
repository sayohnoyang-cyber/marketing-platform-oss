<template>
  <button class="pickcard coupon-choice" :class="{ on: chosen }" type="button" :aria-pressed="chosen">
    <span class="choice-face"><b :class="{ compact: String(face).replace(/[¥￥折\s]/g, '').length > 4 }"><CouponFace :value="face" /></b></span>
    <span class="choice-content"><strong>{{ name }}</strong><small>{{ rule }}</small><small><slot /></small></span>
    <span class="choice-check" aria-hidden="true">{{ chosen ? '✓' : '' }}</span>
  </button>
</template>
<script>
import CouponFace from './CouponFace.vue'
export default {
  components: { CouponFace },
  props: { chosen: Boolean, face: String, name: String, rule: String }
}
</script>
