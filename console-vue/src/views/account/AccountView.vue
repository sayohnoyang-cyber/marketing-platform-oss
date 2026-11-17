<template>
  <section>
    <div class="pagehead">
      <div>
        <h1>账号</h1>

      </div>
      <button class="btn" type="button" :disabled="loading" @click="exit">{{ loading ? '退出中…' : '退出登录' }}</button>
    </div>
    <div class="card" style="padding: 18px">
      <p v-if="error" class="error">{{ error }}</p>
      <p>用户：{{ profile?.username || '—' }}</p>
      <p>用户编号：{{ profile?.userId || '—' }}</p>
    </div>
  </section>
</template>

<script>
import { logout } from '@/api/identity'

export default {
  data() {
    return { loading: false, error: '' }
  },
  computed: {
    profile() {
      return this.$store.state.profile
    }
  },
  methods: {
    async exit() {
      this.loading = true
      this.error = ''
      try {
        await logout()
      } catch (error) {
        this.error = error?.message || ''
      } finally {
        this.$store.commit('clearSession')
        this.loading = false
        this.$router.push('/login')
      }
    }
  }
}
</script>
