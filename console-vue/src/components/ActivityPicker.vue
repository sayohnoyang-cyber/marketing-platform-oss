<template>
  <div class="activity-picker">
    <button type="button" class="btn picker-trigger" :aria-label="label" :aria-expanded="opened" @click="toggle">{{ modelValue?.name || label }} <span>⌄</span></button>
    <div v-if="opened" class="picker-panel" @keydown.esc="close">
      <div class="picker-search"><input v-model.trim="keyword" :aria-label="`${label}名称`" placeholder="搜索活动名称" maxlength="128" @keydown.enter.prevent.stop="search" /><button type="button" class="linkbtn" @click="search">搜索</button></div>
      <button type="button" class="picker-option" @click="choose(null)">{{ clearLabel }}</button>
      <p v-if="error" class="error" role="alert">{{ error }} <button type="button" class="linkbtn" @click="search">重试</button></p>
      <button v-for="item in records" :key="item.id" class="picker-option" type="button" :class="{ selected: String(item.id) === String(modelValue?.id) }" @click="choose(item)">{{ item.name }}</button>
      <p v-if="!loading && !error && !records.length" class="note">没有匹配的活动</p>
      <button v-if="hasMore" class="linkbtn" type="button" :disabled="loading" @click="load(page + 1)">加载更多</button>
      <p v-if="loading" class="note" role="status">正在读取活动…</p>
    </div>
  </div>
</template>
<script>
import { activityOptions } from '@/api/link'
export default {
  props: { modelValue: Object, label: { type: String, default: '选择活动' }, clearLabel: { type: String, default: '全部活动' } },
  emits: ['update:modelValue'],
  data() { return { opened: false, keyword: '', records: [], page: 0, hasMore: false, loading: false, error: '', requestToken: 0 } },
  mounted() { document.addEventListener('click', this.outside) },
  beforeUnmount() { this.requestToken++; document.removeEventListener('click', this.outside) },
  methods: {
    toggle() { this.opened = !this.opened; if (this.opened) this.search() },
    close() { this.opened = false },
    outside(event) { if (!this.$el.contains(event.target)) this.close() },
    choose(item) { this.$emit('update:modelValue', item); this.close() },
    search() { this.load(1) },
    async load(page) {
      const token = ++this.requestToken
      this.loading = true; this.error = ''
      // 搜索时立即清除旧选项，防止把旧结果误认为当前搜索结果。
      if (page === 1) { this.records = []; this.hasMore = false; this.page = 0 }
      try {
        const response = await activityOptions({ keyword: this.keyword, page })
        if (token !== this.requestToken) return
        const rows = response.data?.records || []
        this.records = page === 1 ? rows : [...this.records, ...rows]
        this.page = Number(response.data.page); this.hasMore = Boolean(response.data.hasMore)
      } catch (error) { if (token === this.requestToken) this.error = error?.message || '活动读取失败' }
      finally { if (token === this.requestToken) this.loading = false }
    }
  }
}
</script>
<style scoped>
.activity-picker { position: relative; min-width: 0; }.picker-trigger { max-width: 100%; overflow-wrap: anywhere; text-align: left; }.picker-trigger span { margin-left: 12px; }
.picker-panel { position: absolute; z-index: 30; top: calc(100% + 8px); left: 0; width: min(310px,calc(100vw - 64px)); max-height: 360px; overflow-y: auto; padding: 12px; background: var(--bg); border: 1px solid var(--line); border-radius: 12px; box-shadow: 0 12px 30px #14141312; }
.picker-search { display: flex; gap: 8px; margin-bottom: 8px; }.picker-panel input { min-width: 0; width: 100%; border: 1px solid var(--line); border-radius: 8px; padding: 8px; background: var(--bg); color: var(--ink); }.picker-option { display: block; width: 100%; text-align: left; padding: 9px; border-radius: 6px; overflow-wrap: anywhere; }.picker-option:hover,.picker-option.selected { background: var(--tint); color: var(--clay); }
</style>
