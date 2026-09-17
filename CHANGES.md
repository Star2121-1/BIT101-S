# CHANGES

## 2026-09-17 M2.2 任务列表持久化 + 三处缺陷修复

### M2.2 任务列表持久化

**背景**：`_tasks` 原先只在内存中，杀进程后任务列表全部丢失。同时 M2.3 的后台保活需要任务定义能在 ViewModel 之外被读写，因此持久化是它的**前置条件**。

**新增**（`config` 模块，新建 `config/seat/` 包）
- `config/seat/base/SeatTaskStore.kt`：公开接口，暴露 `tasks: SettingItem<String>`
- `config/seat/DefaultSeatTaskStore.kt`：`internal` 实现，绑定 `UserDataStore.seatTasks`
- `config/seat/SeatConfigModule.kt`：Hilt 绑定

**修改**
- `UserDataStore`：新增 `seatTasks`（普通 DataStore，任务不属于敏感信息）
- `DefaultLoginStatus.clear()`：登出时一并清除任务
- `model/Task.kt`：新增 `toJson()` / `toReservationTask()` / `serializeTasks()` / `deserializeTasks()`，使用模块已有的 `org.json`，不引入新依赖；枚举值未知时回落到安全默认值，脏数据不会导致崩溃
- `SeatViewModel`：注入 `SeatTaskStore`；启动时恢复任务；`addTask` / `cancelTask` / `updateTaskStatus` 变更后落盘

> ⚠️ 当前恢复的任务若为「运行中 / 等待中」，会被标记为 `FAILED` 并提示「应用已重启，任务未继续运行」—— 因为此刻还没有后台执行能力。**M2.3 完成后应改为续跑。**

### 顺带修复的三处缺陷

1. **持久化可能乱序**：原先每次变更各自 `launch` 一个协程写 DataStore，协程不保证 FIFO，崩溃恢复可能读到比实际更旧的状态。改为**单写入者**：状态变更只更新 `pendingTasksJson`，由唯一收集者经 `distinctUntilChanged` 串行写入。
2. **认证失效后任务无限空转**：`handleApiError` 命中 `TOKEN_EXPIRED` 时只清 token，正在轮询的任务会继续以 5 分钟退避无限重试，而用户看不到「需要重新登录」。新增 `stopRunningTasks()`，认证失效时终止所有在跑任务并置为 `FAILED`。
3. **终态被覆盖的竞态**：`stopRunningTasks` 置为 `FAILED` 后，紧接着的状态更新会把任务改回 `RUNNING`。给 `updateTaskStatus()` / `cancelTask()` 增加终态保护（成功 / 失败 / 已取消不再被覆盖）。

另：`_seatlibReady` / `_isLoggedIn` 由 `var` 改为 `val`。

**验证**：`:features:seat:compileDebugKotlin` BUILD SUCCESSFUL。
**待真机验证**：杀进程重开后任务列表是否还在、登出后是否清空。

---

## 2026-09-17 M2.1 持久化 seatlib JWT，冷启动免重登

**背景**：`SeatApi.token` 原先是纯粹的实例字段（内存），App 一重启就丢失，每次冷启动都要重新走一遍 WebView CAS 登录才能用。

**方案**：沿用项目现有的持久化约定（`SettingItem<T>` + DataStore + Hilt 绑定），新增座位会话专用的 `SeatStatus`。seatlib 的 phpCAS 会话与 BIT101 学校会话是两套独立体系，因此**不并入通用的 `LoginStatus`**，避免把座位概念塞进公共接口。

**新增**（`config` 模块）：
- `config/user/base/SeatLoginStatus.kt`：公开接口，仅暴露 `token: SettingItem<String>`
  （**命名刻意避开 `SeatStatus`** —— 该名字已被座位模块的座位可用状态枚举占用，最初同名导致 KSP 报 `error.NonExistentClass`）
- `config/user/DefaultSeatLoginStatus.kt`：`internal` 实现，绑定到 `UserDataStore.seatToken`

