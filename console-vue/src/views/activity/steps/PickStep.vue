<template>
  <div>
    <div class="stephead"><div><h2>选券与投放</h2></div></div>
    <p v-if="message" :class="failed ? 'error' : 'flow-message'" role="status">{{ message }}</p>
    <p v-if="loading" class="loading">正在读取券库…</p>
    <div v-else-if="!form.planId" class="card"><EmptyState title="本场尚未选券"><button class="btn primary" :disabled="!canChange" @click="picker = true">选择优惠券</button></EmptyState></div>
    <div v-else class="pickgrid">
      <div class="card flow-pad">
        <div class="sectionhead flush"><h2>本场优惠券</h2><button class="linkbtn" :disabled="!canChange" @click="picker = true">更换券</button></div>
        <div class="pickhead"><div class="pn">{{ couponFace(selected?.benefitPolicy || activity.couponRule) }}</div><div><strong>{{ selected?.name || activity.couponName }}</strong><p>{{ couponRuleText(selected?.benefitPolicy || activity.couponRule) }}</p></div></div>
        <div class="checkrow"><span>券使用有效期</span><b>{{ clockLabel(selected?.usableUntil || activity.couponValidEndTime) }}</b></div>
        <div class="checkrow"><span>券模板发行总量</span><b>{{ formatNumber(selected?.issuedTotal) }} 张</b></div>
        <div class="checkrow"><span>本场可投放上限</span><b>{{ formatNumber(sliderMax) }} 张</b></div>
        <div class="divider"></div>
        <div class="stock-grid"><div><span>已保存总量</span><b>{{ formatNumber(activity.quantity) }}</b></div><div><span>已领取</span><b>{{ formatNumber(activity.claimed) }}</b></div><div><span>剩余</span><b>{{ formatNumber(leftOf(activity)) }}</b></div></div>

      </div>
      <form class="card flow-pad" @submit.prevent="save">
        <h2>{{ running ? '追加投放' : '编辑投放设置' }}</h2>
        <label class="field">{{ running ? '投放总量（原总量 + 追加数量）' : '投放总量（张）' }}<input v-model.number="form.quantity" type="number" :min="quantityMin" :max="sliderMax" step="1" :disabled="locked || saving" required /></label>
        <input class="allocation-range" aria-label="调整投放总量" v-model.number="form.quantity" type="range" :min="quantityMin" :max="Math.max(quantityMin, sliderMax)" :disabled="locked || saving" />
        <label class="field">每人限领<input v-model.number="form.maxClaimsPerUser" type="number" min="1" :max="personMax" :disabled="running || locked || saving" required /></label>
        <label class="field">预算上限（元）<input v-model.trim="form.budget" inputmode="decimal" :disabled="locked || saving" required /></label>
        <div class="flow-message"><div class="checkrow"><span>单张最高优惠</span><b>{{ unitDiscount || '—' }} 元</b></div><div class="checkrow"><span>最大让利</span><b>{{ maxGive }}</b></div></div>
        <p v-if="locked" class="note">当前活动状态不允许修改投放。</p>
        <div class="actions modal-actions"><button class="btn" type="button" :disabled="!dirty || saving" @click="reset">取消修改</button><button class="btn primary" :disabled="!dirty || invalid || locked || saving">{{ saving ? '保存中…' : '保存投放设置' }}</button></div>
      </form>
    </div>
    <StudioModal v-if="picker" title="选择优惠券" @close="picker = false; candidate = null">
      
      <p v-if="!templates.length" class="empty">券库还没有模板。<router-link class="linkbtn" to="/coupons">去创建</router-link></p>
      <CouponChoice v-for="item in templates" :key="item.id" :chosen="candidate?.id === item.id" :face="couponFace(item.benefitPolicy)" :name="item.title" :rule="couponRuleText(item.benefitPolicy)" :disabled="item.status !== 'open' || !covers(item) || (Number(item.inventoryQty) <= 0 && String(item.id) !== String(activity.planId))" @click="choose(item)">可投放 {{ formatNumber(item.inventoryQty) }} 张 · {{ covers(item) ? `至 ${clockLabel(item.usableUntil).slice(0, 10)}` : '有效期未覆盖活动时间' }}</CouponChoice>
      <div class="actions modal-actions"><button class="btn" @click="picker = false; candidate = null">取消</button><button class="btn primary" :disabled="!candidate" @click="confirmChoice">确认选用</button></div>
    </StudioModal>
  </div>
</template>

<script>
import EmptyState from '@/components/EmptyState.vue'
import CouponChoice from '@/components/CouponChoice.vue'
import { saveOffer } from '@/api/campaign'
import { listAllTemplates } from '@/api/coupon'
import StudioModal from '@/components/StudioModal.vue'
import { clockLabel, formatNumber, leftOf, couponFace, couponRuleText, moneyTimes, ruleAmount } from '../format'

