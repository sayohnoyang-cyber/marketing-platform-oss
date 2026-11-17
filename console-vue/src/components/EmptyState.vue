<template>
  <div class="empty-state">
    <div class="empty-state-icon" aria-hidden="true"><svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.6"><rect x="10" y="9" width="28" height="31" rx="5" /><path d="M18 19h12M18 25h12M18 31h7" /></svg></div>
    <h3>{{ title }}</h3>
    <div v-if="$slots.default" class="actions"><slot /></div>
  </div>
</template>
<script>
export default { props: { title: { type: String, required: true } } }
</script>
