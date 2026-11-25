<template>
  <!-- 实时与复盘整合视图：同时承接开场监控与活动后五级漏斗复盘，避免来回切换标签页。 -->
  <div>
    <div class="stephead">
      <div><h2>实时数据与转化复盘</h2></div>
      <div class="actions">
        <router-link class="btn" :to="`/preview?activity=${activity.id}`">预览用户页面</router-link>
        <button class="btn" :disabled="loading" @click="load">{{ loading ? '更新中…' : '刷新数据' }}</button>
      </div>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="linkbtn" @click="load">重试</button></p>
    <p v-if="loading && !board" class="loading">正在读取实时与复盘数据…</p>
    <template v-if="board">
      <div class="flow-demo">
        <div><b>统计截止 · 北京时间 {{ clockLabel(board.generatedAt) }}</b></div>
        <span class="pill">{{ phaseText(displayPhase(current)) }}</span>
      </div>
      <div class="stats">
        <div class="card stat"><div class="statlabel">投放总量</div><div class="value">{{ formatNumber(current.quantity) }}</div></div>
        <div class="card stat"><div class="statlabel">已领取</div><div class="value">{{ formatNumber(current.claimed) }}</div></div>
        <div class="card stat"><div class="statlabel">剩余可领取</div><div class="value">{{ formatNumber(leftOf(current)) }}</div></div>
        <div class="card stat"><div class="statlabel">净核销率</div><div class="value">{{ board.redeemRate == null ? '—' : (Number(board.redeemRate) * 100).toFixed(1) + '%' }}</div></div>
      </div>

      <div class="card flow-pad">
        <div class="sectionhead flush"><h2>五级转化漏斗</h2><span class="pill">截止 {{ clockLabel(board.generatedAt) }}</span></div>
        <div class="stock-grid">
          <div><span>短链点击</span><b>{{ formatNumber(board.funnel?.clicks ?? board.clicks ?? 0) }}</b></div>
          <div><span>落地页到达</span><b>{{ formatNumber(board.funnel?.visits ?? board.visits ?? 0) }}</b></div>
          <div><span>预约人数</span><b>{{ formatNumber(board.funnel?.reservations ?? 0) }}</b></div>
          <div><span>已领取</span><b>{{ formatNumber(current.claimed) }}</b></div>
          <div><span>当前已核销</span><b>{{ formatNumber(board.funnel?.netRedeemed ?? board.funnel?.redeems ?? 0) }}</b></div>
          <div><span>领取占比</span><b>{{ current.quantity ? (Number(current.claimed || 0) / current.quantity * 100).toFixed(1) + '%' : '—' }}</b></div>
        </div>
        <p v-if="!board.reconciled" class="error">领取统计不一致，请核对记录。</p>
        <div class="divider"></div>
        <div class="checkrow"><span>核销笔数 / 净核销券数</span><b>{{ formatNumber(board.funnel?.redeems ?? 0) }} 笔 / {{ formatNumber(board.funnel?.netRedeemed ?? board.funnel?.redeems ?? 0) }} 张</b></div>
        <div class="checkrow"><span>退款金额</span><b>{{ refundText(board) }}</b></div>
        <p class="note">{{ board.redeemRateNote || '直接打开活动直达页不经过短链时，落地页到达可能大于短链点击。' }}</p>
      </div>

      <!-- 合并展示各入口与渠道的访问、领取、核销及退款明细，消除重复表格 -->
      <div class="card tablecard section-gap">
        <div class="tabletop"><h2>各入口与渠道全链路明细</h2></div>
        <p v-if="copyNotice" :class="copyFailed ? 'error' : 'flow-message'" role="status">{{ copyNotice }}</p>
        <div class="tableoverflow">
          <table class="entry-table">
            <thead><tr><th>来源</th><th>入口链接</th><th>短链点击</th><th>落地页到达 / 预约</th><th>领取</th><th>核销</th><th>退款笔数</th><th>退款金额</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="item in channelRows" :key="item.key">
                <td><strong>{{ item.name }}</strong></td>
                <td><EntryLink :url="item.url">{{ item.url }}</EntryLink></td>
                <td>{{ item.hasShortLink ? formatNumber(item.clicks || 0) : '—' }}</td>
                <td>{{ formatNumber(item.visits || 0) }} / {{ formatNumber(item.reservations || 0) }}</td>
                <td>{{ formatNumber(item.claims) }}</td>
                <td>{{ formatNumber(item.redeems) }}</td>
                <td>{{ formatNumber(item.refunds) }}</td>
                <td>{{ refundText(item) }}</td>
                <td>
                  <div class="row-actions">
                    <button class="linkbtn" type="button" :disabled="!item.url" @click="copyUrl(item.url)">复制链接</button>
                    <router-link class="linkbtn" :to="`/preview?activity=${current.id}&source=${item.source}`">预览此入口</router-link>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import EntryLink from '@/components/EntryLink.vue'
