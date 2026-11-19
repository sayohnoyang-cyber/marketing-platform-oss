<template>
  <section class="link-management">
    <div class="pagehead">
      <div>
        <h1>短链管理</h1>
      </div>
      <button class="btn primary" type="button" @click="openCreate">创建短链</button>
    </div>
    <p v-if="notice" class="flow-message" role="status">{{ notice }}</p>
    <p v-if="error" class="error">{{ error }} <button class="btn" type="button" @click="load">重试</button></p>
    <DotStage ref="linkStage" class="link-stage" :offset-right="210" :palette="[[82, 126, 168], [198, 97, 63]]">
      <div class="link-aura link-aura-1" aria-hidden="true"></div>
      <div class="link-aura link-aura-2" aria-hidden="true"></div>
      <div class="link-total">
        <span :title="statsDefinition">全部短链有效访问</span>
        <b>{{ formatNumber(summary?.visits) }}</b>
        <small><i></i>实时汇聚</small>
      </div>
      <div class="link-bars">
        <div class="link-stack"><i v-for="(segment,index) in (summary?.segments || [])" :key="segment.id" :style="{ width: `${Number(summary?.visits) ? segment.value / Number(summary.visits) * 100 : 0}%`, background: tones[index % tones.length] }"></i></div>
        <div class="link-legend"><span v-for="(segment,index) in (summary?.segments || [])" :key="segment.id"><u :style="{ background: tones[index % tones.length] }"></u>{{ segment.name }} {{ formatNumber(segment.value) }}</span></div>
      </div>
      <div class="link-right">
        <div class="link-orbit" aria-hidden="true">
          <svg viewBox="0 0 150 150" fill="none">
            <g class="ring-a"><circle cx="75" cy="75" r="46" stroke="#b9c6d3" /><circle cx="75" cy="29" r="3.5" fill="#527ea8" /></g>
            <g class="ring-b"><circle cx="75" cy="75" r="66" stroke="#c6613f" stroke-opacity=".45" stroke-dasharray="2 5" /><circle cx="75" cy="9" r="3" fill="#5b7040" /></g>
          </svg>
          <div class="link-seal">链</div>
        </div>
        <div class="link-counts">
          <div style="--c: #527ea8"><b>{{ formatNumber(summary?.total) }}</b><span>短链总数</span></div>
          <div style="--c: #5b7040"><b>{{ formatNumber(summary?.enabled) }}</b><span>生效中</span></div>
        </div>
      </div>
    </DotStage>
    <div class="card">
      <div class="tabletop">
        <div class="seg flow-filters" aria-label="按活动筛选短链">
          <button type="button" :class="{ active: filter === 'ALL' }" @click="filter = 'ALL'; selectedActivity = null">全部</button>
          <button type="button" :class="{ active: filter === 'PLAIN' }" @click="filter = 'PLAIN'; selectedActivity = null">未归属活动</button>
          <ActivityPicker :model-value="selectedActivity" @update:model-value="selectActivity" />
        </div>
        <form class="link-search" @submit.prevent="loadPage(1)"><input v-model.trim="keyword" aria-label="搜索短链或活动" placeholder="搜索名称或短链" maxlength="128" /><button class="btn" type="submit">搜索</button></form>
        <select v-model="stateFilter" aria-label="筛选入口状态"><option value="ALL">全部状态</option><option value="ACTIVE">生效中</option><option value="INACTIVE">未生效</option></select>
      </div>
      <p v-if="loading" class="loading">正在读取短链…</p>
      <EmptyState v-else-if="!rows.length" :title="hasFilters ? '没有匹配的短链' : '暂无短链'"><button v-if="hasFilters" class="btn" @click="clearFilters">清除筛选</button><button v-else class="btn primary" @click="openCreate">创建短链</button></EmptyState>
      <div v-else class="tableoverflow">
          <table class="linktable">
          <thead><tr><th>名称 / 短链</th><th>归属活动</th><th>类型</th><th>入口访问</th><th>当前状态或页面</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in rows" :key="item.key">
              <td>
                <strong>{{ item.name }}</strong>
                <small><EntryLink :url="item.url">{{ readable(item.url) || '未生成' }}</EntryLink></small>
                <small v-if="item.kind === '普通短链'"><EntryLink :url="item.origin">{{ readable(item.origin) }}</EntryLink></small>
              </td>
              <td>
                <router-link v-if="item.activityId" class="linkbtn" :to="`/activities/${item.activityId}/link`">{{ item.activityName }}</router-link>
                <span v-else>不归属活动</span>
              </td>
              <td>{{ item.kind }}</td>
              <td>{{ formatNumber(item.visits) }}</td>
              <td><span class="pill">{{ item.page }}</span></td>
              <td><div class="row-actions">
                <button class="linkbtn copy-link" type="button" :disabled="!item.url || copyingUrl === item.url" @click="copy(item.url)">{{ copyingUrl === item.url ? '复制中…' : copiedUrl === item.url ? '已复制' : '复制' }}</button>
                <router-link v-if="item.kind !== '普通短链'" class="linkbtn" :to="`/preview?activity=${item.activityId}&source=${item.source}`">预览入口</router-link>
                <button v-else class="linkbtn" type="button" @click="editPlain(item)">改地址</button>
                <button class="linkbtn" type="button" @click="requestToggle(item)">{{ item.on ? '停用' : '启用' }}</button>
              </div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="link-pagination"><span>共 {{ total }} 条</span><div class="actions"><select v-model.number="pageSize" aria-label="每页条数"><option :value="20">20 条 / 页</option><option :value="50">50 条 / 页</option><option :value="100">100 条 / 页</option></select><button class="btn" :disabled="loading || page <= 1" @click="loadPage(page - 1)">上一页</button><span>{{ page }} / {{ pages }}</span><button class="btn" :disabled="loading || page >= pages" @click="loadPage(page + 1)">下一页</button></div></div>
    </div>
    <StudioModal v-if="show" :title="createdLink ? '短链已生成' : '创建短链'" :busy="saving" @close="show = false">
      <template v-if="createdLink">
        <div class="created-link"><EntryLink :url="createdLink.fullShortUrl" /></div>
        <div class="actions modal-actions"><button class="btn" @click="openCreate">继续创建</button><button class="btn primary" :disabled="Boolean(copyingUrl)" @click="copyCreated">{{ copyingUrl ? '复制中…' : createdCopied ? '已复制' : '复制短链' }}</button></div>
      </template>
      <form v-else @submit.prevent="createPlain">
        <label class="field">短链名称<input v-model.trim="linkName" required maxlength="128" /></label>
        <label class="field">原始链接<input v-model.trim="originUrl" type="url" required placeholder="https://" /></label>
        <!-- 选择器里还有搜索框和选项按钮，不能包在 label 里，否则点标题只会激活第一个按钮。 -->
        <div class="field">归属活动（选填）<ActivityPicker v-model="createActivity" label="归属活动" clear-label="不归属活动" /></div>
        <p v-if="formError" class="form-alert" role="alert">{{ formError }}</p>
        <div class="actions modal-actions"><button class="btn" type="button" :disabled="saving" @click="show = false">取消</button><button class="btn primary" :disabled="saving">{{ saving ? '生成中…' : '生成短链' }}</button></div>
      </form>
    </StudioModal>
    <StudioModal v-if="editing" title="修改原始链接" :busy="saving" @close="editing = null">
      <form @submit.prevent="saveOrigin"><label class="field">新的原始链接<input v-model.trim="editOrigin" type="url" required /></label><p class="note entry-url">当前：{{ editing.origin }}</p><p v-if="formError" class="form-alert" role="alert">{{ formError }}</p><div class="actions modal-actions"><button class="btn" type="button" :disabled="saving" @click="editing = null">取消</button><button class="btn primary" :disabled="saving">确认修改</button></div></form>
    </StudioModal>
    <StudioModal v-if="stopping" title="确认停用？" :busy="saving" @close="stopping = null"><p class="note">{{ stopping.kind === '主链' ? '主链停用后，这场活动的所有渠道入口也将失效。已领取的券不受影响。' : '停用后，新的访问无法从这条入口进入。' }}</p><p v-if="formError" class="form-alert">{{ formError }}</p><div class="actions modal-actions"><button class="btn" :disabled="saving" @click="stopping = null">取消</button><button class="btn primary" :disabled="saving" @click="toggle(stopping)">确认停用</button></div></StudioModal>
    <StudioModal v-if="manualCopyUrl" title="手动复制链接" @close="manualCopyUrl = ''"><label class="field">短链地址<input :value="manualCopyUrl" readonly @focus="$event.target.select()" /></label></StudioModal>
    <Teleport to="body"><div v-if="copyMessage" class="copy-toast" role="status" aria-live="polite">{{ copyMessage }}</div></Teleport>
  </section>
