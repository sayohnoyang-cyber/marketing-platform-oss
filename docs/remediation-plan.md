# 修复与完善任务书（Remediation Plan）

> 本文档是给 AI 编码助手直接执行的任务说明。每个任务包含：**位置 → 问题 → 根因 → 修复方案 → 验收标准**。
> 请按优先级顺序执行，每完成一个任务就跑一次测试，不要把多个任务混在一个大改动里。
>
> 编写日期：2026-11-19。基于仓库当前代码（迁移脚本 V001–V014）。

---

## 0. 执行前必读：项目背景与硬约束

### 0.1 项目概况

- Maven 多模块 Java 17 / Spring Boot 3 模块化单体，前端 Vue 3（`console-vue`）。
- 模块：`marketing-app`（启动、配置、`db/V*.sql` 迁移）、`marketing-common`（Result、上下文、Outbox/Inbox）、`marketing-identity`（登录、工作空间）、`marketing-coupon`（券方案、投放库存、领券、核销）、`marketing-shortlink`（短链、跳转、统计）、`marketing-campaign`（活动工作台、预测、限流、预热、归因）。
- 依赖：MySQL 8、Redis 7；RocketMQ 默认关闭（`marketing.mq.enabled=false`，走 `LocalOutboxTransport` 进程内投递）。
- 核心业务链路：
  `短链 /s/{shortUri}` →(302, 种 `marketing_visitor` Cookie)→ `落地页 /a/{activityId}?c={channelCode}` →(`POST /api/public/activities/{id}/visit` 签发 30 分钟 `credential`)→ `预约 / 领券（只认 credential）` → `券包` → `锁券 → 核销 → 退款` →(Outbox 事件)→ `campaign_conversion` 归因到渠道。
- 库存守恒：`offer_plan.issued_total = 未分配 inventory_qty + Σ offer_inventory.quantity + 直接领取张数`。领券的数据库最终防线是 `OfferInventoryMapper.claimOne`（`claimed_count < quantity` 条件更新），Redis Lua 只是前置预扣。

### 0.2 硬约束（必须遵守）

1. **不得修改已有迁移脚本 `V001`–`V014`**。如需改表，新增 `marketing-app/src/main/resources/db/V015__xxx.sql`（依次递增），同时同步 `marketing-app/src/test/resources/db/business.sql`（H2 测试库结构），并更新 `docker-compose.yml` 中的脚本挂载（如有逐个列出）和 README 中“当前为 V001 至 V0xx”的描述。
2. Redis 键必须显式带命名空间前缀（`identity:` / `coupon:` / `offer:` / `shortlink:` / `campaign:`），Lua 脚本只能通过 `KEYS[]` 访问键；不要引入全局前缀序列化器。
3. Lua 中过期时间参数一律以**秒**为单位。
4. 领券方法不能在外层事务中调用（`VoucherClaimService` 中已有 `isActualTransactionActive` 检查），顺序必须保持“Redis 预扣 → 独立事务落库 → 释放预扣”，失败要回补。
5. 所有写接口保持幂等语义（requestId / requestKey / idempotencyKey），同参重复返回原结果，异参返回 `CONFLICT`。
6. 不要信任客户端传入的 `merchantId`、`userId`、`visitorId`、渠道；归属一律由服务端会话和 `credential` 推导。
7. 保持现有 API 路径和响应字段向后兼容；新增字段可以，删除/改名字段不行（除非任务中明确说明）。
8. 每个任务完成后必须通过：
   ```bash
   ./mvnw test
   npm --prefix console-vue run test:models
   ```
   涉及 Redis 的改动，尽量补充或运行集成测试：
   ```bash
   MARKETING_IT=true REDIS_PORT=6379 ./mvnw -pl marketing-app -am test -Dtest=CampaignRedisOperationsIT
   ```
9. 新增测试放在 `marketing-app/src/test/java/com/sayohno/marketing/flow/` 下（参考 `AllocationFlowTest`、`CampaignOperationsTest` 的写法；内存版库存网关见 `support/MemoryOfferStockGateway.java`，如改了 `OfferStockGateway` 接口需同步修改它）。
10. 文档同步：行为变化需更新 `docs/` 下对应文档（`campaign-operations.md`、`activity-distribution.md`、`design-differences.md`）。

---

## 1. 任务总览

