import { readFile } from 'node:fs/promises'
import test from 'node:test'
import assert from 'node:assert/strict'

async function source(name) {
  const text = await readFile(new URL(`../src/views/activity/${name}.js`, import.meta.url), 'utf8')
  return import(`data:text/javascript;base64,${Buffer.from(text).toString('base64')}`)
}
const { createSandbox, previewPerson, previewSource, visitPreview, actPreview } = await source('previewSandbox')
const { estimateForecast } = await source('forecastModel')
const { couponFace, couponRuleText, couponValidityText } = await source('format')

test('preview reservations and limits apply to the same person across channels', () => {
  const activity = { id: 'a', version: 1, quantity: 2, claimed: 0 }
  const box = createSandbox(activity)
  visitPreview(box, 'wx', true); visitPreview(box, 'wx', true)
  assert.equal(previewSource(box, 'wx').visits, 1)
  assert.equal(actPreview(box, { phase: 'before', source: 'wx', limit: 1 }).ok, true)
  assert.equal(actPreview(box, { phase: 'before', source: 'sms', limit: 1 }).ok, false)
  assert.equal(previewSource(box, 'sms').reservations, 0)
  assert.equal(actPreview(box, { phase: 'live', source: 'wx', limit: 1 }).ok, true)
  assert.equal(actPreview(box, { phase: 'live', source: 'sms', limit: 1 }).ok, false)
  assert.equal(box.claimed, 1)
  assert.equal(activity.claimed, 0)
  box.serial++
  assert.equal(actPreview(box, { phase: 'live', source: 'sms', limit: 1 }).ok, true)
  box.serial++
  assert.equal(actPreview(box, { phase: 'live', source: 'sms', limit: 1 }).ok, false)
  assert.equal(box.claimed, 2)
})

test('blocked and ended previews never mutate stock, visits or people', () => {
  const box = createSandbox({ id: 'a', quantity: 10, claimed: 2 })
  visitPreview(box, 'off', false)
  assert.deepEqual(box.sources, {})
  assert.equal(actPreview(box, { phase: 'live', source: 'off', limit: 2, blocked: '渠道已停用' }).ok, false)
  assert.equal(actPreview(box, { phase: 'ended', source: 'direct', limit: 2 }).ok, false)
  assert.equal(box.claimed, 2)
  assert.equal(previewPerson(box).claims, 0)
})

test('forecast weights each source, includes growth and does not cap by inventory', () => {
  const result = estimateForecast([{ reservations: 100, rate: 50 }, { reservations: 200, rate: 25 }], 100, 2)
  assert.equal(result.reservations, 600)
  assert.equal(result.arrivals, 200)
  assert.equal(result.average, 40)
  assert.deepEqual(result.scenarios.map(row => row.qps), [40, 72, 112])
  assert.equal(result.rows[0].share, 0.5)
  assert.equal(estimateForecast([], 0, 2).average, 0)
  assert.throws(() => estimateForecast([{ reservations: 1, rate: 101 }], 0, 2))
  assert.throws(() => estimateForecast([], 0, 2, 0))
})

test('coupon presentation follows the backend discount fraction convention', () => {
  assert.equal(couponFace('{"percentOff":"0.15","capAmount":"30"}'), '8.5 折')
  assert.equal(couponFace('{"capAmount":"30"}'), '¥30')
  assert.equal(couponRuleText('{"minSpend":"199","capAmount":"30"}'), '满 199 减 30')
  assert.equal(couponValidityText('{"capAmount":"30","validityPeriodHours":72}'), '领取后 72 小时内有效')
  assert.equal(couponValidityText('{"capAmount":"30","validityPeriodHours":0}'), '')
  assert.equal(couponFace('broken'), '—')
})


test('disabled sources do not contribute arrivals and unreserved QPS is added once per scenario', () => {
  const result = estimateForecast([{ reservations: 100, rate: 100, enabled: false }, { reservations: 100, rate: 50 }], 0, 2, 10, 30)
  assert.equal(result.arrivals, 50)
  assert.equal(result.rows[0].share, 0)
  assert.deepEqual(result.scenarios.map(row => row.qps), [40, 48, 58])
  assert.throws(() => estimateForecast([], 0, 2, 10, -1))
})


const { requestId } = await import('../src/lib/requestId.mjs')
const { refreshDelay, personalState } = await import('../src/views/activity/landingState.mjs')

