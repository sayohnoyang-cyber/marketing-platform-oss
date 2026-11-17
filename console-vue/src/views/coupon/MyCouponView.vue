<template>
  <main class="wallet">
    <header class="wallet-head"><router-link v-if="back" class="linkbtn" :to="back">← {{ back.startsWith('/a/') ? '返回活动' : '返回工作空间' }}</router-link><span v-else>{{ $store.state.profile?.username }}</span><div class="actions"><button class="linkbtn" :disabled="loading" @click="load(true)">刷新</button><button class="linkbtn" @click="exit">退出登录</button></div></header>
    <div class="wallet-title"><span>我的礼遇</span><h1>优惠券包</h1><p>{{ total }} 张优惠券</p></div>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="linkbtn" @click="load(true)">重试</button></p>
    <p v-if="loading && !records.length" class="loading">正在读取优惠券…</p>
    <EmptyState v-else-if="!records.length && !error" title="还没有领取优惠券"><router-link v-if="back.startsWith('/a/')" class="btn primary" :to="back">返回活动领取</router-link></EmptyState>
    <div class="wallet-grid">
      <article v-for="item in records" :key="item.id" class="wallet-coupon" :class="{ inactive: ['redeemed','expired','voided'].includes(item.status) }">
        <div class="wallet-face"><strong><CouponFace :value="couponFace(item.benefitPolicy)" /></strong><span>{{ statusText(item) }}</span></div>
        <div class="wallet-detail"><h2>{{ item.title }}</h2><p class="wallet-rule">{{ couponRuleText(item.benefitPolicy) }}</p><p>{{ clockLabel(item.usableFrom) }} — {{ clockLabel(item.usableUntil) }}</p><div class="wallet-bottom"><button class="linkbtn wallet-code" @click="copyCode(item.id)">券码 {{ item.id }}</button><div class="actions"><button v-if="item.usable" class="btn" @click="showCode(item)">出示券码</button><button v-if="item.usable" class="btn primary" @click="open(item, 'lock')">使用</button><button v-else-if="item.status === 'held'" class="btn" @click="open(item, 'cancel')">取消锁定</button></div></div></div>
      </article>
    </div>
    <p v-if="notice" class="flow-message" role="status">{{ notice }}</p>
    <div v-if="records.length < total" class="wallet-more"><button class="btn" :disabled="loading" @click="load(false)">{{ loading ? '正在读取…' : '加载更多' }}</button></div>
    <StudioModal v-if="dialog === 'qr'" title="出示核销码" :busy="false" @close="dialog = ''">
      <div class="wallet-qr">
        <img v-if="qrDataUrl" :src="qrDataUrl" alt="优惠券核销码" />
        <strong class="wallet-qr-code">{{ selected?.id }}</strong>
        <div class="actions modal-actions"><button class="btn" type="button" @click="copyCode(selected?.id)">复制券码</button><button class="btn primary" type="button" @click="dialog = ''">关闭</button></div>
      </div>
    </StudioModal>
    <StudioModal v-else-if="dialog" :title="dialog === 'lock' ? '使用优惠券' : '取消锁定'" :busy="saving" @close="dialog = ''">
      <form @submit.prevent="submit">
        <label class="field">订单号<input v-model.trim="form.orderNo" maxlength="64" required :readonly="dialog === 'cancel'" /></label>
        <template v-if="dialog === 'lock'"><label class="field">订单金额（元）<input v-model="form.orderAmount" type="number" min="0.01" max="9999999999.99" step="0.01" required /></label><label class="field">商品编号（选填）<input v-model.trim="form.goodsNumber" maxlength="64" /></label></template>
        <p v-if="formError" class="form-alert" role="alert">{{ formError }}</p>
        <div class="actions modal-actions"><button class="btn" type="button" :disabled="saving" @click="dialog = ''">取消</button><button class="btn primary" :disabled="saving">{{ saving ? '保存中…' : '确认' }}</button></div>
      </form>
    </StudioModal>
  </main>