| ID | 优先级 | 模块 | 标题 |
| --- | --- | --- | --- |
| P0-1 | P0 | campaign / coupon | 异步领券使用 JVM 内存队列：重启丢单、Redis 库存与限领永久泄漏、多实例查单 404 |
| P0-2 | P0 | coupon (Lua) | 回补脚本对“同用户多张”乱序回补时不回退限领计数 |
| P0-3 | P0 | coupon | `persistAsyncClaim` 前置校验抛异常时不回补 Redis |
| P0-4 | P0 | campaign | 活动结束/暂停后无法查询异步领券结果 |
| P1-1 | P1 | common | 本地 Outbox 投递吞吐过低（每 2 秒 50 条） |
| P1-2 | P1 | campaign / coupon | 预热不初始化库存；Redis 库存键丢失时用滞后值重建 |
| P1-3 | P1 | campaign | `CampaignPageCache` 只写不读，落地页轮询每次穿透数据库 |
| P1-4 | P1 | campaign (Lua) | 限流缺少单用户维度，固定窗口边界突发 |
| P1-5 | P1 | coupon / console-vue | 商家核销台无法“扫码+填金额”一步核销 |
| P2-1 | P2 | shortlink / campaign | 短链跳转与落地页到达两套口径割裂，缺少五级漏斗 |
| P2-2 | P2 | campaign | `activityType` 无资格校验（产品决策项） |
| P2-3 | P2 | campaign | `warmupStartTime` 字段语义被架空 |
| P2-4 | P2 | campaign | 预测中“未预约访客”跨口径相减 |
| P2-5 | P2 | campaign | 草稿活动信息可被公开接口读取 |
| P2-6 | P2 | campaign | 已结束活动永久阻止该券的直接领取 |
| P2-7 | P2 | campaign | 预热分级只给文案，没有自动预热兜底 |
| P3-1 | P3 | common / campaign / shortlink | 大表无归档清理 |
| P3-2 | P3 | campaign | 回收调度每分钟全量扫描历史活动 |

---

## 2. P0：必须修复（正确性 / 资金与库存一致性）

### P0-1 异步领券使用 JVM 内存队列：重启丢单、Redis 泄漏、多实例 404

**位置**
- `marketing-campaign/.../service/CampaignFlowService.java`
  - 字段 `asyncClaimQueue`（`LinkedBlockingQueue<>(50_000)`）、`inFlightClaims`、`asyncClaimWorkers`（8 线程）
  - 方法 `claimAsync`、`claimStatus`、`drainAsyncLoop`、`processAsyncWork`
- `marketing-coupon/.../service/VoucherClaimService.java`
  - `reserveAllocationByKey`、`persistAsyncClaim`、`compensateAllocationByKey`
  - 未被调用的 `claimAllocationAsync` / `completeAsyncClaim`（`ASYNC_CLAIM_EVENT = "coupon.claim.async"`）
- 前端唯一领券入口：`console-vue/src/views/activity/ActivityLanding.vue` 调用 `POST /user/activities/{id}/claims/async`，再轮询 `GET /user/activities/{id}/claims/{requestId}?planId=`。

**问题**
1. `claimAsync` 在 Redis Lua 预扣成功后（已扣 `offer:inventory:{id}`、已 INCR 用户限领键、已写票据 `offer:ticket:inv:{inventoryId}:{claimKey}`，TTL 一直到券过期），只把任务放进 JVM 内存队列就返回 `RESERVED`，**数据库中没有任何 `claim_ticket` 记录**。
2. 进程崩溃、重启、发布时队列中任务全部丢失：
   - Redis 库存被扣但没发券 → 少卖；
   - 用户限领计数被占 → 用户再领提示“已达到领取上限”；
   - `ClaimTicketRecoveryScheduler` 只扫描数据库 `claim_ticket`，且它读的是 `offer:ticket:inv:{inventoryId}:{requestRowId}` 格式，而异步路径写的是 `...:{claimKey}`（sha256 截断），**完全无法恢复**。
3. `inFlightClaims` 是进程内 Map。多实例部署时，提交打到 A 节点、轮询打到 B 节点，B 节点内存无记录、数据库尚未落库 → `findClaimTicket` 抛 `NOT_FOUND("领取请求不存在")`。
4. `@PreDestroy` 只等待 15 秒，队列积压时剩余任务直接丢弃。

**修复方案（推荐方案 A：先落库请求再异步完成，复用已有 Outbox 能力）**

