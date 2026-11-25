<template>
  <section>
    <div class="pagehead">
      <div>
        <h1>券库</h1>
      </div>
      <button class="btn primary" type="button" @click="show = true">＋ 创建券模板</button>
    </div>
    <p v-if="notice" class="flow-message" role="status">{{ notice }}</p>
    <p v-if="error" class="error">{{ error }} <button class="btn" type="button" @click="load">重试</button></p>
    <p v-if="loading" class="loading">正在读取券库…</p>
    <template v-else-if="!error">

      <div class="split">
        <div>
          <div class="seg">
            <button type="button" :class="{ active: view === 'cards' }" @click="view = 'cards'">券面</button>
            <button type="button" :class="{ active: view === 'table' }" @click="view = 'table'">表格</button>
          </div>
          <div v-if="!records.length" class="card section-gap"><EmptyState title="暂无优惠券"><button class="btn primary" @click="show = true">创建优惠券</button></EmptyState></div>
          <div v-else-if="view === 'cards'" class="tkgrid">
            <article v-for="(item, index) in records" :key="item.id" class="tk" :style="{ '--tone': tones[index % tones.length] }">
              <div class="tk-stub"><b :class="{ compact: couponFace(item.benefitPolicy).replace(/[¥￥折\s]/g, '').length > 4 }"><CouponFace :value="couponFace(item.benefitPolicy)" /></b><span>{{ ruleText(item) }}</span></div>
              <div class="tk-body">
                <div class="tk-top"><strong :title="item.title">{{ item.title }}</strong><small>{{ typeLabels[item.benefitKind] || '优惠券' }} · {{ item.status === 'open' ? '可选用' : '已结束' }}</small></div>
                <div class="tk-meta"><span>有效期 {{ clockLabel(item.usableFrom) }} — {{ clockLabel(item.usableUntil) }}</span><span>发行 {{ formatNumber(item.issuedTotal) }} 张{{ couponValidityText(item.benefitPolicy) ? ` · ${couponValidityText(item.benefitPolicy)}` : '' }}</span></div>
                <div class="tk-bar"><i :style="{ width: barWidth(item) }"></i></div>
                <div class="tk-foot">
                  <span>已投放 {{ formatNumber(putOf(item)) }} / {{ formatNumber(item.issuedTotal) }}</span>
                  <div class="row-actions">
                    <button v-if="item.status === 'open'" class="linkbtn" type="button" @click="openStock(item)">追加库存</button>
                    <button class="linkbtn" type="button" @click="openEdit(item)">编辑</button>
                    <button v-if="item.status === 'open'" class="linkbtn" type="button" @click="openTerminate(item)">停发</button>
                    <router-link v-for="activity in usersOf(item)" :key="activity.id" class="linkbtn" :title="activity.name" :to="`/activities/${activity.id}/setup`">{{ activity.name }}</router-link>
                    <span v-if="!usersOf(item).length">未用于活动</span>
                  </div>
                </div>
              </div>
            </article>
          </div>
          <div v-else class="card">
            <div class="tableoverflow">
              <table>
                <thead><tr><th>券模板</th><th>规则</th><th>有效期</th><th>发行总量</th><th>已投放</th><th>可投放剩余</th><th>用于活动</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-for="item in records" :key="item.id">
                    <td><strong>{{ item.title }}</strong><small>{{ item.status === 'open' ? '可选用' : '已停发' }}</small></td>
                    <td>{{ ruleText(item) }}<small v-if="couponValidityText(item.benefitPolicy)">{{ couponValidityText(item.benefitPolicy) }}</small></td>
                    <td>{{ clockLabel(item.usableFrom) }} — {{ clockLabel(item.usableUntil) }}</td>
                    <td>{{ formatNumber(item.issuedTotal) }}</td>
                    <td>{{ formatNumber(putOf(item)) }}</td>
                    <td>{{ formatNumber(item.inventoryQty) }}</td>
                    <td>{{ usersOf(item).map((activity) => activity.name).join('、') || '未用于活动' }}</td>
                    <td><div class="row-actions"><button v-if="item.status === 'open'" class="linkbtn" type="button" @click="openStock(item)">追加库存</button><button class="linkbtn" type="button" @click="openEdit(item)">编辑</button><button v-if="item.status === 'open'" class="linkbtn" type="button" @click="openTerminate(item)">停发</button></div></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
        <aside class="rail">
          <DotStage class="card rc donut-stage" :center-y="0.42" :palette="[[125, 111, 160], [198, 97, 63]]">
            <h3>投放概况</h3>
            <div class="donutwrap">
              <svg class="donut-orbit" viewBox="0 0 182 182" fill="none" aria-hidden="true">
                <circle cx="91" cy="91" r="84" stroke="#c5bfd1" stroke-dasharray="2 5" />
                <circle cx="91" cy="7" r="3.2" fill="#7d6fa0" />
                <circle cx="175" cy="91" r="2.8" fill="#c6613f" />
                <circle cx="7" cy="91" r="2.8" fill="#5b7040" />
              </svg>
              <svg class="donut" viewBox="0 0 42 42">
                <defs>
                  <linearGradient id="coupon-donut-grad" x1="0" y1="0" x2="1" y2="1">
                    <stop offset="0%" stop-color="#c6613f" />
                    <stop offset="100%" stop-color="#7d6fa0" />
                  </linearGradient>
                </defs>
                <circle r="15.9" cx="21" cy="21" fill="none" stroke="#ffffffcc" stroke-width="5" />
                <circle class="donut-progress" r="15.9" cx="21" cy="21" fill="none" stroke="url(#coupon-donut-grad)" stroke-width="5" stroke-linecap="round" :stroke-dasharray="`${percent} ${100 - percent}`" stroke-dashoffset="25" />
              </svg>
              <div class="dnum"><b>{{ percent }}%</b><span>已投放</span></div>
            </div>
            <div class="checkrow"><span>发行总量</span><b>{{ formatNumber(totalIssue) }} 张</b></div>
            <div class="checkrow"><span>已投放</span><b>{{ formatNumber(totalPut) }} 张</b></div>
            <div class="checkrow"><span>可投放剩余</span><b>{{ formatNumber(totalLeft) }} 张</b></div>
          </DotStage>
          <div class="card rc">
            <h3>即将到期</h3>
            <p v-if="!soon.length" class="muted">还没有券模板。</p>
            <div v-for="item in soon" :key="item.id" class="checkrow"><span>{{ item.title }}</span><b class="expire-date">{{ shortEnd(item) }} 到期</b></div>
          </div>

        </aside>
      </div>
    </template>
    <StudioModal v-if="show" title="创建券模板" :busy="saving" @close="show = false">
      <form class="config-form-grid" @submit.prevent="create">

        <label class="field">名称<input v-model.trim="form.name" required maxlength="256" /></label>
        <label class="field">发行总量<input v-model.number="form.stock" type="number" min="1" required /></label>
        <label class="field">每人限领<input v-model.number="form.limit" type="number" min="1" required /></label>
        <label class="field">券类型<select v-model="form.type"><option value="amount_off">立减券</option><option value="amount_off_over">满减券</option><option value="percent_off">折扣券</option></select></label>
        <label v-if="form.type === 'percent_off'" class="field">折扣（例如 8.5 折）<input v-model.number="form.discount" type="number" min="0.1" max="9.9" step="0.1" required /></label>
        <label v-if="form.type !== 'amount_off'" class="field">使用门槛<input v-model.trim="form.terms" required /></label>
        <label class="field">单张最高优惠<input v-model.trim="form.amount" required /></label>
        <label class="field">生效开始<input v-model="form.validStart" type="datetime-local" required /></label>
        <label class="field">使用截止<input v-model="form.validEnd" type="datetime-local" required /></label>
        <label class="field">领取后有效期<select v-model="form.validityMode"><option value="template">跟随券使用截止时间</option><option value="hours">领取后固定小时内有效</option></select></label>
        <label v-if="form.validityMode === 'hours'" class="field">领取后有效小时数<input v-model.number="form.validityHours" type="number" min="1" max="8760" step="1" required /></label>
        <div class="actions modal-actions">
          <button class="btn primary" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
          <button class="btn" type="button" @click="show = false">取消</button>
        </div>
        <p v-if="formError" class="error">{{ formError }}</p>
      </form>
    </StudioModal>
    <StudioModal v-if="stockTarget" title="追加券模板库存" :busy="saving" @close="stockTarget = null">
      <form @submit.prevent="submitStock">
        <p class="note">当前券模板：{{ stockTarget.title }}（剩余可投放 {{ formatNumber(stockTarget.inventoryQty) }} 张）</p>
        <label class="field">追加发行张数<input v-model.number="addStockCount" type="number" min="1" step="1" required /></label>
        <p v-if="formError" class="form-alert" role="alert">{{ formError }}</p>
        <div class="actions modal-actions">
          <button class="btn" type="button" :disabled="saving" @click="stockTarget = null">取消</button>
          <button class="btn primary" :disabled="saving">{{ saving ? '提交中…' : '确认追加' }}</button>
        </div>
      </form>
    </StudioModal>
    <StudioModal v-if="editTarget" title="编辑券模板" :busy="saving" @close="editTarget = null">
      <form class="config-form-grid" @submit.prevent="submitEdit">
        <label class="field">名称<input v-model.trim="editForm.title" required maxlength="256" /></label>
        <label class="field">每人总限领<input v-model.number="editForm.limit" type="number" min="1" step="1" required /></label>
        <label v-if="editTarget.benefitKind === 'percent_off'" class="field">折扣（例如 8.5 折）<input v-model.number="editForm.discount" type="number" min="0.1" max="9.9" step="0.1" required /></label>
        <label v-if="editTarget.benefitKind !== 'amount_off'" class="field">使用门槛（元）<input v-model.trim="editForm.terms" required /></label>
        <label class="field">单张最高优惠（元）<input v-model.trim="editForm.amount" required /></label>
        <label class="field">生效开始<input v-model="editForm.validStart" type="datetime-local" required /></label>
        <label class="field">使用截止<input v-model="editForm.validEnd" type="datetime-local" required /></label>
        <label class="field">领取后有效期<select v-model="editForm.validityMode"><option value="template">跟随券使用截止时间</option><option value="hours">领取后固定小时内有效</option></select></label>
        <label v-if="editForm.validityMode === 'hours'" class="field">领取后有效小时数<input v-model.number="editForm.validityHours" type="number" min="1" max="8760" step="1" required /></label>
        <div class="actions modal-actions">
          <button class="btn" type="button" :disabled="saving" @click="editTarget = null">取消</button>
          <button class="btn primary" :disabled="saving">{{ saving ? '保存中…' : '保存修改' }}</button>
        </div>
        <p v-if="formError" class="form-alert" role="alert">{{ formError }}</p>
      </form>
    </StudioModal>
    <StudioModal v-if="terminateTarget" title="确认停发券模板？" :busy="saving" @close="terminateTarget = null">
      <p class="note">停发「{{ terminateTarget.title }}」后，不能再被新活动选用，也不能恢复为可选用；已领取的券仍按原规则正常使用。若该券已关联未结束活动，请先终止对应活动。</p>
      <p v-if="formError" class="form-alert" role="alert">{{ formError }}</p>
      <div class="actions modal-actions">
        <button class="btn" type="button" :disabled="saving" @click="terminateTarget = null">取消</button>
        <button class="btn primary" type="button" :disabled="saving" @click="confirmTerminate">{{ saving ? '处理中…' : '确认停发' }}</button>
      </div>
    </StudioModal>
  </section>
