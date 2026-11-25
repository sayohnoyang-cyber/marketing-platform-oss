<template>
  <section>
    <div class="pagehead">
      <div>
        <h1>活动管理</h1>
      </div>
      <router-link class="btn primary" to="/activities/new">＋ 创建活动</router-link>
    </div>
    <p v-if="error" class="error">{{ error }} <button class="btn" type="button" @click="load">重试</button></p>
    <p v-else-if="loading" class="loading">正在读取活动…</p>
    <template v-else>
      <DotStage ref="actStage" class="act-stage" :offset-left="96" :palette="stagePalette">
        <div class="act-aura act-aura-1" aria-hidden="true"></div>
        <div class="act-aura act-aura-2" aria-hidden="true"></div>
        <div class="act-hub">
          <div class="act-orbit" aria-hidden="true">
            <svg viewBox="0 0 144 144" fill="none">
              <g class="ring-a"><circle cx="72" cy="72" r="44" stroke="#bcc7b6" /><circle cx="72" cy="28" r="3.5" fill="#5b7040" /></g>
              <g class="ring-b"><circle cx="72" cy="72" r="64" stroke="#527ea8" stroke-opacity=".45" stroke-dasharray="2 5" /><circle cx="72" cy="8" r="3" fill="#c6613f" /></g>
            </svg>
            <div class="act-seal" :class="statusFilter.toLowerCase()">活</div>
          </div>
          <div class="act-hub-info">
            <b>{{ rows.length }} 场活动</b>
            <span><i :class="{ active: count('LIVE') > 0 }"></i>{{ count('LIVE') }} 场进行中</span>
          </div>
        </div>
        <div class="act-kpis">
          <button
            v-for="card in kpiCards"
            :key="card.key"
            class="act-kpi"
            :class="{ selected: statusFilter === card.key }"
            :style="{ '--accent': card.accent, '--accent-bg': card.bg }"
            type="button"
            :aria-pressed="statusFilter === card.key"
            @click="toggleFilter(card.key)"
          >
            <div class="k-top">
              <span>{{ card.label }}</span>
              <span class="k-ico" aria-hidden="true">
                <svg v-if="card.key === 'DRAFT'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 20h9M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z"/></svg>
                <svg v-else-if="card.key === 'WARMUP'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
                <svg v-else-if="card.key === 'LIVE'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M5 12l5 5L20 7"/></svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16v.5"/></svg>
              </span>
            </div>
            <div class="k-num">{{ card.value }}</div>
            <div class="k-bar"><span :style="{ width: card.barWidth }"></span></div>
          </button>
        </div>
      </DotStage>

      <div class="card">
        <div class="tabletop list-tabletop">
          <h2>
            {{ activeCardLabel }} <span>{{ filtered.length }} 个活动</span>
            <button v-if="statusFilter !== 'ALL'" class="linkbtn clear-filter" type="button" @click="toggleFilter('ALL')">查看全部</button>
          </h2>
          <div class="list-controls">
            <select v-model="statusFilter" class="list-select" aria-label="按活动状态筛选">
              <option value="ALL">全部状态</option>
              <option value="DRAFT">草稿</option>
              <option value="WARMUP">预热中</option>
              <option value="LIVE">进行中</option>
              <option value="SOLDOUT">已领完</option>
              <option value="ENDED">已结束</option>
              <option value="OFFLINE">已下线</option>
              <option value="CANCELLED">已终止</option>
              <option value="PENDING">有待处理事项</option>
            </select>
            <input v-model.trim="keyword" placeholder="搜索活动名称…" aria-label="搜索活动名称" />
          </div>
        </div>
        <p v-if="notice" class="flow-message" role="status">{{ notice }}</p>
        <EmptyState v-if="!filtered.length" :title="keyword || statusFilter !== 'ALL' ? '没有匹配的活动' : '暂无活动'">
          <button v-if="keyword || statusFilter !== 'ALL'" class="btn" @click="keyword = ''; statusFilter = 'ALL'">清除筛选</button>
          <router-link v-else class="btn primary" to="/activities/new">创建活动</router-link>
        </EmptyState>
        <div v-else class="tableoverflow">
          <table>
            <thead>
              <tr><th>活动名称</th><th>状态</th><th>优惠券</th><th>渠道入口</th><th>触达 / 领券</th><th>发布准备度</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="item in filtered" :key="item.id">
                <td><strong>{{ item.name }}</strong><small>{{ dayRange(item) }}</small></td>
                <td><span class="pill" :class="{ amber: item.phase === 'WARMUP' }">{{ phaseText(displayPhase(item)) }}</span></td>
                <td>
                  <template v-if="item.couponName"><strong>{{ item.couponName }}</strong><small>{{ formatNumber(item.quantity) }} 张</small></template>
                  <small v-else>未选券</small>
                </td>
                <td>{{ item.channelCount ? item.channelCount + ' 个渠道' : '未配置' }}</td>
                <td>{{ reachOf(item) }} / {{ formatNumber(item.claimed) }}</td>
                <td>
                  {{ item.checksPassed || 0 }} / {{ item.checksTotal || 0 }}
                  <div class="cellprogress"><span :style="{ width: readyWidth(item), background: isFullReady(item) ? '#5b7040' : '#b87e18' }"></span></div>
                </td>
                <td>
                  <div class="row-actions">
                    <router-link class="linkbtn" :to="activityPath(item, 'setup')">进入配置</router-link>
                    <router-link v-if="item.phase !== 'DRAFT'" class="linkbtn" :to="`/activities/${item.id}/analytics`">数据</router-link>
                    <router-link class="linkbtn" :to="`/preview?activity=${item.id}`">预览</router-link>
                    <router-link class="linkbtn" :to="`/activities/new?copy=${item.id}`">复制</router-link>
                    <button v-if="item.phase === 'DRAFT'" class="linkbtn danger-link" type="button" @click="deleteTarget = item; deleteError = ''">删除</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <StudioModal v-if="deleteTarget" title="确认删除草稿？" :busy="deleting" @close="deleteTarget = null">
        <p class="note">确定删除草稿「{{ deleteTarget.name }}」？若已预占优惠券库存，删除时会自动退回对应券模板。</p>
        <p v-if="deleteError" class="form-alert" role="alert">{{ deleteError }}</p>
        <div class="actions modal-actions">
          <button class="btn" type="button" :disabled="deleting" @click="deleteTarget = null">取消</button>
          <button class="btn primary" type="button" :disabled="deleting" @click="confirmDelete">{{ deleting ? '删除中…' : '确认删除' }}</button>
        </div>
      </StudioModal>
    </template>
  </section>