export default {
  components: { EmptyState, StudioModal, CouponChoice },
  props: { activity: { type: Object, required: true } },
  emits: ['reload'],
  data() {
    return {
      picker: false, candidate: null, templates: [], loading: false, saving: false, message: '', failed: false,
      form: this.blank(), pendingKey: '', pendingSignature: ''
    }
  },
  computed: {
    running() { return this.activity.timePhase !== 'BEFORE' && this.activity.publishStatus !== 'DRAFT' },
    locked() { return ['ENDED', 'CANCELLED', 'OFFLINE'].includes(this.activity.phase) },
    canChange() { return this.activity.publishStatus === 'DRAFT' && !this.saving },
    invalid() { return !this.selected || !Number.isInteger(this.form.quantity) || this.form.quantity < this.quantityMin || this.form.quantity > this.sliderMax || !Number.isInteger(this.form.maxClaimsPerUser) || this.form.maxClaimsPerUser < 1 || this.form.maxClaimsPerUser > this.personMax || !/^\d+(\.\d{1,2})?$/.test(String(this.form.budget)) },
    quantityMin() { return this.running ? Number(this.activity.quantity || 1) : 1 },
    selected() {
      return this.templates.find((item) => String(item.id) === this.form.planId)
    },
    unitDiscount() {
      return ruleAmount(this.selected?.benefitPolicy)
    },
    personMax() {
      const limit = this.limitOf(this.selected)
      return limit || 1
    },
    sliderMax() {
      const stock = Number(this.selected?.stock || 0)
      const same = String(this.activity.planId || '') === this.form.planId
      const held = same ? Number(this.activity.quantity || 0) : 0
      return Math.max(1, stock + held)
    },
    maxGive() {
      const amount = moneyTimes(this.unitDiscount, this.form.quantity)
      return amount == null ? '券未写明单张最高优惠' : `${amount} 元`
    },
    dirty() {
      const origin = this.blank()
      return this.form.planId !== origin.planId
        || Number(this.form.quantity) !== Number(origin.quantity)
        || Number(this.form.maxClaimsPerUser) !== Number(origin.maxClaimsPerUser)
        || String(this.form.budget) !== String(origin.budget)
    }
  },
  watch: {
    activity: {
      immediate: true,
      handler() { this.form = this.blank() }
    }
  },
  beforeRouteUpdate() { return this.confirmLeave() },
  beforeRouteLeave() {
    return this.confirmLeave()
  },
  created() {
    this.load()
  },
  mounted() {
    window.addEventListener('beforeunload', this.beforeUnload)
  },
  beforeUnmount() {
    window.removeEventListener('beforeunload', this.beforeUnload)
  },
  methods: {
    clockLabel, formatNumber, leftOf, couponFace, couponRuleText,
    blank() {
      return {
        planId: this.activity.planId ? String(this.activity.planId) : '',
        quantity: this.activity.quantity || 1,
        maxClaimsPerUser: this.activity.maxClaimsPerUser || 1,
        budget: this.activity.budgetAmount != null ? String(this.activity.budgetAmount) : ''
      }
    },
    limitOf(item) {
      try {
        return JSON.parse(item?.claimPolicy || '{}').maxClaimsPerUser || 1
      } catch {
        return 1
      }
    },
    face(item) {
      const amount = ruleAmount(item.benefitPolicy)
      let terms = ''
      try { terms = JSON.parse(item.benefitPolicy || '{}').minSpend || '' } catch { terms = '' }
      if (terms && amount) return `满 ${terms} 减 ${amount}`
      if (amount) return `最高优惠 ${amount} 元`
      return '优惠规则见券模板'
    },
    choose(item) {
      if (!this.covers(item)) return
      this.candidate = item
    },
    covers(item) {
      const start = String(item?.usableFrom || '').slice(0, 19)
      const end = String(item?.usableUntil || '').slice(0, 19)
      const actStart = String(this.activity?.startTime || '').slice(0, 19)
      const actEnd = String(this.activity?.endTime || '').slice(0, 19)
      return Boolean(start && end && actStart && actEnd && start <= actStart && end >= actEnd)
    },
    confirmChoice() {
      const item = this.candidate
      if (!item) return
      this.picker = false; this.candidate = null

      this.form.planId = String(item.id)
      if (this.form.quantity > this.sliderMax) this.form.quantity = this.sliderMax
      if (this.form.maxClaimsPerUser > this.personMax) this.form.maxClaimsPerUser = this.personMax
    },
    reset() {
      this.form = this.blank()
      this.message = ''
      this.failed = false
    },
    confirmLeave() {
      if (!this.dirty) return true
      return window.confirm('投放草稿还没保存，确定离开？')
    },
    beforeUnload(event) {
      if (!this.dirty) return
      event.preventDefault()
      event.returnValue = ''
    },
    signature() {
      return JSON.stringify({
        planId: this.form.planId,
        quantity: this.form.quantity,
        maxClaimsPerUser: this.form.maxClaimsPerUser,
        budget: this.form.budget
      })
    },
    retryKey() {
      const signature = this.signature()
      if (this.pendingSignature !== signature || !this.pendingKey) {
        this.pendingSignature = signature
        this.pendingKey = crypto.randomUUID()
      }
      return this.pendingKey
    },
    async load() {
      this.loading = true
      try {
        const response = await listAllTemplates()
        this.templates = response.data?.records || []
      } catch (error) {
        this.failed = true
        this.message = error?.message || '券库读取失败'
      } finally {
        this.loading = false
      }
    },
    async save() {
      if (this.saving || this.locked || this.invalid) return
      this.saving = true
      this.message = ''
      this.failed = false
      const signature = this.signature()
      try {
        await saveOffer(this.activity.id, {
          planId: this.form.planId,
          quantity: this.form.quantity,
          maxClaimsPerUser: this.form.maxClaimsPerUser,
          budgetAmount: this.form.budget,
          idempotencyKey: this.retryKey(),
          version: this.activity.version
        })
        this.pendingKey = ''
        this.pendingSignature = ''
        this.message = '投放已保存'
        this.$emit('reload')
      } catch (error) {
        this.failed = true
        const fields = error?.fields ? Object.values(error.fields).join('；') : ''
        this.message = fields || error?.message || '保存失败'
        if (this.pendingSignature !== signature) this.pendingKey = ''
      } finally {
        this.saving = false
      }
    }
  }
}
</script>