把 `claimAsync` 改为基于数据库票据的可靠异步：

1. 在 `CampaignFlowService.claimAsync` 中：
   - 保留：参数校验、`requireVisitFast`、`rateLimits.admit`、`loadAsyncMeta`、`rememberIntent`（写 `campaign_claim_intent`，保证归因不丢）。
   - 改为调用 `claimService.claimAllocationAsync(userId, templateId, inventoryId, IssuerScope.MERCHANT, requestId)`。该方法已实现：插入/复用 `claim_ticket`（PENDING）→ 抢租约 → Redis 预扣（票据键用 `requestRowId`）→ 同一小事务内 `markReserved` + 写 Outbox `coupon.claim.async` → 返回 `RESERVED`。
   - 删除 `asyncClaimQueue`、`asyncClaimWorkers`、`drainAsyncLoop`、`processAsyncWork`、`inFlightClaims`、`evictFinishedInFlightClaims`、`@PreDestroy shutdownAsyncWorkers` 及相关 record。
2. 落库由 `VoucherEventHandler` 消费 `coupon.claim.async` → `VoucherClaimService.completeAsyncClaim`（已存在）。`commit` 内会写 `coupon.claim` Outbox 事件，`CampaignConversionHandler` 会调用 `completeClaimAttribution` 补齐 `campaign_claim_intent.voucher_id` 和 `CLAIM` 转化——这条链路已存在，确认能走通即可。
3. `claimStatus` 改为只读数据库：直接 `claimService.findClaimTicket(...)`，SUCCESS 时调用 `completeClaimAttribution`（幂等）。不再依赖内存。
4. 宕机恢复：确认 `ClaimTicketRecoveryScheduler.recoverStuck` 能处理 `RESERVED` 且租约过期的异步票据（它会走 `finishOwned → finishAllocation`，读取 `claim_ticket.claim_seq / alloc_receive_count`，可直接 `commit`）。补一个测试覆盖“Outbox 事件丢失/未消费 + 租约过期 → 被恢复发券”。
5. 热路径性能：此方案每次领券会有少量 MySQL 写（插入 `claim_ticket` + `markReserved` + `outbox_event`），换取可靠性。这是可接受的取舍；高峰保护依赖 P1-4 的限流。**不要**为了“零 MySQL”再回到内存队列。
6. 如坚持零 MySQL 热路径（方案 B，仅在方案 A 压测不达标时采用）：用 Redis Stream（如 `campaign:claim:stream`）替代 JVM 队列，消费组 + `XACK` + 定时 `XAUTOCLAIM` 处理挂起消息；`inFlight` 状态存 Redis 哈希（`campaign:claim:state:{ticketKey}`，TTL 10 分钟）；并实现“票据存在但 Stream 中无消息”的对账回补任务。方案 B 复杂度显著更高。

**验收标准**
- 代码中不再存在用于领券的 JVM 内存队列与内存状态 Map。
- 新增测试：异步领券提交后、在消费前模拟“应用重启”（直接新建服务实例或清空内存），调用恢复任务后用户最终拿到券，且 Redis 库存 = 数据库剩余。
- 新增测试：两个独立的 `CampaignFlowService` 实例（或直接只走数据库查询）中，提交后立即查单返回 `RESERVED/PENDING` 而不是 404。
- 同一 `requestId` 重复提交只发一张券；不同 `requestId` 受本场限领与方案总限领双重约束。
- 前端 `ActivityLanding.vue` 无需改动即可工作（响应字段 `requestId / status / voucherId / coupon` 不变；`status` 可能多出 `PENDING`，前端已兼容）。

---

### P0-2 回补脚本对“同用户多张”乱序回补时不回退限领计数

**位置**
- `marketing-coupon/src/main/resources/lua/release_offer_quota.lua`
- `marketing-coupon/src/main/resources/lua/release_offer_inventory.lua`

**问题**
```lua
local planClaimed = tonumber(redis.call('GET', KEYS[3]) or '0')
if planClaimed == tonumber(ARGV[1]) then redis.call('DECR', KEYS[3]) end
```
仅当当前计数恰好等于本次序号时才回退。场景：限领 2 张，用户请求 A 拿到序号 1、请求 B 拿到序号 2；A 落库失败回补时计数已是 2 ≠ 1，**不回退**，用户只拿到 1 张却被 Redis 判定已满 2 张（数据库 `commit` 中按真实张数再校验，所以不会多发，但会误拒）。

