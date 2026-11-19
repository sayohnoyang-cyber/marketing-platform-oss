<template>
  <div>
    <div class="stephead"><div><h2>活动复盘</h2></div><button class="btn" :disabled="loading" @click="load">刷新数据</button></div>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="linkbtn" @click="load">重试</button></p><p v-if="loading && !board" class="loading">正在读取复盘…</p>
    <template v-if="board">
      <div class="card flow-pad"><div class="stock-grid"><div><span>短链点击</span><b>{{ formatNumber(board.funnel?.clicks ?? board.clicks ?? 0) }}</b></div><div><span>落地页到达</span><b>{{ formatNumber(board.funnel?.visits ?? board.visits ?? 0) }}</b></div><div><span>预约人数</span><b>{{ formatNumber(board.funnel?.reservations ?? 0) }}</b></div><div><span>已领取</span><b>{{ formatNumber(current.claimed) }}</b></div><div><span>已核销</span><b>{{ formatNumber(board.funnel?.redeems ?? 0) }}</b></div><div><span>领取占比</span><b>{{ current.quantity ? (Number(current.claimed || 0) / current.quantity * 100).toFixed(1) + '%' : '—' }}</b></div></div><p v-if="!board.reconciled" class="error">领取统计不一致，请核对记录。</p><div class="divider"></div><div class="checkrow"><span>核销率</span><b>{{ board.redeemRate == null ? '—' : (Number(board.redeemRate) * 100).toFixed(1) + '%' }}</b></div><div class="checkrow"><span>退款金额</span><b>{{ refundText(board) }}</b></div><p class="note">统计截止 · 北京时间 {{ clockLabel(board.generatedAt) }}。直接打开落地页不经过短链时，到达可能大于点击。</p></div>
      <div class="card tablecard section-gap"><div class="tabletop"><h2>渠道明细</h2></div><ActivityEntries :activity="current" :channels="channels" :main="main" :reports="board.channels" /></div>
      <div class="card tablecard section-gap"><div class="tabletop"><h2>全链路转化与退款</h2></div><div class="tableoverflow"><table><thead><tr><th>来源</th><th>短链点击</th><th>落地页到达</th><th>领取</th><th>核销</th><th>退款笔数</th><th>退款金额</th></tr></thead><tbody><tr v-for="item in board.channels" :key="item.kind + (item.channelId || 'direct')"><td>{{ item.name }}</td><td>{{ formatNumber(item.clicks || 0) }}</td><td>{{ formatNumber(item.visits || 0) }}</td><td>{{ formatNumber(item.claims) }}</td><td>{{ formatNumber(item.redeems) }}</td><td>{{ formatNumber(item.refunds) }}</td><td>{{ refundText(item) }}</td></tr></tbody></table></div></div>
    </template>
  </div>
</template>
<script>
import { activityReview } from '@/api/campaign'
import ActivityEntries from '../ActivityEntries.vue'
import { clockLabel, formatNumber } from '../format'
export default {
  components: { ActivityEntries },
  props: { activity: { type: Object, required: true }, channels: { type: Array, default: () => [] }, main: { type: Object, default: () => ({}) } },
  data() { return { board: null, loading: false, error: '', requestToken: 0 } },
  computed: { current() { return this.board?.activity || this.activity } },
  watch: { 'activity.id': { immediate: true, handler() { this.board = null; this.load() } } },
  methods: { clockLabel, formatNumber,
    refundText(row) {
      const missing = Number(row.unreportedRefunds || 0)
      const known = Number(row.knownRefundAmount ?? row.refundAmount ?? 0)
      if (missing) return `${known ? '¥' + known.toFixed(2) + ' · ' : ''}${missing}笔待补录`
      return row.refundAmount == null ? '未采集' : `¥${Number(row.refundAmount).toFixed(2)}`
    }, async load() { const token = ++this.requestToken; this.loading = true; this.error = ''; try { const response = await activityReview(this.activity.id); if (token === this.requestToken) this.board = response.data } catch (error) { if (token === this.requestToken) this.error = error?.message || '复盘读取失败' } finally { if (token === this.requestToken) this.loading = false } } }
}
</script>
