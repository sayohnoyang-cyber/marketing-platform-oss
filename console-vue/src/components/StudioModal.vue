<template>
  <Teleport to="body">
    <div class="modalback" @click.self="close">
      <section ref="panel" class="studio-modal" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1" @keydown="onKey">
        <header class="modal-header">
          <h2>{{ title }}</h2>
          <button class="modal-close" type="button" aria-label="关闭弹窗" :disabled="busy" @click="close"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button>
        </header>
        <div class="modal-body"><slot /></div>
      </section>
    </div>
  </Teleport>
</template>

<script>
export default {
  props: { title: { type: String, required: true }, busy: Boolean },
  emits: ['close'],
  mounted() {
    this.previousFocus = document.activeElement
    this.previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    this.$nextTick(() => (this.controls().find(node => ['INPUT', 'SELECT', 'TEXTAREA'].includes(node.tagName)) || this.controls()[0] || this.$refs.panel)?.focus())
  },
  beforeUnmount() {
    document.body.style.overflow = this.previousOverflow
    this.previousFocus?.focus?.()
  },
  methods: {
    close() { if (!this.busy) this.$emit('close') },
    controls() { return [...this.$refs.panel.querySelectorAll('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]')] },
    onKey(event) {
      if (event.key === 'Escape') { event.preventDefault(); this.close() }
      if (event.key !== 'Tab') return
      const controls = this.controls()
      const first = controls[0], last = controls.at(-1)
      if (!first) { event.preventDefault(); return }
      if (event.shiftKey && (document.activeElement === first || document.activeElement === this.$refs.panel)) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && (document.activeElement === last || document.activeElement === this.$refs.panel)) { event.preventDefault(); first.focus() }
    }
  }
}
</script>
