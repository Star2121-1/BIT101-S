# BIT101-S

基于 [BIT101-Android](https://github.com/BIT101-dev/BIT101-Android) 扩展的座位预约功能模块。

---

## 与原项目不同点

| 维度 | BIT101-Android | BIT101-S（本仓库） |
|------|---------------|-------------------|
| 新增 Tab | 5个：卷、图、网、话、我 | +1个：**座**（座位预约） |
| 登录方式 | BIT-Login SDK 登录后自动持有学校 Cookie | **复用同一份学校 Cookie**，无需再次输入密码 |
| 座位 API 认证 | 无 | OkHttp + Bearer JWT，JWT 来自 seatlib 的 phpCAS 会话 |
| 会话持久化 | — | JWT 加密落盘，冷启动免重登 |
| 后台抢座 | — | 前台服务（`dataSync`）轮询，锁屏 / 退后台 / 进程重启均可续跑 |

---

## 使用方式

1. 打开 APP，进入 **"卷"** 页面，使用学号+密码登录 BIT101（完成学校统一身份认证）
2. 切换到底部 **"座"** 标签页
3. 进入后可进行以下操作：

| 页面 | 功能 |
|------|------|
| **预约** | 选择校区→楼层→区域、日期，查看座位图，点击空位预约 |
| **列表** | 查看当前所有预约任务状态 |

### 三种预约模式

| 模式 | 说明 |
|------|------|
| 单次预约 | 指定座位号，立即查询并尝试预约 |
| 监控预约 | 定时轮询目标座位，空出后自动预约 |
| 优先预约 | 定时轮询区域内所有空闲座位，优先抢最早可用的 |

---

## 原理

### 登录态共享（方案 A）

```
BIT101 登录
    ↓
BIT-Login SDK → 写入 LoginStatus.cookieManager (CookieManager)
    ↓
SeatApi 注入同一个 cookieManager
    ↓
访问 seatlib.bit.edu.cn 时自动携带学校 Cookie
    ↓
POST /api/cas/user 换取 JWT → Authorization: bearer{token}
```

座位 API 不需要独立登录，BIT101 登录成功后学校会话已存在，直接复用即可。

### 登录与换 JWT

BIT101 的学校会话与 seatlib 的 phpCAS 会话是**两套彼此独立的会话**，Cookie 不互通，
因此不能只靠「复用学校 Cookie」拿到 JWT。实际流程：

```
BIT101 登录（学校统一身份认证）
    ↓
尝试静默换取：POST seatlib.bit.edu.cn/api/cas/user
    ├─ 成功 → 拿到 JWT，Authorization: bearer{token}
    └─ 失败 → 打开 App 内 WebView 完成 phpCAS 登录
                 ↓ 拦截含 cas= 的 URL 取 ticket
                 ↓ 同步 WebView cookie 到 OkHttp 的 cookie store
                 ↓ POST /api/cas/user（带 ticket）换 JWT
```

要点：
- **必须在 App 内 WebView 完成 CAS**。曾尝试引导外部浏览器登录，但 Android 沙箱下
  OkHttp 读不到浏览器的 cookie，方案不可行。
- `api/cas/user` 返回的 `member` 是 **JSON 对象而非数组**，用 `optJSONArray` 取恒为 null。
- JWT 加密落盘；token 过期时首次 401 会清空会话并提示重新登录。

### 座位数据结构

```
API: seatlib.bit.edu.cn/api/Seat/tree   → 校区/楼层/区域三级树（type=1 为区域）
API: seatlib.bit.edu.cn/api/Seat/date   → 可用时间段
API: seatlib.bit.edu.cn/api/Seat/seat   → 座位列表（含状态）
API: seatlib.bit.edu.cn/api/Seat/confirm → 确认预约
API: seatlib.bit.edu.cn/api/Space/cancel → 取消预约
```

---

## 模块结构

```
features/seat/
├── api/
│   ├── SeatHttp.kt            # 共享 OkHttpClient 与 cookie 桥（WebView ↔ OkHttp）
│   ├── SeatCookieJar.kt       # okhttp3.CookieJar ↔ java.net.CookieManager 转换
│   ├── SeatApi.kt             # 座位业务接口封装；token 的读写、持久化与 401 识别
│   ├── SeatSession.kt         # CAS 换 JWT（静默认证 / ticket 换取）
│   └── SeatTaskRepository.kt  # 任务状态的唯一持有者（ViewModel 与服务共享）
├── model/
│   ├── Seat.kt                # 座位、时段数据
│   ├── Task.kt                # 预约任务（模式/状态）+ JSON 序列化
│   └── Area.kt                # 座位树节点
├── ui/
│   ├── component/             # SeatGrid、SeatColors、CascadingDropdown、ModeSelector、ErrorCard、NotificationPermission
│   └── screen/                # SeatMapScreen、NewTaskScreen、TaskListScreen、CasLoginScreen
├── SeatViewModel.kt           # MVVM 状态管理（Hilt 注入），不执行轮询
├── SeatMonitorService.kt      # 前台服务：实际轮询与结果通知
├── SeatLog.kt                 # 统一日志出口（按 BuildConfig.DEBUG 开关）
└── SeatScreen.kt              # 入口，内部 NavHost 管理三页导航
```

任务执行链路：`SeatViewModel`（加/取消任务）→ `SeatTaskRepository`（状态 + 落盘）
→ `SeatMonitorService`（由任务流驱动，实际轮询）→ 结果通知。

---

## 构建

```bash
cd F:\Agent_Work\BIT-102\BIT101-seat
./gradlew assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

---

## 相关文档

- [docs/seatlib-contract.md](docs/seatlib-contract.md) —— **seatlib 服务端契约与关键发现**（时段 id / 取消参数 / 单会话 / TLS 缺陷等，联调前必读）
- [ROADMAP.md](ROADMAP.md) —— 路线图与真机验证清单
- [DEVELOPMENT.md](DEVELOPMENT.md) —— 开发环境与构建细节
- [CHANGES.md](CHANGES.md) —— 变更记录
## 测试状态

| 项目 | 状态 |
|------|------|
| Gradle 编译 | ✅ 通过（`:features:seat:compileDebugKotlin`） |
| 单元测试 | ✅ 31 条通过（`./gradlew :features:seat:testDebugUnitTest`） |
| Hilt 依赖注入 | ✅ 正常 |
| 座位页面 UI 渲染 | ✅ 正常（模拟器 Pixel_6_API_34） |
| 真实座位数据加载 | ⏳ 需连接 seatlib.bit.edu.cn |
| 三种模式实际预约行为 | ⏳ 需真机 + 校园网 |
| 后台保活 / 结果通知 | ⏳ 需真机验证 |

单元测试覆盖纯逻辑部分：任务 JSON 序列化与容错、任务状态机（终态保护）、
CAS 响应解析（含 `member` 为对象/数组两种形态）。

### 已知问题

- **模拟器无法联调 seat**：学校防火墙封锁到 `10.0.0.0/8` 的 TCP 443，必须真机 + 校园网
- Android 15 对 `dataSync` 前台服务有 6 小时/天上限（单任务最长 2 小时，在限内）
- 5-10 秒持续轮询 2 小时，耗电会比较明显
