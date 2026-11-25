<template>
  <section class="redeem-page">
    <div class="pagehead"><h1>优惠券核销</h1></div>
    <form class="card form redeem-lookup" @submit.prevent="lookup">
      <label class="field">优惠券码
        <input ref="codeInput" v-model.trim="code" :disabled="loading || saving" required inputmode="numeric" pattern="[0-9]+" placeholder="输入券码，或使用扫码枪 / 摄像头扫码" />
      </label>
      <div class="actions">
        <button class="btn primary" :disabled="loading || saving">{{ loading ? '查询中…' : '查询优惠券' }}</button>
        <button class="btn" type="button" :disabled="loading || saving" @click="openScanner">摄像头扫码</button>
      </div>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="flow-message" role="status">{{ notice }}</p>

    <div v-if="coupon" class="card section-gap">
      <div class="tabletop">
        <div>
          <h2>{{ coupon.title }} <span class="pill" :class="{ amber: !coupon.usable }">{{ statusText(coupon) }}</span></h2>
          <p>{{ couponRuleText(coupon.benefitPolicy) }} · 有效期至 {{ clockLabel(coupon.usableUntil) }}</p>
        </div>
        <button v-if="coupon.usable" class="btn primary" :disabled="saving" @click="openDirect">直接核销</button>
      </div>
      <EmptyState v-if="!coupon.redemptions.length" title="暂无订单记录" />
      <div v-else class="tableoverflow">
        <table>
          <thead><tr><th>订单号</th><th>订单金额</th><th>优惠</th><th>实付</th><th>状态</th><th>已记录退款</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in coupon.redemptions" :key="row.id">
              <td>{{ row.orderNo }}</td>
              <td>{{ money(row.orderAmount) }}</td>
              <td>{{ money(row.discountAmount) }}</td>
              <td>{{ money(paid(row)) }}</td>
              <td>{{ redemptionStatusText(row.status) }}</td>
              <td>{{ money(row.refundAmount) }}</td>
              <td>
                <button v-if="row.status === 'held'" class="linkbtn" :disabled="saving" @click="open(row, 'complete', coupon.id)">核销</button>
                <button v-if="canRefund(row)" class="linkbtn" :disabled="saving" @click="open(row, 'refund', coupon.id)">{{ row.status === 'refunded' ? '补录退款' : '记录退款' }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="card section-gap">
      <div class="tabletop ledger-top">
        <div>
          <h2>近期核销与退款台账</h2>
          <span>按订单号或券码检索历史单据</span>
        </div>
        <form class="ledger-filters" @submit.prevent="loadRedemptions(1)">
          <input v-model.trim="ledgerKeyword" placeholder="搜索订单号或券码…" aria-label="搜索订单号或券码" />
          <select v-model="ledgerStatus" aria-label="筛选结算状态" @change="loadRedemptions(1)">
            <option value="ALL">全部状态</option>
            <option value="completed">已核销</option>
            <option value="refunded">已退款</option>
            <option value="held">待核销</option>
            <option value="voided">已取消</option>
          </select>
          <button class="btn" type="submit" :disabled="ledgerLoading">查询</button>
        </form>
      </div>
      <p v-if="ledgerError" class="error" role="alert">{{ ledgerError }} <button class="linkbtn" type="button" @click="loadRedemptions(ledgerPage)">重试</button></p>
      <p v-else-if="ledgerLoading && !ledgerRows.length" class="loading">正在读取核销台账…</p>
      <EmptyState v-else-if="!ledgerRows.length" title="暂无核销记录" />
      <div v-else class="tableoverflow">
        <table>
          <thead><tr><th>订单号</th><th>优惠券 / 券码</th><th>订单金额</th><th>优惠</th><th>实付</th><th>退款金额</th><th>状态</th><th>时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in ledgerRows" :key="row.id">
              <td><strong>{{ row.orderNo }}</strong></td>
              <td><strong>{{ row.couponTitle || '优惠券' }}</strong><small>券码 {{ row.voucherId }}</small></td>
              <td>{{ money(row.orderAmount) }}</td>
              <td>{{ money(row.discountAmount) }}</td>
              <td>{{ money(paid(row)) }}</td>
              <td>{{ money(row.refundAmount) }}</td>
              <td><span class="pill" :class="{ amber: row.status === 'held' || row.status === 'refunded' }">{{ redemptionStatusText(row.status) }}</span></td>
              <td>{{ clockLabel(row.updateTime || row.createTime) }}</td>
              <td>
                <div class="row-actions">
                  <button class="linkbtn" type="button" :disabled="loading || saving" @click="inspectVoucher(row.voucherId)">查看券</button>
                  <button v-if="row.status === 'held'" class="linkbtn" type="button" :disabled="saving" @click="open(row, 'complete', row.voucherId)">核销</button>
                  <button v-if="canRefund(row)" class="linkbtn" type="button" :disabled="saving" @click="open(row, 'refund', row.voucherId)">{{ row.status === 'refunded' ? '补录退款' : '记录退款' }}</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="ledgerTotal > ledgerSize" class="ledger-pagination">
        <span class="muted">共 {{ ledgerTotal }} 条</span>
        <div class="actions">
          <button class="btn" type="button" :disabled="ledgerLoading || ledgerPage <= 1" @click="loadRedemptions(ledgerPage - 1)">上一页</button>
          <span>{{ ledgerPage }} / {{ ledgerPages }}</span>
          <button class="btn" type="button" :disabled="ledgerLoading || ledgerPage >= ledgerPages" @click="loadRedemptions(ledgerPage + 1)">下一页</button>
        </div>
      </div>
    </div>

    <StudioModal v-if="dialog === 'direct'" title="直接核销优惠券" :busy="saving" @close="closeDirect">
      <form @submit.prevent="submitDirect">
        <label class="field">订单号<input v-model.trim="directForm.orderNo" maxlength="64" required /></label>
        <!-- 输入订单金额后自动防抖试算优惠与顾客实付，移除无实际用途的商品编号输入框 -->
        <label class="field">订单消费金额（元）<input v-model="directForm.orderAmount" type="number" min="0.01" max="9999999999.99" step="0.01" required @input="scheduleQuote" /></label>
        <p v-if="quoting" class="note">正在试算优惠金额…</p>
        <div v-else-if="quoteDiscount != null" class="flow-message">
          <div class="checkrow"><span>预计优惠金额</span><b>-¥{{ Number(quoteDiscount).toFixed(2) }}</b></div>
          <div class="checkrow"><span>顾客预计实付</span><b>¥{{ Math.max(0, Number(directForm.orderAmount || 0) - Number(quoteDiscount)).toFixed(2) }}</b></div>
        </div>
        <p v-if="formError" class="form-alert" role="alert">{{ formError }}</p>
        <div class="actions modal-actions"><button class="btn" type="button" :disabled="saving" @click="closeDirect">取消</button><button class="btn primary" :disabled="saving">{{ saving ? '核销中…' : '确认核销' }}</button></div>
      </form>
    </StudioModal>

    <StudioModal v-else-if="dialog === 'scan'" title="摄像头扫码核销" :busy="false" @close="stopScan">
      <div class="scan-box">
        <video v-show="scanActive" ref="scanVideo" class="scan-video" autoplay playsinline muted></video>
        <p v-if="scanError" class="form-alert" role="alert">{{ scanError }}</p>
        <p v-else class="note">请将顾客出示的核销二维码对准摄像头，识别到券码后将自动查询。</p>
      </div>
      <div class="actions modal-actions">
        <button class="btn" type="button" @click="stopScan">关闭</button>
      </div>
    </StudioModal>

    <StudioModal v-else-if="dialog" :title="dialog === 'complete' ? '确认核销' : '记录整单退款'" :busy="saving" @close="dialog = ''">
      <form @submit.prevent="submit">
        <div class="checkrow"><span>订单</span><b>{{ selected.orderNo }}</b></div>
        <div class="checkrow"><span>实付金额</span><b>{{ money(paid(selected)) }}</b></div>
        <template v-if="dialog === 'refund'">
          <label class="field">实际退款金额（元）<input v-model="refundAmount" type="number" min="0" :max="paid(selected)" step="0.01" readonly required /></label>
          <p class="note">当前仅支持整单退券，退款金额固定为订单实付金额。</p>
        </template>
        <p v-if="formError" class="form-alert" role="alert">{{ formError }}</p>
        <div class="actions modal-actions"><button class="btn" type="button" :disabled="saving" @click="dialog = ''">取消</button><button class="btn primary" :disabled="saving">{{ saving ? '保存中…' : '确认' }}</button></div>
      </form>
    </StudioModal>
  </section>
</template>

<script>
import http from '@/api/http'
import { listMerchantRedemptions } from '@/api/coupon'
import EmptyState from '@/components/EmptyState.vue'
import StudioModal from '@/components/StudioModal.vue'
import { clockLabel, couponRuleText } from '@/views/activity/format'

export default {
  components: { EmptyState, StudioModal },
  data() {
    return {
      code: '', coupon: null, loading: false, saving: false, quoting: false, quoteDiscount: null, quoteTimer: 0, quoteToken: 0, error: '', notice: '',
      dialog: '', selected: null, targetVoucherId: null, refundAmount: '', formError: '',
      directForm: { orderNo: '', orderAmount: '' },
      ledgerRows: [], ledgerTotal: 0, ledgerPage: 1, ledgerSize: 20, ledgerKeyword: '', ledgerStatus: 'ALL', ledgerLoading: false, ledgerError: '',
      scanActive: false, scanError: '', scanStream: null, scanTimer: 0
    }
  },
  computed: {
    ledgerPages() { return Math.max(1, Math.ceil(this.ledgerTotal / this.ledgerSize)) }
  },
  created() {
    const code = this.$route?.query?.code
    if (code && /^[0-9]+$/.test(String(code))) {
      this.code = String(code)
      this.lookup()
    }
    this.loadRedemptions(1)
  },
  mounted() {
    this.$refs.codeInput?.focus()
  },
  beforeUnmount() {
    window.clearTimeout(this.quoteTimer)
    this.quoteToken++
    this.stopScanStream()
  },
  methods: {
    clockLabel, couponRuleText,
    statusText(item) {
      if (item.status === 'available' && !item.usable) return '未生效'
      return ({ available: '可使用', held: '使用中', redeemed: '已核销', expired: '已过期', voided: '已作废' })[item.status] || '状态未知'
    },
    redemptionStatusText(status) {
      return ({ held: '待核销', voided: '已取消', completed: '已核销', refunded: '已退款' })[status] || status
    },
    canRefund(row) {
      return row.status === 'completed' || (row.status === 'refunded' && row.refundAmount == null && row.orderAmount != null)
    },
    money(value) { return value == null ? '—' : `¥${Number(value).toFixed(2)}` },
    paid(row) { return row.orderAmount == null ? null : (Number(row.orderAmount) - Number(row.discountAmount)).toFixed(2) },
    async lookup() {
      if (this.loading || this.saving) return
      this.coupon = null; this.error = ''; this.notice = ''; this.loading = true
      try { this.coupon = (await http.get(`/merchant/vouchers/${this.code}`)).data }
      catch (error) { this.error = error?.message || '查询失败' }
      finally { this.loading = false }
    },
    async loadRedemptions(page = 1) {
      this.ledgerLoading = true; this.ledgerError = ''
      try {
        const res = await listMerchantRedemptions({
          page,
          size: this.ledgerSize,
          keyword: this.ledgerKeyword || undefined,
          status: this.ledgerStatus
        })
        this.ledgerRows = res.data?.records || []
        this.ledgerTotal = Number(res.data?.total || 0)
        this.ledgerPage = Number(res.data?.page || page)
      } catch (error) {
        this.ledgerError = error?.message || '台账读取失败'
      } finally {
        this.ledgerLoading = false
      }
    },
    inspectVoucher(voucherId) {
      this.code = String(voucherId)
      this.lookup()
    },
    openDirect() {
      window.clearTimeout(this.quoteTimer)
      this.quoteToken++
      this.formError = ''; this.notice = ''; this.quoteDiscount = null; this.quoting = false
      this.directForm = { orderNo: `POS${Date.now()}`, orderAmount: '' }
      this.dialog = 'direct'
    },
    closeDirect() {
      window.clearTimeout(this.quoteTimer)
      this.quoteToken++
      this.quoting = false
      this.dialog = ''
    },
    /** 订单金额变化后 300ms 防抖自动试算优惠金额与顾客实付 */
    scheduleQuote() {
      window.clearTimeout(this.quoteTimer)
      this.quoteDiscount = null
      this.formError = ''
      const amount = Number(this.directForm.orderAmount)
      if (!this.directForm.orderAmount || !Number.isFinite(amount) || amount <= 0) {
        this.quoteToken++
        this.quoting = false
        return
      }
      this.quoteTimer = window.setTimeout(() => this.previewQuote(), 300)
    },
    /** 试算优惠金额与顾客实付，便于核销前与消费者当面确认 */
    async previewQuote() {
      const amount = Number(this.directForm.orderAmount)
      if (!this.directForm.orderAmount || !Number.isFinite(amount) || amount <= 0 || !this.coupon?.id) return
      const token = ++this.quoteToken
      this.quoting = true; this.formError = ''; this.quoteDiscount = null
      try {
        const res = await http.post(`/merchant/vouchers/${this.coupon.id}/quote`, {
          orderNo: this.directForm.orderNo || 'QUOTE',
          orderAmount: this.directForm.orderAmount,
          goodsNumber: null
        })
        if (token === this.quoteToken) this.quoteDiscount = res.data
      } catch (error) {
        if (token === this.quoteToken) this.formError = error?.message || '试算失败'
      } finally {
        if (token === this.quoteToken) this.quoting = false
      }
    },
    async submitDirect() {
      if (this.saving) return
      this.saving = true; this.formError = ''
      try {
        await http.post(`/merchant/vouchers/${this.coupon.id}/redeem`, {
          orderNo: this.directForm.orderNo,
          orderAmount: this.directForm.orderAmount,
          goodsNumber: null
        })
        this.coupon = (await http.get(`/merchant/vouchers/${this.coupon.id}`)).data
        this.closeDirect(); this.notice = '已核销'
        this.loadRedemptions(1)
      } catch (error) { this.formError = error?.message || '核销失败' }
      finally { this.saving = false }
    },
    open(row, dialog, voucherId) {
      this.selected = row
      this.targetVoucherId = voucherId || this.coupon?.id
      this.dialog = dialog
      this.refundAmount = dialog === 'refund' ? (this.paid(row) ?? '') : ''
      this.formError = ''; this.notice = ''
    },
    async submit() {
      if (this.saving || !this.targetVoucherId) return
      this.saving = true; this.formError = ''
      try {
        await http.post(`/merchant/vouchers/${this.targetVoucherId}/${this.dialog}`, {
          orderNo: this.selected.orderNo,
          ...(this.dialog === 'refund' ? { refundAmount: this.refundAmount } : {})
        })
        if (this.coupon && String(this.coupon.id) === String(this.targetVoucherId)) {
          this.coupon = (await http.get(`/merchant/vouchers/${this.targetVoucherId}`)).data
        }
        this.dialog = ''; this.notice = '操作已保存'
        this.loadRedemptions(this.ledgerPage)
      } catch (error) { this.formError = error?.message || '操作失败' }
      finally { this.saving = false }
    },
    async openScanner() {
      this.dialog = 'scan'; this.scanError = ''; this.scanActive = false
      if (typeof window === 'undefined' || !('BarcodeDetector' in window) || !navigator.mediaDevices?.getUserMedia) {
        this.scanError = '当前浏览器不支持摄像头二维码识别，请使用扫码枪或手动输入券码。'
        return
      }
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
        this.scanStream = stream
        this.scanActive = true
        await this.$nextTick()
        const video = this.$refs.scanVideo
        if (!video) { this.stopScanStream(); return }
        video.srcObject = stream
        const detector = new window.BarcodeDetector({ formats: ['qr_code'] })
        this.scanTimer = window.setInterval(async () => {
          if (!this.scanActive || video.readyState < 2) return
          try {
            const codes = await detector.detect(video)
            const matched = codes.map(item => String(item.rawValue || '').trim()).find(val => /^\d+$/.test(val))
            if (matched) {
              this.stopScan()
              this.code = matched
              this.lookup()
            }
          } catch { /* 忽略单帧识别异常，继续尝试下一帧。 */ }
        }, 250)
      } catch {
        this.stopScanStream()
        this.scanError = '未能打开摄像头，请检查相机权限或改用扫码枪/手动输入。'
      }
    },
    stopScanStream() {
      window.clearInterval(this.scanTimer)
      this.scanTimer = 0
      this.scanActive = false
      if (this.scanStream) {
        this.scanStream.getTracks().forEach(track => track.stop())
        this.scanStream = null
      }
    },
    stopScan() {
      this.stopScanStream()
      if (this.dialog === 'scan') this.dialog = ''
    }
  }
}
</script>

<style scoped>
.redeem-lookup { display: flex; align-items: flex-end; gap: 14px; flex-wrap: wrap; }
.redeem-lookup .field { flex: 1; min-width: 240px; margin: 0; }
.ledger-top { flex-wrap: wrap; gap: 12px; }
.ledger-filters { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.ledger-filters select { min-height: 38px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); color: var(--ink); }
.ledger-pagination { display: flex; justify-content: space-between; align-items: center; padding: 14px 24px; border-top: 1px solid var(--line); }
.scan-box { display: flex; flex-direction: column; gap: 12px; }
.scan-video { width: 100%; max-height: 320px; border-radius: 12px; background: #141413; object-fit: cover; }
</style>