**修改**：
- `UserDataStore`：新增 `seatToken`，使用 **`EncryptedPreferencesItem`**（与学号/密码同级的加密存储，因为它是凭据）
- `UserModule`：新增 `bindSeatLoginStatus` 绑定
- `DefaultLoginStatus.clear()`：登出时**一并清除 seat token**（学校会话失效后 phpCAS 会话也随之失效，避免残留显示「已登录」）
- `SeatViewModel`：
  - 注入 `SeatLoginStatus`
  - 新增 `setSeatToken()` 统一入口，**所有 7 处 token 写入都收敛到这里**并同步持久化，避免以后新增写入点时遗漏
  - `init` 中先恢复持久化 token，再决定 UI 状态，最后尝试静默认证刷新

**行为**：冷启动若存在持久化 token，直接进入可用状态，无需再走 WebView 登录；若 token 已过期，首次 API 调用返回 401 后由已有的 `handleApiError` 清理并回到登录态（自愈）。

**验证**：`:features:seat:compileDebugKotlin` BUILD SUCCESSFUL。
**待真机验证**：杀进程重开是否免登录、登出 BIT101 后座位侧凭据是否被清除。

---

## 2026-09-17 M1.2 统一单次预约 + M2.4 轮询退避与时长上限

### M1.2 统一单次预约实现（保留座位图路径）

此前单次预约存在**两套并行实现**：`SeatMapScreen.reserveSeat()` 直连 `confirmSeat`（实际在用的），以及 `executeSingleReserve()`（`addTask` 那条）。经确认**保留座位图路径**——它可以在座位图上精确挑座，体验更好、代码更少。

- 删除 `executeSingleReserve()`
- `addTask()` 入口拦截 `TaskMode.SINGLE` 并直接返回
- `TaskMode.SINGLE` **保留**，仅用于 `NewTaskScreen` 的模式选择（决定显示「选择座位」还是「创建任务」）

### M2.4 轮询退避与最长运行时长

此前 `executeMonitor` / `executePreferReserve` 是**固定 10s / 5s 的无上限 `while(true)` 轮询**，任务创建后会一直轮询到成功或手动取消，容易触发服务端限流。

- 新增可调常量：`MAX_TASK_DURATION_MS`(2 小时)、`MAX_POLL_INTERVAL_MS`(5 分钟)、`MONITOR_INTERVAL_MS`(10s)、`PREFER_INTERVAL_MS`(5s)
- **指数退避**：连续查询失败时轮询间隔每次翻倍，封顶 5 分钟；查询成功后立即重置为基准间隔
- **时长上限**：单任务运行超过 2 小时自动停止，状态置为 `FAILED` 并提示「已超过最长…时长，任务自动停止」
- 任务消息中显示下次重试倒计时（便于真机观察退避是否生效）
- 顺带抽取 `resolveTaskSegment()`，消除 `executeMonitor`/`executePreferReserve` 中重复的时段解析块；并修掉原代码 `dates.first()` 在时段列表为空时会抛异常的隐患（现返回 null 由调用方置为失败）

**验证**：`:features:seat:compileDebugKotlin` BUILD SUCCESSFUL。
**待真机验证**：监控/优先的实际轮询表现、退避是否按预期生效、2 小时上限。

---

## 2026-09-17 M1 补齐核心功能：接线三种预约模式 + 修两个座位图缺陷

**背景**：`SeatViewModel.addTask()` 此前在全仓库无任何调用点，导致「监控预约 / 优先预约」两种模式点了没反应、任务列表恒为空。实现代码（`executeSingleReserve`/`executeMonitor`/`executePreferReserve`）本身是完整的，只是从未被接线。

### M1.1 接线 `addTask()`

- `NewTaskScreen`：新增座位号输入框与「创建任务」按钮。**监控模式座位号必填**（需要指定目标座位），**优先模式可留空**（表示在所选区域内抢最早空出的座位）；单次模式保持原有「选择座位」→ 座位图路径不变
- `NewTaskScreen`：新增 `onTaskCreated` 回调
- `SeatScreen`：把 `onTaskCreated` 接到 `navController.navigate("tasks")`，创建任务后自动跳到列表页
- `SeatScreen`：修正底部 Tab 高亮的默认路由（原为 `"tasks"`，与 `startDestination = "new_task"` 不一致）

**效果**：三种预约模式均可创建任务，任务列表不再是空态。

### M1.3 修复取消预约后的日期/时段错乱

原实现存在两个缺陷：
1. 取消后重载座位图时**硬编码 `LocalDate.now()`**，导致查看「明天」时取消会跳回「今天」
2. 重载时**丢失时段参数**（回落为 `segmentId="1"`、`08:00-22:30`），可能展示错误时段的座位状态

