<template>
  <div class="tableoverflow">
    <table class="entry-table">
      <thead><tr><th>来源</th><th>入口链接</th><th>短链点击</th><th>落地页到达 / 预约</th><th>领取</th><th>当前状态</th><th>操作</th></tr></thead>
      <tbody><tr v-for="item in rows" :key="item.key">
        <td><strong>{{ item.name }}</strong></td>
        <td><EntryLink :url="item.url">{{ item.url }}</EntryLink></td>
        <td>{{ item.hasShortLink ? formatNumber(item.stats.clicks || 0) : '—' }}</td>
        <td>{{ formatNumber(item.stats.visits) }} / {{ formatNumber(item.stats.reservations) }}</td>
        <td>{{ formatNumber(item.stats.claims) }}</td>
        <td><span class="pill" :class="{ amber: !item.enabled }">{{ page(item) }}</span></td>
        <td><div class="row-actions"><button class="linkbtn" :disabled="!item.url" @click="copy(item.url)">复制链接</button><router-link class="linkbtn" :to="`/preview?activity=${activity.id}&source=${item.source}`">预览此入口</router-link><button v-if="editable && item.toggleable" class="linkbtn" :disabled="busy || !item.url" @click="$emit('toggle', item.channel)">{{ item.enabled ? '停用' : '启用' }}</button></div></td>
      </tr></tbody>
    </table>
    <p v-if="notice" :class="failed ? 'error' : 'flow-message'" role="status">{{ notice }}</p>
  </div>
</template>

<script>
import EntryLink from '@/components/EntryLink.vue'
import { copyText } from '@/lib/clipboard'
import { formatNumber } from './format'
export default {
  components: { EntryLink },
  props: { activity: { type: Object, required: true }, channels: { type: Array, default: () => [] }, main: { type: Object, default: () => ({}) }, reports: { type: Array, default: () => [] }, editable: Boolean, busy: Boolean },
  emits: ['toggle'],
  data() { return { notice: '', failed: false } },
  computed: {
    directUrl() {
      const origin = typeof window !== 'undefined' ? window.location.origin : ''
      return `${origin}/a/${this.activity.id}`
    },
    rows() {
      const hasMain = Boolean(this.main.fullShortUrl)
      // 未生成主短链时直接使用直达页链接；已生成主短链时展示主短链以便管理短链启停
      const direct = {
        key: 'direct',
        name: hasMain ? '直接访问（直达页 / 主短链）' : '直接访问（活动直达页）',
        source: 'direct',
        url: hasMain ? this.main.fullShortUrl : this.directUrl,
        hasShortLink: hasMain,
        toggleable: hasMain,
        enabled: hasMain ? Boolean(this.main.enabled) : true,
        channel: null,
        stats: this.reports.find(row => row.kind === 'DIRECT') || {}
      }
      return [direct, ...this.channels.map(channel => ({
        key: channel.id,
        name: channel.name,
        source: channel.channelCode,
        url: hasMain ? `${this.main.fullShortUrl}?c=${encodeURIComponent(channel.channelCode)}` : `${this.directUrl}?c=${encodeURIComponent(channel.channelCode)}`,
        hasShortLink: hasMain,
        toggleable: true,
        enabled: channel.status === 1,
        channel,
        stats: this.reports.find(row => String(row.channelId) === String(channel.id)) || {}
      }))]
    }
  },
  methods: {
    formatNumber,
    async copy(url) {
      this.failed = false
      try { await copyText(url); this.notice = '入口链接已复制' }
      catch { this.failed = true; this.notice = '复制失败，请手动复制入口地址' }
    },
    page(item) {
      if (item.source === 'direct' && this.main.fullShortUrl && !this.main.enabled) return '主链已停用（直达可用）'
      if (item.source !== 'direct' && this.main.fullShortUrl && !this.main.enabled) return '主链已停用'
      if (!item.enabled) return '渠道已停用'
      return { RESERVE: '预约页', CLAIMABLE: '领券页', SOLD_OUT: '已领完', ENDED: '已结束', DRAFT: '草稿页', UNAVAILABLE: '暂不可用' }[this.activity.entryState] || '暂不可用'
    }
  }
}
</script>
