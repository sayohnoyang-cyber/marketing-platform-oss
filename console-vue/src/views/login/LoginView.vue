<template>
  <main class="page">
    <section ref="scene" class="scene" aria-hidden="true">
      <canvas ref="dots"></canvas>
      <svg ref="links" class="links"></svg>

      <div class="login-brand rise"><span class="login-mark">序</span><strong>营销工作台</strong></div>

      <div ref="core" class="core">
        <div class="orbit">
          <svg viewBox="0 0 560 560" fill="none">
            <g class="spin" style="--d: 120s" stroke="#cfc6b4" stroke-width="1">
              <circle cx="280" cy="280" r="120" />
              <circle cx="280" cy="280" r="200" stroke-dasharray="1 7" stroke-linecap="round" stroke-width="1.6" />
              <circle cx="280" cy="280" r="272" stroke-opacity=".6" />
            </g>
            <g class="spin rev" style="--d: 90s" stroke="#c6613f" stroke-opacity=".45" stroke-width="1.2" stroke-linecap="round">
              <path d="M160 280a120 120 0 0 1 120-120" />
              <path d="M480 280a200 200 0 0 1-90 167" />
            </g>
            <g class="spin" style="--d: 18s"><circle cx="280" cy="160" r="4" fill="#c6613f" /></g>
            <g class="spin rev" style="--d: 34s"><circle cx="80" cy="280" r="3.5" fill="#c6613f" /><circle cx="80" cy="280" r="9" stroke="#c6613f" stroke-opacity=".3" /></g>
            <g class="spin" style="--d: 56s"><circle cx="280" cy="552" r="3" fill="#5b7040" /></g>
          </svg>
        </div>
        <div ref="seal" class="seal rise" style="--delay: .15s">序</div>
      </div>

      <!-- 漂浮的业务卡片仅作装饰，数据为演示用 -->
      <div class="chip coupon rise" style="--delay: .35s; --f: 7s" data-link>
        <div class="stub"><b>¥20</b><small>满100可用</small></div>
        <div class="chip-body"><strong>新客立减券</strong><span class="k">已领取 <span class="num">{{ fmt(demo.claimed) }}</span> 张</span></div>
      </div>
      <div class="chip activity rise" style="--delay: .5s; --f: 8s" data-link>
        <div class="row"><strong>双十一预热</strong><span class="tag"><i></i>进行中</span></div>
        <div class="bar"><span :style="{ width: demo.progress + '%' }"></span></div>
        <div class="row k"><span>距开场</span><span class="num">{{ countdown }}</span></div>
      </div>
      <div class="chip reach rise" style="--delay: .65s; --f: 6.5s" data-link>
        <span class="k">今日触达</span>
        <div class="big num">{{ fmt(demo.reach) }}<span class="up">↑ 12.4%</span></div>
        <svg viewBox="0 0 180 38" preserveAspectRatio="none"><path class="spark" d="M2 32 L22 28 L40 30 L58 22 L76 24 L94 15 L112 18 L130 10 L150 12 L178 4" /></svg>
      </div>
      <div class="chip link rise" style="--delay: .8s; --f: 7.5s" data-link>
        <span class="ico"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/></svg></span>
        <code>s.xu/a8Kd2</code><span :key="demo.clicks" class="plus">点击 +1</span>
      </div>

      <div class="headline rise" style="--delay: .25s"><h1>有序准备，<br><em>从容开场。</em></h1></div>
    </section>

    <section class="entry" aria-label="账号入口">
      <canvas ref="dots2" class="dots2" aria-hidden="true"></canvas>
      <div ref="card" :key="mode" class="form-card rise" style="--delay: .2s">
        <h2>{{ mode === 'login' ? '登录' : '创建工作空间' }}</h2>
        <p class="sub">{{ mode === 'login' ? '欢迎回来，继续今天的活动安排。' : '几步完成设置，开始筹备第一场活动。' }}</p>

        <p v-if="error" class="notice error" role="alert"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5v.01"/></svg>{{ error }}</p>
        <p v-else-if="expired && mode === 'login'" class="notice info" role="status"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>登录已过期，请重新登录。</p>

        <form @submit.prevent="submit">
          <label class="field"><span>用户名</span>
            <span class="shell"><input v-model.trim="form.username" autocomplete="username" placeholder="请输入用户名" required minlength="4" maxlength="32" @focus="focusRipple" @input="typeRipple" /></span>
          </label>
          <label class="field"><span>密码</span>
            <span class="shell pwd">
              <input v-model="form.password" :type="showPassword ? 'text' : 'password'" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" placeholder="请输入密码" required :minlength="mode === 'register' ? 8 : 1" maxlength="72" @focus="focusRipple" @input="typeRipple" />
              <button class="eye" type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" :aria-pressed="showPassword" @click="showPassword = !showPassword"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/><path v-if="showPassword" d="m3 3 18 18"/></svg></button>
            </span>
          </label>
          <label v-if="mode === 'login'" class="remember"><input v-model="remember" type="checkbox" />记住账号</label>
          <button class="submit" :disabled="loading" type="submit">
            <span v-if="loading" class="spinner"></span>
            {{ loading ? (mode === 'login' ? '正在登录…' : '正在创建…') : (mode === 'login' ? '登录' : '创建工作空间') }}
          </button>
        </form>
        <div class="switch">
          <span>{{ mode === 'login' ? '还没有账号？' : '已有账号？' }}</span>
          <button type="button" :disabled="loading" @click="toggle">{{ mode === 'login' ? '创建账号' : '返回登录' }}</button>
        </div>
        <div class="login-icp"><IcpFooter /></div>
      </div>
    </section>

    <svg ref="bridge" class="bridge" aria-hidden="true"></svg>
  </main>