修法：新增 `SeatQuery` 数据类并挂到 `SeatMapState.query`，加载时记录实际使用的查询参数；`cancelReservation()` 改为 `suspend` 并返回错误信息，成功后按 `query` 原样重载。
`SeatMapScreen` 相应展示真实结果（成功/失败），不再笼统提示「已尝试取消」。

### M1.4 修复座位图时段参数竞态

原实现中 UI 侧根据 `seatDates` 计算 `segId`/`startTime`/`endTime`，但 `LaunchedEffect(areaId, day)` 未把它们纳入 key —— 若 `seatDates` 晚于副作用加载，就会用回落值发出一次错误请求且不会重试。

修法：把时段解析下沉到 ViewModel 的 `openSeatMap(areaId, day)` + `resolveSegmentParams()`，**先确保时段数据就绪（必要时主动拉取）再发座位请求**，参数解析完记录进 `query` 供 UI 读取。同时消除了「从任务列表直接进入座位图」时 `seatDates` 为空的问题。

**验证**：`:features:seat:compileDebugKotlin` BUILD SUCCESSFUL。
**待真机验证**：三种模式的实际预约行为、监控/优先的轮询表现（模拟器无法访问 seatlib）。

---

## 2026-09-17 代码清理：移除被 WebView 方案取代的死代码

**背景**：`features/seat` 自 2026-08-29 集成后经历多轮方案迭代（OkHttp CAS 抓取 → 外部浏览器 → App 内 WebView），遗留大量已废弃实现。本次按全仓库调用点逐一核实后清理。

**移除**（均已确认无任何调用点）：
- `SeatSession`：`login()`（约 200 行 OkHttp CAS 抓取链）、`findFieldValue()`、`AESUtils`、`noRedirectClient`、`parseSalt()`/`parseExecution()`/`findCasField()`。保留仍在使用中的 `authenticateSeatlib()`。文件由 350 行降至 118 行
- `SeatCasLogin`：`openCasLogin(context)`、`hasActiveSession()`
- `SeatViewModel`：`login(username, password)`、`logout()`、`loginResultFlow`；`ensureSeatlibSession(context)` 中未使用的 `context` 参数（同步更新 `SeatMapScreen` 两处调用，移除 `LocalContext` 依赖）
- `SeatModule.kt`：无任何绑定的空 Hilt 模块

**保留说明**：`addTask()` 与 `executeSingleReserve`/`executeMonitor`/`executePreferReserve` 当前虽无 UI 调用点，但属待接线功能的现成骨架，**予以保留**，待后续接通 ModeSelector。

**效果**：`features/seat` 由 2145 行降至 1858 行，净减 287 行。

**文档**：合并 `CHANGES.md` 中两段完全重复的「2026-08-29 登录状态快速响应」记录。

**已知遗留（本次未处理）**：
- 三种预约模式中仅「单次」可用，监控/优先未接线
- `cancelReservation()` 重载座位图硬编码 `LocalDate.now()`，忽略当前查看日期
- `SeatMapScreen` 的 `segId`/`startTime`/`endTime` 未纳入 `LaunchedEffect` key
- JWT token 仅存内存，无持久化
- `features/seat` 缺少 `consumer-rules.pro`/`proguard-rules.pro`（其余 16 个 feature 模块均有）
- 仓库缺 `.gitattributes`，`gradlew` 在 `core.autocrlf=true` 下被检出为 CRLF，Git Bash 中无法执行

---

## 2026-08-30 seatlib phpCAS WebView 登录方案（修复浏览器 cookie 隔离问题）

**问题**：外部浏览器登录后，App 的 OkHttp 无法读取浏览器 cookie（Android 沙箱隔离），导致始终拿不到 JWT token。

**根因**：CAS 流程需要执行 JavaScript 重定向链（JS SPA → phpCAS → seatlib），OkHttp 无法完成；WebView 与 OkHttp 共享同一 `SharedPreferencesCookieStore`，可以桥接。