**修复方案**
- 票据 `KEYS[4]`（或 `KEYS[3]`）的存在与值匹配已经保证了“同一预扣只回补一次”，因此只要票据匹配，就无条件回退计数，但不低于 0：
  ```lua
  local ticket = redis.call('GET', KEYS[4])
  if not ticket or tostring(ticket) ~= (ARGV[1] .. ':' .. ARGV[2]) then return 0 end
  if tonumber(redis.call('GET', KEYS[3]) or '0') > 0 then redis.call('DECR', KEYS[3]) end
  if tonumber(redis.call('GET', KEYS[2]) or '0') > 0 then redis.call('DECR', KEYS[2]) end
  if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCR', KEYS[1]) end
  redis.call('DEL', KEYS[4])
  return 1
  ```
- 注意最后一行库存回补：原脚本在库存键不存在时 `INCR` 会创建值为 1 的键，导致之后 `reserve` 脚本以为“缓存已初始化且只剩 1”。改为仅在键存在时 `INCR`（键不存在说明已冻结/删除或尚未初始化，交给下次初始化读取数据库）。
- `release_offer_inventory.lua`（直接领取路径）同样处理。
- 序号（`claimSeq`）在数据库中参与唯一键 `(user_id, offer_plan_id, claim_seq)`：回退计数后，下一次预扣可能拿到与已成功券相同的序号吗？分析：序号 1 失败回退后计数为 1，下一次 INCR 得到 2，而序号 2 已被 B 占用 → 唯一键冲突。**因此必须同步修改序号的来源**：
  - 方案（推荐）：`commit` 中不再直接使用 Redis 返回的序号作为 `claim_seq`，而是在事务内用 `SELECT COALESCE(MAX(claim_seq),0)+1 ... FOR UPDATE`（按 user_id + plan_id）计算；或把唯一键改为基于 `claim_ticket_id`。若改唯一键，需新增 V015 迁移。
  - 选择任一方案后，在 `docs/activity-distribution.md` “限领”一节更新说明。

**验收标准**
- 新增测试（可用 `MemoryOfferStockGateway` 模拟同语义，或 Redis 集成测试）：限领 2，A、B 同时预扣，A 落库失败回补，用户随后再领一次能成功，最终持有 2 张，且无唯一键冲突。
- 冻结投放后回补不会重新创建库存键。

---

### P0-3 `persistAsyncClaim` 前置校验抛异常时不回补 Redis

**位置**：`VoucherClaimService.persistAsyncClaim`

**问题**：`try { commit } catch { compensate }` 只包住了 `commit`。在其之前的 `loadOpenTemplate`（券已停用/过期）、`begin`（幂等哈希冲突）、`lease` 失败（抛“同一异步请求正在落库”）都会直接抛出，**Redis 已预扣的库存和限领不回补**。

**修复方案**
- 若 P0-1 采用方案 A 并删除了 `persistAsyncClaim`，此任务随之关闭；但需对 `completeAsyncClaim` 做同样检查：所有提前 `return` / 抛异常的分支，若票据仍持有 Redis 预扣，都必须回补或交给恢复任务（确保 `claim_ticket` 状态为 `RESERVED` 且租约会过期，从而被 `recoverStuck` 接手）。
- 若保留 `persistAsyncClaim`：把整个方法体包进 try/catch，任何失败都执行 `compensateAllocationByKey`，lease 失败的场景除外（此时另一个执行者持有，不能回补）。

**验收标准**：新增测试——预扣成功后把券模板改为非 OPEN，异步落库失败，Redis 库存与用户限领计数恢复原值。

---

### P0-4 活动结束/暂停后无法查询异步领券结果

**位置**：`CampaignFlowService.claimStatus` → `loadAsyncMeta(activityId, templateId, null)`

**问题**：`loadAsyncMeta` 会校验 `phase.claimOpen()`，活动刚好结束、被暂停或下线时直接抛 `CONFLICT`，用户已经预扣成功的请求查不到结果，前端显示失败。

**修复方案**：`claimStatus` 不应走 `loadAsyncMeta`。改为只校验活动存在 + 券属于该活动，直接从 `campaign_offer` 读取 `inventoryId` 后查 `claim_ticket`。

**验收标准**：新增测试——领券提交后把活动暂停，查单仍能返回最终状态。

---

## 3. P1：高价值（性能 / 名实相符 / 业务闭环）

