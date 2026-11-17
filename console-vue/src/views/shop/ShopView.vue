<template>
  <section>
    <div class="pagehead">
      <div>
        <h1>店铺</h1>
        <p class="muted">只显示当前登录商家的店铺。地址栏或请求里的商家编号不会改变查询范围。</p>
      </div>
      <button v-if="isOwner" class="btn primary" type="button" @click="showForm = !showForm">创建店铺</button>
    </div>
    <p v-if="error" class="error">{{ error }} <button class="btn" type="button" @click="load">重试</button></p>
    <form v-if="showForm" class="card" style="padding: 18px; margin-bottom: 16px" @submit.prevent="create">
      <label class="field">店铺名称<input v-model.trim="name" required maxlength="128" /></label>
      <button class="btn primary" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
    </form>
    <div class="card">
      <p v-if="loading" class="loading">正在读取店铺…</p>
      <p v-else-if="!shops.length" class="empty">还没有店铺。商家所有者可以创建第一家店铺。</p>
      <table v-else>
        <thead><tr><th>名称</th><th>店铺编号</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="shop in shops" :key="shop.id">
            <td>{{ shop.name }}</td>
            <td>{{ shop.storeCode }}</td>
            <td>{{ shop.status === 1 ? '启用' : '停用' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script>
import { createShop, listShops } from '@/api/identity'

export default {
  data() {
    return { shops: [], loading: false, saving: false, error: '', name: '', showForm: false }
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
        const response = await listShops()
        this.shops = response.data || []
      } catch (error) {
        this.shops = []
        this.error = error?.message || '店铺读取失败'
      } finally {
        this.loading = false
      }
    },
    async create() {
      this.saving = true
      this.error = ''
      try {
        await createShop(this.name)
        this.name = ''
        this.showForm = false
        await this.load()
      } catch (error) {
        this.error = error?.message || '创建失败'
      } finally {
        this.saving = false
      }
    }
  }
}
</script>