**方案**：用 Accompanist WebView 替代系统浏览器，在 App 内部完成 CAS 流程
- CAS 登录页面在 WebView 中加载 → SSO 自动认证 → phpCAS 回调带 `cas=TICKET`
- `shouldOverrideUrlLoading` 拦截含 `cas=` 参数的 URL → 提取 ticket → 调 `api/cas/user` 换 JWT
- WebView 与 OkHttp 共享 CookieManager，登录后 token 立即可用

**修改**：
- 新增 `CasLoginScreen.kt`：Accompanist WebView 实现 CAS 登录页面
- `SeatViewModel`：新增 `casLoginFlow`、`openCasLoginScreen()`、`trySeatlibAuth()`、`exchangeTicket(ticket)`
- `SeatScreen`：`casLoginFlow=true` 时显示 WebView 登录页
- `SeatMapScreen`：预约/取消时检测 BIT101 登录状态，未登录则打开 CAS 登录页
- `build.gradle`：seat 模块增加 `accompanist-webview` 依赖

---

## 2026-08-30 seatlib phpCAS 浏览器登录方案（已废弃，见上）

**问题**：OkHttp 无法完成 seatlib 的 phpCAS 认证（CAS 是 JS SPA，返回 HTTP 200 而非 302）。`authenticateSeatlib()` 和 `login()` 均失败。

**根因**：学校防火墙阻止模拟器 TCP 443 → 10.0.0.0/8，seatlib 只能通过真机直连访问。CAS 认证流程需要浏览器执行 JavaScript 重定向链（JS→PHP→CAS→seatlib），OkHttp 无法模拟。

**方案**：改为"按需引导浏览器登录"模式
- `isLoggedIn` 基于 BIT101 登录状态，进入座位页面无闪烁
- 点击"预约"/"取消预约"时调用 `ensureSeatlibSession(context)`：
  - 有 cookie session → 刷新 token，直接操作
  - 无 session → 打开 seatlib 主页（已有 SSO cookie 自动完成 phpCAS 认证）
  - 引导用户："请在弹出的浏览器中完成 seatlib 登录，然后重试"
- 浏览器登录后 phpCAS session 建立，App 后续 API 调用即可正常认证

**修改**：
- 新增 `SeatCasLogin.kt`：`openCasLogin(context)` 打开浏览器；`trySilentAuth()` / `hasActiveSession()` 检查现有 session
- `SeatViewModel`：移除 `tryAutoLogin()`，改为 `ensureSeatlibSession(context)` suspend 函数
- `SeatMapScreen`：预约/取消按钮增加 session 检查，未认证时打开浏览器并提示
- `SeatSession`：增加 CAS SPA HTTP 200 响应处理（检测 cas= 参数并调用 api/cas/user）

---

## 2026-08-29 登录状态快速响应

**问题**：用户已登录 BIT101 后进入"座"页面，会先显示"登录"按钮，约 2 秒后才跳转回正常预约界面。

**根因**：`_isLoggedIn` 初始硬编码为 `false`，需等待异步 seatlib token 获取完成后才变为 `true`。UI 实时收集 `isLoggedIn` StateFlow 时在此期间显示登录按钮。

**修改**：
- `SeatViewModel.kt`：`init` 块内先同步读取 `loginStatus.status.get()` 设置 `_isLoggedIn`，无需等待 seatlib token；异步流程保留用于获取真实 seatlib JWT token
- `updateLoginState()` 直接操作 `_isLoggedIn.value`（移除 helper 函数冗余）
- 所有 token 设置处统一改为 `_isLoggedIn.value = true`

**效果**：BIT101 已登录 → 进入 Seat 页面无闪烁，立即显示预约界面。

---

## 2026-08-29 修复并发登录导致 token 丢失

**问题**：预约座位时返回"该空间当前时段不可预约"（实际可预约），token 为空导致服务端返回"您尚未登录"。

**根因**：`SeatViewModel.init` 块中的 `tryAutoLogin()` 和 `loginStatus.status.flow` 监听器中的 `tryAutoLogin()` 两个协程同时执行 CAS 登录。第一个成功设置 token，但第二个同时失败后覆盖了 token，导致后续请求无认证信息。

**日志证据**：
```
00:20:30 auto-login with stored credentials
00:20:31 authenticateSeatlib failed → tryAutoLogin() (second call)
00:20:32 auto-login failed: ③ CAS fail HTTP 200  ← 覆盖了第一个的成功结果
00:20:33 loadSeatTree: token=  ← token 已被清空
00:20:34 interceptor: auth=NONE, tokenLen=0  ← confirmSeat 无 token
```