### P1-1 本地 Outbox 投递吞吐过低

**位置**：`marketing-common/.../outbox/OutboxPublisher.java`

**问题**：`@Scheduled(fixedDelay = 2000)` 每次 `publishDue(50)`，单实例最大约 **25 条/秒**。而每次领券产生 `coupon.claim.async` + `coupon.claim` + `campaign.conversion` 三条事件，每次短链跳转产生 `shortlink.visit`。秒杀 1 万张券会积压数十分钟，异步领券的“排队中”状态会持续很久，`CampaignMessageService` 也会报 BACKLOG。

**修复方案**
- 批量大小与间隔配置化：`marketing.outbox.batch-size`（默认 200）、`OUTBOX_PUBLISH_DELAY`（默认 200ms）。
- 循环拉取：一轮中若本批拿满 `batch-size`，立即继续下一批，直到不满或达到单轮上限（如 2000 条/轮）。
- 可选：按 `event_type` 分组，用有界线程池并行 dispatch（同一 `aggregate_id` 保序可不要求，因 Inbox 已去重）。
- 在 `MarketingProperties.Outbox` 中新增字段并在 `application.yaml` 写默认值。

**验收标准**：新增测试——插入 1000 条待发事件，单次调度在可接受时间内全部发出；配置项生效。

---

### P1-2 预热不初始化库存；Redis 库存键丢失时用滞后值重建

**位置**
- `OfferInventoryService.inspectStock`（只读，键不存在返回 `SKIPPED`）
- `CampaignPreparationService.run` 中 `stock` 任务
- `reserve_offer_quota.lua`：`if not remaining then SET KEYS[1] ARGV[4]`
- `CampaignFlowService.loadAsyncMeta` 中 `remaining` 被本地缓存 2 秒

**问题**
1. 预热不预热最关键的库存键，开场第一批请求才懒加载。
2. 库存键在活动中途丢失（驱逐、主从切换）时，会用调用方传入的 `remaining` 重建。该值 = 数据库 `quantity - claimed_count`，**不包含在途预扣**（已在 Redis 扣减但尚未落库的请求），异步路径还叠加了 2 秒本地缓存。重建值偏大 → Redis 放行的请求多于实际库存。数据库 `claimOne` 条件更新会拦住真正的超发，但用户会先看到“排队中”再变成“失败”，体验差且放大写压力。

**修复方案**
1. 新增 `OfferInventoryService.warmStock(inventoryId)`：用 `SET key value NX EX ttl` 初始化 `offer:inventory:{id}`，值 = 数据库剩余 − 数据库中 `claim_ticket` 状态为 `RESERVED`/`PENDING(已预扣)` 的数量；键已存在则走原 `inspectStock` 对比逻辑。预热 `stock` 任务调用它，成功读回则 `SUCCEEDED`。TTL 与领券路径一致（到券 `usableUntil`）。
2. `publish` 成功时也调用一次 `warmStock`，保证“发布即就绪”。
3. Lua 重建时的 `ARGV[4]`：调用方改为使用“数据库剩余 − 在途票据数”的实时计算值，不使用本地缓存值（P0-1 方案 A 之后同步路径与异步路径都可直接在 `claimAllocationAsync` 内读取 `allocation`，天然是实时值）。
4. 文档 `campaign-operations.md` “真实预热”第 4 条同步更新。

**验收标准**
- 预热后 Redis 中存在库存键且值正确，`stock` 任务 `SUCCEEDED`。
- 测试：存在 N 条在途 `RESERVED` 票据时删除库存键，下一次领取重建的值 = 剩余 − N。

---

### P1-3 `CampaignPageCache` 只写不读

**位置**：`marketing-campaign/.../service/CampaignPageCache.java`；`CampaignFlowService.entry`；`ActivityEntryController` 的 `/status`、`/visit`、`/entry`。

**问题**：`view()` 每次只是把活动 4 个字段写进 Redis（不同才写），**从不从缓存读取返回**。落地页每次 `entry`（包括每几秒一次的 `/status` 轮询）都查 `link`、`campaign_activity`、`campaign_channel`、`campaign_offer`、`offer_plan`、`offer_inventory`。预热里的“活动热点读缓存准备”因此名不副实。

