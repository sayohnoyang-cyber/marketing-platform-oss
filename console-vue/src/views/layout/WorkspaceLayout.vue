<template>
  <div class="shell">
    <button v-if="open" class="sidebar-backdrop" aria-label="关闭导航" @click="open = false"></button>
    <aside class="sidebar" :class="{ open }">
      <div class="brand"><div class="mark">序</div><strong>营销工作台</strong></div>
      <div class="nav-label">概览</div>
      <nav>
        <router-link to="/overview" @click="open = false"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>营销总览</router-link>
        <router-link to="/activities" @click="open = false"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 2v6m10-6v6M3 11h18"/></svg>活动管理</router-link>
      </nav>
      <div class="nav-label">资源与体验</div>
      <nav>
        <router-link to="/links" @click="open = false"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M10 13a5 5 0 007 0l3-3a5 5 0 00-7-7l-2 2M14 11a5 5 0 00-7 0l-3 3a5 5 0 007 7l2-2"/></svg>短链管理</router-link>
        <router-link to="/coupons" @click="open = false"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 7h18v4a3 3 0 000 6v3H3v-3a3 3 0 000-6zM15 7v13"/></svg>券库</router-link>
        <router-link to="/preview" @click="open = false"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 18h4"/></svg>领券页预览</router-link>
      </nav>
      <div class="sidecard spotlight-card" :class="{ 'is-live': spotlightPhase === 'LIVE', 'is-warmup': spotlightPhase === 'WARMUP' }">
        <div class="spotlight-top"><div class="k"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="M4 6h16v4a2 2 0 0 0 0 4v4H4v-4a2 2 0 0 0 0-4V6Z"/><path d="M15 6v12" stroke-dasharray="2 2"/></svg>当前活动</div><router-link v-if="spotlight" class="spotlight-open" :to="activityPath(spotlight)" aria-label="打开当前活动" @click="open = false">↗</router-link></div>
        <template v-if="spotlight">
          <router-link class="n spotlight-name" :to="activityPath(spotlight)" @click="open = false">{{ shortName(spotlight.name) }}</router-link>
          <div class="spotlight-status"><i aria-hidden="true"></i>{{ phaseText(spotlightPhase) }}</div>
          <div class="spotlight-stock"><span>剩余券量</span><b>{{ left(spotlight) }}<small>张</small></b></div>
          <div v-if="remainingPercent != null" class="spotlight-meter" role="progressbar" aria-label="剩余券量比例" :aria-valuenow="remainingPercent" aria-valuemin="0" aria-valuemax="100"><i :style="{ width: `${remainingPercent}%` }"></i></div>
        </template>
        <p v-else class="muted">还没有活动</p>
        <router-link class="btn primary" to="/activities/new" style="margin-top: 14px" @click="open = false">创建活动</router-link>
      </div>
      <div class="profile" @keydown.esc="menu = false">
        <button class="profile-trigger" :aria-expanded="menu" aria-label="我的工作空间" @click="menu = !menu">
        <div class="avatar">{{ initials }}</div>
        <div>我的工作空间<small>{{ profile?.username || '我的账号' }}</small></div>
        <span style="margin-left: auto; color: #a4ada1">⌄</span>
        </button>
        <div v-if="menu" class="menu-pop" @click.stop>
          <router-link to="/my-coupons" @click="close">我的券</router-link>
          <router-link v-if="profile?.merchantId" to="/coupon-management" @click="close">优惠券核销</router-link>
          <router-link to="/account" @click="close">账号</router-link>
        </div>
      </div>
    </aside>
    <main>
      <header class="topbar">
        <div>
          <button class="menu" type="button" aria-label="打开导航" :aria-expanded="open" @click="open = !open">☰</button>
          工作空间 <span class="crumb-gap">/</span><strong :title="crumb">{{ crumb }}</strong>
        </div>
      </header>
      <div class="content"><router-view @spotlight="spotlight = $event" /><footer class="footer"><span>序 · 营销工作台</span></footer></div>
    </main>
  </div>
</template>

<script>
import { getActivity } from '@/api/campaign'
import { activitySpotlight } from '@/api/link'
import { leftOf, phaseText, activityPath, displayPhase } from '@/views/activity/format'

export default {
  data() {
    return { open: false, menu: false, spotlight: null, sideToken: 0 }
  },
  computed: {
    spotlightPhase() { return this.spotlight ? displayPhase(this.spotlight) : '' },
    remainingPercent() {
      if (!this.spotlight || !(Number(this.spotlight.quantity) > 0) || leftOf(this.spotlight) == null) return null
      return Math.min(100, Math.max(0, Math.round(leftOf(this.spotlight) / Number(this.spotlight.quantity) * 100)))
    },
    profile() { return this.$store.state.profile },
    crumb() {
      const title = this.$route.meta.title || '营销总览'
      if (this.$route.params.activityId && this.$store.state.workspaceTitle) {
        return `活动管理 / ${this.$store.state.workspaceTitle}`
      }
      return title
    },
    initials() {
      const name = this.profile?.username || '序'
      return name.slice(0, 2).toUpperCase()
    }
  },
  created() {
    this.loadSide()
  },
  watch: {
    '$route.fullPath'() { this.close(); this.loadSide() }
  },
  methods: {
    phaseText, activityPath,
    shortName(name) { return String(name || '').split(' · ')[0] },
    left(item) { return leftOf(item) == null ? '—' : leftOf(item) },
    close() { this.menu = false; this.open = false },
    async loadSide() {
      const requestToken = ++this.sideToken
      try {
        const id = this.$route.params.activityId || this.$route.query.activity
        if (id) {
          const response = await getActivity(id)
          if (requestToken === this.sideToken) this.spotlight = response.data
          return
        }
        const response = await activitySpotlight()
        if (requestToken === this.sideToken) this.spotlight = response.data || null
      } catch (error) {
        if (requestToken === this.sideToken) this.spotlight = null
      }
    }
  }
}
</script>
