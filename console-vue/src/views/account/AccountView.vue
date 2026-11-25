<template>
  <section class="account-page">
    <div class="pagehead">
      <div>
        <h1>账号</h1>
      </div>
      <button class="btn" type="button" :disabled="loading" @click="exit">{{ loading ? '退出中…' : '退出登录' }}</button>
    </div>

    <div class="account-grid">
      <div class="card account-card">
        <h2>基本信息</h2>
        <p v-if="error" class="error">{{ error }}</p>
        <div class="checkrow"><span>用户名</span><b>{{ profile?.username || '—' }}</b></div>
        <div class="checkrow"><span>用户编号</span><b>{{ profile?.userId || '—' }}</b></div>
        <div class="checkrow"><span>工作空间编号</span><b>{{ profile?.merchantId || '个人账号' }}</b></div>
      </div>

      <form class="card account-card" @submit.prevent="submitPassword">
        <h2>修改密码</h2>
        <label class="field">当前密码<input v-model="pwdForm.oldPassword" type="password" autocomplete="current-password" required /></label>
        <label class="field">新密码（8–72 位）<input v-model="pwdForm.newPassword" type="password" autocomplete="new-password" minlength="8" maxlength="72" required /></label>
        <label class="field">确认新密码<input v-model="pwdForm.confirmPassword" type="password" autocomplete="new-password" minlength="8" maxlength="72" required /></label>
        <p v-if="pwdError" class="form-alert" role="alert">{{ pwdError }}</p>
        <p v-if="pwdNotice" class="flow-message" role="status">{{ pwdNotice }}</p>
        <div class="actions"><button class="btn primary" type="submit" :disabled="savingPwd">{{ savingPwd ? '保存中…' : '更新密码' }}</button></div>
      </form>
    </div>
  </section>
</template>

<script>
import { changePassword, logout } from '@/api/identity'

export default {
  data() {
    return {
      loading: false,
      error: '',
      savingPwd: false,
      pwdError: '',
      pwdNotice: '',
      pwdForm: { oldPassword: '', newPassword: '', confirmPassword: '' }
    }
  },
  computed: {
    profile() {
      return this.$store.state.profile
    }
  },
  methods: {
    async submitPassword() {
      if (this.savingPwd) return
      this.pwdError = ''; this.pwdNotice = ''
      if (this.pwdForm.newPassword.length < 8) {
        this.pwdError = '新密码长度至少为 8 位'
        return
      }
      if (this.pwdForm.newPassword !== this.pwdForm.confirmPassword) {
        this.pwdError = '两次输入的新密码不一致'
        return
      }
      this.savingPwd = true
      try {
        await changePassword({
          oldPassword: this.pwdForm.oldPassword,
          newPassword: this.pwdForm.newPassword
        })
        this.pwdForm = { oldPassword: '', newPassword: '', confirmPassword: '' }
        this.pwdNotice = '密码已更新，下次登录请使用新密码'
      } catch (error) {
        this.pwdError = error?.message || '密码修改失败'
      } finally {
        this.savingPwd = false
      }
    },
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

<style scoped>
.account-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; align-items: start; }
.account-card { padding: 24px; }
.account-card h2 { margin: 0 0 14px; font-size: 18px; }
@media (max-width: 900px) { .account-grid { grid-template-columns: 1fr; } }
</style>
