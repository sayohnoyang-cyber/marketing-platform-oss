# 序 · 营销工作台（Marketing Platform）

一套面向高并发营销场景的模块化单体平台：把工作空间身份、优惠券全生命周期、渠道短链归因、活动工作台、流量预测与预热、异步秒杀，以及核销退款闭环放在同一个工程里。

A modular monolith for high-concurrency marketing: workspaces and auth, coupon lifecycle, attributed short links, a seven-step campaign console, traffic forecast and warmup, async seckill, and in-store redemption/refund. See the Chinese sections below for the full guide.

**License:** [Apache License 2.0](LICENSE). The `marketing-shortlink` module is adapted from [nageoffer/shortlink](https://github.com/nageoffer/shortlink) (Apache-2.0); see [NOTICE](NOTICE).

---

## 功能概览

- **活动工作台**：收拢为 4 个核心视图（活动配置 → 推广入口 → 实时与复盘 → 高级保障），同时兼容旧版 7 步路由重定向；支持草稿续存与幂等提交。
- **双层库存与秒杀**：方案库存与活动投放池隔离；同步领券带请求级租约与失败回补；异步秒杀走 Redis Lua 预扣与后台落库。
- **直达页与短链解耦**：活动直达页默认可用且可无短链发布；需要短信/海报推广或分渠道归因时再按需生成主短链与 `?c=渠道码`，活动主短链禁止通过普通短链接口误改或回收；302 统计异步批量写入。
- **预测、限流与预热**：收拢于工作台「高级保障」，按预约和历史到场率估算峰值，可下发 Redis 限流，并做分级预热检查。
- **券库、券包与核销**：券库支持创建、追加库存、编辑与停发；用户端「我的券」聚焦出示核销码；商家核销台支持查券、试算优惠、一步直接核销与退款。

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

# 按文件名顺序执行全部迁移（当前为 V001 至 V015）
set -a && . ./.env && set +a
mysql -h 127.0.0.1 -P "${MYSQL_PORT:-3306}" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" \
  -e "CREATE DATABASE IF NOT EXISTS marketing DEFAULT CHARACTER SET utf8mb4"
for migration in marketing-app/src/main/resources/db/V*.sql; do
  mysql -h 127.0.0.1 -P "${MYSQL_PORT:-3306}" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" marketing < "$migration" || break
done
```

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

生产机的 Compose 文件是 `compose.yaml`（本仓库的 `docker-compose.yml` 只给本地依赖用）。`deploy.sh` 和 `enable-https.sh` 会在 `compose.yaml`、`compose.yml`、`docker-compose.yaml`、`docker-compose.yml` 里选实际文件。`docker-compose.yml` 如果只是指向 `compose.yaml` 的符号链接，脚本使用 `compose.yaml`，避免 Compose 把两份配置一起读进去。

服务器 `.env` 的对外地址只有一项：`PUBLIC_ORIGIN=https://sayohno.cn`。`compose.yaml` 把它填进 `CONSOLE_BASE_URL`、`SHORTLINK_PUBLIC_BASE_URL` 和 `MARKETING_WEB_CORSALLOWEDORIGINS_0`。改完后重建应用：

```bash
docker compose -f compose.yaml -f docker-compose.https.yml up -d --no-deps app
```

证书镜像是 `certbot/certbot:v2.11.0`。本机已经有这个镜像时，签发和续期用 `docker run --pull never`，不再访问 Docker Hub。本机没有、而且 Docker Hub 超时，脚本会改拉 `docker.m.daocloud.io/certbot/certbot:v2.11.0` 并打成同样的标签。也可以手动：

```bash
docker pull docker.m.daocloud.io/certbot/certbot:v2.11.0
docker tag docker.m.daocloud.io/certbot/certbot:v2.11.0 certbot/certbot:v2.11.0
```

`CERTBOT_IMAGE` 和 `CERTBOT_IMAGE_MIRROR` 可以换成别的镜像名。

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
