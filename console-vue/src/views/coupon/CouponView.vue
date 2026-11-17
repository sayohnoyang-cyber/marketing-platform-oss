<template>
  <section>
    <div class="pagehead">
      <div>
        <h1>券库</h1>
      </div>
      <button class="btn primary" type="button" @click="show = true">＋ 创建券模板</button>
    </div>
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
                <div class="tk-meta"><span>有效期 {{ clockLabel(item.usableFrom) }} — {{ clockLabel(item.usableUntil) }}</span><span>发行 {{ formatNumber(item.issuedTotal) }} 张</span></div>
                <div class="tk-bar"><i :style="{ width: barWidth(item) }"></i></div>
                <div class="tk-foot">
                  <span>已投放 {{ formatNumber(putOf(item)) }} / {{ formatNumber(item.issuedTotal) }}</span>
                  <span>
                    <router-link v-for="activity in usersOf(item)" :key="activity.id" class="linkbtn" :title="activity.name" :to="`/activities/${activity.id}/pick`">{{ activity.name }}</router-link>
                    <template v-if="!usersOf(item).length">未用于活动</template>
                  </span>
                </div>
              </div>
            </article>
          </div>
          <div v-else class="card">
            <div class="tableoverflow">
              <table>
                <thead><tr><th>券模板</th><th>规则</th><th>有效期</th><th>发行总量</th><th>已投放</th><th>可投放剩余</th><th>用于活动</th></tr></thead>
                <tbody>
                  <tr v-for="item in records" :key="item.id">
                    <td><strong>{{ item.name }}</strong></td>
                    <td>{{ ruleText(item) }}</td>
                    <td>{{ clockLabel(item.usableUntil) }}</td>
                    <td>{{ formatNumber(item.issuedTotal) }}</td>
                    <td>{{ formatNumber(putOf(item)) }}</td>
                    <td>{{ formatNumber(item.inventoryQty) }}</td>
                    <td>{{ usersOf(item).map((activity) => activity.name).join('、') || '未用于活动' }}</td>
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
            <div v-for="item in soon" :key="item.id" class="checkrow"><span>{{ item.name }}</span><b class="expire-date">{{ shortEnd(item) }} 到期</b></div>
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
        <label class="field">使用截止<input v-model="form.validEnd" type="datetime-local" required /></label>
        <div class="actions modal-actions">
          <button class="btn primary" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
          <button class="btn" type="button" @click="show = false">取消</button>
        </div>
        <p v-if="formError" class="error">{{ formError }}</p>
      </form>
    </StudioModal>
  </section>
</template>

<script>
import EmptyState from '@/components/EmptyState.vue'
import CouponFace from '@/components/CouponFace.vue'
import StudioModal from '@/components/StudioModal.vue'
import DotStage from '@/components/DotStage.vue'
import { listActivities } from '@/api/campaign'
import { createTemplate, listAllTemplates } from '@/api/coupon'
import { clockLabel, formatNumber, fromLocalInput, couponRuleText, couponFace } from '@/views/activity/format'

export default {
  components: { EmptyState, StudioModal, CouponFace, DotStage },
  data() {
    const end = new Date(Date.now() + 30 * 86400000)
    const pad = (value) => String(value).padStart(2, '0')
    return {
      records: [], activities: [], loading: false, saving: false, error: '', formError: '', show: false, view: 'cards',
      tones: ['#c6613f', '#788c5d', '#6a9bcc', '#c8a26b', '#8a7fb0'],
      typeLabels: { amount_off: '立减券', amount_off_over: '满减券', percent_off: '折扣券' },
      form: {
        type: 'amount_off_over', discount: 8.5, name: '', stock: 100, limit: 1, terms: '199', amount: '30',
        validEnd: `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())}T23:59`
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
    clockLabel, formatNumber, couponFace,
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
          usableFrom: this.localNow(),
          usableUntil: fromLocalInput(this.form.validEnd),
          claimPolicy: JSON.stringify({ maxClaimsPerUser: this.form.limit }),
          benefitPolicy: JSON.stringify({ minSpend: this.form.type === 'amount_off' ? '0' : this.form.terms, capAmount: this.form.amount, ...(this.form.type === 'percent_off' ? { percentOff: (1 - this.form.discount / 10).toFixed(4) } : {}), validityPeriodHours: 72 })
        })
        this.show = false
        await this.load()
      } catch (error) {
        this.formError = error?.message || '模板保存失败'
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
