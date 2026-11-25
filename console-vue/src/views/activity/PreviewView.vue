<template>
  <section>
    <div class="pagehead"><div><h1>领券页预览</h1></div><router-link v-if="activityId" class="btn" :to="`/activities/${activityId}/promote`">返回推广入口</router-link></div>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="linkbtn" @click="loadOne">重试</button></p>
    <p v-if="loading" class="loading">正在读取活动…</p>
    <div v-else-if="!current && !error" class="card"><EmptyState title="暂无可预览的活动"><router-link class="btn primary" to="/activities/new">创建活动</router-link></EmptyState></div>
    <template v-else-if="sandbox">
      <div class="preview-toolbar">
        <div class="preview-mode"><span class="mode-symbol" aria-hidden="true">◷</span><b>模拟预览</b></div>
        <div class="mode-switch" role="group" aria-label="预览时间"><button v-for="item in modes" :key="item.key" :class="{ selected: mode === item.key }" :aria-pressed="mode === item.key" @click="mode = item.key">{{ item.label }}</button></div>
        <button class="linkbtn reset-preview" @click="resetSandbox"><span aria-hidden="true">↺</span> 重置体验</button>
      </div>
      <div class="preview-workbench">
        <div class="preview-settings">
          <section class="card settings-card pv-card" style="--tone: #527ea8">
            <div class="preview-sectionhead"><h2>体验设置</h2><span class="section-index" aria-hidden="true">01</span></div>
            <label class="field">当前活动<select v-model="activityId" aria-label="预览活动"><option v-for="item in rows" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
            <label class="field">访问来源<select v-model="source" aria-label="访问来源"><option value="direct">活动直达页</option><option v-for="item in channels" :key="item.id" :value="item.channelCode">{{ item.name }}{{ item.status === 1 ? '' : '（已停用）' }}</option></select></label>
            <div class="visitor-card"><div class="visitor-identity"><span class="visitor-avatar" aria-hidden="true">{{ String(sandbox.serial).padStart(2, '0') }}</span><div><span>模拟用户</span><b>体验用户 {{ sandbox.serial }}</b></div></div><button class="linkbtn" aria-label="换一位体验用户" @click="newVisitor">换一位 <span aria-hidden="true">↗</span></button></div>
          </section>
          <section class="card inventory-card pv-card" style="--tone: #5b7040">
            <div class="preview-sectionhead"><h2>活动概览</h2><span class="preview-status" :class="shown">{{ pageLabel }}</span></div>
            <div class="inventory-summary"><div><span>活动剩余</span><strong>{{ formatNumber(remaining) }}<small>张</small></strong></div><div class="inventory-total"><span>已领取 / 已投放</span><b>{{ formatNumber(sandbox.claimed) }} / {{ formatNumber(sandbox.quantity) }}</b></div></div>
            <div class="inventory-track" role="meter" aria-label="优惠券已领取比例" aria-valuemin="0" :aria-valuemax="Math.max(1, sandbox.quantity)" :aria-valuenow="sandbox.claimed"><i :style="{ width: `${claimPercent}%` }"></i></div>
            <dl class="preview-details"><div><dt>券使用有效期</dt><dd>{{ clockLabel(current.couponValidEndTime) }}</dd></div></dl>
          </section>
          <section class="card source-card pv-card" style="--tone: var(--clay)">
            <div class="preview-sectionhead"><h2>本次来源体验</h2><span class="section-index" aria-hidden="true">02</span></div>
            <div class="source-metrics"><div><span>模拟领取</span><strong>{{ stats.claims }}<small>张</small></strong></div><div><span>模拟预约</span><strong>{{ stats.reservations }}<small>人</small></strong></div></div>
          </section>
        </div>
        <DotStage ref="stage" class="preview-stage preview-dot-stage" :center-y="0.54" :palette="stagePalette" aria-label="手机领券页预览">
          <div class="pv-aura pv-aura-1" aria-hidden="true"></div>
          <div class="pv-aura pv-aura-2" aria-hidden="true"></div>
          <div class="stage-heading"><span><i aria-hidden="true"></i>领券页效果</span><span>{{ mode === 'real' ? '真实时间' : modes.find(item => item.key === mode).label }}</span></div>
          <div class="stage-orbit" aria-hidden="true">
            <svg viewBox="0 0 460 460" fill="none">
              <g class="pv-spin" style="--d: 96s" stroke="#c3c8be" stroke-width="1">
                <circle cx="230" cy="230" r="158" />
                <circle cx="230" cy="230" r="200" stroke-dasharray="1 7" stroke-linecap="round" stroke-width="1.5" />
                <circle cx="230" cy="230" r="226" stroke-opacity=".55" />
              </g>
              <g class="pv-spin rev" style="--d: 64s" stroke-width="1.2">
                <path d="M72 230a158 158 0 0 1 158-158" stroke="#5b7040" stroke-opacity=".48" />
                <path d="M430 230a200 200 0 0 1-96 168" stroke="#527ea8" stroke-opacity=".48" />
              </g>
              <g class="pv-spin" style="--d: 20s"><circle cx="230" cy="72" r="3.8" fill="#5b7040" /></g>
              <g class="pv-spin rev" style="--d: 34s"><circle cx="30" cy="230" r="3.2" fill="#527ea8" /><circle cx="30" cy="230" r="8" stroke="#527ea8" stroke-opacity=".3" /></g>
            </svg>
          </div>
          <div class="stage-device">
            <CouponPhone ref="phone" :class="`preview-phone phase-${shown}`" :palette="stagePalette" :name="current.name" :phase="phaseLabel" :rule="couponRuleText(current.couponRule)" :limit="current.maxClaimsPerUser" :time-text="`${clockLabel(current.startTime)} — ${clockLabel(current.endTime)}`" :face="couponFace(current.couponRule)" :coupon-name="current.couponName" :eligibility="eligibilityText" :validity-note="validityText" :action-text="actionText" :note="message || actionNote" :disabled="!canAct" @action="act" />
          </div>
        </DotStage>
      </div>
    </template>
  </section>