</template>

<script>
import EntryLink from '@/components/EntryLink.vue'
import EmptyState from '@/components/EmptyState.vue'
import StudioModal from '@/components/StudioModal.vue'
import DotStage from '@/components/DotStage.vue'
import { copyText } from '@/lib/clipboard'
import { setChannelEnabled, setMainEnabled } from '@/api/campaign'
import { createLink, updateLink, listLinkDirectory } from '@/api/link'
import { formatNumber } from '@/views/activity/format'

import ActivityPicker from '@/components/ActivityPicker.vue'
import { requestId } from '@/lib/requestId.mjs'
import { linkRows } from './linkRows.mjs'

export default {
  components: { EntryLink, EmptyState, StudioModal, ActivityPicker, DotStage },
  data() {
    return {
      summary: null, statsDefinition: '', tones: ['#c6613f','#788c5d','#6a9bcc','#c8a26b','#8a7fb0'], rows: [], filter: 'ALL', selectedActivity: null, keyword: '', stateFilter: 'ALL', page: 1, pageSize: 20, total: 0, requestToken: 0,
      copyingUrl: '', copiedUrl: '', copyMessage: '', copyTimer: null, manualCopyUrl: '',
      createdLink: null, createdCopied: false, linkName: '', createActivity: null, editing: null, stopping: null, editOrigin: '', notice: '', formError: '', pendingKey: '', pendingSignature: '', show: false, originUrl: '', loading: false, saving: false, error: ''
    }
  },
  computed: {
    hasFilters() { return Boolean(this.keyword || this.filter !== 'ALL' || this.stateFilter !== 'ALL') },
    pages() { return Math.max(1, Math.ceil(this.total / this.pageSize)) },
    filterSignature() { return JSON.stringify([this.filter, this.stateFilter, this.pageSize]) }
  },
  watch: { filterSignature() { this.loadPage(1) } },
  created() {
    this.load()
  },
  beforeUnmount() { this.requestToken++; clearTimeout(this.copyTimer) },
  methods: {
    formatNumber,
    shortName(name) { return String(name || '').split(' · ')[0] },
    readable(value) {
      if (!value) return ''
      try {
        return decodeURIComponent(value)
      } catch {
        return value
      }
    },
    async copy(url) {
      if (!url || this.copyingUrl) return false
      clearTimeout(this.copyTimer)
      this.copyingUrl = url; this.copiedUrl = ''; this.createdCopied = false; this.copyMessage = ''
      try {
        await copyText(url)
        this.copiedUrl = url; this.copyMessage = '链接已复制'
        return true
      } catch {
        this.copyMessage = '未能自动复制，请手动复制'
        this.manualCopyUrl = url
        return false
      } finally {
        this.copyingUrl = ''
        this.copyTimer = setTimeout(() => { this.copyMessage = ''; this.copiedUrl = ''; this.createdCopied = false }, 2500)
      }
    },
    clearFilters() {
      const selectionChanged = this.filter !== 'ALL' || this.stateFilter !== 'ALL'
      this.keyword = ''; this.selectedActivity = null; this.filter = 'ALL'; this.stateFilter = 'ALL'
      if (!selectionChanged) this.loadPage(1)
    },
    selectActivity(item) { this.selectedActivity = item; this.filter = item ? String(item.id) : 'ALL' },
    load() { return this.loadPage(this.page) },
    async loadPage(page = 1) {
      const token = ++this.requestToken
      this.loading = true; this.error = ''
      try {
        const response = await listLinkDirectory({ page, size: this.pageSize, keyword: this.keyword,
          activityId: ['ALL', 'PLAIN'].includes(this.filter) ? undefined : this.filter,
          unassigned: this.filter === 'PLAIN', state: this.stateFilter })
        if (token !== this.requestToken) return
        const data = response.data
        this.rows = linkRows(data.records || [])
        this.page = Number(data.page); this.total = Number(data.total); this.pageSize = Number(data.size)
        this.summary = data.summary; this.statsDefinition = data.definition || ''
      } catch (error) {
        if (token === this.requestToken) { this.rows = []; this.total = 0; this.error = error?.message || '短链读取失败' }
      } finally { if (token === this.requestToken) this.loading = false }
    },
    async toggle(item) {
      if (this.saving) return
      this.error = ''; this.formError = ''; this.saving = true
      try {
        if (item.channelId) await setChannelEnabled(item.activityId, item.channelId, !item.on)
        else if (item.kind === '主链') await setMainEnabled(item.activityId, !item.on)
        else await updateLink(item.linkId, { enableStatus: item.on ? 1 : 0, validDate: item.validDate })
        this.stopping = null; this.notice = '入口状态已更新'; await this.load()
      } catch (error) {
        this.formError = this.error = error?.message || '状态更新失败'
      } finally { this.saving = false }
    },
    requestToggle(item) { this.formError = ''; if (item.on) this.stopping = item; else this.toggle(item) },
    editPlain(item) { this.editing = item; this.editOrigin = item.origin; this.formError = '' },
    async saveOrigin() {
      this.saving = true; this.formError = ''
      try { const url = new URL(this.editOrigin); if (!['http:', 'https:'].includes(url.protocol)) throw new Error('网址须以 http:// 或 https:// 开头'); await updateLink(this.editing.linkId, { originUrl: this.editOrigin, validDate: this.editing.validDate }); this.editing = null; this.notice = '目标地址已修改，短码保持不变'; await this.load() }
      catch (error) { this.formError = error?.message || '地址更新失败' }
      finally { this.saving = false }
    },
    openCreate() {
      this.createdLink = null; this.createdCopied = false; this.formError = ''
      this.createActivity = this.selectedActivity
      this.show = true
    },
    async copyCreated() { this.createdCopied = await this.copy(this.createdLink.fullShortUrl) },
    async createPlain() {
      if (this.saving) return
      this.saving = true; this.formError = ''
      try {
        const url = new URL(this.originUrl)
        if (!['http:', 'https:'].includes(url.protocol)) throw new Error('原始链接须以 http:// 或 https:// 开头')
        const signature = JSON.stringify([this.createActivity?.id, this.originUrl, this.linkName])
        if (signature !== this.pendingSignature || !this.pendingKey) { this.pendingSignature = signature; this.pendingKey = requestId() }
        const response = await createLink({ originUrl: this.originUrl, name: this.linkName, activityId: this.createActivity?.id || null, idempotencyKey: this.pendingKey })
        this.createdLink = response.data; this.createdCopied = false
        this.originUrl = ''; this.linkName = ''; this.pendingKey = ''; this.notice = ''
        await this.load()
      } catch (error) {
        this.formError = error?.message || '短链创建失败'
      } finally { this.saving = false }
    }
  }
}
</script>

