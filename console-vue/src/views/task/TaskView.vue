<template>
  <section>
    <div class="pagehead">
      <div>
        <h1>批量发券</h1>
        <p class="muted">Excel 第一列表头为「用户ID」。外部通知未接入，页面不会显示已发送成功。</p>
      </div>
    </div>
    <p v-if="error" class="error">{{ error }} <button class="btn" type="button" @click="load">重试</button></p>
    <form class="card form" @submit.prevent="submit">
      <label class="field">模板编号<input v-model.trim="templateId" required /></label>
      <label class="field">执行时间<input v-model="runAt" type="datetime-local" /></label>
      <label class="field">用户文件<input type="file" accept=".xlsx" required @change="file = $event.target.files[0]" /></label>
      <button class="btn primary" :disabled="saving">{{ saving ? '上传中…' : '创建任务' }}</button>
    </form>
    <div class="card">
      <p v-if="loading" class="loading">正在读取任务…</p>
      <p v-else-if="!tasks.length" class="empty">还没有批量任务。</p>
      <table v-else>
        <thead><tr><th>批次</th><th>进度</th><th>成功</th><th>失败</th><th>通知</th><th></th></tr></thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.id">
            <td>{{ task.batchCode }}</td>
            <td>{{ task.progress }}%</td>
            <td>{{ task.successCount }} / {{ task.totalCount }}</td>
            <td>{{ task.failCount }}</td>
            <td>{{ task.notificationMessage }}</td>
            <td><button class="btn" type="button" @click="download(task)">失败明细</button></td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script>
import { createTask, listTasks } from '@/api/coupon'

export default {
  data() {
      return { tasks: [], templateId: '', runAt: '', file: null, loading: false, saving: false, error: '' }
  },
  created() {
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      this.error = ''
      try {
        const response = await listTasks()
        this.tasks = response.data || []
      } catch (error) {
        this.tasks = []
        this.error = error?.message || '任务读取失败'
      } finally {
        this.loading = false
      }
    },
    async download(task) {
      this.error = ''
      try {
        const response = await fetch(`/api/merchant/grant-batches/${task.id}/failures`, {
          headers: { Authorization: `Bearer ${this.$store.state.token}` }
        })
        if (!response.ok) {
          this.error = '失败明细下载失败'
          return
        }
        const blob = await response.blob()
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = `task-${task.id}-failures.csv`
        link.click()
        URL.revokeObjectURL(url)
      } catch (error) {
        this.error = '失败明细下载失败'
      }
    },
    async submit() {
      if (!this.file) {
        this.error = '请选择文件'
        return
      }
      this.saving = true
      this.error = ''
      try {
        await createTask(this.templateId, this.file, this.runAt)
        await this.load()
      } catch (error) {
        this.error = error?.message || '任务创建失败'
      } finally {
        this.saving = false
      }
    }
  }
}
</script>