test('HTTP browsers generate valid distinct UUIDs without randomUUID', () => {
  const webcrypto = globalThis.crypto
  const api = { getRandomValues: values => webcrypto.getRandomValues(values) }
  const ids = new Set(Array.from({ length: 100 }, () => requestId(api)))
  assert.equal(ids.size, 100)
  for (const id of ids) assert.match(id, /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/)
  assert.throws(() => requestId({}), /浏览器/)
})

test('opening refresh uses server boundary and restores personal quota', () => {
  assert.equal(refreshDelay({ phase: 'WARMUP', serverTime: '2026-11-15T10:00:00', startTime: '2026-11-15T10:00:02' }), 2150)
  assert.equal(refreshDelay({ phase: 'LIVE', serverTime: '2026-11-15T10:00:00', endTime: '2026-11-15T10:00:03' }), 3150)
  assert.equal(refreshDelay({ phase: 'ENDED' }), null)
  assert.equal(refreshDelay({ phase: 'CANCELLED' }), null)
  assert.deepEqual(personalState({ personalReserved: true, personalClaimed: '2', maxClaimsPerUser: 2 }), { reserved: true, claimed: 2, remaining: 0 })
  assert.deepEqual(personalState({ maxClaimsPerUser: 1 }), { reserved: false, claimed: 0, remaining: 1 })
})