**修改**：
- `SeatViewModel.kt`：添加 `@Volatile autoLoginInProgress` 标志，防止并发执行 `tryAutoLogin()`
- `SeatApi.kt`：添加 interceptor 请求/响应日志，便于调试

**效果**：并发登录竞争消除，token 不再被意外覆盖。

---

## 2026-08-29 风格统一：Seat 页登录按钮

**问题**：Seat 三个子页面（NewTask/TaskList/SeatMap）在未登录时显示自定义 AlertDialog，与其他页面（Schedule/Gallery）的居中"登录"按钮风格不一致。

**修改**：
- `SeatScreen.kt`：新增 `mainController` 参数，传递给子页面
- `NewTaskScreen.kt`：未登录时显示居中"登录"按钮，登录后显示预约表单；移除自定义弹窗
- `TaskListScreen.kt`：同上，移除"未登录"文字提示，改为中心化登录按钮
- `SeatMapScreen.kt`：未登录时覆盖整个座位图显示登录按钮；移除自定义弹窗
- `IndexScreen.kt`：SeatScreen 用 `WithLoginStatus` 包裹，传入 `mainController`

**效果**：Seat 页面未登录体验与其他功能页完全一致。

---

## 2026-08-29 Seat 模块自动登录修复

**问题**：用户在 BIT101 登录后进入 Seat 页面，点击"选择座位"/"列表"均弹出"需要登录"提示。

**根因分析**：
1. seatlib 是独立 phpCAS 系统，BIT101 学校 Cookie 无法直接建立 seatlib session
2. `authenticateSeatlib()` 静默认证失败（cookie 不互通）
3. `SeatSession.kt` 解析 `api/cas/user` 返回的 `member` 字段时用了 `optJSONArray`，但 API 实际返回单个对象

**修改**：
- `SeatSession.kt`：`optJSONArray("member")` → `optJSONObject("member")`，修复 JSON 解析
- `SeatViewModel.kt`：监听 `LoginStatus.status.flow` 变化；新增 `tryAutoLogin()` 用存储的学号密码完整 CAS 登录
- `isLoggedIn` 改为 `StateFlow<Boolean>`，通过 `seatApi.token.isNotEmpty()` 驱动 UI
- `NewTaskScreen`/`TaskListScreen`/`SeatMapScreen`：使用新 `isLoggedIn` StateFlow，未登录弹提示框

**效果**：用户首次登录 BIT101 后进入 Seat 页面，自动完成 seatlib CAS 认证，无需手动输入密码。

---

## 2026-08-29 Seat 模块网络调试

**问题**：模拟器无法访问 seatlib.bit.edu.cn（学校防火墙封锁 TCP 443 到 10.0.0.0/8）。

**尝试**：路由器静态路由、Node.js HTTPS 代理（port 8443）均失败。

**结论**：模拟器无法用于 seat 功能调试，必须使用真机 + school WiFi。真机直连 seatlib 完全正常（HTTP 200）。

---

## 2026-08-29 Hilt 依赖注入修复

**问题**：点击底部"座"按钮后 App 崩溃（SIG 9 Killed）。

**根因**：`SeatViewModel` 缺少 `@HiltViewModel` 注解，且使用了不兼容的 `@ViewModelScoped`。

**修改**：
- `SeatViewModel.kt`：添加 `@HiltViewModel`，移除 `@ViewModelScoped`
- `SeatModule.kt`：简化为空模块（移除错误的 `@Singleton` binding）

---

## 2026-08-29 Seat 模块集成

**背景**：将座位预约功能集成进 BIT101-Android，复用已登录的学校会话。

**新增文件**：
- `features/seat/` 模块：SeatViewModel、SeatSession、SeatApi、模型类、UI 组件
- `IndexViewModel.kt`：添加 Seat 图标和路由
- `IndexScreen.kt`：添加 Seat composable route
- `build.gradle`（根目录、features 目录）：更新 include 配置

**核心功能**：
- 座位树查询（校区→楼层→区域级联）
- 图形化座位选择
- 单次/监控/优先三种预约模式
- 任务列表管理
- seatlib CAS 认证（通过 BIT101 会话自动恢复）