</template>

<script>
import { login, register } from '@/api/identity'
import IcpFooter from '@/components/IcpFooter.vue'
import { safeReturn } from './returnTo'

const USERNAME_KEY = 'marketing.username'
const LOCAL_LOGIN = import.meta.env.DEV ? {
  username: import.meta.env.VITE_LOCAL_LOGIN_USERNAME || '',
  password: import.meta.env.VITE_LOCAL_LOGIN_PASSWORD || ''
} : { username: '', password: '' }

// 点阵参数：网格间距、周期波纹间隔（秒）、交互波纹扩散速度（px/秒）
const GAP = 22
const PERIOD = 3.4
const BURST_SPEED = 260

export default {
  components: { IcpFooter },
  data() {
    return {
      mode: 'login',
      loading: false,
      error: '',
      remember: false,
      showPassword: false,
      form: { ...LOCAL_LOGIN },
      demo: { claimed: 1284, reach: 0, progress: 0, left: 2 * 3600 + 14 * 60 + 36, clicks: 0 }
    }
  },
  computed: {
    expired() {
      return this.$route.query.expired === '1'
    },
    countdown() {
      const s = this.demo.left
      return [s / 3600 | 0, s % 3600 / 60 | 0, s % 60].map(n => String(n).padStart(2, '0')).join(':')
    }
  },
  mounted() {
    const saved = localStorage.getItem(USERNAME_KEY)
    if (saved && !LOCAL_LOGIN.username) {
      this.form.username = saved
      this.remember = true
    }
    this.reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    this.start = performance.now()
    this.bursts = []
    this.pointer = { x: -9999, y: -9999 }
    this.layers = [
      { el: this.$refs.dots, host: this.$refs.scene, base: [205, 198, 182], alpha: 0.55 },
      { el: this.$refs.dots2, host: this.$refs.dots2, base: [214, 208, 194], alpha: 0.5 }
    ].map(layer => ({ ...layer, ctx: layer.el.getContext('2d') }))
    this.onPointer = event => { this.pointer = { x: event.clientX, y: event.clientY } }
    this.onLeave = () => { this.pointer = { x: -9999, y: -9999 } }
    window.addEventListener('pointermove', this.onPointer)
    document.addEventListener('pointerleave', this.onLeave)
    this.observer = new ResizeObserver(() => this.resize())
    this.observer.observe(document.body)
    this.resize()
    this.frame = requestAnimationFrame(this.loop)
    this.startDemo()
  },
  beforeUnmount() {
    cancelAnimationFrame(this.frame)
    cancelAnimationFrame(this.countFrame)
    clearTimeout(this.progressTimer)
    clearInterval(this.tickTimer)
    clearInterval(this.liveTimer)
    window.removeEventListener('pointermove', this.onPointer)
    document.removeEventListener('pointerleave', this.onLeave)
    if (this.observer) this.observer.disconnect()
  },
  updated() {
    // 提示条出现、模式切换会改变卡片高度，连线终点需要跟着更新
    this.$nextTick(() => this.drawBridge())
  },
  methods: {
    fmt(n) {
      return n.toLocaleString('en-US')
    },
    toggle() {
      if (this.loading) return
      this.mode = this.mode === 'login' ? 'register' : 'login'
      this.form = this.mode === 'login' ? { ...LOCAL_LOGIN, username: LOCAL_LOGIN.username || localStorage.getItem(USERNAME_KEY) || '' } : { username: '', password: '' }
      this.error = ''
    },
    async submit() {
      this.submitRipple()
      this.loading = true
      this.error = ''
      try {
        const response = this.mode === 'login' ? await login(this.form) : await register({ ...this.form, registerType: safeReturn(this.$route.query.return)?.startsWith('/a/') ? 'USER' : 'WORKSPACE' })
        if (this.mode === 'login' && this.remember) {
          localStorage.setItem(USERNAME_KEY, this.form.username)
        } else if (this.mode === 'login') {
          localStorage.removeItem(USERNAME_KEY)
        }
        this.$store.commit('setSession', { token: response.data.token, profile: response.data })
        const back = safeReturn(this.$route.query.return)
        const merchant = response.data.merchantId
        if (back && back.startsWith('/a/')) {
          this.$router.push(back)
        } else if (merchant) {
          this.$router.push('/overview')
        } else {
          this.$router.push(back || '/my-coupons')
        }
      } catch (error) {
        this.error = error?.message || '登录失败，请检查账号后重试'
      } finally {
        this.loading = false
      }
    },

    /* ---------- 演示卡片数据 ---------- */
    startDemo() {
      const target = 12486
      if (this.reduced) {
        Object.assign(this.demo, { reach: target, progress: 68 })
        return
      }
      this.progressTimer = setTimeout(() => { this.demo.progress = 68 }, 700)
      const t0 = performance.now()
      const count = now => {
        const p = Math.min(1, (now - t0 - 600) / 1800)
        if (p > 0) this.demo.reach = Math.round(target * (1 - (1 - p) ** 3))
        if (p < 1) this.countFrame = requestAnimationFrame(count)
      }
      this.countFrame = requestAnimationFrame(count)
      this.tickTimer = setInterval(() => { this.demo.left = Math.max(0, this.demo.left - 1) }, 1000)
      this.liveTimer = setInterval(() => {
        this.demo.claimed += 1 + (Math.random() * 3 | 0)
        if (this.demo.reach >= target) this.demo.reach += 3 + (Math.random() * 9 | 0)
        this.demo.clicks += 1
      }, 2400)
    },

    /* ---------- 点阵：左右两块画布共用页面坐标，从“序”向外有序点亮 ---------- */
    resize() {
      const ratio = Math.min(window.devicePixelRatio || 1, 2)
      for (const layer of this.layers) {
        const box = layer.host.getBoundingClientRect()
        Object.assign(layer, { x: box.left, y: box.top, w: box.width, h: box.height })
        layer.el.width = Math.round(box.width * ratio)
        layer.el.height = Math.round(box.height * ratio)
        layer.ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
      }
      const core = this.$refs.core.getBoundingClientRect()
      this.cx = core.left
      this.cy = core.top
      this.maxR = Math.hypot(window.innerWidth, window.innerHeight)
      this.drawLinks()
      this.drawBridge()
      this.draw(this.reduced ? 1.2 : (performance.now() - this.start) / 1000)
    },
    intensity(gx, gy, time) {
      const d = Math.hypot(gx - this.cx, gy - this.cy)
      const reach = this.maxR * 0.8
      let value = 0
      // 三道周期波纹交替扩散，越过面板边界继续向右侧淡出
      for (let k = 0; k < 3; k += 1) {
        const r = ((time / PERIOD + k / 3) % 1) * reach
        value += Math.exp(-(((d - r) / 26) ** 2)) * (1 - r / reach)
      }
      // 表单交互触发的额外波纹
      for (const b of this.bursts) {
        const r = (time - b.t) * BURST_SPEED
        if (r < 0) continue
        value += Math.exp(-(((Math.hypot(gx - b.x, gy - b.y) - r) / 22) ** 2)) * Math.max(0, 1 - r / b.size) * b.power
      }
      value += Math.exp(-((Math.hypot(gx - this.pointer.x, gy - this.pointer.y) / 80) ** 2)) * 0.9
      return Math.min(1, value)
    },
    draw(time) {
      this.bursts = this.bursts.filter(b => (time - b.t) * BURST_SPEED <= b.size)
      for (const layer of this.layers) {
        const { ctx, w, h, base, alpha } = layer
        if (!w || !h) continue
        ctx.clearRect(0, 0, w, h)
        // 网格按页面坐标对齐，左右两块画布的点落在同一条线上
        const ox = (((GAP / 2 - layer.x) % GAP) + GAP) % GAP
        const oy = (((GAP / 2 - layer.y) % GAP) + GAP) % GAP
        for (let y = oy; y < h; y += GAP) {
          for (let x = ox; x < w; x += GAP) {
            const I = this.intensity(x + layer.x, y + layer.y, time)
            const r = base[0] + (198 - base[0]) * I
            const g = base[1] + (97 - base[1]) * I
            const b = base[2] + (63 - base[2]) * I
            ctx.fillStyle = `rgba(${r | 0},${g | 0},${b | 0},${alpha + I * (1 - alpha)})`
            ctx.beginPath()
            ctx.arc(x, y, 1 + I * 1.8, 0, Math.PI * 2)
            ctx.fill()
          }
        }
      }
    },
    loop(now) {
      if (!this.reduced && !document.hidden) this.draw((now - this.start) / 1000)
      this.frame = requestAnimationFrame(this.loop)
    },
    burst(x, y, power, size) {
      if (this.reduced) return
      this.bursts.push({ x, y, t: (performance.now() - this.start) / 1000, power, size })
    },
    cardAnchor() {
      const box = this.$refs.card.getBoundingClientRect()
      return [box.left, box.top + box.height / 2]
    },
    pulseSeal() {
      const seal = this.$refs.seal
      seal.classList.remove('beat', 'rise')
      void seal.offsetWidth
      seal.classList.add('beat')
    },
    focusRipple() {
      this.burst(this.cx, this.cy, 1, 1400)
      this.pulseSeal()
    },
    typeRipple() {
      const [x, y] = this.cardAnchor()
      this.burst(x, y, 0.55, 420)
    },
    submitRipple() {
      const [x, y] = this.cardAnchor()
      this.burst(x, y, 1, 1600)
      setTimeout(() => {
        this.burst(this.cx, this.cy, 1, 1600)
        if (this.$refs.seal) this.pulseSeal()
      }, 500)
    },

    /* 业务卡片到“序”的虚线连线 */
    drawLinks() {
      const svg = this.$refs.links
      const scene = this.$refs.scene.getBoundingClientRect()
      const ox = this.cx - scene.left
      const oy = this.cy - scene.top
      svg.setAttribute('viewBox', `0 0 ${scene.width} ${scene.height}`)
      svg.innerHTML = [...this.$refs.scene.querySelectorAll('[data-link]')].map(el => {
        const box = el.getBoundingClientRect()
        const x = box.left - scene.left + box.width / 2
        const y = box.top - scene.top + box.height / 2
        return `<path d="M${x} ${y} Q${(x + ox) / 2} ${(y + oy) / 2 - 30} ${ox} ${oy}" />`
      }).join('')
    },
    /* “序”到登录卡片的连线：跨过面板边界，把左右两侧连成一体 */
    drawBridge() {
      const svg = this.$refs.bridge
      if (!svg || !this.$refs.card) return
      if (window.innerWidth <= 960) {
        svg.innerHTML = ''
        return
      }
      const [ex, ey] = this.cardAnchor()
      const { cx, cy } = this
      const d = `M${cx + 46} ${cy} C${cx + 180} ${cy}, ${ex - 160} ${ey}, ${ex - 6} ${ey}`
      svg.setAttribute('viewBox', `0 0 ${window.innerWidth} ${window.innerHeight}`)
      svg.innerHTML = `<path class="track" d="${d}" /><path class="flow" d="${d}" />${this.reduced ? '' : `<circle r="3" fill="#c6613f"><animateMotion dur="2.6s" repeatCount="indefinite" path="${d}" /></circle>`}`
    }
  }
}
</script>

