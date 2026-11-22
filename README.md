# 序 · 营销工作台（Marketing Platform）

一套面向高并发营销场景的模块化单体平台：把工作空间身份、优惠券全生命周期、渠道短链归因、活动工作台、流量预测与预热、异步秒杀，以及核销退款闭环放在同一个工程里。

A modular monolith for high-concurrency marketing: workspaces and auth, coupon lifecycle, attributed short links, a seven-step campaign console, traffic forecast and warmup, async seckill, and in-store redemption/refund. See the Chinese sections below for the full guide.

**License:** [Apache License 2.0](LICENSE). The `marketing-shortlink` module is adapted from [nageoffer/shortlink](https://github.com/nageoffer/shortlink) (Apache-2.0); see [NOTICE](NOTICE).

---

## 功能概览

- **活动工作台**：创建活动（草稿续存与幂等提交）→ 选券投放 → 主链与多渠道分发 → 流量预测 → 分级预热 → 开场看板 → 转化与退款复盘。
- **双层库存与秒杀**：方案库存与活动投放池隔离；同步领券带请求级租约与失败回补；异步秒杀走 Redis Lua 预扣与后台落库。
- **短链与归因**：每场活动生成主短链，渠道通过查询参数共享短码；访客 Cookie 与布隆过滤器辅助去重统计。
- **预测、限流与预热**：按预约和历史到场率估算峰值，可下发 Redis 限流，并做分级预热检查。
- **券包与核销**：用户端落地页预约/领取；商家核销台查券、核销与退款，校验工作空间归属。

## 架构

Maven 多模块 Java 17 / Spring Boot 3 单体，前端是独立的 Vue 3 控制台。

| 模块 | 职责 |
| --- | --- |
| `marketing-app` | 启动入口、配置与数据库迁移脚本 |
| `marketing-common` | 公共结果模型、鉴权上下文、Outbox 等 |
| `marketing-identity` | 注册登录、工作空间与会话 |
| `marketing-coupon` | 优惠方案、库存、领券、核销 |
| `marketing-campaign` | 活动工作台、预测、限流、预热与归因 |
| `marketing-shortlink` | 短链生成、跳转与访问统计（源自 nageoffer/shortlink） |
| `console-vue` | 管理端与活动落地页 |

默认配套 MySQL 8 与 Redis 7。RocketMQ 默认关闭，可用进程内 Outbox；需要外部 Broker 时设置 `marketing.mq.enabled=true`。

## 快速开始

需要 JDK 17+、Maven（可用仓库里的 `./mvnw`）、Node.js 18+、Docker。

### 1. 准备配置

```bash
cp .env.example .env
# 把 MYSQL_PASSWORD 改成你自己的密码，不要使用示例值
```

可选：复制 `marketing-app/src/main/resources/application-local.yaml.example` 为 `application-local.yaml`（已在 .gitignore 中），用环境变量或该文件覆盖本机端口。

### 2. 启动依赖并初始化数据库

```bash
docker compose up -d
```

首次使用空数据卷时，MySQL 容器会自动按文件名执行 Compose 挂载的初始化脚本。已有数据卷升级时，请先备份，再按版本顺序执行尚未应用的迁移。重复执行历史脚本会导致表结构冲突。

从 V012 之前的版本升级，或仍需执行 V013、V014 时，请先暂停入口流量和所有写入实例，停止生产 Compose 的 `app`，再运行发布脚本。脚本会在迁移前保存 MySQL 备份。验证新版本的健康接口和领券流程后再开放流量。跨这些版本回退时，应在维护窗口内把迁移前备份恢复到独立数据库，核对迁移记录与发券库存，并将应用和 Redis 切回与备份匹配的版本。若新版本已接收写入，还需先对账并保留这些变更。

V016 起，默认把批量发券文件与任务一同写入 MySQL，任务完成后释放文件内容。已配置 `OFFER_GRANT_BATCH_DIR` 的部署继续使用该目录，多实例需挂载相同的共享路径。升级前已排队的文件任务仍需保留原路径。

### 3. 启动后端

```bash
set -a && . ./.env && set +a
./mvnw -pl marketing-app -am spring-boot:run
```

- 服务地址：`http://127.0.0.1:9100`
- 健康检查：`GET http://127.0.0.1:9100/api/health`

### 4. 启动前端控制台

```bash
cd console-vue
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`。开发时 `/api` 会代理到 `VITE_API_ORIGIN`（默认 `http://127.0.0.1:9100`）。用页面上的注册接口创建账号，不要使用任何预置默认密码。

## 配置说明

| 变量 | 含义 |
| --- | --- |
| `MYSQL_URL` / `MYSQL_USER` / `MYSQL_PASSWORD` | 数据库连接，密码必填，没有默认值 |
| `MYSQL_PORT` | compose 映射的 MySQL 端口，默认 3306 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接；无密码可留空 |
| `SERVER_PORT` | 后端端口，默认 9100 |
| `SHORTLINK_PUBLIC_BASE_URL` | 短链对外访问前缀 |
| `VITE_PUBLIC_SHORTLINK_ORIGIN` | 前端展示短链时使用的对外地址 |
| `VITE_API_ORIGIN` | 前端开发代理的后端地址 |

生产部署可参考 `deploy/` 与 `.github/workflows/deploy.yml`。这两个是通用示例：主机、密钥只应放在环境变量或 GitHub Secrets 里，不要写进仓库。示例工作流里的 SSH 用户是 `root`，如果可以，请改成独立的发布账号。

## 测试

```bash
npm --prefix console-vue run test:models
./mvnw test
```

需要连本机 Redis 的集成测试默认跳过。显式打开时：

```bash
MARKETING_IT=true REDIS_PORT=6379 ./mvnw -pl marketing-app -am test -Dtest=CampaignRedisOperationsIT
```

## 文档

- [架构设计与工程取舍](docs/design-differences.md)
- [活动投放、主链与发布检查](docs/activity-distribution.md)
- [活动统计、预测、限流与预热](docs/campaign-operations.md)
- [简单版工作空间](docs/simple-workspace.md)
- [活动工作台路由与状态](docs/workspace-flow.md)

## 致谢与许可

本项目以 [Apache License 2.0](LICENSE) 开源。

短链模块改编自 [nageoffer/shortlink](https://github.com/nageoffer/shortlink)（Apache-2.0）。按该许可证要求，原作者的权利声明见 [NOTICE](NOTICE)。