</template>
<script>
import QRCode from 'qrcode'
import EmptyState from '@/components/EmptyState.vue'
import CouponFace from '@/components/CouponFace.vue'
import StudioModal from '@/components/StudioModal.vue'
import { listMyCoupons, lockCoupon, cancelCoupon, couponSettlements } from '@/api/coupon'
import { couponFace, couponRuleText, clockLabel } from '@/views/activity/format'
import { safeReturn } from '@/views/login/returnTo'
import { logout } from '@/api/identity'
import { copyText } from '@/lib/clipboard'
export default {
  components: { EmptyState, CouponFace, StudioModal },
  data() { return { records: [], total: 0, page: 0, loading: false, error: '', notice: '', dialog: '', saving: false, selected: null, qrDataUrl: '', formError: '', form: { orderNo: '', orderAmount: '', goodsNumber: '' } } },
  computed: { back() { return safeReturn(this.$route.query.return) || (this.$store.state.profile?.merchantId ? '/overview' : '') } },
  created() { this.load(true) },
  methods: {
    couponFace, couponRuleText, clockLabel,
    async exit() {
      try { await logout() } catch { /* 服务端不可达时仍清除本地会话。 */ } finally { this.$store.commit('clearSession'); this.$router.push('/login') }
    },
    statusText(item) { return item.status === 'available' && !item.usable ? '未生效' : ({ available: '可使用', held: '使用中', redeemed: '已使用', expired: '已过期', voided: '已作废' })[item.status] || '状态未知' },
    async load(reset = false) {
      if (this.loading) return
      this.loading = true; this.error = ''
      try {
        const next = reset ? 1 : this.page + 1
        const response = await listMyCoupons(next)
        const rows = response.data?.records || []
        this.records = reset ? rows : [...this.records, ...rows]
        this.total = Number(response.data?.total || 0); this.page = next
      } catch (error) { this.error = error?.message || '优惠券读取失败' }
      finally { this.loading = false }
    },
    async copyCode(id) {
      try { await copyText(String(id)); this.notice = '券码已复制' }
      catch { this.notice = `请手动复制券码：${id}` }
    },
    async showCode(item) {
      this.selected = item; this.qrDataUrl = ''; this.error = ''
      try {
        this.qrDataUrl = await QRCode.toDataURL(String(item.id), { margin: 2, width: 320 })
        this.dialog = 'qr'
      } catch {
        this.notice = `请手动出示券码：${item.id}`
      }
    },
    async open(item, kind) {
      this.selected = item; this.formError = ''; this.error = ''
      this.form = { orderNo: '', orderAmount: '', goodsNumber: '' }
      if (kind === 'cancel') {
        try {
          const response = await couponSettlements(item.id)
          const locked = (response.data || []).find(row => row.status === 'held')
          if (!locked) { this.error = '锁定状态已变化，请刷新优惠券'; return }
          this.form.orderNo = locked.orderNo
        } catch (error) { this.error = error?.message || '订单读取失败'; return }
      }
      this.dialog = kind
    },
    async submit() {
      if (this.saving) return
      this.saving = true; this.formError = ''
      try {
        if (this.dialog === 'lock') await lockCoupon(this.selected.id, { ...this.form, goodsNumber: this.form.goodsNumber || null })
        else await cancelCoupon(this.selected.id, this.form.orderNo)
        this.dialog = ''; await this.load(true)
      } catch (error) { this.formError = error?.message || '操作失败' }
      finally { this.saving = false }
    }
  }
}
</script>
<style scoped>
.wallet { max-width: 1060px; min-height: 100vh; margin: auto; padding: 28px 24px 64px; }
.wallet-head,.wallet-bottom { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.wallet-title { margin: 42px 0 28px; }.wallet-title>span { color: var(--clay); }.wallet-title h1 { font-family: var(--serif); font-size: 38px; margin: 10px 0; }.wallet-title p { color: var(--muted); }
.wallet-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 20px; }
.wallet-coupon { display: flex; border: 1px solid var(--line); border-radius: 14px; background: var(--card, #fffdf8); overflow: hidden; }
.wallet-face { width: 116px; flex-shrink: 0; padding: 24px 10px; display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 14px; color: var(--clay); border-right: 1px dashed var(--line); background: rgba(179,98,68,.05); }.wallet-face strong { font-family: var(--serif); font-size: 34px; }.wallet-face strong :deep(small) { font-size: 18px; }
.wallet-detail { min-width: 0; flex: 1; padding: 22px 18px; }.wallet-detail h2 { font-size: 18px; margin: 0 0 12px; overflow-wrap: anywhere; }.wallet-detail p { font-size: 12px; color: var(--muted); line-height: 1.8; }.wallet-detail .wallet-rule { color: var(--ink); font-size: 14px; }.wallet-bottom { margin-top: 18px; flex-wrap: wrap; }.wallet-code { font-size: 11px; overflow-wrap: anywhere; text-align: left; }.wallet-more { text-align: center; margin-top: 28px; }.inactive .wallet-face { color: var(--muted); }
.wallet-qr { display: flex; flex-direction: column; align-items: center; gap: 14px; padding-top: 8px; }
.wallet-qr img { width: 200px; height: 200px; border: 1px solid var(--line); border-radius: 12px; background: #fff; }
.wallet-qr-code { font-family: var(--serif); font-size: 20px; letter-spacing: .04em; color: var(--ink); word-break: break-all; text-align: center; }
@media(max-width:760px) { .wallet-grid { grid-template-columns: 1fr; }.wallet { padding: 22px 16px 40px; }.wallet-title { margin-top: 30px; }.wallet-face { width: 96px; }.wallet-detail { padding: 18px 14px; } }
</style>
