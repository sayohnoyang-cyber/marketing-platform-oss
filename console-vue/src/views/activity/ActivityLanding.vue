<template>
  <div class="landing">
    <p v-if="loading && !entry" class="muted">正在打开活动…</p>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="linkbtn" @click="load">重试</button></p>
    <template v-if="entry">
      <CouponPhone public-page :name="entry.name" :phase="phaseText(entry.phase)" :rule="couponRuleText(entry.couponRule)" :limit="entry.maxClaimsPerUser" :time-text="`${clockLabel(entry.startTime)} — ${clockLabel(entry.endTime)}`" :face="couponFace(entry.couponRule)" :coupon-name="entry.couponName" :action-text="actionText" :note="message || entry.reason || note" :disabled="!canAct || loading" :busy="acting" @action="act">
        <p class="landing-source">来源 · {{ entry.source === 'DIRECT' ? '直接访问' : entry.source === 'UNAVAILABLE' ? '入口不可用' : entry.source }}</p>
        <button class="linkbtn landing-refresh" :disabled="loading || acting" @click="load">{{ loading ? '正在刷新…' : '刷新活动状态' }}</button>
        <router-link v-if="token" class="linkbtn landing-refresh" :to="{ path: '/my-coupons', query: { return: $route.fullPath } }">查看我的优惠券</router-link>
      </CouponPhone>
    </template>
    <div class="landing-icp"><IcpFooter /></div>
  </div>
</template>

<script>
import http from '@/api/http'
import { requestId } from '@/lib/requestId.mjs'
import { personalState, refreshDelay } from './landingState.mjs'
import CouponPhone from './CouponPhone.vue'
import IcpFooter from '@/components/IcpFooter.vue'
import { couponFace, couponRuleText, clockLabel, phaseText } from './format'