async function landingHarness(post, storage = new Map(), get = async () => ({ data: {} })) {
  const text = await readFile(new URL('../src/views/activity/ActivityLanding.vue', import.meta.url), 'utf8')
  const script = text.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')
  const storageApi = { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  const options = Function('http', 'requestId', 'personalState', 'refreshDelay', 'CouponPhone', 'IcpFooter', 'couponFace', 'couponRuleText', 'clockLabel', 'phaseText', 'sessionStorage', 'document', script)(
    { post, get }, requestId, personalState, refreshDelay, {}, {}, () => '', () => '', () => '', () => '', storageApi, { hidden: true })
  const vm = { ...options.data(), $store: { state: { token: 'test', profile: { userId: '9' } } }, $route: { params: { id: '1' }, query: {}, fullPath: '/a/1' } }
  for (const [key, value] of Object.entries(options.methods)) vm[key] = value.bind(vm)
  for (const [key, value] of Object.entries(options.computed)) Object.defineProperty(vm, key, { get: () => value.call(vm) })
  return vm
}

test('landing refresh restores reservation and changes opening action without creating new visits', async () => {
  const paths = []
  const vm = await landingHarness(async path => {
    paths.push(path)
    return { data: paths.length === 1
      ? { activityId: '1', action: 'RESERVE', personalReserved: true, credential: 'original', personalClaimed: 0 }
      : { activityId: '1', action: 'CLAIM', personalReserved: true, personalClaimed: '1', maxClaimsPerUser: 1 } }
  })
  await vm.load()
  assert.equal(vm.actionText, '已预约 · 等待开场')
  await vm.load()
  assert.deepEqual(paths, ['/public/activities/1/visit', '/public/activities/1/status'])
  assert.equal(vm.entry.credential, 'original')
  assert.equal(vm.claimed, 1)
  assert.equal(vm.canAct, false)
})

test('lost claim response survives page reload and retries the same request', async () => {
  const storage = new Map(), ids = []
  let issued = false
  const post = async (path, body) => {
    if (path.endsWith('/claims/async')) {
      ids.push(body.requestId)
      if (!issued) { issued = true; throw new Error('响应丢失') }
      return { data: { status: 'RESERVED', requestId: body.requestId } }
    }
    return { data: { activityId: '1', planId: '2', action: 'CLAIM', personalClaimed: issued ? 1 : 0, maxClaimsPerUser: 2, credential: 'valid' } }
  }
  const get = async () => ({ data: { status: 'SUCCESS', voucherId: 'coupon-1' } })
  const first = await landingHarness(post, storage, get)
  await first.load(); await first.act()
  assert.equal(storage.size, 1)
  const reloaded = await landingHarness(post, storage, get)
  await reloaded.load()
  assert.equal(reloaded.claimed, 1)
  await reloaded.act()
  assert.equal(ids.length, 2)
  assert.equal(ids[0], ids[1])
  assert.equal(storage.size, 0)
  assert.equal(reloaded.claimed, 1)
})

test('late entry response cannot overwrite a newly selected activity', async () => {
  let resolveOld
  const vm = await landingHarness(() => new Promise(resolve => { resolveOld = resolve }))
  const old = vm.load()
  vm.$route.params.id = '2'; vm.reset()
  vm.entry = { activityId: '2' }
  resolveOld({ data: { activityId: '1', personalClaimed: 1 } })
  await old
  assert.equal(vm.entry.activityId, '2')
})

test('landing exposes eligibility, validity note and cancels active reservation', async () => {
  const calls = []
  const vm = await landingHarness(async path => {
    calls.push(path)
    return {
      data: {
        activityId: '1',
        activityType: '新客领取活动',
        couponRule: '{"capAmount":"10","validityPeriodHours":48}',
        action: 'RESERVE',
        personalReserved: true,
        personalClaimed: 0
      }
    }
  })
  await vm.load()
  assert.equal(vm.eligibilityText, '新客专享 · 仅限本工作空间首次领券用户')
  assert.equal(vm.validityText, '领取后 48 小时内有效')
  assert.equal(vm.reserved, true)
  await vm.cancelReserve()
  assert.equal(vm.reserved, false)
  assert.equal(calls[1], '/user/activities/1/reservations/cancel')
})

test('landing disables action and shows eligibilityReason when signed-in user is ineligible', async () => {
  let action = 'RESERVE'
  const vm = await landingHarness(async () => ({
    data: {
      activityId: '1',
      action,
      personalReserved: false,
      personalClaimed: 0,
      eligible: false,
      eligibilityReason: '本活动仅限新用户参与'
    }
  }))
  await vm.load()
  assert.equal(vm.canAct, false)
  assert.equal(vm.actionText, '本活动仅限新用户参与')

  action = 'CLAIM'
  await vm.load()
  assert.equal(vm.canAct, false)
  assert.equal(vm.actionText, '本活动仅限新用户参与')
})

const { createPlan, savePlan, draftKey, readDraft, writeDraft } = await import('../src/views/activity/createDraft.mjs')
const creationForm = { name: '精确开场', type: '优惠券推广活动', startDate: '2026-11-20T10:30', endDate: '2026-11-20T12:00', budget: '100', planId: '2', quantity: 10, maxClaimsPerUser: 1 }

test('drafts are scoped by account and workspace and tolerate unavailable storage', () => {
  const memory = new Map()
  const storage = { getItem: key => memory.get(key), setItem: (key, value) => memory.set(key, value) }
  const key = draftKey({ userId: '1', merchantId: '2' })
  assert.equal(writeDraft(storage, key, { form: creationForm }), true)
  assert.equal(readDraft(storage, key).form.startDate, '2026-11-20T10:30')
  assert.equal(readDraft(storage, draftKey({ userId: '9', merchantId: '2' })), null)
  assert.equal(readDraft(storage, draftKey({ userId: '1', merchantId: '8' })), null)
  assert.equal(draftKey({ userId: '1' }), '')
  assert.equal(writeDraft({ setItem() { throw new Error('denied') } }, key, {}), false)
})

test('partial channel failure resumes after reload without recreating or reallocating', async () => {
  let plan = createPlan(creationForm, [{ name: '微信', code: 'wx' }, { name: '短信', code: 'sms' }])
  const calls = [], channelKeys = []
  let fail = true, snapshot
  const api = {
    async createActivity(payload) { calls.push('create'); assert.equal(payload.startTime, '2026-11-20T10:30:00'); return { data: { id: '10' } } },
    async getActivity() { return { data: { ...plan.payload, phase: 'DRAFT', version: 3 } } },
    async openMainLink() { calls.push('main') },
    async saveOffer(id, payload) { calls.push('offer'); assert.equal(payload.version, 3) },
    async addChannel(id, payload) { channelKeys.push(payload.idempotencyKey); if (payload.channelCode === 'sms' && fail) throw new Error('timeout') }
  }
  const persist = () => { snapshot = JSON.stringify(plan) }
  await assert.rejects(savePlan(plan, api, persist), /timeout/)
  plan = JSON.parse(snapshot); fail = false
  assert.equal(await savePlan(plan, api, persist), '10')
  assert.deepEqual(calls, ['create', 'main', 'offer'])
  assert.equal(channelKeys.length, 3)
  assert.equal(channelKeys[1], channelKeys[2])
  assert.equal(plan.channelDone.length, 2)
})

test('lost creation response keeps original request and changed server state stops resumption', async () => {
  let plan = createPlan({ ...creationForm, planId: '' }, [])
  const keys = []
  let snapshot, fail = true, phase = 'DRAFT'
  const api = {
    async createActivity(payload) { keys.push(payload.idempotencyKey); if (fail) throw new Error('lost'); return { data: { id: '10' } } },
    async getActivity() { return { data: { ...plan.payload, phase } } },
    async openMainLink() {}
  }
  const persist = () => { snapshot = JSON.stringify(plan) }
  await assert.rejects(savePlan(plan, api, persist), /lost/)
  plan = JSON.parse(snapshot); fail = false
  await savePlan(plan, api, persist)
  assert.equal(keys[0], keys[1])
  phase = 'LIVE'
  await assert.rejects(savePlan(plan, api, persist), /活动状态已变化/)
})

const { linkRows } = await import('../src/views/link/linkRows.mjs')

test('paged link rows preserve shared short codes, channel attribution and operation ids', () => {
  const rows = linkRows([
    { entryKey: 'm-1', name: '会员礼遇 · 第一场', activityId: '1', kind: 'MAIN', fullShortUrl: 'https://example.com/s/abc', onFlag: true, effectiveFlag: true },
    { entryKey: 'c-2', name: '微信', activityId: '1', channelId: '2', kind: 'CHANNEL', source: 'wx', fullShortUrl: 'https://example.com/s/abc', onFlag: true, effectiveFlag: false },
    { entryKey: 'p-3', name: '外链', linkId: '3', kind: 'PLAIN', fullShortUrl: 'https://example.com/s/plain', origin: 'https://example.com/offer', onFlag: false, effectiveFlag: false }
  ])
  assert.equal(rows[0].name, '会员礼遇 · 主链')
  assert.equal(rows[1].url, 'https://example.com/s/abc?c=wx')
  assert.equal(rows[1].channelId, '2')
  assert.equal(rows[1].on, true); assert.equal(rows[1].effective, false)
  assert.equal(rows[2].linkId, '3'); assert.equal(rows[2].origin, 'https://example.com/offer')
})

async function linkHarness(list) {
  const text = await readFile(new URL('../src/views/link/LinkView.vue', import.meta.url), 'utf8')
  const script = text.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')
  const names = ['EntryLink','EmptyState','StudioModal','ActivityPicker','DotStage','copyText','setChannelEnabled','setMainEnabled','createLink','updateLink','listLinkDirectory','formatNumber','requestId','linkRows']
  const options = Function(...names, script)({}, {}, {}, {}, {}, () => {}, () => {}, () => {}, () => {}, () => {}, list, value => value, requestId, linkRows)
  const vm = options.data()
  for (const [key, fn] of Object.entries(options.methods)) vm[key] = fn.bind(vm)
  for (const [key, fn] of Object.entries(options.computed)) Object.defineProperty(vm,key,{get:()=>fn.call(vm)})
  return vm
}

test('link pagination uses one aggregate request and keeps global summary while filtering', async () => {
  const params = []
  const vm = await linkHarness(async query => { params.push(query); return { data: { records: [], page:'2', size:'20', total:'35', summary:{total:'100', enabled:'60',visits:'800', segments:[] } } } })
  vm.filter = '9007199254740993'; vm.keyword = '会员'; vm.stateFilter = 'ACTIVE'
  await vm.loadPage(2)
  assert.equal(params.length,1)
  assert.equal(params[0].activityId, '9007199254740993')
  assert.equal(params[0].page, 2); assert.equal(params[0].keyword,'会员')
  assert.equal(vm.total,35); assert.equal(vm.pages,2)
  assert.equal(vm.summary.total,'100')
  vm.filter = 'PLAIN'; await vm.loadPage(1)
  assert.equal(params[1].unassigned,true); assert.equal(params[1].activityId,undefined)
})

test('late list response cannot replace the latest filter or pagination result', async () => {
  let oldResolve
  const vm = await linkHarness(query => query.keyword === 'old' ? new Promise(resolve => { oldResolve = resolve })
    : Promise.resolve({data:{records:[],page:'1',size:'20',total:'1',summary:{total:'50'}}}))
  vm.keyword = 'old'; const old = vm.loadPage(2)
  vm.keyword = 'new'; await vm.loadPage(1)
  oldResolve({data:{records:[],page:'2',size:'20',total:'90',summary:{total:'90'}}}); await old
  assert.equal(vm.total,1); assert.equal(vm.page,1); assert.equal(vm.summary.total,'50'); assert.equal(vm.loading,false)
})
