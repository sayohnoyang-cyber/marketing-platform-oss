<template>
  <div ref="host" class="dot-stage" @pointermove="onMove" @pointerleave="onLeave">
    <canvas ref="canvas" class="dot-stage-canvas" aria-hidden="true"></canvas>
    <slot />
  </div>
</template>

<script>
const DOT_GAP = 20
const WAVE_PERIOD = 3.8
const BURST_SPEED = 260
const DEFAULT_PALETTE = [[198, 97, 63], [198, 97, 63]]

/**
 * 复用点阵波纹舞台：支持双色交织周期扩散、鼠标跟随点亮与外部脉冲触发。
 */
export default {
  name: 'DotStage',
  props: {
    centerX: { type: Number, default: 0.5 },
    centerY: { type: Number, default: 0.52 },
    offsetLeft: { type: Number, default: 0 },
    offsetRight: { type: Number, default: 0 },
    offsetTop: { type: Number, default: 0 },
    rings: { type: Number, default: 2 },
    // 双色交织调色板：[[r1, g1, b1], [r2, g2, b2]]
    palette: { type: Array, default: () => DEFAULT_PALETTE }
  },
  mounted() {
    this.reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    this.start = performance.now()
    this.pointer = { x: -999, y: -999 }
    this.bursts = []
    this.size = { w: 0, h: 0 }
    this.observer = new ResizeObserver(() => this.resize())
    this.observer.observe(this.$refs.host)
    this.resize()
    const loop = now => {
      if (!this.reduced && !document.hidden) this.draw((now - this.start) / 1000)
      this.frame = requestAnimationFrame(loop)
    }
    this.frame = requestAnimationFrame(loop)
  },
  beforeUnmount() {
    cancelAnimationFrame(this.frame)
    if (this.observer) this.observer.disconnect()
  },
  methods: {
    onMove(event) {
      const box = this.$refs.host?.getBoundingClientRect()
      if (!box) return
      this.pointer = { x: event.clientX - box.left, y: event.clientY - box.top }
    },
    onLeave() {
      this.pointer = { x: -999, y: -999 }
    },
    burst() {
      if (this.reduced) return
      this.bursts.push({ t: (performance.now() - this.start) / 1000 })
    },
    resize() {
      const host = this.$refs.host
      const canvas = this.$refs.canvas
      if (!host || !canvas) return
      const box = host.getBoundingClientRect()
      if (!box.width || !box.height) return
      const ratio = Math.min(window.devicePixelRatio || 1, 2)
      this.size = { w: box.width, h: box.height }
      canvas.width = Math.round(box.width * ratio)
      canvas.height = Math.round(box.height * ratio)
      canvas.getContext('2d').setTransform(ratio, 0, 0, ratio, 0, 0)
      this.draw(this.reduced ? 1.1 : (performance.now() - this.start) / 1000)
    },
    draw(time) {
      const canvas = this.$refs.canvas
      const { w, h } = this.size
      if (!canvas || !w || !h) return
      const ctx = canvas.getContext('2d')
      this.bursts = this.bursts.filter(b => (time - b.t) * BURST_SPEED <= 620)
      const cx = this.offsetLeft > 0 ? this.offsetLeft : this.offsetRight > 0 ? Math.max(0, w - this.offsetRight) : w * this.centerX
      const cy = this.offsetTop > 0 ? this.offsetTop : h * this.centerY
      const maxR = Math.hypot(w, h) * 0.72
      const palA = this.palette?.[0] || DEFAULT_PALETTE[0]
      const palB = this.palette?.[1] || palA
      ctx.clearRect(0, 0, w, h)
      for (let y = DOT_GAP / 2; y < h; y += DOT_GAP) {
        for (let x = DOT_GAP / 2; x < w; x += DOT_GAP) {
          const d = Math.hypot(x - cx, y - cy)
          const r1 = ((time / WAVE_PERIOD) % 1) * maxR
          const r2 = ((time / WAVE_PERIOD + 0.5) % 1) * maxR
          let i1 = Math.exp(-(((d - r1) / 24) ** 2)) * (1 - r1 / maxR)
          let i2 = this.rings > 1 ? Math.exp(-(((d - r2) / 24) ** 2)) * (1 - r2 / maxR) : 0
          for (const b of this.bursts) {
            const r = (time - b.t) * BURST_SPEED
            if (r >= 0) i1 += Math.exp(-(((d - r) / 22) ** 2)) * Math.max(0, 1 - r / 620)
          }
          const mouse = Math.exp(-((Math.hypot(x - this.pointer.x, y - this.pointer.y) / 72) ** 2)) * 0.85
          i1 = Math.min(1, i1 + mouse * 0.6)
          i2 = Math.min(1, i2 + mouse * 0.4)
          const intensity = Math.min(1, i1 + i2)
          const w1 = intensity > 0 ? i1 / (i1 + i2) : 0.5
          const tr = palA[0] * w1 + palB[0] * (1 - w1)
          const tg = palA[1] * w1 + palB[1] * (1 - w1)
          const tb = palA[2] * w1 + palB[2] * (1 - w1)
          const r = 200 + (tr - 200) * intensity
          const g = 196 + (tg - 196) * intensity
          const b = 184 + (tb - 184) * intensity
          ctx.fillStyle = `rgba(${r | 0},${g | 0},${b | 0},${0.42 + intensity * 0.54})`
          ctx.beginPath()
          ctx.arc(x, y, 1 + intensity * 1.6, 0, Math.PI * 2)
          ctx.fill()
        }
      }
    }
  }
}
</script>

<style scoped>
.dot-stage {
  position: relative;
  overflow: hidden;
  isolation: isolate;
}
.dot-stage-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  z-index: -2;
  pointer-events: none;
}
</style>
