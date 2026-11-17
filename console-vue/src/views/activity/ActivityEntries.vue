<template>
  <div class="tableoverflow">
    <table class="entry-table">
      <thead><tr><th>来源</th><th>入口</th><th>访问 / 预约</th><th>领取</th><th>当前页面</th><th>操作</th></tr></thead>
      <tbody><tr v-for="item in rows" :key="item.key">
        <td><strong>{{ item.name }}</strong></td>
        <td><EntryLink :url="item.url">{{ item.url || '主链未生成' }}</EntryLink></td>
        <td>{{ formatNumber(item.stats.visits) }} / {{ formatNumber(item.stats.reservations) }}</td>
        <td>{{ formatNumber(item.stats.claims) }}</td>
        <td><span class="pill" :class="{ amber: !item.enabled }">{{ page(item) }}</span></td>
        <td><div class="row-actions"><button class="linkbtn" :disabled="!item.url" @click="copy(item.url)">复制链接</button><router-link class="linkbtn" :to="`/preview?activity=${activity.id}&source=${item.source}`">预览此入口</router-link><button v-if="editable" class="linkbtn" :disabled="busy || !item.url" @click="$emit('toggle', item.channel)">{{ item.enabled ? '停用' : '启用' }}</button></div></td>
      </tr></tbody>
    </table>
    <p v-if="notice" :class="failed ? 'error' : 'flow-message'" role="status">{{ notice }}</p>
  </div>
</template>

<script>
import EntryLink from '@/components/EntryLink.vue'
import { formatNumber } from './format'
export default {
  components: { EntryLink },
  props: { activity: { type: Object, required: true }, channels: { type: Array, default: () => [] }, main: { type: Object, default: () => ({}) }, reports: { type: Array, default: () => [] }, editable: Boolean, busy: Boolean },
  emits: ['toggle'],
  data() { return { notice: '', failed: false } },
  computed: {
    rows() {
      const direct = { key: 'direct', name: '主链 · 直接访问', source: 'direct', url: this.main.fullShortUrl, enabled: Boolean(this.main.enabled), channel: null, stats: this.reports.find(row => row.kind === 'DIRECT') || {} }
      return [direct, ...this.channels.map(channel => ({ key: channel.id, name: channel.name, source: channel.channelCode, url: this.main.fullShortUrl ? `${this.main.fullShortUrl}?c=${encodeURIComponent(channel.channelCode)}` : '', enabled: channel.status === 1, channel, stats: this.reports.find(row => String(row.channelId) === String(channel.id)) || {} }))]
    }
  },
  methods: {
    formatNumber,
    async copy(url) {
      this.failed = false
      try { await navigator.clipboard.writeText(url); this.notice = '入口链接已复制' }
      catch { this.failed = true; this.notice = '复制失败，请手动复制入口地址' }
    },
    page(item) {
      if (!this.main.fullShortUrl) return '主链未生成'
      if (!this.main.enabled) return '主链已停用'
      if (!item.enabled) return '渠道已停用'
      return { RESERVE: '预约页', CLAIMABLE: '领券页', SOLD_OUT: '已领完', ENDED: '已结束', DRAFT: '草稿页', UNAVAILABLE: '暂不可用' }[this.activity.entryState] || '暂不可用'
    }
  }
}
</script>