export default {
  components: { CouponPhone, IcpFooter },
  data() { return { entry: null, loading: false, acting: false, error: '', message: '', reserved: false, claimed: 0, claimRequestId: '', requestToken: 0, refreshTimer: null } },
  computed: {
    token() { return this.$store.state.token },
    loginLink() { return { path: '/login', query: { return: this.$route.fullPath } } },
    canAct() { if (this.error) return false; return this.entry?.action === 'RESERVE' ? !this.reserved : this.entry?.action === 'CLAIM' && this.claimed < Number(this.entry.maxClaimsPerUser || 1) },
    actionText() {
      if (this.entry.action === 'RESERVE') return this.reserved ? '已预约 · 等待开场' : '预约活动 · 开场不错过'
      if (this.entry.action === 'CLAIM') return this.canAct ? '立即领取优惠券' : '已达本场领取上限'
      return { SOLD_OUT: '本场优惠券已领完', ENDED: '活动已结束', UNAVAILABLE: this.entry.reason || '链接暂不可用' }[this.entry.action] || '暂不可领取'
    },
    note() { return this.entry.action === 'RESERVE' ? `${clockLabel(this.entry.startTime)} 开始领取` : this.token ? `本场已领 ${this.claimed} 张 · 还可领 ${Math.max(0, Number(this.entry.maxClaimsPerUser || 1) - this.claimed)} 张` : `已领 ${this.entry.claimed || 0} / 投放 ${this.entry.quantity || 0} 张` },
    pendingKey() { return `marketing.claim.${this.$store.state.profile?.userId || 'guest'}.${this.$route.params.id}.${this.$route.query.c || 'direct'}` }
  },
  watch: {
    '$route.fullPath'() { this.reset(); this.load() },
    token() { this.reset(); this.load() }
  },
  created() { this.load() },
  mounted() { document.addEventListener('visibilitychange', this.resume) },
  beforeUnmount() { this.requestToken++; clearTimeout(this.refreshTimer); document.removeEventListener('visibilitychange', this.resume) },
  methods: {
    couponFace, couponRuleText, clockLabel, phaseText,
    reset() {
      this.requestToken++; clearTimeout(this.refreshTimer)
      this.entry = null; this.message = ''; this.reserved = false; this.claimed = 0; this.claimRequestId = ''; this.loading = false
    },
    resume() {
      clearTimeout(this.refreshTimer)
      if (!document.hidden) this.load()
    },
    schedule() {
      clearTimeout(this.refreshTimer)
      if (document.hidden) return
      const delay = refreshDelay(this.entry)
      if (delay != null) this.refreshTimer = setTimeout(() => { if (this.acting) this.schedule(); else this.load() }, delay)
    },
    async load({ renew = false } = {}) {
      if (this.loading) return
      const token = ++this.requestToken
      this.loading = true; this.error = ''
      try {
        // 状态轮询不重复记访问；仅首次进入及凭证临近过期时重新获取访问凭证。
        const endpoint = !this.entry || renew ? 'visit' : 'status'
        const response = await http.post(`/public/activities/${this.$route.params.id}/${endpoint}`, { channelCode: this.$route.query.c || null })
        if (token !== this.requestToken) return
        this.entry = { ...this.entry, ...response.data }
        const state = personalState(this.entry)
        this.reserved = state.reserved; this.claimed = state.claimed
        if (!this.claimRequestId) {
          try { this.claimRequestId = sessionStorage.getItem(this.pendingKey) || '' } catch { /* 禁用存储时仍可在当前页面重试。 */ }
        }
      } catch (error) { if (token === this.requestToken) this.error = error?.message || '活动入口读取失败' }
      finally { if (token === this.requestToken) { this.loading = false; this.schedule() } }
    },
    async act() {
      if (this.acting || this.loading || !this.canAct) return
      if (!this.token) { this.$router.push(this.loginLink); return }
      this.acting = true; this.message = ''; clearTimeout(this.refreshTimer)
      const route = this.$route.fullPath, account = this.token
      const current = () => route === this.$route.fullPath && account === this.token
      let reserving = this.entry.action === 'RESERVE'
      try {
        if (!this.entry.credential || Date.parse(this.entry.credentialExpireAt) - Date.parse(this.entry.serverTime) < 5000) {
          await this.load({ renew: true })
          if (!current() || this.error || !this.canAct) return
          reserving = this.entry.action === 'RESERVE'
        }
        if (reserving) {
          await http.post(`/user/activities/${this.entry.activityId}/reservations`, { credential: this.entry.credential })
          if (!current()) return
          this.reserved = true; this.message = '预约成功，等待开场'
        } else {
          // 失败或刷新后复用本用户、本场、本渠道的请求编号，避免重试重复发券。
          if (!this.claimRequestId) {
            this.claimRequestId = requestId()
            try { sessionStorage.setItem(this.pendingKey, this.claimRequestId) } catch { /* 当前页面保留幂等编号。 */ }
          }
          const resp = await http.post(`/user/activities/${this.entry.activityId}/claims/async`, { planId: this.entry.planId, requestId: this.claimRequestId, credential: this.entry.credential })
          if (!current()) return
          let ticket = resp.data || {}
          if (ticket.status === 'RESERVED' || ticket.status === 'PENDING') {
            this.message = '正在排队抢券，请稍候…'
            ticket = await this.pollClaimResult(this.entry.activityId, this.claimRequestId, this.entry.planId, current)
            if (!current()) return
          }
          if (ticket.status === 'SUCCESS' || (!ticket.status && ticket.id)) {
            try { sessionStorage.removeItem(this.pendingKey) } catch { /* 存储可能被浏览器禁用。 */ }
            this.claimRequestId = ''; this.claimed++; this.message = '领取成功，可在“我的优惠券”中查看'
            await this.load()
          } else if (ticket.status === 'FAILED') {
            try { sessionStorage.removeItem(this.pendingKey) } catch { /* 存储可能被浏览器禁用。 */ }
            this.claimRequestId = ''; this.message = '本场抢券人数较多或已领完，请重试'
          } else {
            this.message = '排队处理中，请稍后刷新查看'
          }
        }
      } catch (error) { if (current()) this.message = error?.message || (reserving ? '预约失败' : '领取失败') }
      finally { this.acting = false; this.schedule() }
    },
    async pollClaimResult(activityId, reqId, planId, isCurrent) {
      for (let i = 0; i < 20; i++) {
        await new Promise((resolve) => setTimeout(resolve, 250))
        if (!isCurrent()) return {}
        const res = await http.get(`/user/activities/${activityId}/claims/${encodeURIComponent(reqId)}`, { params: { planId } })
        const data = res.data || {}
        if (data.status === 'SUCCESS' || data.status === 'FAILED') return data
      }
      return { status: 'RESERVED' }
    }
  }
}
</script>

<style scoped>
.landing { min-height: 100vh; background: var(--bg); padding: 40px 16px; display: flex; flex-direction: column; align-items: center; }
.landing-source { color: var(--muted); font-size: 12px; text-align: center; margin: 22px 0 10px; }
.landing-refresh { display: block; margin: 12px auto 0; }
.landing-icp { margin-top: 28px; }
@media (max-width: 480px) { .landing { padding: 20px 12px; } }
</style>