<style scoped>
.page { height: 100svh; min-height: 640px; display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(420px, 1fr); gap: 16px; padding: 16px; background: var(--bg); color: var(--ink); }

/* ---------- 左侧画布 ---------- */
.scene { position: relative; overflow: hidden; border-radius: 24px; background: var(--cloud); border: 1px solid var(--line); isolation: isolate; }
.scene > canvas { position: absolute; inset: 0; width: 100%; height: 100%; z-index: -2; }
.scene::after { content: ''; position: absolute; inset: 0; z-index: -1; pointer-events: none; background: linear-gradient(0deg, var(--cloud) 6%, #f0eee6d0 22%, transparent 42%), linear-gradient(180deg, var(--cloud) 0%, transparent 18%); }

.login-brand { position: absolute; top: 30px; left: 34px; z-index: 3; display: flex; gap: 11px; align-items: center; }
.login-mark { width: 34px; height: 34px; border-radius: 10px; background: var(--clay); color: #fff; display: grid; place-items: center; font-family: var(--serif); font-size: 19px; }
.login-brand strong { font-size: 18px; font-weight: 600; letter-spacing: -.02em; }

.headline { position: absolute; left: 38px; bottom: 36px; z-index: 3; }
.headline h1 { margin: 0; font-family: var(--serif); font-weight: 400; font-size: clamp(34px, 3.4vw, 52px); line-height: 1.3; letter-spacing: .02em; }
.headline em { font-style: normal; color: var(--clay); }

.core { position: absolute; left: 56%; top: 44%; width: 0; height: 0; z-index: 1; }
.orbit { position: absolute; width: 560px; height: 560px; left: -280px; top: -280px; pointer-events: none; }
.orbit svg { width: 100%; height: 100%; overflow: visible; }
.spin { transform-origin: 280px 280px; animation: turn var(--d, 60s) linear infinite; }
.spin.rev { animation-direction: reverse; }
.seal { position: absolute; width: 76px; height: 76px; left: -38px; top: -38px; border-radius: 22px; background: var(--clay); color: #fff; display: grid; place-items: center; font-family: var(--serif); font-size: 40px; box-shadow: 0 18px 40px -12px #b0502f88, inset 0 1px 0 #ffffff40; }
.seal::before, .seal::after { content: ''; position: absolute; inset: -1px; border-radius: inherit; border: 1.5px solid var(--clay); animation: halo 3.2s ease-out infinite; }
.seal::after { animation-delay: 1.6s; }
.seal.beat { animation: beat .6s cubic-bezier(.2, .7, .2, 1); }

.links { position: absolute; inset: 0; width: 100%; height: 100%; z-index: 1; pointer-events: none; }
.links :deep(path) { fill: none; stroke: #c6613f; stroke-opacity: .35; stroke-width: 1; stroke-dasharray: 3 5; animation: flow 1.6s linear infinite; }

/* 漂浮的业务卡片 */
.chip { position: absolute; z-index: 2; background: #fff; border: 1px solid var(--line); border-radius: 14px; box-shadow: 0 14px 34px -18px #5a432b40, 0 2px 6px -2px #5a432b14; }
.k { font-size: 12px; color: var(--muted); }
.num { font-variant-numeric: tabular-nums; }
.coupon { left: 9%; top: 19%; width: 238px; display: flex; overflow: hidden; }
.stub { position: relative; width: 78px; padding: 14px 0; background: var(--clay); color: #fff; text-align: center; }
.stub b { display: block; font-family: var(--serif); font-size: 26px; font-weight: 500; line-height: 1.1; }
.stub small { font-size: 11px; opacity: .85; }
.stub::after { content: ''; position: absolute; right: -1px; top: 8px; bottom: 8px; border-right: 2px dotted #fff8; }
.chip-body { flex: 1; padding: 13px 14px; }
.chip-body strong { display: block; margin-bottom: 4px; font-size: 14px; font-weight: 600; }
.activity { right: 7%; top: 13%; width: 236px; padding: 14px 16px; }
.row { display: flex; justify-content: space-between; align-items: center; }
.activity strong { font-size: 14px; font-weight: 600; }
.tag { display: inline-flex; gap: 6px; align-items: center; padding: 2px 8px; border-radius: 999px; background: #eef1e7; color: var(--olive); font-size: 11px; }
.tag i { width: 6px; height: 6px; border-radius: 50%; background: var(--olive); animation: blink 1.6s ease-in-out infinite; }
.bar { height: 6px; margin: 12px 0 9px; border-radius: 6px; background: var(--cloud); overflow: hidden; }
.bar span { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #e3a183, var(--clay)); transition: width 1.4s cubic-bezier(.2, .7, .2, 1); }
.reach { right: 12%; top: 60%; width: 210px; padding: 14px 16px 10px; }
.big { margin: 2px 0; font-family: var(--serif); font-size: 28px; line-height: 1.2; }
.up { margin-left: 6px; color: var(--olive); font-family: var(--font); font-size: 12px; }
.reach svg { display: block; width: 100%; height: 38px; }
.spark { fill: none; stroke: var(--clay); stroke-width: 1.8; stroke-linecap: round; stroke-dasharray: 260; stroke-dashoffset: 260; animation: draw 5s ease-in-out infinite; }
.link { left: 15%; top: 58%; display: flex; gap: 10px; align-items: center; padding: 9px 12px; border-radius: 999px; }
.ico { width: 26px; height: 26px; border-radius: 50%; background: var(--tint); color: var(--clay); display: grid; place-items: center; }
.ico svg { width: 14px; height: 14px; }
.link code { font-family: ui-monospace, Menlo, monospace; font-size: 12px; color: var(--ink2); }
.plus { min-width: 52px; color: var(--clay); font-size: 12px; font-weight: 600; animation: pop 2.4s ease-out both; }

/* ---------- 右侧：点阵延续 + 登录卡片 ---------- */
.entry { position: relative; display: flex; justify-content: center; align-items: center; padding: 24px; isolation: isolate; }
.dots2 { position: absolute; inset: -16px; width: calc(100% + 32px); height: calc(100% + 32px); z-index: -1; pointer-events: none; -webkit-mask-image: linear-gradient(90deg, #000 0%, #0009 45%, transparent 95%); mask-image: linear-gradient(90deg, #000 0%, #0009 45%, transparent 95%); }
.form-card { position: relative; width: min(420px, 100%); padding: 40px 40px 30px; background: #fff; border: 1px solid var(--line); border-radius: 20px; box-shadow: 0 24px 60px -30px #5a432b55, 0 2px 8px -3px #5a432b14; }
.form-card::before { content: ''; position: absolute; left: -5px; top: 50%; width: 9px; height: 9px; margin-top: -4.5px; border-radius: 50%; background: var(--clay); box-shadow: 0 0 0 4px #c6613f22; }
.form-card h2 { margin: 0 0 8px; font-family: var(--serif); font-size: 34px; font-weight: 500; letter-spacing: -.01em; }
.sub { margin: 0 0 34px; color: var(--muted); font-size: 14px; line-height: 1.6; }
.notice { display: flex; gap: 10px; align-items: center; margin: -14px 0 24px; padding: 11px 14px; border-radius: 10px; font-size: 13px; line-height: 1.5; }
.notice svg { width: 16px; height: 16px; flex-shrink: 0; }
.notice.info { background: var(--cloud); border: 1px solid var(--line); color: var(--ink2); }
.notice.error { background: var(--tint); border: 1px solid #efd5c8; color: #9a3f24; }
.field { display: block; margin-bottom: 18px; }
.field > span:first-child { display: block; margin-bottom: 8px; color: var(--ink2); font-size: 13px; font-weight: 500; }
.shell { position: relative; display: block; }
.shell input { width: 100%; height: 48px; padding: 0 15px; border: 1px solid var(--line); border-radius: 10px; background: #fff; color: var(--ink); font-size: 15px; outline: none; transition: border-color .2s, box-shadow .2s; }
.shell input::placeholder { color: #a8a499; }
.shell input:hover { border-color: #d6d1c3; }
.shell input:focus { border-color: var(--clay); box-shadow: 0 0 0 4px #c6613f14; }
.shell.pwd input { padding-right: 48px; }
.eye { position: absolute; top: 7px; right: 7px; width: 34px; height: 34px; display: grid; place-items: center; border-radius: 8px; color: #8f8b81; }
.eye:hover { background: var(--cloud); color: var(--ink2); }
.eye svg { width: 18px; height: 18px; }
.remember { display: inline-flex; gap: 8px; align-items: center; margin: 4px 0 26px; color: var(--muted); font-size: 13px; cursor: pointer; }
.remember input { width: 15px; height: 15px; margin: 0; accent-color: var(--clay); }
.submit { width: 100%; height: 48px; display: flex; gap: 10px; align-items: center; justify-content: center; border-radius: 10px; background: var(--clay); color: #fff; font-size: 15px; font-weight: 500; box-shadow: 0 8px 18px -10px #b0502faa; transition: background-color .2s, transform .15s; }
.submit:hover:not(:disabled) { background: var(--clayd); }
.submit:active:not(:disabled) { transform: scale(.99); }
.submit:disabled { opacity: .85; cursor: progress; }
.spinner { width: 15px; height: 15px; border: 1.5px solid #ffffff55; border-top-color: #fff; border-radius: 50%; animation: turn .7s linear infinite; }
.switch { margin-top: 24px; text-align: center; color: var(--muted); font-size: 13px; }
.switch button { padding: 4px; color: var(--clay); font-size: 13px; font-weight: 500; }
.switch button:hover:not(:disabled) { color: var(--clayd); text-decoration: underline; text-underline-offset: 4px; }
.login-icp { margin-top: 22px; text-align: center; }

.bridge { position: fixed; inset: 0; z-index: 4; width: 100vw; height: 100vh; pointer-events: none; }
.bridge :deep(.track) { fill: none; stroke: #c6613f; stroke-opacity: .14; stroke-width: 6; stroke-linecap: round; }
.bridge :deep(.flow) { fill: none; stroke: #c6613f; stroke-opacity: .55; stroke-width: 1.2; stroke-dasharray: 3 5; animation: flow 1.2s linear infinite; }

/* ---------- 动画 ---------- */
.rise { opacity: 0; transform: translateY(14px); animation: rise .8s cubic-bezier(.2, .7, .2, 1) forwards var(--delay, 0s); }
.chip.rise { animation: rise .8s cubic-bezier(.2, .7, .2, 1) forwards var(--delay, 0s), float var(--f, 7s) ease-in-out infinite alternate calc(var(--delay, 0s) + .8s); }
@keyframes turn { to { transform: rotate(360deg); } }
@keyframes halo { from { transform: scale(1); opacity: .6; } to { transform: scale(1.9); opacity: 0; } }
@keyframes beat { 40% { transform: scale(1.1); } }
@keyframes flow { to { stroke-dashoffset: -16; } }
@keyframes float { from { transform: translateY(-6px); } to { transform: translateY(6px); } }
@keyframes rise { to { opacity: 1; transform: none; } }
@keyframes blink { 50% { opacity: .3; } }
@keyframes draw { 0% { stroke-dashoffset: 260; } 45%, 80% { stroke-dashoffset: 0; } 100% { stroke-dashoffset: -260; } }
@keyframes pop { 0% { opacity: 0; transform: translateY(4px); } 12% { opacity: 1; transform: none; } 40% { opacity: 1; } 60%, 100% { opacity: 0; transform: translateY(-8px); } }

@media (max-width: 1180px) {
  .coupon { left: 5%; } .activity { right: 4%; } .reach { right: 6%; } .link { left: 8%; }
  .orbit { transform: scale(.82); }
}
@media (max-width: 960px) {
  .page { display: block; height: auto; min-height: 100svh; padding: 12px; }
  .scene { height: 300px; }
  .chip, .links, .form-card::before { display: none; }
  .core { left: 74%; top: 46%; }
  .orbit { transform: scale(.62); }
  .seal { transform: scale(.8); }
  .login-brand { top: 22px; left: 24px; }
  .headline { left: 24px; bottom: 24px; }
  .headline h1 { font-size: 30px; }
  .entry { padding: 40px 0 48px; }
  .form-card { padding: 32px 24px 26px; }
  .shell input { font-size: 16px; }
}
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation: none !important; transition: none !important; }
  .rise { opacity: 1; transform: none; }
  .spark { stroke-dashoffset: 0; }
}
</style>
