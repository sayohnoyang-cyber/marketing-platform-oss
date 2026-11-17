<template>
  <small v-if="parts.prefix">{{ parts.prefix }}</small>{{ parts.number }}<small v-if="parts.suffix">{{ parts.suffix }}</small>
</template>

<script>
export default {
  props: { value: String },
  computed: {
    parts() {
      const face = String(this.value || '—').trim()
      const amount = face.match(/^[¥￥]\s*(\d+(?:\.\d+)?)$/)
      if (amount) return { prefix: '¥', number: amount[1] }
      const discount = face.match(/^(\d+(?:\.\d+)?)\s*折$/)
      return discount ? { number: discount[1], suffix: '折' } : { number: face }
    }
  }
}
</script>