</template>

<script>
import EmptyState from '@/components/EmptyState.vue'
import DotStage from '@/components/DotStage.vue'
import StudioModal from '@/components/StudioModal.vue'
import { deleteActivity, listActivities } from '@/api/campaign'
import { activityPath, dayRange, displayPhase, formatNumber, phaseText } from './format'

const STAGE_PALETTES = {
  ALL: [[91, 112, 64], [82, 126, 168]],
  DRAFT: [[82, 126, 168], [91, 112, 64]],
  WARMUP: [[184, 126, 24], [82, 126, 168]],
  LIVE: [[91, 112, 64], [198, 97, 63]],
  PENDING: [[198, 97, 63], [184, 126, 24]]
}

export default {
  components: { EmptyState, DotStage, StudioModal },
  data() {
    return { rows: [], keyword: '', statusFilter: 'ALL', loading: false, error: '', notice: '', deleteTarget: null, deleting: false, deleteError: '' }
  },
  computed: {
    stagePalette() {
      return STAGE_PALETTES[this.statusFilter] || STAGE_PALETTES.ALL
    },
    kpiCards() {
      const total = Math.max(1, this.rows.length)
      const ratio = value => `${Math.min(100, Math.round((value / total) * 100))}%`
      const draft = this.count('DRAFT')
      const warmup = this.count('WARMUP')
      const live = this.count('LIVE')
      return [
        { key: 'DRAFT', label: '草稿', value: draft, accent: '#527ea8', bg: '#ebf1f6', barWidth: ratio(draft) },
        { key: 'WARMUP', label: '预热中', value: warmup, accent: '#b87e18', bg: '#f8f0df', barWidth: ratio(warmup) },
        { key: 'LIVE', label: '进行中', value: live, accent: '#5b7040', bg: '#eef1e7', barWidth: ratio(live) },
        { key: 'PENDING', label: '待处理事项', value: this.pendingCount, accent: 'var(--clay)', bg: 'var(--tint)', barWidth: this.pendingCount ? '100%' : '0%' }
      ]
    },
    activeCardLabel() {
      return { ALL: '全部活动', DRAFT: '草稿活动', WARMUP: '预热中活动', LIVE: '进行中活动', SOLDOUT: '已领完活动', ENDED: '已结束活动', OFFLINE: '已下线活动', PAUSED: '已暂停活动', CANCELLED: '已终止活动', PENDING: '有待处理事项' }[this.statusFilter] || '全部活动'
    },
    filtered() {
      const word = this.keyword.toLowerCase()
      return this.rows.filter((item) => {
        if (word && !String(item.name).toLowerCase().includes(word)) return false
        if (this.statusFilter === 'ALL') return true
        if (this.statusFilter === 'PENDING') return !['OFFLINE', 'ENDED', 'CANCELLED'].includes(item.phase) && (item.pending?.length || 0) > 0
        return displayPhase(item) === this.statusFilter || item.phase === this.statusFilter
      })
    },
    pendingCount() {
      return this.rows
        .filter((item) => !['OFFLINE', 'ENDED', 'CANCELLED'].includes(item.phase))
        .reduce((sum, item) => sum + (item.pending?.length || 0), 0)
    }
  },
  created() {
    this.load()
  },
  methods: {
    phaseText, displayPhase, formatNumber, dayRange, activityPath,
    toggleFilter(key) {
      this.statusFilter = this.statusFilter === key ? 'ALL' : key
      this.$refs.actStage?.burst()
    },
    count(phase) {
      return this.rows.filter((item) => item.phase === phase).length
    },
    isFullReady(item) {
      const total = Number(item.checksTotal || 0)
      return total > 0 && Number(item.checksPassed || 0) >= total
    },
    readyWidth(item) {
      const total = Number(item.checksTotal || 0)
      if (!total) return '0%'
      return `${Math.round((Number(item.checksPassed || 0) / total) * 100)}%`
    },
    reachOf(item) {
      return item.visits == null ? '—' : formatNumber(item.visits)
    },
    async confirmDelete() {
      if (this.deleting || !this.deleteTarget) return
      this.deleting = true; this.deleteError = ''
      try {
        await deleteActivity(this.deleteTarget.id)
        this.notice = `已删除草稿「${this.deleteTarget.name}」`
        this.deleteTarget = null
        await this.load()
      } catch (error) {
        this.deleteError = error?.message || '草稿删除失败'
      } finally {
        this.deleting = false
      }
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
    }
  }
}
</script>