**修复方案**
1. 定义缓存快照（Redis 哈希或 JSON，键 `campaign:page:{activityId}:{version}`，TTL 5 分钟）：活动名称、时间、状态、主链 ID/启用、渠道列表（code → id/name/status）、券公开信息（`templateService.publicOffer` 结果）、`inventoryId`、`quantity`、`maxClaimsPerUser`。
2. `entry(..., recordVisit=false)`（即 `/status` 轮询）**只读缓存**：剩余量从 Redis 库存键读取（键存在时），不存在才回源数据库并回填。
3. 失效：活动 `update / publish / pause / offline / cancel / setMainEnabled / bumpVersion`、渠道启停、投放调整时删除该活动的缓存键（版本号已在键中，`bumpVersion` 后旧键自然失效；渠道启停目前不 bump 版本，需显式删除或 bump）。
4. `/visit`（需要签发 credential）仍需写 `campaign_visit`，但读取部分走缓存。
5. 预热第 5 项改为：构建快照 → 读回比对 → `SUCCEEDED`。

**验收标准**
- 测试：预热后连续调用 `/status` N 次，活动相关表查询次数为 0（可通过计数代理 Mapper 或 H2 日志断言）。
- 修改活动/停用渠道后，`/status` 立即反映新状态。

---

### P1-4 限流缺少单用户维度，固定窗口边界突发

**位置**：`CampaignRateLimitService.admit`；`marketing-campaign/src/main/resources/lua/activity_rate_limit.lua`

**问题**
- 只有“活动总额度 + 来源额度”两个桶，一个脚本用户 1 秒内连发即可耗尽整个渠道额度，正常用户全部 429。
- 自然秒固定窗口在秒边界可能放行 2 倍额度。

**修复方案**
1. `admit` 增加 `userId` 参数；Lua 增加 `KEYS[3] = campaign:rate:{activityId}:user:{userId}`，每用户每秒上限 `marketing.capacity.per-user-claim-qps`（默认 2）。**先检查用户桶**，用户超限时不消耗全局与来源额度。
2. （可选）把全局/来源桶改为滑动窗口（两个相邻秒加权）或令牌桶，减少边界突发。保持“检查 + 扣减”在同一脚本中原子完成，使用 Redis `TIME`。
3. 调用方 `claimAsync` / `prepareClaim` 传入 `userId`。
4. 同步更新 `CampaignRedisOperationsIT`。

**验收标准**：单用户 1 秒内第 3 次请求返回 429 且不影响其他用户；现有跨来源总额度测试仍通过。

---

### P1-5 商家核销台无法“扫码 + 填金额”一步核销

**位置**
- 后端：`marketing-coupon/.../web/VoucherManagementController.java`、`VoucherRedemptionService.java`
- 前端：`console-vue/src/views/coupon/CouponManagementView.vue`、`MyCouponView.vue`

**问题**：用户券包已能“出示券码（二维码）”，但商家在核销台输入券码后，若用户没有先在自己手机上“使用”（手动填订单号、订单金额进行锁券），商家只看到“暂无订单记录”，没有任何可操作按钮。要求消费者自己编订单号锁券，不符合线下核销习惯。

**修复方案**
1. 新增 `VoucherRedemptionService.redeemDirect(userId, voucherId, orderNo, orderAmount, goodsNumber)`（`@Transactional`）：内部依次执行 `lock` 的校验与写入 + `pay` 的状态迁移，复用现有条件更新与 Outbox `coupon.redeem` 事件；幂等：同 `orderNo` 重复调用返回已有结算，参数不一致返回 `CONFLICT`。
2. 新增接口 `POST /api/merchant/vouchers/{id}/redeem`，请求体 `{ orderNo, orderAmount, goodsNumber? }`，先 `wallet.requireManaged(id)` 校验工作空间归属。
3. 可选：新增 `POST /api/merchant/vouchers/{id}/quote` 让商家先预览优惠金额。
4. 前端核销台：查询到券且 `status === 'available'` 且 `usable` 时，显示“直接核销”表单（订单号默认自动生成如 `POS{时间戳}`，订单金额必填），提交后刷新。保留原有“锁定中 → 核销”流程。
5. 券详情返回中补充券状态 `status`、`usable`、`usableUntil`，供前端判断。

**验收标准**
- 测试：未锁定的可用券，商家一步核销成功，产生 `REDEEM` 归因转化；重复提交同订单号不重复核销；跨工作空间返回 NOT_FOUND。
- 前端：输入券码 → 填金额 → 核销 → 表格出现“已核销”记录并可“记录退款”。

