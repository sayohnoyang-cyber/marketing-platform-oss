# 架构设计与工程取舍

本文档记录 `marketing-platform` 在模块化单体架构、数据一致性、高并发领券与缓存治理上的核心工程取舍。

| 主题 | 常见问题 / 风险 | 平台设计方案 |
| --- | --- | --- |
| 启动与发现机制 | 拆分多个微服务并引入注册中心会显著提高本地与中小规模部署复杂度 | 采用 `MarketingApplication` 单一启动入口，仅扫描 `com.sayohno.marketing`，模块化划分领域边界，不依赖外部服务发现与网关 |
| Mapper 扫描 | 不限定注解扫描时容易把普通业务接口误注册为 MyBatis Mapper | `@MapperScan` 严格限定只扫描带有 `@Mapper` 注解的接口 |
| 聚合构建 | 在多模块 Maven Reactor 根路径直接执行 `spring-boot:run` 会因父 POM 无主类失败 | 父模块默认跳过 `spring-boot-maven-plugin`，仅在 `marketing-app` 模块执行 |
| 身份与鉴权 | 硬编码演示用户或信任客户端传入的 `merchantId`、`userId` 会造成越权风险 | 服务端会话仅保存认证用户编号，工作空间归属与成员权限统一由服务端根据会话上下文核验 |
| 密码安全 | 明文或弱哈希存储密码存在泄漏风险 | 统一使用 BCrypt 哈希存储；登录失败统一返回“用户名或密码错误”，账号停用状态单独校验 |
| 多端会话 | 单用户单 Token 哈希结构无法良好支持多设备独立续期与登出 | 会话键采用 `identity:session:{token}`，支持多端共存，登出仅销毁当前 Token，并支持高并发读场景下的短时本地缓存优化 |
| Redis 键空间 | 自定义全局前缀序列化器容易导致普通读写与 Lua 脚本、Pipeline 访问不同物理键 | 不注册隐式前缀序列化器；各模块键名显式携带 `identity:`、`coupon:`、`shortlink:`、`campaign:` 命名空间，Lua 脚本严格通过 `KEYS` 访问 |
| 领券 Lua 过期单位 | 毫秒时间戳直接传入按秒解释的 `EXPIRE` 会导致限领计数键过期时间失真 | 领券与预扣 Lua 脚本统一约定过期时间参数以秒为单位 |
| 布隆过滤器恢复 | 布隆过滤器丢失或未就绪时若直接拦截未命中请求，会导致合法短链全部 404 | Redisson 布隆过滤器固定为 `shortlink:bloom:uri`；应用启动时从 `link` 表重建，完成后写入 `shortlink:bloom:uri:ready` 标记，重建完成前未命中请求继续回源查库 |
| 主键与表结构 | 早期过度分库分表会割裂跨维度查询与事务边界 | 采用标准单库表结构与雪花算法主键，通过复合唯一索引与条件更新保障并发安全 |
| 短链归属与唯一键 | 删除短链后需允许同一完整短链再次创建，同时保留回收站恢复能力 | 归属绑定 `merchant_id`；保留 `(full_short_url, del_time)` 唯一键，`del_time = 0` 表示有效记录，`del_flag` 作为普通字段显式过滤 |
| 领券幂等与回补 | 仅靠 Redis 预扣而不处理数据库异常，在进程崩溃或事务失败时会造成少卖或状态不一致 | 引入同步幂等表 `claim_ticket`（带租约与预扣状态机，失败精准回补）与可靠异步模式（先落 `claim_ticket` 为 `RESERVED` 并在同事务写 `outbox_event`，由消费者幂等落库，进程重启后可由租约恢复任务兜底） |
| 结算与核销约束 | 缺少订单唯一约束容易在并发重试下产生重复结算行 | `voucher_redemption` 建立 `(order_no, voucher_id)` 唯一约束；锁定、核销、退款、取消均采用基于前置状态的条件更新 |
| 短链跳转入口 | 短链跳转与管理 API 同进程部署时，根路径通配会拦截 `/api` 路由 | 公开短链跳转统一使用 `GET /s/{shortUri}`，公开域名由 `SHORTLINK_PUBLIC_BASE_URL` 控制，默认忽略未授信转发头 |
| 可靠事件投递 | 业务操作后直接裸发 MQ 消息，在 Broker 抖动或进程退出时会丢失交易与统计事件 | 交易、核销、转化与访问统计统一写入本地 `outbox_event` 表；`marketing.mq.enabled=false` 时由进程内可靠投递，开启后通过 RocketMQ 投递并配合 Inbox 消费去重 |
| 批量发券调度 | 引入外部重型调度中心会增加额外运维组件负担 | 应用内基于数据库租约抢占扫描 `grant_batch`，单行执行使用 `b:{taskId}:{rowNo}` 幂等键防重 |
| Excel 组件依赖 | EasyExcel 4.0.1 内置 POI 依赖新版 `ZipArchiveEntry` 方法签名 | 显式声明 `commons-compress 1.26.2`，避免导出或解析时触发 `NoSuchMethodError` |
