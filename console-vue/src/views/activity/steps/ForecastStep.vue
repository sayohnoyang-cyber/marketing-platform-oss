<template>
  <div>
    <div class="stephead"><div><h2>预约与开场预测</h2></div><div class="actions"><button class="btn" :disabled="loading || saving" @click="refreshReservations">更新预约</button><button class="btn" :disabled="loading || !rows.length" @click="exportReport">↓ 导出报告</button><button class="btn primary" :disabled="saving || loading || !rows.length" @click="save">{{ saving ? '保存中…' : '保存预测' }}</button></div></div>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="linkbtn" @click="load">重新读取</button></p><p v-if="message" class="flow-message" role="status">{{ message }}</p>
    <OpeningPlanCard ref="openingPlan" :activity-id="activity.id" :version="activity.version" :draft="loading ? null : draftPlan" :dirty="dirty" allow-save :busy="saving || loading" @save="save" />
    <details class="forecast-advanced card section-gap">
      <summary>工程参数与压测限流配置 <span>研发 / 运维选配 · 到场率 · 场景 · 容量</span></summary>
      <div class="banner"><div><strong>容量报告</strong><p>{{ baseline ? baseline.reportName : '尚未关联实测容量' }}</p></div><button class="linkbtn" @click="capacityDialog = true">录入压测基线</button></div>
    <div class="card flow-pad section-gap">
      <div class="sectionhead flush"><h2>开场压力</h2><span class="pill">情景估算</span></div>
      <div class="calibration-bar"><span class="pill" :title="calibration?.definition">{{ historyApplied ? '已采用历史到场率' : saved ? '已保存参数' : '手动估算' }}</span><span v-if="calibration" :title="calibration.definition">历史 {{ calibration.activityCount }} 场 · {{ formatNumber(calibration.sampleCount) }} 人</span><button class="btn" :disabled="loading || saving || !historyUsable" @click="useHistory">使用历史数据</button></div>
      <label class="field">容量基线<select v-model="baselineId"><option value="">暂不关联</option><option v-for="item in baselines" :key="item.id" :value="String(item.id)" :disabled="!item.usable">{{ item.reportName }} · {{ item.safeQps }} QPS{{ item.usable ? '' : '（过期或部署不匹配）' }}</option></select></label>
      
      <p v-if="loading" class="loading">正在读取预测与预约数据…</p>
      <div v-else class="tableoverflow section-gap"><table><thead><tr><th>来源</th><th>有效预约</th><th>开场 10 秒到场率</th><th>请求量占比</th><th>建议入口限流</th></tr></thead><tbody><tr v-for="row in estimate.rows" :key="row.key"><td><strong>{{ row.name }}</strong><small v-if="row.enabled === false">已停用</small></td><td>{{ formatNumber(Math.round(row.reservations * (1 + growth / 100))) }}</td><td><label class="rate-control"><b>{{ row.rate }}%</b><input type="range" min="0" max="100" :disabled="row.enabled === false" :aria-label="`${row.name}到场率`" :value="row.rate" @input="setRate(row.key, $event.target.value)" /></label></td><td>{{ (row.share * 100).toFixed(1) }}%</td><td>{{ baseline ? Math.max(1, Math.floor(Math.min(selected.qps, Number(baseline.safeQps)) * (estimate.arrivals ? row.share : 1 / Math.max(1, rows.length)))) + ' QPS' : '待验证' }}</td></tr></tbody></table></div>
      <p class="actions"><button class="linkbtn" :disabled="saving || applying || !saved || saved.stale || dirty || !baseline || selected.qps <= 0" @click="apply">{{ applying ? '应用中…' : '应用保存的预测到渠道入口' }}</button></p>
      <div v-if="policy?.status === 'ACTIVE'" class="flow-message"><b>{{ policy.stale ? '策略配置已变化，需重新应用或停用' : '限流策略已生效' }}</b><p class="note">活动领券总额度 {{ policy.data.totalQps }} QPS · 有效期至 {{ clockLabel(policy.data.expiresAt) }}。</p><button class="linkbtn" :disabled="applying" @click="disable">停用此策略</button></div>
      <div class="sliders"><label>预约还会增长 <b>+{{ growth }}%</b><input v-model.number="growth" type="range" min="0" max="200" step="5" aria-label="预约增长" /></label><label>人均领券尝试 <b>{{ attempts.toFixed(1) }} 次</b><input v-model.number="attempts" type="range" min="1" max="5" step="0.1" aria-label="人均领券尝试" /></label></div>
      <label class="field">未预约用户额外请求量（QPS）<input v-model.number="unreservedQps" type="number" min="0" max="1000000" @change="normalizeExtra" /></label>
      <div class="scenarios"><button v-for="item in estimate.scenarios" :key="item.key" class="scenario" :class="{ selected: scenario === item.key }" :aria-pressed="scenario === item.key" @click="scenario = item.key"><h3>{{ item.label }} <span v-if="scenario === item.key">当前</span></h3><strong>{{ formatNumber(item.qps) }}</strong> <small>QPS</small><p>假设集中系数 × {{ item.multiplier.toFixed(1) }}</p></button></div>
      
    </div>
    </details>
    <div v-if="history.length" class="card flow-pad section-gap"><h2>预测记录</h2><label v-if="history.length" class="field">已保存的预测记录<select :value="saved?.id" @change="chooseHistory($event.target.value)"><option v-for="item in history" :key="item.id" :value="item.id">{{ clockLabel(item.createdAt) }} · {{ item.id }}{{ item.stale ? ' · 配置已过期' : '' }}</option></select></label></div>
    <StudioModal v-if="capacityDialog" title="录入真实压测基线" :busy="capacitySaving" @close="capacityDialog = false">
      
      <form @submit.prevent="createBaseline"><label class="field">当前部署标识<input v-model.trim="capacityForm.deploymentId" required maxlength="128" /></label><label class="field">报告名称或可追溯编号<input v-model.trim="capacityForm.reportName" required maxlength="256" /></label><div class="info-grid"><label class="field">稳定吞吐 QPS<input v-model.number="capacityForm.sustainedQps" type="number" min="1" max="1000000" required /></label><label class="field">P95（毫秒）<input v-model.number="capacityForm.p95Millis" type="number" min="1" required /></label><label class="field">P99（毫秒）<input v-model.number="capacityForm.p99Millis" type="number" min="1" max="1000" required /></label><label class="field">错误率（0.01表示1%）<input v-model.number="capacityForm.errorRate" type="number" min="0" max="0.01" step="0.000001" required /></label><label class="field">持续时间（秒）<input v-model.number="capacityForm.durationSeconds" type="number" min="30" max="86400" required /></label><label class="field">样本数量<input v-model.number="capacityForm.sampleCount" type="number" min="1000" required /></label></div><label class="field">压测完成时间（北京时间）<input v-model="capacityForm.measuredAt" type="datetime-local" required /></label><label class="field">有效期至（最多7天）<input v-model="capacityForm.expiresAt" type="datetime-local" required /></label><p v-if="capacityError" class="form-alert" role="alert">{{ capacityError }}</p><div class="actions modal-actions"><button class="btn" type="button" :disabled="capacitySaving" @click="capacityDialog = false">取消</button><button class="btn primary" :disabled="capacitySaving">{{ capacitySaving ? '保存中…' : '保存报告基线' }}</button></div></form>
    </StudioModal>
  </div>