<style scoped>
.link-stage {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 32px;
  align-items: center;
  padding: 26px 30px;
  margin-bottom: 20px;
  border-radius: 20px;
  background: linear-gradient(120deg, #eff2f5 0%, #f0eee6 52%, #f5eee9 100%);
  border: 1px solid var(--line);
  min-height: 152px;
}
.link-stage::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background: linear-gradient(90deg, #eff2f5e6 0%, #f0eee670 50%, #f5eee9d0 100%);
}
.link-aura { position: absolute; border-radius: 50%; filter: blur(42px); z-index: -3; pointer-events: none; opacity: .55; }
.link-aura-1 { width: 280px; height: 220px; left: -40px; top: -60px; background: radial-gradient(circle, #527ea838, transparent 70%); }
.link-aura-2 { width: 280px; height: 220px; right: 12%; bottom: -80px; background: radial-gradient(circle, #c6613f30, transparent 70%); }
.link-total span { display: block; font-size: 12px; color: var(--muted); }
.link-total b { display: block; font-family: var(--serif); font-size: 44px; font-weight: 500; line-height: 1.1; margin-top: 4px; font-variant-numeric: tabular-nums; }
.link-total small { display: inline-flex; align-items: center; gap: 5px; margin-top: 6px; font-size: 11px; color: #527ea8; font-weight: 500; }
.link-total small i { width: 6px; height: 6px; border-radius: 50%; background: #527ea8; animation: link-blink 1.6s ease-in-out infinite; }
.link-bars { position: relative; z-index: 2; min-width: 0; }
.link-stack { display: flex; height: 11px; border-radius: 999px; background: #fff; border: 1px solid var(--line); overflow: hidden; padding: 1.5px; gap: 2px; }
.link-stack i { display: block; height: 100%; border-radius: 999px; transition: width .8s cubic-bezier(.2, .7, .2, 1); }
.link-legend { display: flex; flex-wrap: wrap; gap: 8px 10px; margin-top: 14px; font-size: 12px; color: var(--ink2); }
.link-legend span { display: inline-flex; align-items: center; gap: 6px; padding: 3px 10px; border-radius: 999px; background: #ffffffd6; border: 1px solid var(--line); backdrop-filter: blur(4px); }
.link-legend u { display: inline-block; width: 7px; height: 7px; border-radius: 50%; text-decoration: none; }
.link-right { position: relative; display: flex; align-items: center; gap: 22px; padding-left: 12px; }
.link-orbit { position: relative; width: 118px; height: 118px; display: grid; place-items: center; flex-shrink: 0; }
.link-orbit svg { position: absolute; inset: -16px; width: 150px; height: 150px; pointer-events: none; }
.link-orbit .ring-a { transform-origin: 75px 75px; animation: link-turn 38s linear infinite; }
.link-orbit .ring-b { transform-origin: 75px 75px; animation: link-turn 26s linear infinite reverse; }
.link-seal { position: relative; width: 44px; height: 44px; border-radius: 14px; background: linear-gradient(135deg, #6591bc, #527ea8); color: #fff; display: grid; place-items: center; font-family: var(--serif); font-size: 22px; box-shadow: 0 12px 26px -8px #527ea888; }
.link-seal::before { content: ''; position: absolute; inset: -1px; border-radius: inherit; border: 1.5px solid #527ea8; animation: link-halo 3s ease-out infinite; }
.link-counts { display: grid; gap: 10px; position: relative; z-index: 2; }
.link-counts div { position: relative; overflow: hidden; background: #ffffffe0; border: 1px solid var(--line); border-radius: 12px; padding: 8px 14px 8px 16px; min-width: 100px; backdrop-filter: blur(6px); }
.link-counts div::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--c, #527ea8); }
.link-counts b { display: block; font-family: var(--serif); font-size: 22px; font-weight: 500; line-height: 1.15; font-variant-numeric: tabular-nums; }
.link-counts span { font-size: 11px; color: var(--muted); }
@keyframes link-turn { to { transform: rotate(360deg); } }
@keyframes link-halo { from { transform: scale(1); opacity: .55; } to { transform: scale(1.9); opacity: 0; } }
@keyframes link-blink { 50% { opacity: .35; } }
.link-management .tabletop { flex-wrap: wrap; gap: 12px; }.flow-filters { display: flex; flex-wrap: wrap; gap: 6px; }.link-search { display: flex; gap: 8px; min-width: 0; }.link-search input { min-width: 0; width: 190px; }.link-pagination { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding-top: 18px; }.link-pagination .actions { flex-wrap: wrap; }.link-management .tabletop > select,.link-pagination select { min-height: 42px; padding: 9px 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--bg); color: var(--ink); }
@media (max-width: 1050px) { .link-stage { grid-template-columns: 1fr; gap: 20px; } .link-right { padding-left: 0; justify-content: space-between; } }
@media(max-width:600px) { .link-orbit { display: none; } .link-counts { grid-template-columns: 1fr 1fr; width: 100%; } .link-search { width: 100%; }.link-search input { flex: 1; }.link-pagination .actions { width: 100%; justify-content: space-between; } }
@media (prefers-reduced-motion: reduce) { .link-orbit .ring-a, .link-orbit .ring-b, .link-seal::before, .link-total small i { animation: none; } }
</style>