---

## 4. P2：体验与自洽

### P2-1 短链跳转与落地页到达两套口径割裂

**位置**：`RedirectService.recordVisit`；`VisitStatsHandler`；`CampaignLinkListMapper.xml`（`jumpSegments` / `entryVisits`）；`CampaignFlowService.board`；`console-vue/src/views/link/LinkView.vue`、`steps/ReviewStep.vue`。

**问题**：短链管理页顶部用 `link_access_log`（302 跳转访客），表格中主链/渠道用 `campaign_visit`（落地页到达访客），普通短链又用跳转访客。运营无法理解为何加不起来，也看不到“点击 → 到达”的流失。

**修复方案**
1. `RedirectController` 把查询参数中的 `c` 传给 `RedirectService.resolve`，事件 payload 增加 `channelCode`；`LinkAccessLog` 新增可空列 `channel_code`（V015 迁移 + H2 结构）。
2. 活动 `report` / `dashboard` / `review` 每个来源行新增 `clicks`（该主链 + channelCode 的跳转去重访客；直接访问对应 `channel_code IS NULL`）。
3. 复盘页展示五级漏斗：短链点击 → 落地页到达 → 预约 → 领取 → 核销（以及退款）。说明“直接打开落地页不经过短链时，到达可能大于点击”。
4. 短链管理页：主链/渠道行同时展示“点击”和“到达”两列，顶部文案改为明确的“短链点击访客”。

**验收标准**：通过 `/s/{code}?c=xxx` 访问后，该渠道 `clicks` 增加；页面口径说明与数据一致。

---

### P2-2 `activityType` 无资格校验（**产品决策项，执行前请确认**）

**现状**：`docs/simple-workspace.md` 明确写“活动类型是分类字段，不自动产生会员资格或新客资格校验”。但创建页让运营选择“新客领取活动 / 会员专享活动”，容易被认为是 Bug。

**二选一**
- **方案 A（实现校验）**：在 `prepareClaim` 与 `claimAsync` 校验（限流之后、预扣之前）：
  - 新客领取活动：该用户在当前工作空间（`merchant_id`）下没有任何已领取的券（`issued_voucher` 关联 `offer_plan.merchant_id`），否则返回 `VALIDATION("本活动仅限新用户参与")`。
  - 会员专享活动：该用户在当前工作空间有至少 1 次已核销记录（或注册满 N 天，阈值配置化）。
  - 落地页 `entry` 返回 `eligibility` 字段，前端在不满足时禁用按钮并提示。
- **方案 B（去误导）**：前端把选项文案改为“活动分类（仅用于统计和历史到场率匹配）”，并保留现状。

**验收标准**：按所选方案补测试和文档。

---

### P2-3 `warmupStartTime` 语义被架空

**问题**：`ActivityPhase.of` 完全不使用 `warmupStart`；只要已发布且未开场就是 `WARMUP`（可预约）。创建时若不传还会自动设为 `start - 1s`。字段存在但无意义。

**修复方案（二选一，推荐 A）**
- A：让它生效——`now < warmupStart` 时返回新阶段 `SCHEDULED`（已发布但未开放预约，落地页 `action = UNAVAILABLE`，reason“预约尚未开始”）。需同步修改 `entryState`、`allowedActions`、前端 `phaseText`、`landingState.mjs`、`CampaignLinkListService.phaseLabel`（注意它调用 `ActivityPhase.of` 时第二个参数传的是 `startTime`，需改为 `warmupStartTime`；`CampaignLinkListMapper.xml` 的 `spotlight` 已查询 `warmup_start_time`，`page` 查询需补该列）。
- B：明确废弃——前端隐藏该字段，后端继续自动填充，文档注明“仅保留兼容”。

---

### P2-4 预测中“未预约访客”跨口径相减

**位置**：`CampaignForecastService.calculate`

**问题**：`activeVisits` 是各来源独立去重 UV 之和（同一人访问两个渠道计 2），`eligibleReservations` 按用户去重；直接相减得到 `unreservedVisitors`，口径不一致，且访问者与预约者不一定是同一批人。

**修复方案**：新增 Mapper 查询“当前启用入口下，有有效访问但没有有效预约的去重访客数”：`campaign_visit` 按 `visitor_id` 去重，排除 `user_id` 在 `campaign_reservation(status=RESERVED)` 中的记录（未登录访客按 visitor 计入）。用它替换相减结果；系数 `0.2` 提取为配置 `marketing.capacity.unreserved-arrival-rate` 并写入快照参数，前端展示该假设。

