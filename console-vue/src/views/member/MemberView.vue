<template>
  <section>
    <div class="pagehead">
      <div>
        <h1>成员</h1>
        <p class="muted">成员关系保存在服务端。普通用户不能打开这个页面。</p>
      </div>
    </div>
    <p v-if="error" class="error">{{ error }} <button class="btn" type="button" @click="load">重试</button></p>
    <form v-if="isOwner" class="card" style="padding: 18px; margin-bottom: 16px" @submit.prevent="add">
      <label class="field">已注册用户名<input v-model.trim="form.username" required /></label>
      <label class="field">角色
        <select v-model="form.role">
          <option value="OPERATOR">运营</option>
          <option value="OWNER">所有者</option>
        </select>
      </label>
      <button class="btn primary" :disabled="saving">{{ saving ? '添加中…' : '添加成员' }}</button>
    </form>
    <div class="card">
      <p v-if="loading" class="loading">正在读取成员…</p>
      <p v-else-if="!members.length" class="empty">当前没有可显示的成员。</p>
      <table v-else>
        <thead><tr><th>用户</th><th>角色</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="member in members" :key="member.userId">
            <td>{{ member.username }}</td>
            <td>{{ member.role === 'OWNER' ? '所有者' : '运营' }}</td>
            <td>{{ member.status === 1 ? '有效' : '停用' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script>
import { addMember, listMembers } from '@/api/identity'

export default {
  data() {
    return { members: [], loading: false, saving: false, error: '', form: { username: '', role: 'OPERATOR' } }
  },
  computed: {
    isOwner() {
      return this.$store.state.profile?.roles?.includes('OWNER')
    }
  },
  created() {
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      this.error = ''
      try {
        const response = await listMembers()
        this.members = response.data || []
      } catch (error) {
        this.members = []
        this.error = error?.message || '成员读取失败'
      } finally {
        this.loading = false
      }
    },
    async add() {
      this.saving = true
      this.error = ''
      try {
        await addMember(this.form)
        this.form.username = ''
        await this.load()
      } catch (error) {
        this.error = error?.message || '添加失败'
      } finally {
        this.saving = false
      }
    }
  }
}
</script>
