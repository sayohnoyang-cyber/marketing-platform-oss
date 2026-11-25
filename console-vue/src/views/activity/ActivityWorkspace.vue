<template>
  <section class="config-page activity-workspace">
    <router-link class="workspace-back" to="/activities"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M19 12H5m7-7-7 7 7 7" /></svg>返回活动列表</router-link>
    <p v-if="loading" class="loading">正在读取这场活动…</p>
    <p v-else-if="error" class="error">{{ error }} <button class="btn" type="button" @click="load">重试</button></p>
    <template v-else-if="activity">
      <div class="pagehead">
        <div>
          <h1>{{ activity.name }}</h1>
        </div>
        <div class="actions">
          <router-link class="btn" :to="`/activities/${activity.id}/setup?edit=time`">领取时间</router-link>
          <button v-if="canPublish" class="btn primary" type="button" :disabled="acting" @click="openPublish">{{ activity.publishStatus === 'DRAFT' ? '发布活动' : '重新发布' }}</button>
          <button v-if="canOffline" class="btn" type="button" :disabled="acting" @click="dialog = 'offline'">下线活动</button>
          <button v-if="canTerminate" class="btn" type="button" :disabled="acting" @click="dialog = 'terminate'">终止活动</button>
        </div>
      </div>
        <div class="card flow-summary">
          <div>
            <span class="flow-label">当前活动</span>
            <strong>{{ activity.name }}</strong>
            <span class="pill">{{ phaseText(displayPhase(activity)) }}</span>
            <p :title="activity.couponName">{{ activity.couponName || '尚未选券' }} · 直达页可用{{ activity.fullShortUrl ? ' · 推广短链已生成' : '' }} · {{ activity.enabledChannels || channelCount }} 个有效渠道</p>
          </div>
          <div class="flow-summary-metrics">
            <span>投放 <b>{{ formatNumber(activity.quantity) }}</b></span>
            <span>已领 <b>{{ formatNumber(activity.claimed) }}</b></span>
            <span>剩余 <b>{{ formatNumber(leftOf(activity)) }}</b></span>
          </div>
          <div class="actions">
            <router-link class="btn" :to="`/preview?activity=${activity.id}`">预览用户页面</router-link>
            <button class="btn" type="button" @click="copyMain">{{ mainLink.fullShortUrl ? '复制主短链' : '复制直达链接' }}</button>
          </div>
        </div>
      <nav ref="stepNavigation" class="stepper" aria-label="活动配置步骤">
        <router-link v-for="(step, index) in steps" :key="step.path" :to="`/activities/${activity.id}/${step.path}`" custom v-slot="{ navigate, isExactActive }">
          <button type="button" :class="{ on: isExactActive }" :aria-current="isExactActive ? 'step' : null" @click="navigate">
            <span class="step-number" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span><strong>{{ step.label }}</strong>
          </button>
        </router-link>
      </nav>
      <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
      <p v-if="notice" class="flow-message" role="status">{{ notice }}</p>
      <router-view :activity="activity" :channels="channels" :main="mainLink" @reload="load" @snapshot="applySnapshot" @forecast-saved="forecastInfo = $event" @preparation-completed="preparationInfo = $event" />
      <nav class="workspace-step-nav" aria-label="步骤翻页">
        <router-link class="btn" :to="previousStep ? `/activities/${activity.id}/${previousStep.path}` : '/activities'">← {{ previousStep ? `上一步：${previousStep.label}` : '返回活动列表' }}</router-link>
        <router-link v-if="nextStep" class="btn primary" :to="`/activities/${activity.id}/${nextStep.path}`">下一步：{{ nextStep.label }} →</router-link>
        <router-link v-else class="btn" to="/activities">返回活动列表 →</router-link>
      </nav>
      <StudioModal v-if="dialog" :title="dialogTitle" :busy="acting" @close="dialog = ''">
        <template v-if="dialog === 'publish'">
          <p class="muted">{{ activity.name }}</p>
          <div class="flow-message">
            <div class="checkrow"><span>领取开始（北京时间）</span><b>{{ clockLabel(activity.startTime) }}</b></div>
            <div class="checkrow"><span>领取结束</span><b>{{ clockLabel(activity.endTime) }}</b></div>
            <div class="checkrow"><span>优惠券</span><b>{{ activity.couponName || '未选券' }}</b></div>
            <div class="checkrow"><span>投放 / 每人限领</span><b>{{ formatNumber(activity.quantity) }} / {{ activity.maxClaimsPerUser || '—' }} 张</b></div>
            <div class="checkrow"><span>预算上限</span><b>¥{{ activity.budgetAmount || '—' }}</b></div>
          </div>
          <p v-if="checking" class="muted">正在检查发布条件…</p>
          <div v-for="item in checks" :key="item.code" class="checkrow"><span>{{ item.passed ? '✓' : item.blocking === false ? '·' : '○' }} {{ item.reason || checkLabel(item.code) }}</span><router-link v-if="!item.passed && item.fix" class="linkbtn" :to="`/activities/${activity.id}/${item.fix}`" @click="dialog = ''">{{ item.blocking === false ? '去查看' : '去完善' }}</router-link><b v-else>{{ item.passed ? '通过' : item.blocking === false ? '提示' : '未通过' }}</b></div>
          
        </template>
        <p v-else class="note">{{ dialog === 'offline' ? '下线后停止预约和领取，保留本场投放。结束前可重新发布，已领取的券不受影响。' : '终止后不能恢复，未领取数量会退回券模板。已领取的券仍按券规则使用。' }}</p>
        <p v-if="dialogError" class="form-alert" role="alert">{{ dialogError }}</p>
        <div class="actions modal-actions"><button class="btn" :disabled="acting" @click="dialog = ''">返回</button><button class="btn primary" :disabled="acting || (dialog === 'publish' && !publishReady)" @click="confirmAction">{{ acting ? '处理中…' : dialog === 'publish' ? '确认发布' : dialog === 'offline' ? '确认下线' : '确认终止' }}</button></div>
      </StudioModal>
    </template>
  </section>