</template>

<script>
import EmptyState from '@/components/EmptyState.vue'
import DotStage from '@/components/DotStage.vue'
import { getActivity, listActivities } from '@/api/campaign'
import CouponPhone from './CouponPhone.vue'
import { clockLabel, formatNumber, couponFace, couponRuleText } from './format'
import { createSandbox, previewPerson, previewSource, visitPreview, actPreview } from './previewSandbox'

export default {
  components: { EmptyState, CouponPhone, DotStage },
  data() { return { rows: [], activityId: '', source: this.$route.query.source || 'direct', mode: 'real', current: null, sandbox: null, snapshots: {}, loading: true, error: '', message: '', requestToken: 0, modes: [{ key: 'warm', label: '开场前' }, { key: 'live', label: '开场中' }, { key: 'ended', label: '已结束' }, { key: 'real', label: '真实时间' }] } },
  computed: {
    claimPercent() { return this.sandbox?.quantity > 0 ? Math.min(100, Math.max(0, this.sandbox.claimed / this.sandbox.quantity * 100)) : 0 },
    channels() { return this.current?.channels || [] },
    sourceLabel() { return this.source === 'direct' ? '活动直达页' : this.channels.find(item => item.channelCode === this.source)?.name || '未知渠道' },
    person() { return this.sandbox ? previewPerson(this.sandbox) : {} },
    stats() { return this.sandbox ? previewSource(this.sandbox, this.source) : {} },
    remaining() { return Math.max(0, this.sandbox.quantity - this.sandbox.claimed) },
    blocked() {
      if (!this.current) return '尚未选择活动'
      const status = this.current.publishStatus || this.current.phase
      const reasons = { DRAFT: '活动尚未发布', OFFLINE: '活动已下线', CANCELLED: '活动已终止', PAUSED: '活动已暂停' }
      if (reasons[status]) return reasons[status]
      // 直达页预览不强依赖推广主短链；仅选择外部渠道时检查渠道是否存在与启用
      if (this.source !== 'direct') {
        const channel = this.channels.find(item => item.channelCode === this.source)
        if (!channel) return '渠道不存在'
        if (channel.status !== 1) return '渠道已停用'
      }
      return ''
    },
    shown() {
      if (this.blocked) return 'disabled'
      if (this.mode === 'warm') return 'before'
      if (this.mode === 'ended') return 'ended'
      if (this.mode === 'live') return this.remaining ? 'live' : 'soldout'
      return { RESERVE: 'before', CLAIMABLE: this.remaining ? 'live' : 'soldout', SOLD_OUT: 'soldout', ENDED: 'ended' }[this.current?.entryState] || 'disabled'
    },
    stagePalette() {
      if (this.shown === 'before') return [[184, 126, 24], [82, 126, 168]]
      if (this.shown === 'live') return [[91, 112, 64], [82, 126, 168]]
      return [[120, 116, 108], [82, 126, 168]]
    },
    phaseLabel() { return { before: '预热中', live: '进行中', soldout: '已领完', ended: '已结束', disabled: '入口不可用' }[this.shown] },
    pageLabel() { return { before: '预约页', live: '领取页', soldout: '已领完', ended: '已结束', disabled: this.blocked || '入口不可用' }[this.shown] },
    canAct() { return this.shown === 'before' ? !this.person.reserved : this.shown === 'live' && this.person.claims < Number(this.current.maxClaimsPerUser || 1) },
    actionText() {
      if (this.shown === 'before') return this.person.reserved ? '已预约 · 等待开场' : '预约活动 · 开场不错过'
      if (this.shown === 'live') return this.canAct ? '立即领取优惠券' : '已达本场领取上限'
      return this.shown === 'soldout' ? '本场优惠券已领完' : this.shown === 'ended' ? '活动已结束' : this.blocked || '暂不可领取'
    },
    eligibilityText() {
      const t = this.current?.activityType
      if (t === '新客领取活动') return '新客专享 · 仅限本工作空间首次领券用户'
      if (t === '会员专享活动') return '会员专享 · 仅限有历史核销记录用户'
      return ''
    },
    validityText() {
      try {
        const hours = Number(JSON.parse(this.current?.couponRule || '{}').validityPeriodHours || 0)
        return hours > 0 ? `领取后 ${hours} 小时内有效` : ''
      } catch {
        return ''
      }
    },
    actionNote() { return this.shown === 'before' ? `${clockLabel(this.current.startTime)} 开始领取` : `本用户模拟已领 ${this.person.claims} / ${this.current.maxClaimsPerUser || 1} 张 · 各渠道共用限领` },
    previewClock() { return this.mode === 'real' ? '以服务端活动状态为准' : clockLabel(this.mode === 'ended' ? this.current.endTime : this.current.startTime) }
  },
  watch: {
    activityId() { this.loadOne() },
    source() { this.message = ''; this.track(); this.pulseStage() },
    mode() { this.message = ''; this.pulseStage() },
    '$route.query.activity': { immediate: true, handler(value) { if (value) this.activityId = String(value) } },
    '$route.query.source'(value) { this.source = value || 'direct' }
  },
  created() { this.load() },
  methods: {
    clockLabel, formatNumber, couponFace, couponRuleText,
    track() { if (this.sandbox) visitPreview(this.sandbox, this.source, !this.blocked) },
    pulseStage() {
      this.$refs.stage?.burst()
      this.$refs.phone?.pulse()
    },
    act() { const result = actPreview(this.sandbox, { phase: this.shown, source: this.source, limit: Number(this.current.maxClaimsPerUser || 1), blocked: this.blocked }); this.message = result.message; this.$refs.stage?.burst() },
    newVisitor() { this.sandbox.serial++; this.message = ''; this.track() },
    resetSandbox() { this.sandbox = createSandbox(this.current); this.snapshots[this.activityId] = this.sandbox; this.mode = 'real'; this.message = ''; this.track() },
    async load() {
      try { const response = await listActivities(); this.rows = response.data || []; if (!this.activityId && this.rows.length) this.activityId = String(this.rows[0].id); if (!this.activityId) this.loading = false }
      catch (error) { this.error = error?.message || '活动读取失败'; this.loading = false }
    },
    async loadOne() {
      if (!this.activityId) return
      const token = ++this.requestToken
      this.loading = true; this.error = ''; this.message = ''; this.current = null; this.sandbox = null
      try {
        const response = await getActivity(this.activityId)
        if (token !== this.requestToken) return
        this.current = response.data
        const existing = this.snapshots[this.activityId]
        this.sandbox = existing?.version === this.current.version ? existing : createSandbox(this.current)
        this.snapshots[this.activityId] = this.sandbox
        this.track()
      } catch (error) { if (token === this.requestToken) this.error = error?.message || '活动读取失败' }
      finally { if (token === this.requestToken) this.loading = false }
    }
  }
}
</script>