<style scoped>
.act-stage {
  padding: 22px 26px;
  margin-bottom: 18px;
  border-radius: 20px;
  border: 1px solid var(--line);
  background: linear-gradient(120deg, #eef2eb 0%, #f0eee6 50%, #eff2f5 100%);
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 28px;
  align-items: center;
}
.act-stage::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background: linear-gradient(90deg, #eef2ebe8 0%, #f0eee660 46%, #eff2f5b0 100%);
}
.act-aura { position: absolute; border-radius: 50%; filter: blur(42px); z-index: -3; pointer-events: none; opacity: .55; }
.act-aura-1 { width: 260px; height: 220px; left: -30px; top: -50px; background: radial-gradient(circle, #5b704036, transparent 70%); }
.act-aura-2 { width: 280px; height: 220px; right: 8%; bottom: -70px; background: radial-gradient(circle, #527ea830, transparent 70%); }

.act-hub { display: flex; align-items: center; gap: 18px; padding-right: 10px; }
.act-orbit { position: relative; width: 112px; height: 112px; display: grid; place-items: center; flex-shrink: 0; }
.act-orbit svg { position: absolute; inset: -16px; width: 144px; height: 144px; pointer-events: none; }
.act-orbit .ring-a { transform-origin: 72px 72px; animation: act-turn 38s linear infinite; }
.act-orbit .ring-b { transform-origin: 72px 72px; animation: act-turn 26s linear infinite reverse; }
.act-seal {
  position: relative; width: 44px; height: 44px; border-radius: 14px;
  background: linear-gradient(135deg, #6c844d, #5b7040); color: #fff;
  display: grid; place-items: center; font-family: var(--serif); font-size: 22px;
  box-shadow: 0 12px 26px -8px #5b704088; transition: all .3s;
}
.act-seal.draft { background: linear-gradient(135deg, #6591bc, #527ea8); box-shadow: 0 12px 26px -8px #527ea888; }
.act-seal.warmup { background: linear-gradient(135deg, #d39b33, #b87e18); box-shadow: 0 12px 26px -8px #b87e1888; }
.act-seal.pending { background: linear-gradient(135deg, #d16f4c, var(--clay)); box-shadow: 0 12px 26px -8px #b0502f88; }
.act-seal::before {
  content: ''; position: absolute; inset: -1px; border-radius: inherit;
  border: 1.5px solid #5b7040; animation: act-halo 3s ease-out infinite;
}
.act-hub-info b { display: block; font-family: var(--serif); font-size: 22px; font-weight: 500; line-height: 1.2; }
.act-hub-info span { display: inline-flex; align-items: center; gap: 6px; margin-top: 4px; font-size: 12px; color: #5b7040; font-weight: 500; }
.act-hub-info span i { width: 6px; height: 6px; border-radius: 50%; background: #5b7040; }
.act-hub-info span i.active { animation: act-blink 1.6s ease-in-out infinite; }

.act-kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; position: relative; z-index: 2; }
.act-kpi {
  position: relative; overflow: hidden; text-align: left; padding: 14px 16px 13px;
  border-radius: 14px; background: #ffffffde; backdrop-filter: blur(8px);
  border: 1px solid var(--line);
  transition: transform .22s, border-color .22s, box-shadow .22s, background-color .22s;
}
.act-kpi::before { content: ''; position: absolute; left: 0; right: 0; top: 0; height: 3px; background: var(--accent); opacity: .88; }
.act-kpi:hover { transform: translateY(-2px); background: #fff; border-color: #d6cfc0; box-shadow: 0 14px 28px -22px #5a432b55; }
.act-kpi.selected { border-color: var(--accent); background: #fff; box-shadow: 0 12px 26px -18px #5a432b38; }
.k-top { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--muted); }
.k-ico { width: 24px; height: 24px; border-radius: 7px; background: var(--accent-bg); color: var(--accent); display: grid; place-items: center; }
.k-ico svg { width: 13px; height: 13px; }
.k-num { font-family: var(--serif); font-size: 32px; line-height: 1.15; margin: 6px 0 8px; color: var(--ink); font-variant-numeric: tabular-nums; }
.k-bar { height: 4px; border-radius: 4px; background: var(--cloud); overflow: hidden; }
.k-bar span { display: block; height: 100%; border-radius: inherit; background: var(--accent); transition: width .8s cubic-bezier(.2, .7, .2, 1); }
.clear-filter { margin-left: 10px; font-size: 12px; font-weight: 500; }
.list-tabletop { flex-wrap: wrap; gap: 12px; }
.list-controls { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.list-select { min-height: 38px; padding: 6px 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); color: var(--ink); }
.danger-link { color: #b4422c; }

@keyframes act-turn { to { transform: rotate(360deg); } }
@keyframes act-halo { from { transform: scale(1); opacity: .55; } to { transform: scale(1.9); opacity: 0; } }
@keyframes act-blink { 50% { opacity: .35; } }

@media (max-width: 1180px) {
  .act-stage { grid-template-columns: 1fr; gap: 18px; }
}
@media (max-width: 900px) {
  .act-kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 560px) {
  .act-orbit { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  .act-orbit .ring-a, .act-orbit .ring-b, .act-seal::before, .act-hub-info span i { animation: none; }
  .act-kpi, .k-bar span { transition: none; }
}
</style>
