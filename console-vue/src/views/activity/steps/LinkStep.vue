<template>
  <!-- 推广入口页：默认提供可立即使用的活动直达页链接，主短链与渠道短参按需生成。 -->
  <div>
    <div class="stephead">
      <div><h2>推广入口与渠道</h2></div>
      <button class="btn primary" :disabled="saving" @click="dialog = 'add'; message = ''">添加渠道入口</button>
    </div>
    <p v-if="message" :class="failed ? 'error' : 'flow-message'" role="status">{{ message }}</p>

    <div class="card tablecard main-link-card">
      <div class="sectionhead">
        <div><h2>活动直达链接</h2></div>
        <span class="pill">{{ directPageLabel }}</span>
      </div>
      <div class="rulebox">
        <div class="flow-url"><EntryLink :url="directUrl">{{ directUrl }}</EntryLink></div>
        <div class="actions">
          <button class="btn primary" type="button" @click="copyUrl(directUrl, '直达链接已复制')">复制直达链接</button>
          <button class="btn" type="button" @click="showQr(directUrl, '活动直达页二维码')">二维码</button>
          <router-link class="btn" :to="`/preview?activity=${activity.id}`">预览效果</router-link>
        </div>
        <p class="note">活动发布后即可通过直达链接打开与领券，不依赖短链开关。</p>
      </div>
    </div>

    <div class="card tablecard main-link-card section-gap">
      <div class="sectionhead">
        <div><h2>推广主短链（按需可选）</h2></div>
        <span class="pill" :class="{ amber: !main.fullShortUrl || !main.enabled }">{{ !main.fullShortUrl ? '尚未生成' : main.enabled ? '主链已启用' : '主链已停用' }}</span>
      </div>
      <div class="rulebox">
        <div class="flow-url"><EntryLink :url="main.fullShortUrl">{{ main.fullShortUrl || '适合短信、海报等外部推广场景，需要时再生成' }}</EntryLink></div>
        <div class="actions">
          <button v-if="!main.fullShortUrl" class="btn primary" :disabled="saving" @click="createMain">生成主短链</button>
          <template v-else>
            <button class="btn primary" type="button" @click="copyUrl(main.fullShortUrl, '主短链已复制')">复制主链</button>
            <button class="btn" type="button" @click="showQr(main.fullShortUrl, '活动主短链二维码')">二维码</button>
            <button class="btn" type="button" :disabled="saving" @click="requestToggle(null)">{{ main.enabled ? '停用' : '启用' }}</button>
          </template>
        </div>
        <div v-if="main.originUrl" class="checkrow"><span>指向地址</span><b class="entry-url"><EntryLink :url="main.originUrl">{{ main.originUrl }}</EntryLink></b></div>
        <div class="checkrow"><span>短链访问结果</span><b>{{ mainPageLabel }}</b></div>
      </div>
    </div>

    <div class="card tablecard section-gap">
      <div class="tabletop"><h2>入口与渠道效果</h2><span>{{ reportLoading ? '正在读取…' : '' }}</span></div>
      <p v-if="reportError" class="error">{{ reportError }} <button class="linkbtn" @click="loadReport">重试</button></p>
      <ActivityEntries :activity="activity" :main="main" :channels="channels" :reports="reports" editable :busy="saving" @toggle="requestToggle" />
    </div>

    <StudioModal v-if="dialog" :title="dialog === 'add' ? '添加渠道入口' : dialog === 'qr' ? qrTitle : '确认停用？'" :busy="saving" @close="dialog = ''">
      <form v-if="dialog === 'add'" @submit.prevent="add">
        <label class="field">归属活动<input :value="activity.name" readonly /></label>
        <label class="field">渠道名称<input v-model.trim="channelName" maxlength="64" placeholder="例如公众号、社群" required /></label>
        <label class="field">渠道标识（选填）<input v-model.trim="channelCode" pattern="[A-Za-z0-9]{2,12}" maxlength="12" placeholder="不填则自动生成" /></label>
        <p class="note">若尚未生成推广主短链，添加首个渠道时将自动生成主短链以区分渠道归因。</p>
        <p v-if="failed" class="form-alert" role="alert">{{ message }}</p>
        <div class="actions modal-actions"><button class="btn" type="button" :disabled="saving" @click="dialog = ''">取消</button><button class="btn primary" :disabled="saving || !channelName">{{ saving ? '创建中…' : '创建' }}</button></div>
      </form>
      <template v-else-if="dialog === 'qr'">
        <img v-if="qr" class="qr-large" :src="qr" :alt="qrTitle" />
        <p class="note entry-url">{{ qrTarget }}</p>
        <div class="actions modal-actions"><button class="btn" @click="dialog = ''">关闭</button><a v-if="qr" class="btn primary" :href="qr" download="活动入口二维码.png">保存二维码</a></div>
      </template>
      <template v-else>
        <p class="note">{{ toggleTarget ? '停用后，新的访问无法从这个渠道进入，直达页与其他渠道不受影响。' : '停用主短链后，通过短链和渠道短链的跳转将不可用；活动直达页与已领优惠券不受影响。' }}</p>
        <p v-if="failed" class="form-alert">{{ message }}</p>
        <div class="actions modal-actions"><button class="btn" :disabled="saving" @click="dialog = ''">取消</button><button class="btn primary" :disabled="saving" @click="toggle">确认停用</button></div>
      </template>
    </StudioModal>
  </div>
