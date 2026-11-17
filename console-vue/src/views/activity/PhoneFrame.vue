<template>
  <div class="phone">
    <div class="phone-bar"></div>
    <div class="phone-screen">
      <p class="kicker">{{ sourceLabel }}</p>
      <h3>{{ name || '未命名活动' }}</h3>
      <p class="muted">{{ stateText }}</p>
      <p class="muted">{{ timeText }}</p>
      <p v-if="quantity != null" class="muted">已领 {{ claimedText }} / 投放 {{ quantity }}</p>
      <button class="btn primary" type="button" disabled>{{ actionText }}</button>
      <p class="preview-note">{{ note }}</p>
    </div>
  </div>
</template>

<script>
const states = {
  draft: { text: '活动还在配置', action: '尚未发布' },
  before: { text: '开场前，可以预约', action: '预约' },
  live: { text: '正在发券', action: '领取' },
  soldout: { text: '本场已领完', action: '已抢完' },
  ended: { text: '活动已结束', action: '已结束' },
  disabled: { text: '入口已停用', action: '暂不可用' }
}

export default {
  props: {
    name: { type: String, default: '' },
    state: { type: String, default: 'draft' },
    source: { type: String, default: '直接访问' },
    quantity: { type: [Number, String], default: null },
    claimed: { type: [Number, String], default: null },
    timeText: { type: String, default: '' },
    note: { type: String, default: '这是预览，不会修改真实时间、库存或统计。' }
  },
  computed: {
    sourceLabel() { return this.source || '直接访问' },
    stateText() { return (states[this.state] || states.draft).text },
    actionText() { return (states[this.state] || states.draft).action },
    claimedText() { return this.claimed == null ? '—' : this.claimed }
  }
}
</script>
