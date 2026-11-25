<template>
  <section class="config-page activity-create">
    <div class="pagehead">
      <div>
        <h1>创建活动</h1>
      </div>
      <!-- 在创建活动页头补充返回入口，方便用户直接退回活动列表 -->
      <router-link class="btn" to="/activities">返回活动列表</router-link>
    </div>
    <div v-if="activities.length" class="card copybar">
      <span class="copy-label"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="3"/><path d="M15 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h3"/></svg>从已有活动复制</span>
      <select v-model="copyFrom" aria-label="选择要复制的活动">
        <option v-for="item in activities" :key="item.id" :value="String(item.id)">{{ item.name }}</option>
      </select>
      <button class="btn" type="button" :disabled="saving || Boolean(plan)" @click="cloneFrom">应用配置 ↗</button>
    </div>
    <p v-if="draftNotice" class="flow-message" role="status">{{ draftNotice }}</p>
    <div class="createwrap">
      <fieldset class="create-fields" :disabled="saving || Boolean(plan)">
        <div class="card create-section">
          <h2 class="config-heading"><span>1</span>基本信息</h2>
          <div class="info-grid">
            <label class="field">活动名称（必填）
              <input v-model.trim="form.name" maxlength="36" placeholder="例如：冬日会员礼遇" />
            </label>
            <label class="field">活动类型
              <select v-model="form.type">
                <option>优惠券推广活动</option>
                <option>会员专享活动</option>
                <option>新客领取活动</option>
              </select>
            </label>
            <label class="field">开场时间
              <input v-model="form.startDate" type="datetime-local" />
            </label>
            <label class="field">结束时间
              <input v-model="form.endDate" type="datetime-local" />
            </label>
            <label class="field">预算上限（元）
              <input v-model.trim="form.budget" type="number" min="0" step="0.01" />
            </label>
          </div>
        </div>
        <div class="card create-section">
          <h2 class="config-heading"><span>2</span>优惠券</h2>
          
          <p v-if="loading" class="loading">正在读取券库…</p>
          <p v-else-if="loadError" class="error" role="alert">{{ loadError }} <button class="linkbtn" @click="load">重试</button></p>
          <EmptyState v-else-if="!templates.length" title="暂无优惠券"><router-link class="btn" to="/coupons">前往券库</router-link></EmptyState>
          <!-- 仅允许选用生效中且仍有剩余可投放库存的券模板 -->
          <CouponChoice v-show="!loading && !loadError" v-for="item in templates" :key="item.id" :chosen="form.planId === String(item.id)" :name="item.title" :face="couponFace(item.benefitPolicy)" :rule="ruleText(item)" :title="validText(item)" :disabled="item.status !== 'open' || (Number(item.inventoryQty) <= 0 && form.planId !== String(item.id))" @click="choose(item)">{{ item.status !== 'open' ? '已停发 · ' : '' }}可投放 {{ formatNumber(item.inventoryQty) }} 张 · 至 {{ clockLabel(item.usableUntil).slice(0, 10) }}</CouponChoice>
          <template v-if="selected">
            <div class="checkrow">
              <span>投放数量</span>
              <!-- 后端返回的可投放剩余字段为 inventoryQty，而非旧字段 stock -->
              <input v-model.number="form.quantity" aria-label="投放数量" type="number" min="1" :max="selected.inventoryQty" />
            </div>
            <div class="checkrow">
              <span>每人限领<small class="muted">（模板上限 {{ personLimit(selected) }} 张）</small></span>
              <input v-model.number="form.maxClaimsPerUser" aria-label="每人限领" type="number" min="1" :max="personLimit(selected)" step="1" />
            </div>
          </template>
        </div>
        <div class="card create-section">
          <h2 class="config-heading"><span>3</span>短链入口</h2>
          

          <div class="seg entry-presets">
            <button v-for="name in presets" :key="name" type="button" :class="{ active: channels.includes(name) }" :aria-pressed="channels.includes(name)" :disabled="saving" @click="toggleChannel(name)">{{ name }}</button>
          </div>
          <form class="entry-add-form" @submit.prevent="addCustom">
            <label class="field">入口名称<input v-model.trim="customName" maxlength="64" placeholder="例如：抖音推广" aria-label="自定义渠道" :disabled="saving" /></label>
            <label class="field">渠道标识（选填）<input v-model.trim="customCode" maxlength="12" placeholder="例如：dy" aria-label="渠道标识" :disabled="saving" /></label>
            <button class="btn primary" type="submit" :disabled="saving">＋ 添加入口</button>
          </form>
          <p v-if="channelError" class="form-alert" role="alert">{{ channelError }}</p>
          <p v-if="channelNotice" class="note" role="status">{{ channelNotice }}</p>
          <div v-if="entryDrafts.length" class="tableoverflow entry-drafts"><table><thead><tr><th>推广入口</th><th>渠道标识</th><th>短链地址</th><th>操作</th></tr></thead><tbody><tr v-for="item in entryDrafts" :key="item.name"><td><strong>{{ item.name }}</strong></td><td>{{ item.code || '自动生成' }}</td><td><span class="muted">保存活动后生成</span></td><td><button class="linkbtn" type="button" :disabled="saving" @click="removeEntry(item.name)">移除</button></td></tr></tbody></table></div>
          <p v-else class="entry-empty">暂无推广入口</p>

        </div>
      </fieldset>
      <aside class="card sumcard">
        <h2>创建摘要</h2>
        <div class="checkrow"><span>活动直达页</span><b>保存后立即可用</b></div>
        <div class="checkrow"><span>推广短链</span><b>{{ entryDrafts.length ? '随渠道自动生成' : '按需在推广入口生成' }}</b></div>
        <div class="checkrow"><span>推广入口</span><b>{{ entryDrafts.length }} 个</b></div>
        <div class="summary-amount"><span>最大让利</span><b>{{ maxGive }}</b></div>
        <div class="summary-progress"><span>配置检查</span><b>{{ checks.filter(item => item.ok).length }} / {{ checks.length }}</b><i><u :style="{ width: `${checks.filter(item => item.ok).length / checks.length * 100}%` }"></u></i></div>
        <div v-for="item in checks" :key="item.label" class="checkrow summary-check" :class="{ passed: item.ok }"><i aria-hidden="true">{{ item.ok ? '✓' : '·' }}</i><span>{{ item.label }}</span></div>

        <div v-if="plan" class="save-progress" role="status"><div class="checkrow"><span>活动</span><b>{{ plan.activityId ? '已保存' : '待确认结果' }}</b></div><div v-if="!plan.mainDone || entryDrafts.length" class="checkrow"><span>推广短链</span><b>{{ plan.mainDone ? '已生成' : '待保存' }}</b></div><div v-if="plan.offer" class="checkrow"><span>券投放</span><b>{{ plan.offerDone ? '已保存' : '待保存' }}</b></div><div class="checkrow"><span>推广入口</span><b>{{ plan.channelDone.length }} / {{ plan.channels.length }}</b></div><router-link v-if="plan.activityId" class="linkbtn" :to="`/activities/${plan.activityId}/setup`">进入已保存活动 ↗</router-link></div>
        <p v-if="error" class="form-alert" role="alert">{{ error }}</p>
        <div class="actions">
          <button class="btn" type="button" :disabled="saving || loading" @click="save(false)">{{ plan ? '继续保存' : '保存草稿' }}</button>
          <button class="btn primary" type="button" :disabled="saving || loading" @click="save(true)">{{ saving ? '保存中…' : plan ? '继续保存并配置' : '保存并继续配置' }}</button>
        </div>
        <button v-if="hasDraft" class="linkbtn" type="button" :disabled="saving" @click="discard">清除本地草稿</button>
      </aside>
    </div>
  </section>
