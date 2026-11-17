<template>
  <section>
    <div class="pagehead"><h1>优惠券核销</h1></div>
    <form class="card form" @submit.prevent="lookup"><label class="field">优惠券码<input v-model.trim="code" :disabled="loading || saving" required inputmode="numeric" pattern="[0-9]+" /></label><button class="btn primary" :disabled="loading || saving">{{ loading ? '查询中…' : '查询优惠券' }}</button></form>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="flow-message" role="status">{{ notice }}</p>
    <div v-if="coupon" class="card">
      <h2>{{ coupon.title }}</h2><p>{{ couponRuleText(coupon.benefitPolicy) }}</p>
      <EmptyState v-if="!coupon.redemptions.length" title="暂无订单记录" />
      <div v-else class="tableoverflow"><table><thead><tr><th>订单号</th><th>订单金额</th><th>优惠</th><th>实付</th><th>状态</th><th>已记录退款</th><th>操作</th></tr></thead><tbody><tr v-for="row in coupon.redemptions" :key="row.id"><td>{{ row.orderNo }}</td><td>{{ money(row.orderAmount) }}</td><td>{{ money(row.discountAmount) }}</td><td>{{ money(paid(row)) }}</td><td>{{ ({ held: '待核销', voided: '已取消', completed: '已核销', refunded: '已退款' })[row.status] || row.status }}</td><td>{{ money(row.refundAmount) }}</td><td><button v-if="row.status === 'held'" class="linkbtn" :disabled="saving" @click="open(row,'complete')">核销</button><button v-if="row.status === 'completed' || (row.status === 'refunded' && row.refundAmount == null && row.orderAmount != null)" class="linkbtn" :disabled="saving" @click="open(row,'refund')">{{ row.status === 'refunded' ? '补录退款' : '记录退款' }}</button></td></tr></tbody></table></div>
    </div>
    <StudioModal v-if="dialog" :title="dialog === 'complete' ? '确认核销' : '记录整单退款'" :busy="saving" @close="dialog = ''"><form @submit.prevent="submit"><div class="checkrow"><span>订单</span><b>{{ selected.orderNo }}</b></div><div class="checkrow"><span>实付金额</span><b>{{ money(paid(selected)) }}</b></div><label v-if="dialog === 'refund'" class="field">实际退款金额（元）<input v-model="refundAmount" type="number" min="0" :max="paid(selected)" step="0.01" required /></label><p v-if="formError" class="form-alert" role="alert">{{ formError }}</p><div class="actions modal-actions"><button class="btn" type="button" :disabled="saving" @click="dialog = ''">取消</button><button class="btn primary" :disabled="saving">{{ saving ? '保存中…' : '确认' }}</button></div></form></StudioModal>
  </section>
</template>
<script>
import http from '@/api/http'
import EmptyState from '@/components/EmptyState.vue'
import StudioModal from '@/components/StudioModal.vue'
import { couponRuleText } from '@/views/activity/format'
export default {
  components: { EmptyState, StudioModal },
  data() { return { code: '', coupon: null, loading: false, saving: false, error: '', notice: '', dialog: '', selected: null, refundAmount: '', formError: '' } },
  created() {
    const code = this.$route?.query?.code
    if (code && /^[0-9]+$/.test(String(code))) {
      this.code = String(code)
      this.lookup()
    }
  },
  methods: {
    couponRuleText,
    money(value) { return value == null ? '—' : `¥${Number(value).toFixed(2)}` },
    paid(row) { return row.orderAmount == null ? null : (Number(row.orderAmount) - Number(row.discountAmount)).toFixed(2) },
    async lookup() {
      if (this.loading || this.saving) return
      this.coupon = null; this.error = ''; this.notice = ''; this.loading = true
      try { this.coupon = (await http.get(`/merchant/vouchers/${this.code}`)).data }
      catch (error) { this.error = error?.message || '查询失败' }
      finally { this.loading = false }
    },
    open(row, dialog) { this.selected = row; this.dialog = dialog; this.refundAmount = ''; this.formError = ''; this.notice = '' },
    async submit() {
      if (this.saving) return
      this.saving = true; this.formError = ''
      try {
        await http.post(`/merchant/vouchers/${this.coupon.id}/${this.dialog}`, { orderNo: this.selected.orderNo, ...(this.dialog === 'refund' ? { refundAmount: this.refundAmount } : {}) })
        this.coupon = (await http.get(`/merchant/vouchers/${this.coupon.id}`)).data
        this.dialog = ''; this.notice = '操作已保存'
      } catch (error) { this.formError = error?.message || '操作失败' }
      finally { this.saving = false }
    }
  }
}
</script>