</template>
<script>
import { forecastCalibration, activityReport, listForecasts, saveForecast, listCapacityBaselines, createCapacityBaseline, applyRateLimit, getRateLimit, disableRateLimit } from '@/api/campaign'
import StudioModal from '@/components/StudioModal.vue'
import OpeningPlanCard from '../OpeningPlanCard.vue'
import { formatNumber, clockLabel, fromLocalInput, toLocalInput } from '../format'
import { estimateForecast } from '../forecastModel'
export default {
  components: { StudioModal, OpeningPlanCard },
  emits: ['forecast-saved'],
  props: { activity: { type: Object, required: true } },
  data() { return { calibration: null, historyApplied: false, rows: [], growth: 0, attempts: 2, scenario: 'normal', unreservedQps: 0, loading: false, saving: false, applying: false, error: '', message: '', requestToken: 0, saved: null, savedSignature: '', history: [], baselines: [], baselineId: '', deploymentId: '', policy: null, pendingKey: '', pendingSignature: '', limitKey: '', capacityDialog: false, capacitySaving: false, capacityError: '', capacityForm: { deploymentId: '', reportName: '', sustainedQps: '', p95Millis: '', p99Millis: '', errorRate: '', durationSeconds: 60, sampleCount: '', measuredAt: toLocalInput(new Date().toLocaleString('sv-SE', {timeZone:'Asia/Shanghai'})), expiresAt: toLocalInput(new Date(Date.now() + 86400000).toLocaleString('sv-SE', {timeZone:'Asia/Shanghai'})) } } },
  computed: {
    draftPlan() {
      const eligible = this.rows.filter(row => row.enabled !== false).reduce((sum, row) => sum + Number(row.reservations), 0)
      const sample = eligible > 0 || Number(this.unreservedQps) > 0
      const peak = sample ? this.selected.qps : null
      return { reservationCount: this.rows.reduce((sum, row) => sum + Number(row.reservations), 0), expectedArrivals: Math.round(this.estimate.arrivals), peakQps: peak,
        capacityStatus: !sample ? (this.rows.some(row => Number(row.reservations) > 0) ? 'NO_ACTIVE_ENTRY' : 'NO_SAMPLE') : !this.baseline ? 'UNVERIFIED' : peak > Number(this.baseline.safeQps) ? 'OVER_CAPACITY' : 'WITHIN_CAPACITY' }
    },
    historyUsable() { return this.calibration?.rows?.some(row => row.usable) || false },
    estimate() { return estimateForecast(this.rows, this.growth, this.attempts, 10, Math.max(0, Math.min(1000000, Number(this.unreservedQps) || 0))) },
    selected() { return this.estimate.scenarios.find(item => item.key === this.scenario) },
    baseline() { return this.baselines.find(item => String(item.id) === String(this.baselineId) && item.usable) },
    draftSignature() { return this.signature({ growthPercent: this.growth, attempts: this.attempts, scenario: this.scenario, unreservedQps: Number(this.unreservedQps) || 0, baselineId: this.baselineId, rows: this.rows }) },
    dirty() { return this.draftSignature !== this.savedSignature }
  },
  watch: { 'activity.id': { immediate: true, handler() { this.load() } } },
  methods: {
    formatNumber, clockLabel,
    refreshReservations() {
      if (this.saved && this.dirty && !window.confirm('当前参数尚未保存，更新预约将重新读取已保存参数，继续吗？')) return
      this.load(); this.$refs.openingPlan?.load()
    },
    normalizeExtra() { this.unreservedQps = Math.max(0, Math.min(1000000, Number(this.unreservedQps) || 0)) },
    signature(data) { return JSON.stringify([Number(data.growthPercent), Number(data.attempts), data.scenario, Number(data.unreservedQps || 0), String(data.baselineId || ''), (data.rows || []).map(row => [String(row.key), Number(row.rate), Number(row.reservations), row.enabled !== false]).sort((a,b) => a[0].localeCompare(b[0]))]) },
    setRate(key, value) { this.rows.find(item => item.key === key).rate = Number(value); this.historyApplied = false },
    restore(record) { this.historyApplied = false; this.saved = record; const data = record.data; this.growth = Number(data.growthPercent); this.attempts = Number(data.attempts); this.scenario = data.scenario; this.unreservedQps = Number(data.unreservedQps || 0); this.baselineId = data.baselineId ? String(data.baselineId) : ''; this.rows.forEach(row => { const old = data.rows.find(item => String(item.key) === String(row.key)); if (old) row.rate = Number(old.rate) }); this.savedSignature = this.signature(data) },
    useHistory() {
      this.rows.forEach(row => { const sample = this.calibration?.rows?.find(item => String(item.key) === String(row.key)); if (sample) row.rate = Number(sample.rate) })
      this.historyApplied = this.historyUsable
    },
    chooseHistory(id) { const record = this.history.find(item => String(item.id) === String(id)); if (record) this.restore(record) },
    async load() {
      const token = ++this.requestToken; this.loading = true; this.error = ''; this.message = ''; this.saved = null; this.savedSignature = ''; this.rows = []; this.calibration = null; this.historyApplied = false
      try {
        const [report, history, baselines, policy, calibration] = await Promise.all([activityReport(this.activity.id), listForecasts(this.activity.id), listCapacityBaselines(), getRateLimit(this.activity.id), forecastCalibration(this.activity.id)])
        if (token !== this.requestToken) return
        this.rows = (report.data || []).map(row => ({ key: row.kind === 'DIRECT' ? 'direct' : String(row.channelId), name: row.name, reservations: Number(row.reservations || 0), rate: 40, enabled: this.activity.mainEnabled !== false && row.enabled !== false }))
        this.history = history.data || []; this.baselines = baselines.data?.records || []; this.deploymentId = baselines.data?.deploymentId; this.capacityForm.deploymentId = this.deploymentId; this.policy = policy.data
        this.calibration = calibration.data
        if (this.history.length) this.restore(this.history[0])
        else this.useHistory()
      } catch (error) { if (token === this.requestToken) this.error = error?.message || '预测数据读取失败' }
      finally { if (token === this.requestToken) this.loading = false }
    },
    async save() {
      if (this.saving) return
      this.normalizeExtra(); this.saving = true; this.error = ''; this.message = ''
      if (this.pendingSignature !== this.draftSignature || !this.pendingKey) { this.pendingSignature = this.draftSignature; this.pendingKey = crypto.randomUUID() }
      try {
        const response = await saveForecast(this.activity.id, { requestKey: this.pendingKey, arrivalRates: Object.fromEntries(this.rows.map(row => [row.key, row.rate])), growthPercent: this.growth, attempts: this.attempts, scenario: this.scenario, unreservedQps: Number(this.unreservedQps) || 0, baselineId: this.baselineId || null })
        this.rows = response.data.data.rows.map(row => ({ ...row, reservations: Number(row.reservations), rate: Number(row.rate) })); this.restore(response.data); this.pendingKey = ''; this.limitKey = ''; this.history = [response.data, ...this.history.filter(row => row.id !== response.data.id)].slice(0,20); this.message = '预测已保存'; this.$refs.openingPlan?.load(); this.$emit('forecast-saved', response.data)
      } catch (error) { if (error?.code === 'A000005') this.pendingKey = ''; this.error = error?.message || '预测保存失败' }
      finally { this.saving = false }
    },
    async apply() {
      if (this.dirty || !this.saved || this.applying) return
      this.applying = true; this.error = ''; if (!this.limitKey) this.limitKey = crypto.randomUUID()
      try { await applyRateLimit(this.activity.id, { requestKey: this.limitKey, forecastId: this.saved.id }); this.policy = (await getRateLimit(this.activity.id)).data; this.message = '活动总额度与来源额度已应用到真实领券请求'; this.limitKey = '' }
      catch (error) { this.error = error?.message || '策略应用失败' }
      finally { this.applying = false }
    },
    async disable() { this.applying = true; try { await disableRateLimit(this.activity.id); this.policy = { status: 'INACTIVE' }; this.message = '限流策略已停用' } catch (error) { this.error = error?.message || '停用失败' } finally { this.applying = false } },
    async createBaseline() { this.capacitySaving = true; this.capacityError = ''; try { const response = await createCapacityBaseline({ ...this.capacityForm, measuredAt: fromLocalInput(this.capacityForm.measuredAt), expiresAt: fromLocalInput(this.capacityForm.expiresAt) }); this.baselines = (await listCapacityBaselines()).data.records; this.baselineId = String(response.data.id); this.capacityDialog = false; this.message = '压测报告已录入，请保存预测以关联该基线' } catch (error) { this.capacityError = error?.message || '基线保存失败' } finally { this.capacitySaving = false } },
    exportReport() {
      const report = { savedSnapshot: !this.dirty ? this.saved : null, activityId: this.activity.id, generatedAt: new Date().toISOString(), status: this.dirty ? '未保存的当前估算' : '已保存快照', modelVersion: 'assumptions-v1', windowSeconds: 10, growthPercent: this.growth, attempts: this.attempts, selectedScenario: this.scenario, unreservedQps: this.unreservedQps, capacityBaseline: this.baseline || null, historyReference: this.calibration, caveat: '到场率和集中系数为假设；容量来自录入报告，系统不独立验证其真实性。', ...this.estimate }
      const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], {type:'application/json'})); const anchor = document.createElement('a'); anchor.href = url; anchor.download = `activity-${this.activity.id}-forecast.json`; anchor.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    }
  }
}
</script>