</template>

<script>
import EmptyState from '@/components/EmptyState.vue'
import CouponFace from '@/components/CouponFace.vue'
import StudioModal from '@/components/StudioModal.vue'
import DotStage from '@/components/DotStage.vue'
import { listActivities } from '@/api/campaign'
import { createTemplate, increaseStock, listAllTemplates, reviseTemplate, terminateTemplate } from '@/api/coupon'
import { clockLabel, formatNumber, fromLocalInput, toLocalInput, couponRuleText, couponFace, couponValidityText } from '@/views/activity/format'

export default {
  components: { EmptyState, StudioModal, CouponFace, DotStage },
  data() {
    const start = new Date()
    const end = new Date(Date.now() + 30 * 86400000)
    const pad = (value) => String(value).padStart(2, '0')
    return {
      records: [], activities: [], loading: false, saving: false, error: '', notice: '', formError: '', show: false, view: 'cards',
      stockTarget: null, addStockCount: 100, editTarget: null, terminateTarget: null,
      editForm: { title: '', limit: 1, terms: '0', amount: '10', discount: 8.5, validStart: '', validEnd: '', validityMode: 'template', validityHours: 72 },
      tones: ['#c6613f', '#788c5d', '#6a9bcc', '#c8a26b', '#8a7fb0'],
      typeLabels: { amount_off: '立减券', amount_off_over: '满减券', percent_off: '折扣券' },
      form: {
        type: 'amount_off_over', discount: 8.5, name: '', stock: 100, limit: 1, terms: '199', amount: '30',
        validStart: `${start.getFullYear()}-${pad(start.getMonth() + 1)}-${pad(start.getDate())}T00:00`,
        validEnd: `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())}T23:59`,
        validityMode: 'template',
        validityHours: 72
      }
    }
  },
  computed: {
    totalIssue() { return this.sum('issuedTotal') },
    totalLeft() { return this.sum('inventoryQty') },
    totalPut() {
      if (this.totalIssue == null || this.totalLeft == null) return null
      return Math.max(0, this.totalIssue - this.totalLeft)
    },
    percent() {
      if (!this.totalIssue) return 0
      return Math.round((this.totalPut / this.totalIssue) * 100)
    },
    soon() {
      return [...this.records].sort((a, b) => String(a.usableUntil).localeCompare(String(b.usableUntil))).slice(0, 3)
    }
  },
  created() {
    this.load()
  },
  methods: {
    clockLabel, formatNumber, couponFace, couponValidityText,
    sum(field) {
      if (!this.records.length) return 0
      if (this.records.some((item) => item[field] == null)) return null
      return this.records.reduce((total, item) => total + Number(item[field]), 0)
    },
    putOf(item) {
      if (item.issuedTotal == null || item.inventoryQty == null) return null
      return Math.max(0, Number(item.issuedTotal) - Number(item.inventoryQty))
    },
    barWidth(item) {
      const put = this.putOf(item)
      if (put == null || !item.issuedTotal) return '0%'
      return `${Math.min(100, Math.round((put / Number(item.issuedTotal)) * 100))}%`
    },
    ruleText(item) { return couponRuleText(item.benefitPolicy) },
    usersOf(item) {
      return this.activities.filter((activity) => String(activity.planId) === String(item.id))
    },
    shortEnd(item) {
      if (!item.usableUntil) return '—'
      const date = new Date(String(item.usableUntil).replace(' ', 'T'))
      if (Number.isNaN(date.getTime())) return '—'
      return `${date.getMonth() + 1}.${date.getDate()}`
    },
    async load() {
      this.loading = true
      this.error = ''
      try {
        const [templates, activities] = await Promise.all([listAllTemplates(), listActivities()])
        this.records = templates.data?.records || []
        this.activities = activities.data || []
      } catch (error) {
        this.records = []
        this.error = error?.message || '模板读取失败'
      } finally {
        this.loading = false
      }
    },
    localNow() {
      const now = new Date()
      const pad = (value) => String(value).padStart(2, '0')
      return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
    },
    async create() {
      this.saving = true
      this.formError = ''
      try {
        await createTemplate({
          title: this.form.name,
          issuerScope: 'merchant',
          applyScope: 'storewide',
          benefitKind: this.form.type,
          inventoryQty: this.form.stock,
          usableFrom: fromLocalInput(this.form.validStart) || this.localNow(),
          usableUntil: fromLocalInput(this.form.validEnd),
          claimPolicy: JSON.stringify({ maxClaimsPerUser: this.form.limit }),
          benefitPolicy: JSON.stringify({
            minSpend: this.form.type === 'amount_off' ? '0' : this.form.terms,
            capAmount: this.form.amount,
            ...(this.form.type === 'percent_off' ? { percentOff: (1 - this.form.discount / 10).toFixed(4) } : {}),
            validityPeriodHours: this.form.validityMode === 'hours' ? Number(this.form.validityHours) : 0
          })
        })
        this.show = false
        this.notice = '券模板已创建'
        await this.load()
      } catch (error) {
        this.formError = error?.message || '模板保存失败'
      } finally {
        this.saving = false
      }
    },
    openStock(item) {
      this.stockTarget = item
      this.addStockCount = 100
      this.formError = ''
      this.notice = ''
    },
    async submitStock() {
      if (this.saving || !this.stockTarget) return
      this.saving = true
      this.formError = ''
      try {
        await increaseStock(this.stockTarget.id, Number(this.addStockCount))
        this.notice = `已为「${this.stockTarget.title}」追加 ${this.addStockCount} 张库存`
        this.stockTarget = null
        await this.load()
      } catch (error) {
        this.formError = error?.message || '追加库存失败'
      } finally {
        this.saving = false
      }
    },
    openEdit(item) {
      let claim = {}, benefit = {}
      try { claim = JSON.parse(item.claimPolicy || '{}') } catch { /* 使用默认值 */ }
      try { benefit = JSON.parse(item.benefitPolicy || '{}') } catch { /* 使用默认值 */ }
      this.editTarget = item
      this.formError = ''
      this.notice = ''
      this.editForm = {
        title: item.title || '',
        limit: Number(claim.maxClaimsPerUser || 1),
        terms: String(benefit.minSpend ?? '0'),
        amount: String(benefit.capAmount ?? '10'),
        discount: benefit.percentOff != null ? Number(((1 - Number(benefit.percentOff)) * 10).toFixed(1)) : 8.5,
        validStart: toLocalInput(item.usableFrom),
        validEnd: toLocalInput(item.usableUntil),
        validityMode: Number(benefit.validityPeriodHours) > 0 ? 'hours' : 'template',
        validityHours: Number(benefit.validityPeriodHours) > 0 ? Number(benefit.validityPeriodHours) : 72
      }
    },
    async submitEdit() {
      if (this.saving || !this.editTarget) return
      this.saving = true
      this.formError = ''
      let prevBenefit = {}
      try { prevBenefit = JSON.parse(this.editTarget.benefitPolicy || '{}') } catch { /* 保留默认结构 */ }
      const nextBenefit = {
        ...prevBenefit,
        minSpend: this.editTarget.benefitKind === 'amount_off' ? '0' : this.editForm.terms,
        capAmount: this.editForm.amount,
        validityPeriodHours: this.editForm.validityMode === 'hours' ? Number(this.editForm.validityHours) : 0
      }
      if (this.editTarget.benefitKind === 'percent_off') {
        nextBenefit.percentOff = (1 - Number(this.editForm.discount) / 10).toFixed(4)
      }
      try {
        await reviseTemplate(this.editTarget.id, {
          title: this.editForm.title,
          usableFrom: fromLocalInput(this.editForm.validStart),
          usableUntil: fromLocalInput(this.editForm.validEnd),
          claimPolicy: JSON.stringify({ maxClaimsPerUser: Number(this.editForm.limit) }),
          benefitPolicy: JSON.stringify(nextBenefit)
        })
        this.notice = `已保存「${this.editForm.title}」的规则修改`
        this.editTarget = null
        await this.load()
      } catch (error) {
        this.formError = error?.message || '券模板更新失败'
      } finally {
        this.saving = false
      }
    },
    openTerminate(item) {
      this.terminateTarget = item
      this.formError = ''
      this.notice = ''
    },
    async confirmTerminate() {
      if (this.saving || !this.terminateTarget) return
      this.saving = true
      this.formError = ''
      try {
        await terminateTemplate(this.terminateTarget.id)
        this.notice = `已停发「${this.terminateTarget.title}」`
        this.terminateTarget = null
        await this.load()
      } catch (error) {
        this.formError = error?.message || '停发失败'
      } finally {
        this.saving = false
      }
    }
  }
}
</script>

<style scoped>
.tk {
  transition: transform .22s, box-shadow .22s, border-color .22s;
}
.tk:hover {
  transform: translateY(-2px);
  border-color: #d6cfc0;
  box-shadow: 0 16px 32px -24px #5a432b55;
}
.donut-stage {
  background: linear-gradient(155deg, #f3eff5 0%, #f0eee6 58%, #f5eee8 100%);
}
.donut-stage::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background: radial-gradient(circle at 50% 42%, transparent 0%, #f0eee6b0 75%);
}
.expire-date {
  color: #b87e18;
}
.donutwrap {
  position: relative;
}
.donut-orbit {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 176px;
  height: 176px;
  margin: -88px 0 0 -88px;
  pointer-events: none;
  animation: coupon-orbit-turn 45s linear infinite;
}
.donut-progress {
  transition: stroke-dasharray .8s cubic-bezier(.2, .7, .2, 1);
}
@keyframes coupon-orbit-turn {
  to { transform: rotate(360deg); }
}
@media (prefers-reduced-motion: reduce) {
  .donut-orbit { animation: none; }
  .tk, .donut-progress { transition: none; }
}
</style>