---

### P2-5 草稿活动信息可被公开接口读取

**位置**：`CampaignFlowService.visit / entry` 使用 `requireReadable`（不校验状态）；`entry` 即使 `blocked` 也返回 `pageCache.view`、`publicOffer`、`quantity` 等。

**修复方案**：活动处于 `DRAFT` 时，公开接口仅返回 `{ action: "UNAVAILABLE", reason: "活动还没发布" }` 与最少字段，不返回券规则、数量。后台预览走 `/preview`（已有沙箱）。

---

### P2-6 已结束活动永久阻止该券的直接领取

**位置**：`CampaignService.assertDirectClaimAllowed`

**问题**：只要券关联过任何非草稿、非取消的活动，就永远不能直接领取，即使活动已结束。

**修复方案**：增加条件 `now.isBefore(activity.getEndTime())` 才阻止（与 `assertTemplateTerminationAllowed` 一致）。

---

### P2-7 预热分级只给文案，没有自动预热兜底

**位置**：`CampaignPreparationService.evaluateWarmDecision`

**修复方案**
1. 新增 `AutoPreparationScheduler`（`@Scheduled` 每分钟）：扫描“已发布、`startTime` 在未来 5 分钟内、最近一次 PREPARE 记录不存在或 `stale`”的活动，以 `requestKey = "auto:" + activityId + ":" + version` 执行 `run`。注意 `run` 内部依赖 `requireOwned`（需要商家会话上下文），调度器中需要提供以活动 `merchantId` 执行的内部入口（新增不做会话校验的 `runInternal(activity)`，Controller 仍走原校验）。
2. 多实例下用数据库条件更新或 Redis `SET NX` 抢占，避免重复执行（`(activity_id, kind, request_key)` 唯一键已可兜底）。
3. 判定为 `PEAK_PROTECT` 且没有生效限流策略时，确保默认保护额度 `default-claim-qps` > 0（仅记录告警，不自动改配置）。

---

## 5. P3：运维治理

### P3-1 大表无归档清理
`outbox_event`（已发送）、`consumer_inbox`、`campaign_visit`（每次打开落地页插一行）、`link_access_log` 无限增长。新增 `RetentionScheduler`：每天低峰分批（每批 1000，`LIMIT` + 主键游标）删除超过保留期的记录，保留期配置化（默认 Outbox/Inbox 7 天、访问明细 90 天）。注意 `campaign_visit` 被历史到场率（90 天）和归因 `visit_id` 引用，保留期不得小于 90 天，且不删除被 `campaign_claim_intent` / `campaign_conversion` 引用的行。

### P3-2 回收调度每分钟全量扫描历史活动
`CampaignService.recycleDue` 每分钟查出所有 `PUBLISHED/OFFLINE/PAUSED/CANCELLED` 活动逐个 `freeze`。改为只查询“关联投放仍为 `open` 且（已取消或已过结束时间）”的活动（联表 `campaign_offer` + `offer_inventory.status='open'`）。

---

## 6. 建议执行顺序与提交拆分

1. P0-2（Lua 回补 + 序号来源）—— 独立、影响面小，先修。
2. P0-1 + P0-3 + P0-4（异步领券改为数据库票据 + Outbox）—— 一个 PR，附带恢复测试。
3. P1-1（Outbox 吞吐）—— P0-1 之后立即做，否则异步领券会排队过久。
4. P1-2、P1-3（真实预热 + 落地页读缓存）。
5. P1-4（单用户限流）。
6. P1-5（一步核销）。
7. P2 系列（P2-2、P2-3 先向需求方确认方案）。
8. P3 系列。

每个 PR 需包含：代码、测试、文档更新、如有表结构变化的 V0xx 迁移与 H2 结构同步。

---

## 7. 不要做的事

- 不要拆微服务、引入注册中心/网关、引入 Flyway 等迁移框架（项目刻意保持单体与手动迁移）。
- 不要新增商品、购物车、支付网关、复杂 RBAC。
- 不要把演示值写成“已验证容量”；容量未实测必须保持 `UNVERIFIED`。
- 不要为提升压测数字移除幂等、租约、回补或数据库条件更新。
- 不要修改 `V001`–`V014`。