<style scoped>
.preview-toolbar { display: flex; align-items: center; gap: 20px; padding: 14px 20px; margin-bottom: 24px; border: 1px solid var(--line); border-radius: 14px; background: #fff; }
.preview-mode { margin-right: auto; color: var(--ink2); }
.preview-mode b { font-size: 14px; font-weight: 500; }
.mode-symbol { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 10px; background: var(--tint); color: var(--clay); font-size: 22px; }
.mode-switch { display: flex; gap: 4px; padding: 4px; border-radius: 11px; background: var(--cloud); }
.mode-switch button { padding: 9px 17px; border-radius: 8px; background: transparent; color: var(--muted); font-size: 13px; white-space: nowrap; }
.mode-switch button.selected { background: var(--clay); color: #fff; box-shadow: 0 2px 5px #93452c20; }
.mode-switch button:not(.selected):hover { background: #ffffffb3; color: var(--ink); }
.reset-preview { white-space: nowrap; }
.reset-preview > span { font-size: 18px; }
.preview-workbench { display: grid; grid-template-columns: minmax(280px, .9fr) minmax(380px, 1.2fr); gap: 24px; align-items: start; }
.preview-settings { display: grid; gap: 18px; min-width: 0; }
.settings-card, .inventory-card, .source-card { padding: 24px; }
.preview-sectionhead { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 22px; }
.preview-sectionhead h2 { margin: 0; font-family: var(--font); font-size: 16px; font-weight: 600; }
.section-index { color: #aaa08e; font-family: var(--serif); font-size: 18px; font-style: italic; }
.settings-card .field { margin: 18px 0; }
.settings-card select { min-height: 44px; background: var(--bg); border-color: var(--line); }
.visitor-card { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--line); }
.visitor-identity { display: flex; align-items: center; gap: 12px; }
.visitor-avatar { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 50%; background: var(--tint); color: var(--clayd); font-family: var(--serif); font-size: 20px; }
.visitor-identity div > span { display: block; color: var(--muted); font-size: 12px; margin-bottom: 4px; }
.visitor-identity b { font-size: 14px; font-weight: 500; }
.preview-status { display: inline-flex; align-items: center; gap: 6px; padding: 4px 9px; border-radius: 6px; background: var(--cloud); color: var(--muted); font-size: 12px; max-width: 65%; overflow-wrap: anywhere; }
.preview-status::before { content: ''; width: 5px; height: 5px; flex-shrink: 0; border-radius: 50%; background: currentColor; }
.preview-status.live { background: #eaf0e1; color: var(--olive); }
.preview-status.before { background: var(--tint); color: var(--clayd); }
.inventory-summary { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; }
.inventory-summary span, .source-metrics span { display: block; color: var(--muted); font-size: 13px; }
.inventory-summary strong, .source-metrics strong { display: block; margin-top: 8px; color: var(--ink2); font-family: var(--serif); font-size: 42px; font-weight: 400; line-height: 1.1; font-variant-numeric: tabular-nums; }
.inventory-summary strong { color: var(--clay); }
.inventory-summary small, .source-metrics small { font-family: var(--font); font-size: 12px; color: var(--muted); margin-left: 7px; }
.inventory-total { text-align: right; padding-bottom: 4px; }
.inventory-total b { display: block; margin-top: 9px; font-size: 15px; font-weight: 500; }
.inventory-track { height: 5px; margin: 20px 0; border-radius: 10px; overflow: hidden; background: var(--cloud); }
.inventory-track i { display: block; height: 100%; background: var(--clay); border-radius: inherit; }
.preview-details { margin: 0; }
.preview-details div { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px; font-size: 12px; }
.preview-details dt { color: var(--muted); }
.preview-details dd { margin: 0; color: var(--ink2); }
.source-card .preview-sectionhead { margin-bottom: 20px; }
.source-metrics { display: grid; grid-template-columns: 1fr 1fr; }
.source-metrics > div + div { border-left: 1px solid var(--line); padding-left: 24px; }
.source-metrics strong { font-size: 32px; }
.preview-stage { position: relative; overflow: hidden; min-width: 0; padding: 22px 28px 36px; border: 1px solid #e8dfd2; border-radius: 20px; background: radial-gradient(ellipse at 80% 20%, #f4dfcf88, transparent 65%), #f0ece2; }
.preview-stage::before, .preview-stage::after { content: ''; position: absolute; width: 470px; height: 470px; border: 1px solid #d3bd9f55; border-radius: 50%; pointer-events: none; }
.preview-stage::before { top: 180px; left: 15%; }
.preview-stage::after { top: 220px; left: 5%; width: 570px; height: 570px; }
.stage-heading { position: relative; z-index: 1; display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 28px; font-size: 12px; color: #827564; }
.stage-heading > span:first-child { display: flex; align-items: center; gap: 7px; color: var(--ink2); }
.stage-heading i { width: 6px; height: 6px; border-radius: 50%; background: var(--clay); }
.stage-device { position: relative; z-index: 1; }
:deep(.preview-phone) { position: relative; max-width: 360px; padding: 18px 18px 30px; border-width: 7px; border-color: #2a2925; border-radius: 42px; box-shadow: 0 24px 40px #5f4c3420, 0 4px 8px #5f4c3410; }
:deep(.preview-phone::after) { content: ''; display: block; width: 90px; height: 4px; margin: 22px auto -16px; border-radius: 9px; background: #292821; }
:deep(.preview-phone .phonebar) { padding: 1px 7px 19px; }
:deep(.preview-phone .phonehero) { position: relative; overflow: hidden; padding: 24px 20px; border: 1px solid #e7d5c3; border-radius: 16px; background: #efe3d3; }
:deep(.preview-phone .phonehero::before), :deep(.preview-phone .phonehero::after) { content: ''; position: absolute; border: 1px solid #c8a57d55; border-radius: 50%; width: 180px; height: 180px; right: -110px; top: -55px; pointer-events: none; }
:deep(.preview-phone .phonehero::after) { width: 240px; height: 240px; top: -85px; right: -140px; }
:deep(.preview-phone .phonehero > span) { display: inline-block; padding: 3px 9px; border-radius: 5px; background: #fff9; color: #795a3e; font-size: 11px; }
:deep(.preview-phone.phase-live .phonehero > span) { background: #f5f8eb; color: var(--olive); }
:deep(.preview-phone .phonehero h2) { position: relative; margin: 17px 0 12px; font-size: 28px; line-height: 1.45; overflow-wrap: anywhere; }
:deep(.preview-phone .phonehero p) { position: relative; font-size: 12px; line-height: 1.8; }
:deep(.preview-phone .phonehero p:last-child) { border-top: 1px solid #cdb49466; margin-top: 20px; padding-top: 12px; font-size: 11px; }
:deep(.preview-phone .device-label) { margin: 22px 0 12px; font-size: 13px; }
:deep(.preview-phone .coupon) { position: relative; gap: 14px; padding: 22px 14px; margin: 0 0 24px; border: 1px solid #e9d7c7; border-radius: 12px; background: linear-gradient(90deg, #fbede4 0, #fbede4 34%, #fff 34%); }
:deep(.preview-phone .coupon::before), :deep(.preview-phone .coupon::after) { content: ''; position: absolute; left: calc(34% - 6px); width: 12px; height: 12px; border-radius: 50%; background: var(--bg); border: 1px solid #e9d7c7; }
:deep(.preview-phone .coupon::before) { top: -7px; clip-path: inset(50% 0 0); }
:deep(.preview-phone .coupon::after) { bottom: -7px; clip-path: inset(0 0 50%); }
:deep(.preview-phone .coupon strong) { width: 30%; flex-shrink: 0; text-align: center; font-family: var(--serif); font-size: 38px; overflow-wrap: anywhere; }
:deep(.preview-phone .coupon strong.compact) { font-size: 26px; }
:deep(.preview-phone .coupon strong small) { font-size: 18px; }
:deep(.preview-phone .coupon > div) { min-width: 0; padding-left: 14px; border-left: 1px dashed #d8bdab; overflow-wrap: anywhere; }
:deep(.preview-phone .coupon h3) { font-size: 14px; line-height: 1.6; }
:deep(.preview-phone .btn) { min-height: 48px; }
:deep(.preview-phone .phone-note) { margin: 16px 0 0; font-size: 11px; line-height: 1.8; text-align: center; }
@media (max-width: 1150px) {
  .preview-workbench { grid-template-columns: minmax(260px, 1fr) minmax(330px, 1.1fr); gap: 18px; }
  .preview-stage { padding-inline: 18px; }
  .settings-card, .inventory-card, .source-card { padding: 20px; }
  .preview-toolbar { flex-wrap: wrap; gap: 12px; }
}
@media (max-width: 1000px) {
  .preview-workbench { grid-template-columns: minmax(0, 1fr); }
  .preview-settings { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .settings-card { grid-row: span 2; }
  .preview-stage { padding-bottom: 32px; }
}
@media (max-width: 600px) {
  .preview-toolbar { padding: 12px; }
  .mode-switch { order: 3; width: 100%; }
  .mode-switch button { flex: 1; padding: 9px 5px; }
  .preview-settings { grid-template-columns: minmax(0, 1fr); }
  .settings-card { grid-row: auto; }
  .preview-stage { padding: 18px 12px 28px; }
  :deep(.preview-phone) { padding-inline: 14px; }
  :deep(.preview-phone .phonehero) { padding: 22px 16px; }
  :deep(.preview-phone .phonehero h2) { font-size: 26px; }
}
.pv-card { position: relative; overflow: hidden; }
.pv-card::before { content: ''; position: absolute; left: 0; right: 0; top: 0; height: 3px; background: var(--tone, #527ea8); opacity: .82; }
.preview-dot-stage { position: relative; background: linear-gradient(135deg, #eef2eb 0%, #f0eee6 52%, #eff2f5 100%); }
.preview-dot-stage::after { content: ''; position: absolute; inset: 0; z-index: -1; pointer-events: none; background: radial-gradient(circle at 50% 54%, transparent 0%, #f0eee660 56%, #f0eee6dc 92%); }
.pv-aura { position: absolute; border-radius: 50%; filter: blur(42px); z-index: -3; pointer-events: none; opacity: .55; }
.pv-aura-1 { width: 300px; height: 260px; left: 4%; bottom: 2%; background: radial-gradient(circle, #5b704030, transparent 70%); }
.pv-aura-2 { width: 300px; height: 260px; right: 4%; top: 6%; background: radial-gradient(circle, #527ea830, transparent 70%); }
.stage-heading, .stage-device { position: relative; z-index: 2; }
.stage-orbit { position: absolute; width: 460px; height: 460px; left: 50%; top: 54%; translate: -50% -50%; pointer-events: none; z-index: 0; }
.stage-orbit svg { width: 100%; height: 100%; overflow: visible; }
.pv-spin { transform-origin: 230px 230px; animation: pv-turn var(--d, 60s) linear infinite; }
.pv-spin.rev { animation-direction: reverse; }
@keyframes pv-turn { to { transform: rotate(360deg); } }
@media (max-width: 600px) { .stage-orbit { display: none; } }
@media (prefers-reduced-motion: reduce) { .pv-spin { animation: none; } }
</style>
