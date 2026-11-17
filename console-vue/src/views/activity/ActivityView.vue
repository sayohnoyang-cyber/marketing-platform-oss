<template>
  <section>
    <div class="pagehead">
      <div>
        <h1>活动管理</h1>
        <p class="muted">先写基本信息，再选券、配渠道。开场前不能领券，由服务端时间判断。</p>
      </div>
    </div>
    <div class="steps">
      <button class="btn" :class="{ active: step === 'info' }" type="button" @click="step = 'info'">基本信息</button>
      <button class="btn" :class="{ active: step === 'pick' }" type="button" @click="step = 'pick'">选券</button>
      <button class="btn" :class="{ active: step === 'link' }" type="button" @click="step = 'link'">配渠道</button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <form v-if="step === 'info'" class="card form" @submit.prevent="create">
      <label class="field">活动名称<input v-model.trim="form.name" required maxlength="128" /></label>
      <label class="field">预热开始<input v-model="form.warmupStartTime" type="datetime-local" required /></label>
      <label class="field">开场<input v-model="form.startTime" type="datetime-local" required /></label>
      <label class="field">结束<input v-model="form.endTime" type="datetime-local" required /></label>
      <button class="btn primary" :disabled="saving">{{ saving ? '保存中…' : '创建草稿' }}</button>
    </form>
    <form v-if="step === 'pick'" class="card form" @submit.prevent="attach">
      <label class="field">活动编号<input v-model.trim="activityId" required /></label>
      <label class="field">券模板
        <select v-model="planId" required>
          <option disabled value="">选择券库里的模板</option>
          <option v-for="item in templates" :key="item.id" :value="String(item.id)">{{ item.title }} · 剩余 {{ item.inventoryQty }} / 发行 {{ item.issuedTotal }}</option>
        </select>
      </label>
      <label class="field">投放数量<input v-model.number="quantity" type="number" min="1" required /></label>
      <label class="field">本场每人限领<input v-model.number="maxClaimsPerUser" type="number" min="1" required /></label>
      <label class="field">预算上限（元）<input v-model.trim="budget" required /></label>
      <div class="actions">
        <button class="btn" type="button" @click="bind">选用这张券</button>
        <button class="btn" type="button" @click="place">保存投放</button>
        <button class="btn" type="button" @click="loadChecks">发布检查</button>
        <button class="btn primary" type="button" @click="publish">发布</button>
        <button class="btn" type="button" @click="pause">暂停</button>
        <button class="btn" type="button" @click="cancel">下线</button>
        <button class="btn" type="button" @click="shift('warmup')">改到预热</button>
        <button class="btn" type="button" @click="shift('live')">改到开场</button>
        <button class="btn" type="button" @click="shift('ended')">改到结束</button>
      </div>
      <p v-for="item in checks" :key="item.code" class="muted">{{ item.passed ? '通过' : '未通过' }} · {{ item.code }} {{ item.reason }}</p>
    </form>
    <form v-if="step === 'link'" class="card form" @submit.prevent="channel">
      <label class="field">活动编号<input v-model.trim="activityId" required /></label>
      <label class="field">渠道名称<input v-model.trim="channelName" required maxlength="64" /></label>
      <label class="field">幂等键<input v-model.trim="idempotencyKey" required maxlength="64" /></label>
      <button class="btn primary" :disabled="saving">{{ saving ? '创建中…' : '添加渠道入口' }}</button>
      <p v-if="mainInfo.fullShortUrl" class="muted">主链 {{ mainInfo.fullShortUrl }} → {{ mainInfo.originUrl }}</p>
      <p v-if="channels.length" class="muted">渠道共用上面这条主链。</p>
      <table v-if="channels.length">
        <tbody>
          <tr v-for="item in channels" :key="item.id">
            <td>{{ item.title }}</td>
            <td>?c={{ item.channelCode }}</td>
            <td><button class="btn" type="button" @click="toggleChannel(item)">{{ item.status === 1 ? '停用' : '启用' }}</button></td>
          </tr>
        </tbody>
      </table>
      <div class="actions">
        <button class="btn" type="button" @click="toggleMain(true)">启用主链</button>
        <button class="btn" type="button" @click="toggleMain(false)">停用主链</button>
      </div>
    </form>
    <div class="card">
      <p v-if="loading" class="loading">正在读取活动…</p>
      <p v-else-if="!rows.length" class="empty">还没有活动。</p>
      <table v-else>
        <thead><tr><th>活动</th><th>阶段</th><th>版本</th></tr></thead>
        <tbody>
          <tr v-for="item in rows" :key="item.id" @click="choose(item)">
            <td><strong>{{ item.title }}</strong><small>{{ item.id }}</small></td>
            <td>{{ item.phase }}</td>
            <td>{{ item.version }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="reports.length" class="card" style="margin-top: 16px">
      <table>
        <thead><tr><th>渠道</th><th>预约</th><th>领取</th><th>核销</th><th>退款</th></tr></thead>
        <tbody>
          <tr v-for="item in reports" :key="item.channelId">
            <td>{{ item.title }}</td><td>{{ item.reservations }}</td><td>{{ item.claims }}</td><td>{{ item.redeems }}</td><td>{{ item.refunds }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script>
import { activityChecks, activityReport, addChannel, attachCoupon, cancelActivity, createActivity, listActivities, listChannels, mainLink, pauseActivity, placeAllocation, postponeActivity, publishActivity, setChannelEnabled, setMainEnabled } from '@/api/campaign'
import { listTemplates } from '@/api/coupon'

export default {
  data() {
    return {
      step: 'info', rows: [], channels: [], checks: [], templates: [], reports: [], mainInfo: {}, loading: false, saving: false, error: '',
      activityId: '', planId: '', channelName: '', idempotencyKey: '',
      quantity: 100, maxClaimsPerUser: 1, budget: '1000.00',
      form: { name: '', warmupStartTime: '', startTime: '', endTime: '' }
    }
  },
  created() {
    this.load()
    this.loadTemplates()
  },
  methods: {
    stamp(value) {
      return value.length === 16 ? `${value}:00` : value
    },
    async load() {
      this.loading = true
      this.error = ''
      try {
        const response = await listActivities()
        this.rows = response.data || []
      } catch (error) {
        this.rows = []
        this.error = error?.message || '活动读取失败'
      } finally {
        this.loading = false
      }
    },
    async create() {
      this.saving = true
      this.error = ''
      try {
        const response = await createActivity({
          name: this.form.name,
          warmupStartTime: this.stamp(this.form.warmupStartTime),
          startTime: this.stamp(this.form.startTime),
          endTime: this.stamp(this.form.endTime)
        })
        this.activityId = response.data.id
        this.step = 'pick'
        await this.load()
      } catch (error) {
        this.error = error?.message || '创建失败'
      } finally {
        this.saving = false
      }
    },
    async bind() {
      this.error = ''
      try {
        await attachCoupon(this.activityId, this.planId)
      } catch (error) {
        this.error = error?.message || '选券失败'
      }
    },
    async place() {
      this.error = ''
      try {
        await placeAllocation(this.activityId, {
          quantity: this.quantity,
          maxClaimsPerUser: this.maxClaimsPerUser,
          idempotencyKey: `place-${Date.now()}`,
          budgetAmount: this.budget
        })
      } catch (error) {
        this.error = error?.message || '投放保存失败'
      }
    },
    async loadChecks() {
      this.error = ''
      try {
        const response = await activityChecks(this.activityId)
        this.checks = response.data || []
      } catch (error) {
        this.checks = []
        this.error = error?.message || '发布检查失败'
      }
    },
    async toggleMain(enabled) {
      this.error = ''
      try {
        await setMainEnabled(this.activityId, enabled)
      } catch (error) {
        this.error = error?.message || '主链开关失败'
      }
    },
    async publish() {
      this.error = ''
      try {
        await publishActivity(this.activityId)
        await this.load()
      } catch (error) {
        this.error = error?.message || '发布失败'
      }
    },
    async pause() {
      this.error = ''
      try {
        await pauseActivity(this.activityId)
        await this.load()
      } catch (error) {
        this.error = error?.message || '暂停失败'
      }
    },
    async loadTemplates() {
      try {
        const response = await listTemplates()
        this.templates = response.data?.records || []
      } catch (error) {
        this.templates = []
      }
    },
    async choose(item) {
      this.activityId = item.id
      this.error = ''
      await this.refreshChannel()
    },
    localStamp(date) {
      const pad = (value) => String(value).padStart(2, '0')
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
    },
    async shift(target) {
      const now = new Date()
      const end = new Date(now.getTime() + 2 * 86400000)
      let warmup = new Date(now.getTime() - 3600000)
      let start = new Date(now.getTime() + 3600000)
      if (target === 'live') {
        warmup = new Date(now.getTime() - 2 * 3600000)
        start = new Date(now.getTime() - 60000)
      }
      if (target === 'ended') {
        warmup = new Date(now.getTime() - 3 * 3600000)
        start = new Date(now.getTime() - 2 * 3600000)
        end.setTime(now.getTime() - 60000)
      }
      this.error = ''
      try {
        await postponeActivity(this.activityId, {
          name: '改期',
          warmupStartTime: this.localStamp(warmup),
          startTime: this.localStamp(start),
          endTime: this.localStamp(end)
        })
        await this.load()
      } catch (error) {
        this.error = error?.message || '改期失败'
      }
    },
    async cancel() {
      this.error = ''
      try {
        await cancelActivity(this.activityId)
        await this.load()
      } catch (error) {
        this.error = error?.message || '下线失败'
      }
    },
    async toggleChannel(item) {
      this.error = ''
      try {
        await setChannelEnabled(this.activityId, item.id, item.status !== 1)
        await this.refreshChannel()
      } catch (error) {
        this.error = error?.message || '渠道开关失败'
      }
    },
    async refreshChannel() {
      const [channels, link, report] = await Promise.all([
        listChannels(this.activityId),
        mainLink(this.activityId),
        activityReport(this.activityId)
      ])
      this.channels = channels.data || []
      this.mainInfo = link.data || {}
      this.reports = report.data || []
    },
    async channel() {
      this.saving = true
      this.error = ''
      try {
        await addChannel(this.activityId, { name: this.channelName, idempotencyKey: this.idempotencyKey })
        await this.refreshChannel()
      } catch (error) {
        this.error = error?.message || '渠道创建失败'
      } finally {
        this.saving = false
      }
    }
  }
}
</script>