</template>

<script>
import EmptyState from '@/components/EmptyState.vue'
import CouponChoice from '@/components/CouponChoice.vue'
import { addChannel, createActivity, getActivity, listActivities, openMainLink, saveOffer } from '@/api/campaign'
import { listAllTemplates } from '@/api/coupon'
import { createPlan, savePlan, draftKey, readDraft, writeDraft } from './createDraft.mjs'
import { clockLabel, formatNumber, moneyTimes, ruleAmount, couponFace, couponRuleText } from './format'

export default {
  components: { EmptyState, CouponChoice },
  data() {
    const today = new Date()
    const end = new Date(today.getTime() + 7 * 86400000)
    const day = (date) => {
      const pad = (value) => String(value).padStart(2, '0')
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    }
    return {
      activities: [], templates: [], copyFrom: '', customName: '', customCode: '', channelCodes: {}, channelError: '', channelNotice: '', customChannels: [],
      presets: ['公众号', '社群', '短信'], channels: [], loading: true, loadError: '', saving: false, error: '', plan: null, draftNotice: '', draftReady: false, finished: false, initialForm: '', departed: false,
      form: {
        name: '', type: '优惠券推广活动', startDate: `${day(today)}T${String(today.getHours()).padStart(2,'0')}:${String(today.getMinutes()).padStart(2,'0')}`, endDate: `${day(end)}T23:59`,
        budget: '100000', planId: '', quantity: 100, maxClaimsPerUser: 1
      }
    }
  },
  computed: {
    storageKey() { return draftKey(this.$store.state.profile) },
    hasDraft() { return Boolean(JSON.stringify(this.form) !== this.initialForm || this.form.name || this.form.planId || this.entryDrafts.length || this.customName || this.customCode || this.plan) },
    draftState() { return { form: this.form, channels: this.channels, customChannels: this.customChannels, channelCodes: this.channelCodes, customName: this.customName, customCode: this.customCode, plan: this.plan } },
    entryDrafts() { return [...new Set([...this.channels, ...this.customChannels])].map(name => ({ name, code: this.channelCodes[name] || ({ '公众号': 'wx', '社群': 'sq', '短信': 'sms' }[name]) || '' })) },
    selected() {
      return this.templates.find((item) => String(item.id) === this.form.planId)
    },
    maxGive() {
      if (!this.selected) return '¥0'
      const amount = moneyTimes(ruleAmount(this.selected.benefitPolicy), this.form.quantity)
      return amount == null ? '¥0' : `¥${amount}`
    },
    timeOk() {
      return Boolean(this.form.startDate && this.form.endDate && this.form.endDate > this.form.startDate)
    },
    checks() {
      const coupon = this.selected
      const covered = coupon && this.timeOk && this.covers(coupon)
      // 校验投放数量是否在券模板当前可投放剩余（inventoryQty）范围内
      const stockOk = coupon && Number.isInteger(Number(this.form.quantity)) && Number(this.form.quantity) >= 1 && Number(this.form.quantity) <= Number(coupon.inventoryQty)
      const limitOk = coupon && Number.isInteger(Number(this.form.maxClaimsPerUser)) && Number(this.form.maxClaimsPerUser) >= 1 && Number(this.form.maxClaimsPerUser) <= this.personLimit(coupon)
      const budgetOk = coupon && this.giveCents() <= this.budgetCents()
      return [
        { ok: Boolean(this.form.name), label: '活动名称' },
        { ok: this.timeOk, label: '活动时间' },
        { ok: Boolean(covered), label: '券有效期覆盖活动时间' },
        { ok: Boolean(stockOk), label: '投放数量在可投放范围内' },
        { ok: Boolean(limitOk), label: `每人限领不超过模板上限（${coupon ? this.personLimit(coupon) : 1} 张）` },
        { ok: Boolean(budgetOk), label: `最大让利不超过预算（¥${formatNumber(this.form.budget || 0)}）` },
        { ok: true, label: '活动直达页立即可用，短链与渠道可选' }
      ]
    }
  },
  watch: { draftState: { deep: true, handler() {
    if (!this.draftReady || this.finished) return
    if (this.hasDraft) this.persistDraft()
    else { try { localStorage.removeItem(this.storageKey) } catch { /* 无草稿可恢复。 */ } }
  } } },
  created() {
    this.initialForm = JSON.stringify(this.form)
    const saved = readDraft(localStorage, this.storageKey)
    if (saved) {
      this.form = { ...this.form, ...saved.form }
      this.channels = saved.channels || []; this.customChannels = saved.customChannels || []
      this.channelCodes = saved.channelCodes || {}; this.customName = saved.customName || ''; this.customCode = saved.customCode || ''
      this.plan = saved.plan || null; this.draftNotice = '已恢复上次未完成的活动'
    }
    this.draftReady = true; this.load()
  },
  mounted() { window.addEventListener('beforeunload', this.beforeLeave) },
  beforeUnmount() { this.departed = true; window.removeEventListener('beforeunload', this.beforeLeave) },
  beforeRouteLeave() {
    if (this.finished || !this.hasDraft) return true
    if (writeDraft(localStorage, this.storageKey, this.draftState)) return true
    return window.confirm('本地草稿未能保存，离开将丢失未提交内容。确定离开？')
  },
  methods: {
    formatNumber, clockLabel, couponFace,
    persistDraft() {
      if (!writeDraft(localStorage, this.storageKey, this.draftState)) this.draftNotice = '浏览器未能保存本地草稿，离开前请先保存活动'
    },
    beforeLeave(event) {
      if (!this.finished && this.hasDraft) { this.persistDraft(); event.preventDefault(); event.returnValue = '' }
    },
    discard() {
      if (!window.confirm(this.plan ? '清除本地草稿不会删除已保存的活动。确定清除？' : '确定清除本地草稿？')) return
      try { localStorage.removeItem(this.storageKey) } catch { /* 本地存储可能被禁用。 */ }
      this.finished = true; this.$router.replace('/activities')
    },
    async load() {
      this.loading = true
      this.loadError = ''
      try {
        const [activities, templates] = await Promise.all([listActivities(), listAllTemplates()])
        this.activities = activities.data || []
        this.templates = templates.data?.records || []
        const copyId = this.$route?.query?.copy
        if (copyId && !this.hasDraft && this.activities.some(item => String(item.id) === String(copyId))) {
          this.copyFrom = String(copyId)
          await this.cloneFrom()
        } else if (!this.copyFrom && this.activities.length) {
          this.copyFrom = String(this.activities[0].id)
        }
      } catch (error) {
        this.loadError = error?.message || '活动与券库读取失败'
      } finally {
        this.loading = false
      }
    },
    ruleText(item) { return couponRuleText(item.benefitPolicy) },
    validText(item) {
      return `${clockLabel(item.usableFrom)} — ${clockLabel(item.usableUntil)}`
    },
    personLimit(item) {
      try { return Math.max(1, Number(JSON.parse(item?.claimPolicy || '{}').maxClaimsPerUser || 1)) } catch { return 1 }
    },
    dayOf(value) {
      if (!value) return ''
      return String(value).slice(0, 10)
    },
    covers(item) {
      const start = String(item.usableFrom || '').slice(0,19)
      const end = String(item.usableUntil || '').slice(0,19)
      return start && end && start <= `${this.form.startDate}:00` && end >= `${this.form.endDate}:00`
    },
    budgetCents() {
      const amount = moneyTimes(this.form.budget || '0', 1)
      return amount == null ? 0 : Math.round(Number(amount) * 100)
    },
    giveCents() {
      const amount = moneyTimes(ruleAmount(this.selected?.benefitPolicy), this.form.quantity)
      return amount == null ? 0 : Math.round(Number(amount) * 100)
    },
    choose(item) {
      const isSelected = this.form.planId === String(item.id)
      // 已停发或无剩余库存的券不允许新选，但允许取消已选中的旧草稿项
      if (!isSelected && (item.status !== 'open' || Number(item.inventoryQty) <= 0)) return
      this.form.planId = isSelected ? '' : String(item.id)
      if (this.selected && Number(this.form.quantity) > Number(this.selected.inventoryQty)) {
        this.form.quantity = Number(this.selected.inventoryQty) || 1
      }
      if (this.form.maxClaimsPerUser > this.personLimit(this.selected)) this.form.maxClaimsPerUser = 1
    },
    toggleChannel(name) {
      this.channelError = ''; this.channelNotice = ''
      if (this.channels.includes(name)) { this.removeEntry(name); return }
      const code = ({ '公众号':'wx', '社群':'sq', '短信':'sms' })[name]
      if (this.entryDrafts.some(item => item.code.toLowerCase() === code)) { this.channelError = '这个渠道标识已经使用，请先移除同标识入口'; return }
      this.channels = [...this.channels, name]
      this.channelCodes[name] = code
      this.channelNotice = `已添加“${name}”，保存活动后生成入口链接`
    },
    addCustom() {
      this.channelError = ''; this.channelNotice = ''
      const name = this.customName.trim(), code = this.customCode.trim()
      if (!name) { this.channelError = '请填写入口名称，例如“抖音推广”'; return false }
      if (this.entryDrafts.some(item => item.name === name)) { this.channelError = '这个入口已添加，请勿重复添加'; return false }
      if (code && !/^[A-Za-z0-9]{2,12}$/.test(code)) { this.channelError = '渠道标识只能使用2–12位字母或数字'; return false }
      if (code && this.entryDrafts.some(item => item.code.toLowerCase() === code.toLowerCase())) { this.channelError = '渠道标识重复，请换一个'; return false }
      this.customChannels = [...this.customChannels, name]
      this.channelCodes[name] = code
      this.customName = ''; this.customCode = ''
      this.channelNotice = `已添加“${name}”，保存活动后生成入口链接`
      return true
    },
    removeEntry(name) {
      this.channels = this.channels.filter(item => item !== name)
      this.customChannels = this.customChannels.filter(item => item !== name)
      delete this.channelCodes[name]
      this.channelError = ''; this.channelNotice = `已移除“${name}”`
    },
    async cloneFrom() {
      if (!this.copyFrom) return
      const response = await getActivity(this.copyFrom)
      const activity = response.data
      this.form.name = `${String(activity.name).split(' · ')[0]} · 复制`
      this.form.startDate = String(activity.startTime).slice(0,16)
      this.form.endDate = String(activity.endTime).slice(0,16)
      this.form.type = activity.activityType || "优惠券推广活动"
      if (activity.budgetAmount != null) this.form.budget = String(activity.budgetAmount)
      this.form.planId = activity.planId ? String(activity.planId) : ''
      if (activity.quantity) this.form.quantity = activity.quantity
      if (activity.maxClaimsPerUser) this.form.maxClaimsPerUser = activity.maxClaimsPerUser
      const names = (activity.channels || []).map((item) => item.name)
      this.channelCodes = Object.fromEntries((activity.channels || []).map(item => [item.name, item.channelCode]))
      this.channels = names.filter((name) => this.presets.includes(name))
      this.customChannels = names.filter((name) => !this.presets.includes(name))
    },
    async save(continueConfig) {
      if (this.saving) return
      this.error = ''
      if ((this.customName.trim() || this.customCode.trim()) && !this.addCustom()) return
      if (!this.form.name) {
        this.error = '请先填写活动名称'
        return
      }
      if (!/^\d+(\.\d{1,2})?$/.test(String(this.form.budget)) || Number(this.form.budget) > 9999999999.99) {
        this.error = '请填写有效预算，最多两位小数'
        return
      }
      if (!this.timeOk) {
        this.error = '请填写开场和结束时间，结束时间须晚于开场'
        return
      }
      if (!this.plan && this.form.planId && !this.selected) { this.error = '所选优惠券已不可用，请重新选择'; return }
      // 防止草稿恢复或复制历史活动时带入已停发的券模板
      if (!this.plan && this.selected && this.selected.status !== 'open') { this.error = '所选优惠券已停发，请重新选择生效中的优惠券'; return }
      if (!this.plan && this.selected && !this.checks[2].ok) { this.error = '优惠券有效期未覆盖活动时间'; return }
      if (!this.plan && this.selected && !this.checks[3].ok) {
        this.error = '投放数量超过可投放剩余'
        return
      }
      if (!this.plan && this.selected && !this.checks[4].ok) {
        this.error = `每人限领须为 1–${this.personLimit(this.selected)} 张的整数`
        return
      }
      if (!this.plan && this.selected && !this.checks[5].ok) {
        this.error = '最大让利超过预算'
        return
      }
      this.saving = true
      try {
        // 仅当用户添加了渠道时自动创建推广主短链，未添加渠道则直接复用活动直达页
        if (!this.plan) this.plan = createPlan(this.form, this.entryDrafts, { generateMain: this.entryDrafts.length > 0 })
        const id = await savePlan(this.plan, { createActivity, getActivity, openMainLink, saveOffer, addChannel }, this.persistDraft)
        if (this.departed) return
        this.finished = true
        try { localStorage.removeItem(this.storageKey) } catch { /* 服务端保存已完成。 */ }
        this.$router.push(continueConfig ? `/activities/${id}/promote` : '/activities')
      } catch (error) {
        this.error = `${error?.message || '保存失败'}；可继续保存，已完成步骤会保留`
        this.persistDraft()
      } finally {
        this.saving = false
      }
    }
  }
}
</script>

<style scoped>
.create-fields { min-width: 0; border: 0; margin: 0; padding: 0; }
.save-progress { margin: 16px 0; padding: 12px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
</style>
