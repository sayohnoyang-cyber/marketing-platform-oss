# 活动工作台

核对日期：2026-11-11。

## 页面与路由

| 路径 | 页面 |
| --- | --- |
| `/overview` | 营销总览。主按钮按当前活动进入选券、预热、开场或复盘 |
| `/activities` | 活动列表，可按状态筛选、复制 |
| `/activities/new` | 创建活动 |
| `/activities/:activityId/info` | 基本信息 |
| `/activities/:activityId/pick` | 选券和投放 |
| `/activities/:activityId/link` | 主链和渠道 |
| `/activities/:activityId/forecast` | 预测占位，不写演示 QPS |
| `/activities/:activityId/prepare` | 发布检查 |
| `/activities/:activityId/live` | 开场 |
| `/activities/:activityId/review` | 复盘 |
| `/links` | 活动入口和普通短链 |
| `/coupons` | 券库 |
| `/preview` | 后台预览，不写真实访问 |
| `/a/:activityId` | 真实公开活动页 |

当前活动以路由里的 `activityId` 为准，详情走 `GET /api/merchant/activities/{id}`。

## 状态

发布状态存在 `campaign_activity.status`：

| 值 | 名称 | 含义 |
| --- | --- | --- |
| 0 | DRAFT | 草稿 |
| 1 | PUBLISHED | 已发布 |
| 4 | OFFLINE | 下线，保留投放，结束前可重新发布 |
| 5 | PAUSED | 暂停，沿用原有能力 |
| 6 | CANCELLED | 终止，不可恢复并回收未领数量 |

时间阶段由服务端现在、开场和结束计算：开场前、领取中、已结束。已发布且未到开场，一律是预热，不再要求运营填写预热时间。入口状态由发布状态、时间和库存推导。

下线不调用冻结。终止和自然结束后的回收由 `CampaignRecycleScheduler` 每分钟执行；公开访问不再顺手改库存。

## 统计口径

- `claimed`：本场投放已领取张数，用来算剩余。
- `claims`：归因成功记录合计，包含直接访问。
- 渠道表单独列出“直接访问”。未知渠道参数拒绝进入，不记成别的渠道。
- 退款金额目前没有采集，接口返回 null，页面写“未采集”。
- 核销率分子是核销笔数，分母是本场已领张数。

## 本次没有新增迁移

下线和终止使用已有 `status` 列的新取值。不修改 `V001`–`V005`。
