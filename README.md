# BIT101-S

基于 [BIT101-Android](https://github.com/BIT101-dev/BIT101-Android) 扩展的座位预约功能模块。

---

## 与原项目不同点

| 维度 | BIT101-Android | BIT101-S（本仓库） |
|------|---------------|-------------------|
| 新增 Tab | 5个：卷、图、网、话、我 | +1个：**座**（座位预约） |
| 登录方式 | BIT-Login SDK 登录（学校统一身份认证） | 复用同一份学校 SDK；座位系统再换一次 seatlib 的 JWT |
| 座位 API 认证 | 无 | OkHttp + Bearer JWT，JWT 来自 seatlib 的 phpCAS 会话 |
| 会话持久化 | — | JWT 加密落盘，冷启动免重登 |
| 后台抢座 | — | 前台服务（`dataSync`）轮询，锁屏 / 退后台 / 进程重启均可续跑 |
| 座位图 | — | 服务端真实房间底图 + 百分比坐标热区，双指缩放 |
| 界面 | 原版 | 座位模块内置三页（预约 / 列表 / 登录） |

---

## 使用方式

1. 打开 APP，进入 **"卷"** 页面，使用学号+密码登录 BIT101（完成学校统一身份认证）
2. 切换到底部 **"座"** 标签页
3. 进入后可进行以下操作：

| 页面 | 功能 |
|------|------|
| **预约** | 选择校区→楼层→区域、日期，查看真实房间底图，图上点选座位预约 |
| **列表** | 管理「我的预约」（签到时限 / 取消）与后台预约任务（进行中 / 已结束） |

### 三种预约模式

| 模式 | 说明 |
|------|------|
| 单次预约 | 在座位图上点选一个座位，立即预约 |
| 监控预约 | 盯住图上点选的一个座位，空出后自动预约 |
| 优先预约 | 图上多选若干个座位并按优先级排队（也可「不限」），空出即抢 |

> 监控与优先模式的座位都在**座位图上点选**，不需要手输座位号
> —— 服务端座位号是补零字符串（`"001"`），手输极易不匹配。

---

## 原理

### 登录态：两套彼此独立的会话

BIT101 的学校会话与 seatlib 的 phpCAS 会话**不互通**（Cookie 与域名都不同），
因此「复用学校 Cookie 直接访问座位接口」是行不通的。实际流程：

```
BIT101 登录（学校统一身份认证，BIT-Login SDK）
    ↓
尝试静默换取：POST seatlib.bit.edu.cn/api/cas/user
    ├─ 成功 → 拿到 JWT，Authorization: bearer{token}
    └─ 失败 → App 内 WebView 完成 phpCAS 登录
                 ↓ 拦截含 cas= / ticket= 的 URL 取 ticket
                 ↓ 同步 WebView cookie 到 OkHttp 的 cookie store
                 ↓ POST /api/cas/user（带 ticket）换 JWT
```

要点：
- **必须在 App 内 WebView 完成 CAS**。曾尝试引导外部浏览器登录，但 Android 沙箱下
  OkHttp 读不到浏览器的 cookie，方案不可行。
- 学校域名（`*.bit.edu.cn`）一律留在 WebView 内，否则 SSO 会被系统踢去外部浏览器；
  且必须等布局完成后再 `loadUrl`（SSO 的前置脚本在 `innerHeight = 0` 时会因 `body` 为空抛错）。
- `api/cas/user` 返回的 `member` 已认证时是 **JSON 对象而非数组**，用 `optJSONArray` 取恒为 null。
- JWT 加密落盘；失效后首次请求会**清空会话并回到登录态**。

### 学校 SSO 二次验证

学校对短时间内的多次登录会触发风控，要求短信 / 邮件 / 扫码二次验证。
**不要自己拼这套流程** —— 直接复用 `com.github.BIT101-dev.BIT-Login:bit-login:v4.0.2`
（App 本就通过 `:api` 依赖它）：`SsoLogin(smsCodeCallback).login(user, pwd, callbackUrl)`
已实现风控指纹（USTC）、CSRF、URL 加密取手机号、二次验证页解析、短信发码与校验。
座位模块指定 `callbackUrl = https://seatlib.bit.edu.cn/api/cas/cas` 即可。

> 冷却机制实测：隔数小时后再登，密码可直登通过，非常态障碍。

### 座位数据接口

```
API: seatlib.bit.edu.cn/api/Seat/tree     → 校区/楼层/区域三级树
API: seatlib.bit.edu.cn/api/Seat/date     → 可约日期与时段
API: seatlib.bit.edu.cn/api/Seat/seat     → 座位列表（含状态与百分比坐标）
API: seatlib.bit.edu.cn/api/seat/map      → 房间底图（五种状态各一张）
API: seatlib.bit.edu.cn/api/Seat/confirm  → 确认预约
API: seatlib.bit.edu.cn/api/index/subscribe → 「我的预约」列表
API: seatlib.bit.edu.cn/api/Space/cancel  → 取消预约
```

完整的服务端契约、参数陷阱与实测结论见
**[docs/seatlib-contract.md](docs/seatlib-contract.md)**（联调前必读）。

---

## 模块结构

```
features/seat/
├── api/
│   ├── SeatHttp.kt            # 共享 OkHttpClient 与 cookie 桥（WebView ↔ OkHttp）
│   ├── SeatCookieJar.kt       # okhttp3.CookieJar ↔ java.net.CookieManager 转换
│   ├── SeatApi.kt             # 座位业务接口封装；token 的读写、持久化与认证失效识别
│   ├── SeatResponseParser.kt  # 服务端响应解析（容错各种类型不一致的字段）
│   ├── SeatSession.kt         # CAS 换 JWT（静默认证 / ticket 换取）
│   ├── SeatSmsChallenge.kt    # 学校 SSO 二次验证（短信）状态
│   └── SeatTaskRepository.kt  # 任务状态的唯一持有者（ViewModel 与服务共享）
├── model/
│   ├── Seat.kt                # 座位、时段数据
│   ├── Task.kt                # 预约任务（模式/状态/存活信息）+ JSON 序列化
│   ├── Reservation.kt         # 「我的预约」记录（签到时限、取消次数）
│   └── Area.kt                # 座位树节点
├── ui/
│   ├── component/             # SeatMapCanvas（底图+热区+缩放）、SeatGrid、SeatColors、
│   │                          #   LocationPicker、ModeSelector、ErrorCard、NotificationPermission
│   └── screen/                # SeatMapScreen、NewTaskScreen、TaskListScreen、
│                              #   TaskListLogic（纯逻辑）、CasLoginScreen
├── SeatViewModel.kt           # MVVM 状态管理（Hilt 注入），不执行轮询
├── SeatMonitorService.kt      # 前台服务：实际轮询与结果通知
├── SeatLog.kt                 # 统一日志出口（按 BuildConfig.DEBUG 开关）
└── SeatScreen.kt              # 入口，内部 NavHost 管理三页导航
```

任务执行链路：`SeatViewModel`（加/取消任务）→ `SeatTaskRepository`（状态 + 落盘）
→ `SeatMonitorService`（由任务流驱动，实际轮询）→ 结果通知。

### 桌面小组件（features/widget）

```
features/widget/               # 桌面小组件（课程 / DDL / 座位 三页）
├── WidgetLogic.kt             # 全部纯逻辑聚合：周次/节次/高亮/行数档位（32 条单测）
├── WidgetRepository.kt        # Room + 设置 → WidgetLogic 取数
├── WidgetViews.kt             # WidgetData → RemoteViews（页签/行/按钮 + PendingIntent）
├── BIT101WidgetProvider.kt    # AppWidgetProvider：onUpdate / 点击广播 / 渲染
├── WidgetPageStore.kt         # 每个实例记住当前页号（SharedPreferences）
├── WidgetRepositoryHolder.kt  # 仓库 Holder + Hilt EntryPoint 兜底
├── SeatWidgetSnapshot.kt      # 座位 → 组件的单向数据桥
├── WidgetRefreshWorker.kt     # WorkManager 兜底刷新 + WidgetUpdater
└── WidgetAppStartup.kt        # Hilt EntryPoint 接线（组件由系统实例化）
```

⚠️ **`features/widget` 不依赖 `features:seat`** —— 由座位侧
`SeatWidgetPublisher` 主动把快照写进 `SeatWidgetSnapshot`。这样组件进程拉起
不会连带初始化座位模块的重依赖，没开座位功能时另两页照常显示。

⚠️ **渲染用传统 `RemoteViews`，不用 Glance** —— Glance 的 `update()` 对已存在的
Session 不保证重跑渲染，点击后会「状态写了但画面不变」。踩坑全过程（含源码证据）
与验证清单见 **[docs/widget.md](docs/widget.md)**。

### 座位图渲染（易踩坑，改动前必读）

`/api/seat/map` 返回的五张图（free/book/close/leave/use）**不是互补图层，
而是五张各自完整的房间图** —— 同一位置在不同图里画的是不同图标。正确做法是
**每个座位按自身状态挑一张图、只显示那一块**，绝不能整体叠加
（叠加会让后叠的盖住先叠的，导致所有座位都显示成同一种状态）。

实现上按状态去重最多加载 5 张位图，在一个 `Canvas` 上逐座位用
`drawImage` 的 `srcOffset`/`srcSize` 做源图裁剪。**不要**用
`withTransform { translate(-x,-y) }` 套 `clipRect`（`clipRect` 也会被同一变换影响，
瓦片会被裁没）；**更不要**「每座位一个 `AsyncImage`」（100+ 座位会造出 100+ 节点、
请求与位图，主线程卡死触发 ANR）。

---

## 构建

```bash
cd BIT101-seat
./gradlew.bat assembleDebug        # 本机为 Windows，需用 .bat
# APK 输出: app/build/outputs/apk/debug/app-debug.apk

./gradlew.bat :features:seat:testDebugUnitTest    # 座位模块单元测试
```

环境的特殊情况（Gradle 缓存、JDK 版本、模拟器网络等）见 [DEVELOPMENT.md](DEVELOPMENT.md)。

---

## 测试状态

| 项目 | 状态 |
|------|------|
| Gradle 编译 | ✅ 通过（`:features:seat:compileDebugKotlin`） |
| 单元测试 | ✅ 全量通过（`./gradlew.bat :features:seat:testDebugUnitTest`） |
| Hilt 依赖注入 | ✅ 正常（release 构建已完整验证） |
| 预约 / 取消全链路 | ✅ 真机实测打通（真实预约成功 → App 内取消成功） |
| 三种模式预约行为 | ✅ 已接线；监控 / 优先已实测 |
| 后台保活 / 结果通知 | ✅ 前台服务轮询与结果通知已交付 |
| 座位图与官网一致性 | ✅ 逐像素比对，平均差 0.0211（与官方前端渲染一致） |

单元测试覆盖纯逻辑部分：任务 JSON 序列化与容错、任务状态机（终态保护）、
列表排序与分组、存活信息与倒计时文案、视角几何、座位状态与底图映射、
CAS 响应解析（含 `member` 为对象/数组两种形态）。

### 已知限制

- **学校 SSO 短时间多次登录会触发二次验证**（短信 / 邮件 / 扫码），且 seatlib 是
  **单会话**系统 —— 同账号在别处登录会立即互踢。联调时避免 App 与脚本同时登录。
- **seatlib 的 TLS 证书链不完整**（服务端只发叶证书，`openssl` 报 code 21）。
  这是服务端配置缺陷，建议向学校反馈；客户端已内置兜底证书
  （`res/raw/thawte_tls_rsa_ca_g1.pem` + `network_security_config.xml`，仅对 seatlib 生效）。
- Android 15 对 `dataSync` 前台服务有 6 小时/天上限（单任务上限 2 小时，在限内，
  但连续挂多个任务可能触顶）。
- 5-10 秒持续轮询 2 小时，耗电会比较明显 —— 这是「能抢到座」的必要代价。
- 失效 token 会让 `tree` 等**免认证**接口返回 HTTP 500 + 空 body（不带 token 则正常），
  已加会话探测：加载失败即探会话，失效就清 token 回登录门禁。

---

## 分支开发流程

本仓库已启用「**一个较大功能模块 = 一个分支**」的流程：

```
master（= 可发布状态，随时能打 APK）
  ├── feature/widget              桌面小组件
  ├── feature/schedule-edit       手动日程 + 手动课程（覆盖层方案）
  ├── feature/security            Cookie 加密 + 生物识别
  └── feature/map-navigation      地图定位导航（最后做）
```

约定：

- 分支**独立完成、独立验证**（编译 + 单测 + 模拟器/真机实测）后合回 `master`。
- 合并前在 `master` 打 tag 作为回退点（已有 `v1.5.3` 座位模块基线、`v1.6.0`/`v1.6.1` 组件）。
- **版本号只在合并回 `master` 时改**（根 `build.gradle` 的 `versions` 块），
  分支内不动 —— 避免多分支并行时冲突。
- 座位模块的既有约定在后续所有开发中沿用：`SeatLog` 统一日志出口、
  纯逻辑抽 `*Logic` 便于单测、`testDebugUnitTest` 必须全绿。

各分支的详细设计、可行性评估与执行顺序见
**[docs/upstream-feature-plan.md](docs/upstream-feature-plan.md)**。

---

## 相关文档

- [docs/seatlib-contract.md](docs/seatlib-contract.md) —— **seatlib 服务端契约与关键发现**
  （时段 id / 取消参数 / 单会话 / TLS 缺陷等，联调前必读）
- [docs/upstream-feature-plan.md](docs/upstream-feature-plan.md) —— **上游设想的梳理与分支规划**
- [docs/widget.md](docs/widget.md) —— **桌面小组件**的设计、Glance 陷阱与验证清单
- [ROADMAP.md](ROADMAP.md) —— 路线图与真机验证清单
- [DEVELOPMENT.md](DEVELOPMENT.md) —— 开发环境与构建细节
- [CHANGES.md](CHANGES.md) —— 变更记录