import { copyText } from '@/lib/clipboard'
import { activityReview } from '@/api/campaign'
import { clockLabel, displayPhase, formatNumber, leftOf, phaseText } from '../format'

export default {
  components: { EntryLink },
  props: { activity: { type: Object, required: true }, channels: { type: Array, default: () => [] }, main: { type: Object, default: () => ({}) } },
  emits: ['snapshot'],
  data() { return { board: null, loading: false, error: '', timer: 0, requestToken: 0, copyNotice: '', copyFailed: false } },
  computed: {
    current() { return this.board?.activity || this.activity },
    // 将渠道统计与入口地址拼装为单表数据，直接访问排在首行
    channelRows() {
      const origin = typeof window !== 'undefined' ? window.location.origin : ''
      const directUrl = `${origin}/a/${this.current.id}`
      const hasMain = Boolean(this.main?.fullShortUrl)
      const baseUrl = hasMain ? this.main.fullShortUrl : directUrl
      return (this.board?.channels || []).map((row) => {
        const isDirect = row.kind === 'DIRECT'
        const channel = isDirect ? null : this.channels.find((ch) => String(ch.id) === String(row.channelId))
        const source = isDirect ? 'direct' : (channel?.channelCode || '')
        const url = isDirect ? baseUrl : (source ? `${baseUrl}?c=${encodeURIComponent(source)}` : '')
        return {
          ...row,
          key: row.kind + (row.channelId || 'direct'),
          source,
          url,
          hasShortLink: hasMain
        }
      })
    }
  },
  watch: { 'activity.id': { immediate: true, handler() { this.board = null; this.load() } } },
  mounted() {
    // 活动处于预热或进行中时每 10 秒自动刷新（页面隐藏时暂停）
    this.timer = window.setInterval(() => {
      if (!document.hidden && !this.loading && ['LIVE', 'WARMUP'].includes(this.current?.phase)) this.load()
    }, 10000)
    document.addEventListener('visibilitychange', this.onVisibility)
  },
  beforeUnmount() {
    window.clearInterval(this.timer)
    this.requestToken++
    document.removeEventListener('visibilitychange', this.onVisibility)
  },
  methods: {
    clockLabel, displayPhase, formatNumber, leftOf, phaseText,
    onVisibility() { if (!document.hidden && !this.loading) this.load() },
    async copyUrl(url) {
      if (!url) return
      this.copyFailed = false
      try { await copyText(url); this.copyNotice = '入口链接已复制' }
      catch { this.copyFailed = true; this.copyNotice = '复制失败，请手动复制入口地址' }
    },
    refundText(row) {
      const missing = Number(row.unreportedRefunds || 0)
      const known = Number(row.knownRefundAmount ?? row.refundAmount ?? 0)
      if (missing) return `${known ? '¥' + known.toFixed(2) + ' · ' : ''}${missing}笔待补录`
      return row.refundAmount == null ? '未采集' : `¥${Number(row.refundAmount).toFixed(2)}`
    },
    async load() {
      const token = ++this.requestToken
      this.loading = true
      this.error = ''
      try {
        const response = await activityReview(this.activity.id)
        if (token !== this.requestToken) return
        this.board = response.data
        this.$emit('snapshot', this.board.activity)
      } catch (error) {
        if (token === this.requestToken) this.error = error?.message || '实时与复盘数据读取失败'
      } finally {
        if (token === this.requestToken) this.loading = false
      }
    }
  }
}
</script>