</template>

<script>
import { listForecasts, listPreparationRuns, activityChecks, getActivity, offlineActivity, publishActivity, terminateActivity } from '@/api/campaign'
import { clockLabel, displayPhase, formatNumber, leftOf, phaseText } from './format'
import StudioModal from '@/components/StudioModal.vue'

export default {
  components: { StudioModal },
  data() {
    return { forecastInfo: null, preparationInfo: null, dialog: '', checks: [], checking: false, dialogError: '', notice: '', activity: null, channels: [], mainLink: {}, loading: false, acting: false, error: '', actionError: '', requestToken: 0 }
  },
  computed: {
    currentStepIndex() { return this.steps.findIndex(step => this.$route.path.endsWith(`/${step.path}`)) },
    previousStep() { return this.steps[this.currentStepIndex - 1] },
    nextStep() { return this.steps[this.currentStepIndex + 1] },
    dialogTitle() { return { publish: '发布前确认', offline: '下线这场活动？', terminate: '终止这场活动？' }[this.dialog] },
    publishReady() { return !this.checking && this.checks.length > 0 && this.checks.every(item => item.blocking === false || item.passed) },
    channelCount() {
      return this.channels.filter((item) => item.status === 1).length
    },
    canPublish() {
      return this.activity?.allowedActions?.includes('publish') || this.activity?.allowedActions?.includes('republish')
    },
    canOffline() {
      return this.activity?.allowedActions?.includes('offline')
    },
    canTerminate() {
      return this.activity?.allowedActions?.includes('terminate')
    },
    steps() {
      // 4 步核心工作台：活动配置 -> 推广入口 -> 实时与复盘 -> 高级保障
      return [
        { path: 'setup', label: '活动配置' },
        { path: 'promote', label: '推广入口' },
        { path: 'analytics', label: '实时与复盘' },
        { path: 'guard', label: '高级保障' }
      ]
    }
  },
  watch: {
    '$route.fullPath'() { this.$nextTick(this.scrollActiveStep) },
    '$route.params.activityId': {
      immediate: true,
      handler() { this.dialog = ''; this.notice = ''; this.load() }
    }
  },
  beforeUnmount() {
    this.$store.commit('setWorkspaceTitle', '')
  },
  methods: {
    scrollActiveStep() {
      const nav = this.$refs.stepNavigation
      const selected = nav?.querySelector('[aria-current="step"]')
      if (nav && selected && nav.scrollWidth > nav.clientWidth) {
        nav.scrollLeft = selected.offsetLeft - nav.offsetLeft - (nav.clientWidth - selected.offsetWidth) / 2
      }
    },
    applySnapshot(row) { if (row && String(row.id) === String(this.activity?.id)) { this.activity = { ...this.activity, ...row }; if (row.mainEnabled != null) this.mainLink = { ...this.mainLink, enabled: row.mainEnabled } } },
    async openPublish() {
      this.dialog = 'publish'; this.dialogError = ''; this.checks = []; this.checking = true
      try { const response = await activityChecks(this.activity.id); this.checks = response.data || [] }
      catch (error) { this.dialogError = error?.message || '发布检查失败' }
      finally { this.checking = false }
    },
    async confirmAction() {
      this.dialogError = ''
      const action = this.dialog
      if (action === 'publish' && !this.publishReady) return
      await this[action]()
      if (this.actionError) this.dialogError = this.actionError
      else { this.dialog = ''; this.notice = { publish: '活动已发布', offline: '活动已下线，投放已保留', terminate: '活动已终止' }[action] }
    },
    phaseText, displayPhase, formatNumber, leftOf, clockLabel,
    checkLabel(code) {
      return { time: '领取时间已设置', template: '优惠券规则可读取', allocation: '投放库存已分配', 'main-link': '推广主短链已启用', budget: '最大让利在预算内' }[code] || code
    },
    async load() {
      const id = this.$route.params.activityId
      const token = ++this.requestToken
      this.loading = !this.activity || String(this.activity.id) !== String(id)
      this.error = ''
      try {
        const [response, forecasts, preparations] = await Promise.all([getActivity(id), listForecasts(id), listPreparationRuns(id)])
        if (token !== this.requestToken) return
        const row = response.data
        this.forecastInfo = forecasts.data?.[0] || null
        this.preparationInfo = preparations.data?.[0] || null
        this.activity = row
        this.channels = row.channels || []
        this.mainLink = row.mainLink || {}
        if (this.$route.query.notice) this.actionError = String(this.$route.query.notice)
        this.$store.commit('setWorkspaceTitle', row.name)
        this.$nextTick(this.scrollActiveStep)
      } catch (error) {
        if (token !== this.requestToken) return
        this.activity = null
        this.error = error?.message || '活动读取失败'
      } finally {
        if (token === this.requestToken) this.loading = false
      }
    },
    async publish() {
      this.acting = true
      this.actionError = ''
      try {
        await publishActivity(this.activity.id)
        await this.load()
      } catch (error) {
        this.actionError = error?.message || '发布失败'
      } finally {
        this.acting = false
      }
    },
    async offline() {
      this.acting = true
      this.actionError = ''
      try {
        await offlineActivity(this.activity.id)
        await this.load()
      } catch (error) {
        this.actionError = error?.message || '下线失败'
      } finally {
        this.acting = false
      }
    },
    async terminate() {
      this.acting = true
      this.actionError = ''
      try {
        await terminateActivity(this.activity.id)
        await this.load()
      } catch (error) {
        this.actionError = error?.message || '终止失败'
      } finally {
        this.acting = false
      }
    },
    async copyMain() {
      this.actionError = ''
      try {
        const target = this.mainLink.fullShortUrl || `${window.location.origin}/a/${this.activity.id}`
        await navigator.clipboard.writeText(target)
        this.notice = this.mainLink.fullShortUrl ? '主短链已复制' : '活动直达链接已复制'
      } catch (error) {
        this.actionError = error?.message || '链接复制失败，请在推广入口手动复制'
      }
    }
  }
}
</script>