</template>

<script>
import EntryLink from '@/components/EntryLink.vue'
import QRCode from 'qrcode'
import StudioModal from '@/components/StudioModal.vue'
import ActivityEntries from '../ActivityEntries.vue'
import { copyText } from '@/lib/clipboard'
import { activityReport, addChannel, openMainLink, setChannelEnabled, setMainEnabled } from '@/api/campaign'

export default {
  components: { EntryLink, StudioModal, ActivityEntries },
  props: { activity: { type: Object, required: true }, channels: { type: Array, default: () => [] }, main: { type: Object, default: () => ({}) } },
  emits: ['reload'],
  data() {
    return {
      channelName: '', channelCode: '', saving: false, message: '', failed: false,
      qr: '', qrTarget: '', qrTitle: '活动二维码', pendingKey: '', pendingName: '',
      dialog: '', toggleTarget: null, reports: [], reportError: '', reportLoading: false, reportToken: 0
    }
  },
  computed: {
    directUrl() {
      const origin = typeof window !== 'undefined' ? window.location.origin : ''
      return `${origin}/a/${this.activity.id}`
    },
    directPageLabel() {
      return { RESERVE: '预约页', CLAIMABLE: '领券页', SOLD_OUT: '已领完', ENDED: '已结束', DRAFT: '草稿中（发布后可领）', UNAVAILABLE: '暂不可用' }[this.activity.entryState] || '暂不可用'
    },
    mainPageLabel() {
      if (!this.main.fullShortUrl) return '尚未生成（可选）'
      if (!this.main.enabled) return '短链已停用（直达页仍可用）'
      return this.directPageLabel
    }
  },
  watch: { 'activity.version': { immediate: true, handler() { this.loadReport() } } },
  methods: {
    async loadReport() {
      const token = ++this.reportToken
      this.reportLoading = true
      this.reportError = ''
      try {
        const response = await activityReport(this.activity.id)
        if (token === this.reportToken) this.reports = response.data || []
      } catch (error) {
        if (token === this.reportToken) this.reportError = error?.message || '统计读取失败'
      } finally {
        if (token === this.reportToken) this.reportLoading = false
      }
    },
    async showQr(url, title) {
      this.failed = false
      this.qrTitle = title
      this.qrTarget = url
      try {
        this.qr = await QRCode.toDataURL(url, { margin: 2, width: 480 })
        this.dialog = 'qr'
      } catch {
        this.failed = true
        this.message = '二维码生成失败，请重试'
      }
    },
    async createMain() {
      this.saving = true
      this.failed = false
      try {
        await openMainLink(this.activity.id)
        this.message = '推广主短链已生成'
        this.$emit('reload')
      } catch (error) {
        this.failed = true
        this.message = error?.message || '主短链生成失败'
      } finally {
        this.saving = false
      }
    },
    async copyUrl(url, successText) {
      this.failed = false
      try {
        await copyText(url)
        this.message = successText
      } catch {
        this.failed = true
        this.message = '复制失败，请手动复制上方链接'
      }
    },
    requestToggle(channel) {
      this.failed = false
      this.message = ''
      this.toggleTarget = channel
      const on = channel ? channel.status === 1 : this.main.enabled
      if (on) this.dialog = 'toggle'
      else this.toggle()
    },
    async toggle() {
      if (this.saving) return
      this.saving = true
      this.failed = false
      try {
        if (this.toggleTarget) await setChannelEnabled(this.activity.id, this.toggleTarget.id, this.toggleTarget.status !== 1)
        else await setMainEnabled(this.activity.id, !this.main.enabled)
        this.dialog = ''
        this.message = '入口状态已更新'
        this.$emit('reload')
      } catch (error) {
        this.failed = true
        this.message = error?.message || '入口更新失败'
      } finally {
        this.saving = false
      }
    },
    async add() {
      if (!this.channelName || this.saving) return
      this.saving = true
      this.failed = false
      this.message = ''
      if (this.pendingName !== `${this.channelName}|${this.channelCode}` || !this.pendingKey) {
        this.pendingName = `${this.channelName}|${this.channelCode}`
        this.pendingKey = crypto.randomUUID()
      }
      try {
        await addChannel(this.activity.id, { name: this.channelName, channelCode: this.channelCode || null, idempotencyKey: this.pendingKey })
        this.channelName = ''
        this.channelCode = ''
        this.pendingKey = ''
        this.pendingName = ''
        this.dialog = ''
        this.message = '渠道已添加'
        this.$emit('reload')
      } catch (error) {
        this.failed = true
        this.message = error?.message || '渠道添加失败'
      } finally {
        this.saving = false
      }
    }
  }
}
</script>
