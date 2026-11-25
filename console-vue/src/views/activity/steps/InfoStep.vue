<template>
  <div>
    <div class="stephead"><div><h2>基本信息</h2></div></div>
    <p v-if="message && !editing" :class="failed ? 'error' : 'flow-message'" role="status">{{ message }}</p>
    <div class="card flow-pad info-details">
      <div class="checkrow"><span>活动名称</span><b>{{ activity.name }}</b></div><div class="checkrow"><span>活动类型</span><b>{{ activity.activityType || '优惠券推广活动' }}</b></div><div class="checkrow"><span>活动编号</span><b>ACT-{{ activity.id }}</b></div><div class="checkrow"><span>领取开始</span><b>{{ clockLabel(activity.startTime) }}</b></div><div class="checkrow"><span>领取结束</span><b>{{ clockLabel(activity.endTime) }}</b></div><div class="checkrow"><span>预算上限</span><b>¥{{ activity.budgetAmount ?? '—' }}</b></div>
      <div class="info-actions"><button class="btn" @click="editing = true">修改活动信息</button></div>
    </div>
    <StudioModal v-if="editing" title="活动信息与领取时间" :busy="saving" @close="cancelEdit">
      
      <form class="config-form-grid" @submit.prevent="save"><label class="field">活动名称<input v-model.trim="form.name" required maxlength="128" /></label><label class="field">活动类型<select v-model="form.activityType"><option>优惠券推广活动</option><option>会员专享活动</option><option>新客领取活动</option></select></label><label class="field">预算上限（元）<input v-model="form.budgetAmount" type="number" min="0" max="9999999999.99" step="0.01" required /></label><label class="field">领取开始<input v-model="form.startTime" type="datetime-local" required :disabled="startLocked" /></label><label class="field">领取结束<input v-model="form.endTime" type="datetime-local" required /></label><p v-if="startLocked" class="note">开场后开始时间已锁定，结束时间只能延长，并且不能晚于券的使用有效期。</p><p v-if="failed" class="form-alert" role="alert">{{ message }}</p><div class="actions modal-actions"><button class="btn" type="button" :disabled="saving" @click="cancelEdit">取消</button><button class="btn primary" :disabled="saving || !dirty">{{ saving ? '保存中…' : '保存' }}</button></div></form>
    </StudioModal>
  </div>
</template>

<script>
import { updateActivity } from '@/api/campaign'
import StudioModal from '@/components/StudioModal.vue'
import { clockLabel, fromLocalInput, toLocalInput } from '../format'

export default {
  components: { StudioModal },
  props: { activity: { type: Object, required: true } },
  emits: ['reload'],
  data() {
    return { editing: this.$route.query.edit === 'time', saving: false, message: '', failed: false, form: this.blank() }
  },
  computed: {
    startLocked() {
      return this.activity.publishStatus && this.activity.publishStatus !== 'DRAFT' && this.activity.timePhase !== 'BEFORE'
    },
    dirty() {
      const origin = this.blank()
      return this.form.name !== origin.name || this.form.activityType !== origin.activityType || this.form.budgetAmount !== origin.budgetAmount || this.form.startTime !== origin.startTime || this.form.endTime !== origin.endTime
    }
  },
  watch: {
    '$route.query.edit'(value) { if (value === 'time') this.editing = true },
    activity: { immediate: true, handler() { this.form = this.blank() } }
  },
  beforeRouteUpdate() { return this.confirmLeave() },
  beforeRouteLeave() {
    return this.confirmLeave()
  },
  mounted() {
    window.addEventListener('beforeunload', this.beforeUnload)
  },
  beforeUnmount() {
    window.removeEventListener('beforeunload', this.beforeUnload)
  },
  methods: {
    clockLabel,
    hasUnsavedChanges() { return this.dirty },
    cancelEdit() { this.form = this.blank(); this.editing = false; this.message = '' },
    blank() {
      return {
        name: this.activity.name || '',
        activityType: this.activity.activityType || '优惠券推广活动',
        budgetAmount: String(this.activity.budgetAmount ?? '0'),
        startTime: toLocalInput(this.activity.startTime),
        endTime: toLocalInput(this.activity.endTime)
      }
    },
    confirmLeave() {
      if (!this.dirty) return true
      return window.confirm('基本信息还没保存，确定离开？')
    },
    beforeUnload(event) {
      if (!this.dirty) return
      event.preventDefault()
      event.returnValue = ''
    },
    async save() {
      this.saving = true
      this.message = ''
      this.failed = false
      try {
        await updateActivity(this.activity.id, {
          name: this.form.name,
          activityType: this.form.activityType,
          budgetAmount: this.form.budgetAmount,
          startTime: fromLocalInput(this.form.startTime),
          endTime: fromLocalInput(this.form.endTime),
          version: this.activity.version
        })
        this.message = '已保存'
        this.editing = false
        this.$emit('reload')
      } catch (error) {
        this.failed = true
        this.message = error?.message || '保存失败'
      } finally {
        this.saving = false
      }
    }
  }
}
</script>
