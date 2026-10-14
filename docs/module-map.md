# 模块地图

> 这份替代了原先散在 `api/` `config/` `data/` `features/` 下的 4 个 README —— 那几份**已过期**
> （包名写错、模块清单缺 seat/widget/notify/nfc），合并到这里统一维护。
> 目录结构会变，但**依赖方向**是设计决策，见文末。

## 顶层模块

| 模块 | 依赖 | 作用 |
|---|---|---|
| `:app` | 全部 | 壳：`Application` / `MainActivity` 装配、Hilt 入口 |
| `:api` | 无 | 网络层：Retrofit 服务、请求/响应模型、转换器、BIT-Login SDK 封装 |
| `:config` | 无 | 设置与用户状态：DataStore 存取、各 `*Settings` 接口、`LoginStatus` |
| `:data` | `api` `config` | 数据库（Room）+ 仓库（`*Repo`）+ **纯逻辑**（`data/*/` 下全是可单测的 `*Logic`） |
| `:features` | `config` `data` | UI 外壳：底部导航、导航图；下辖 18 个子模块 |

## `features/*` 子模块

| 模块 | 提供的界面 / 能力 |
|---|---|
| `:features:common` | 子模块共用的 Compose 依赖、通用组合函数与工具（导航 `NavDest`、WebView 壳、`InAppWebUrls` 等） |
| `:features:theme` | `MainTheme`：Material3 色板 + （新 UI 起）`GridTokens` 设计 token |
| `:features:login` | 登录 / 登出界面（学校统一身份认证） |
| `:features:schedule` | 课表页：课表网格 / DDL / 动态 / 空教室 / 交通 五个子视图；考试列表；手动改课表 |
| `:features:user` | 「我」页：一卡通 / 校园网 / 借阅卡片、**「现在去哪儿自习」建议卡**与详情页 |
| `:features:setting` | 设置：13 组入口 + 多个二级页（账号 / 页面 / 外观 / 课表 / 手动改课 / DDL / 话廊 / 提醒 / NFC / 空教室 …） |
| `:features:seat` | 座位预约：真实房间底图选座、三种预约模式、后台抢座、我的预约、违约计数 |
| `:features:widget` | 桌面小组件（`AppWidgetProvider` + `RemoteViews`，非 Glance）：课程/DDL/动态/座位 四页 |
| `:features:notify` | 提醒中心：七类提醒的排期、到点发送、二次校验、去重 |
| `:features:nfc` | NFC 校园卡：读卡（Reader Mode）、卡号/学号识别、NDEF 贴纸快捷入口、诊断页 |
| `:features:map` | 校园地图（OSM 瓦片 + 校区切换 + 楼栋坐标表） |
| `:features:web` | BIT101 网页壳（学校门户等一律留在 App 内 WebView） |
| `:features:gallery` | 话廊（主页信息流） |
| `:features:poster` `:features:postedit` `:features:report` | 帖子详情 / 发布与修改 / 举报 |
| `:features:message` | 消息（开发中） |
| `:features:versions` | 版本说明与更新检查对话框（`minVersionCode` 判强制更新） |

## 依赖方向（**刻意设计，改动前先读**）

```
:app ──▶ :features ──▶ :config / :data ──▶ :api
             └──▶ 各 :features:* 子模块

:features:theme ──▶ :features:common        （theme 依赖 common，不是反过来）
:features:widget ──▶ :features:theme / :config / :data
```

三条硬约束：

1. **`notify` 不依赖 `seat` / `widget`** —— 反向由 seat 调用 notify；`widget` 不依赖 `seat`，座位快照由 `SeatWidgetPublisher` **单向 push**。
2. **组件库/token 不能放 `:features:common`** —— 因为 `theme` 反过来依赖 `common`，放进去会成环。新 UI 的 token 与组件放在 `:features:theme`。
3. **跨 App 与组件共用的纯逻辑放 `:data`**（如 `DdlSource`、`StudySpotLogic`）—— 因为 `features:widget` 依赖 `data` 但**不依赖** `features:schedule`；放进任一 feature 模块另一侧就取不到。

> 更细的包内结构直接看各界面的 KDoc；构建与环境见根 [../DEVELOPMENT.md](../DEVELOPMENT.md)。
