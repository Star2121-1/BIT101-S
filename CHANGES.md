# CHANGES

## 2026-09-18 真机联调：打通预约/取消全链路（凭据直登 + 三处服务端契约修正）

**环境**：真机 NP05J（Android 16，校园网内），App 全程无崩溃。

### 新增：账号密码直登（纯 HTTP CAS）
- 学校 SSO 登录页在 WebView 里不渲染表单（模拟器/真机双端复现：Angular 在跑、页脚渲染，但登录区空白），WebView 路径死结无法在客户端根治
- 照搬 JAVA 侧已验证方案移植进 `SeatSession.loginWithCredentials()`：
  GET 登录页取 salt/execution → AES/ECB/PKCS5 加密密码 → 不跟随重定向 POST 表单 → 两跳 302 从 hash 路由取 phpCAS code → `/api/cas/user` 换 JWT
- **必须用隔离的内存 CookieJar**：共享 jar 里的陈旧 phpCAS 会话会让票据校验走岔（302 到 authserver 登录页）；JAVA 侧可用实现同样是"每次登录清 cookie"
- `CasLoginScreen` 新增原生凭据表单（默认主路径），WebView 降级为"改用网页登录"
- 真机实测：直登成功、短信验证码不需要（BIT101 登录才要，CAS 不要）

### 修正：confirm 的 segment 必须是真实时段 id（此前恒 500）
- `/api/Seat/date` 的 `times[].id` **只在 `build_id=区域id` 时才非空**（实测区域 4 → id=355533；不带/带校区 id 恒 null）
- 旧代码 `resolveSegmentParams` 缓存优先，而缓存来自无 build_id 的调用（id 全空）→ 永远回落 `"1"` → confirm **HTTP 500**（PHP 对不存在的时段 id 直接崩）
- 修复：`resolveSegmentParams` 始终按区域拉新并合并缓存；`confirmSeat` 的 seat_id 转 int

### 修正：取消预约要传预约记录 id，不是座位 id
- 实测：`/api/Space/cancel` 传 seat_id 恒「操作失败」；传 `/api/index/subscribe` 里该预约的 `id` 才成功
- `cancelSeat()` 改为先查 subscribe（按 `space`==座位 id 匹配，优先 status="2"）再取消

### 修正：取消路径在 UI 上不可达
- `SeatGrid` 此前只允许点 AVAILABLE 座位 → 「我已预约」选不中 → 取消按钮永远不出现
- 现在 RESERVED 也可点；选中自己的预约时底部只显示「取消预约」

### 修复：会话失效的自愈闭环
- `reserveSeat` 失败时调用 `handleApiError`：清 token、终止任务、UI 回到授权门禁；
  错误文案改为「登录已失效，请重新授权座位系统」（此前把内部信号 `TOKEN_EXPIRED` 直接显示给用户）
- 真机实测自愈全链路：token 被踢 → 预约报失效 → 门禁出现 → 凭据重授权 → 预约成功

### 真机验证记录（全部通过）
凭据授权 → token 持久化（重启免登录）→ 座位树/座位图真实数据（图例/剩余 45/55）→
**真实预约成功**（004，segment=355533）→ **App 内取消成功**（subscribe 链路）→
**监控任务**（盯 002）：前台服务启动、正确轮询报告「座位被占，继续监控…」、通知渠道
seat_monitor/seat_result 注册 → 取消任务 → 服务自动停止。
另实测 seatlib 为**单会话**（同账号他处登录互踢），探测时勿并行登录。

## 2026-09-18 模拟器联调：修好 CAS 授权链路（部分受阻）

在模拟器上用真实账号把 App 跑起来，把「座」功能一路验证到学校 SSO 登录页。
过程中又发现并修了 **4 个问题**，其中两个解释了项目历史上的悬案。

### 修复 1（悬案告破）：seatlib 的 TLS 证书链不完整

**这就是「模拟器连不上 seatlib」的真实原因**，与学校防火墙无关。

```
openssl s_client -connect seatlib.bit.edu.cn:443
→ 服务器只下发 1 张证书（叶），缺中间证书
→ Verify return code: 21 (unable to verify the first certificate)
```

对照组：同一张证书、同一个签发者，`login.bit.edu.cn` 与 `www.bit.edu.cn`
都正确下发了完整链（叶 → Thawte TLS RSA CA G1 → DigiCert Global Root G2）并验证通过。
**这是 seatlib 服务端单独的配置缺陷**（`ssl_certificate` 漏拼中间证书）。

为什么 Windows 上 curl 一直能通而 Android 不行：Windows 会自动按 AIA 补链，**Android 不会**。
所以这不是模拟器的问题，任何干净的 Android 客户端都过不了 TLS 握手。

**客户端兜底**（正确修法是服务端把中间证书拼进 `ssl_certificate`，建议向学校反馈）：
- 新增 `features/seat/res/raw/thawte_tls_rsa_ca_g1.pem`（中间证书，
  从 `login.bit.edu.cn` 的完整链中取得，**已密码学验证**确为签发 seatlib 叶证书的那张：
  `openssl verify -untrusted intermediate.pem -CAfile root.pem leaf.pem` → OK）
- 新增 `res/xml/network_security_config.xml`：仅对 `seatlib.bit.edu.cn` 追加该信任锚，
  其他域名维持系统策略不变
- seat 模块 manifest 声明 `android:networkSecurityConfig`（app 未设置，合并无冲突）

### 修复 2（高严重度）：CAS 登录页把学校 SSO 踢出 WebView

`CasLoginScreen.shouldOverrideUrlLoading` 原先只放行 `seatlib.bit.edu.cn`，
其余 URL 一律交给外部浏览器。而 phpCAS 登录**必然**要跳到
`login.bit.edu.cn` / `sso.bit.edu.cn` —— 结果是导航被拦截、WebView 停在空白页、
CAS 会话落进外部浏览器而 App 读不到 cookie。**整条「App 内 WebView 完成 CAS」的链路实际是断的。**

实测证据：修好后日志显示完整链路全部留在 WebView 内：
`seatlib/h5 → #/login → /api/cas/cas → login.bit.edu.cn → sso.bit.edu.cn/cas/login`。

修法：学校域名（`*.bit.edu.cn`）一律留在 WebView 内，只有真正的外链才交给浏览器。

### 修复 3：ticket 提取逻辑过于死板

原先用 `Regex("cas=([a-f0-9]{32})")` 硬匹配，phpCAS 的 ticket 形态
（如 `?ticket=ST-...`）一变就永远抓不到。改为按查询参数取
（`cas` 或 `ticket`），不做格式假设。

### 修复 4：WebView 从未真正加载过页面（两个叠加的时序问题）

1. `loadUrl` 写在 `AndroidView` 的 `update` 里 —— **update 只在重组时执行**，
   WebView 布局完成后若没有新的重组，它就再也不会被调用，页面根本不加载
2. WebView 在 Column 里没有尺寸约束，**首次测量高度为 0**，
   `pageFinished` 后的收尾逻辑（见下）也无法按宽高判断

修法：`Modifier.fillMaxWidth().weight(1f)` 给足空间；
用 `addOnLayoutChangeListener` 在「宽高非 0 的首次布局」时加载一次。

**为什么必须等布局完成**：学校 SSO 页面 `<head>` 里有一段内联脚本
`window.innerHeight || documentElement.clientHeight || document.body.clientHeight`，
WebView 未布局时前两个是 0（假值），会去读尚未存在的 `document.body` → 抛 TypeError，
脚本块中断、指纹对象缺失 → 登录表单不渲染。

### 修复 5：`onPageFinished` 只打日志

注释写着 "then sync+auth on finish"，实际只打了日志 —— 即使 phpCAS 会话已在
WebView 里建立，App 也永远不会去同步 cookie、换取 JWT。
现在回到 seatlib 域名且未登录时，会自动 `syncAndExchange(null)`（带 2 秒防抖）。

### 实测进度

| 步骤 | 结果 |
|------|------|
| 安装、启动、无崩溃 | ✅ |
| 外层门禁（BIT101 登录，含短信二次验证） | ✅ 登录成功，获取 8 个 Cookie |
| 内层门禁（识别「学校已登录但座位未授权」+ 正确引导） | ✅ 文案与按钮均按设计出现 |
| 静默认证（TLS 修复后） | ✅ 正确到达业务层判断（`member:[]` → 未登录）|
| CAS WebView 链路留在 App 内 | ✅ 修复后不再跳外部浏览器 |
| 走到学校 SSO 登录表单 | ⚠️ **页面不渲染**（见下） |

### ⚠️ 未解决：学校 SSO 页面在 WebView 中不渲染

`sso.bit.edu.cn/cas/login` 的 Angular 应用正常启动（控制台可见
`UsernamePassword`、「北京理工大学版权所有」等日志），但**画面始终空白**。
页面自身有脚本错误：`generateFingerprintObject is not defined`、
`Cannot read properties of null (reading 'clientHeight')`，另有一条 Mixed Content 拦截。

尝试过桌面 UA（会切换到 `cas-login-new` 资源包），未解决，已撤销。
由于该页面在软件渲染的模拟器里连自身布局脚本都会失败，
**不能排除是模拟器环境问题** —— 需在真机上复测；也可能是学校页面对 WebView 的兼容问题。

**当前的替代验证路径**：真机上完成学校登录后，seatlib 会话即可建立；
或者等学校修复 SSO 页面的脚本错误 / 我们改用无头 CAS 表单登录（工程量较大）。

### 验证

`compileDebugKotlin` BUILD SUCCESSFUL；`testDebugUnitTest` 51/51 通过。

---

## 2026-09-18 首次真实环境验证：修正三处会静默失效的假设

这一轮不再只是编译和单测 —— 直接对 `seatlib.bit.edu.cn` 发真实请求核对契约，
并在模拟器上把 App 跑起来。**结果发现之前三个「逻辑上说得通」的假设都是错的。**

### 环境结论更正

`DEVELOPMENT.md` 里「模拟器被学校防火墙挡在 seatlib 之外」的结论**不成立**（2026-09-18 实测）：
模拟器 `Pixel_6_API_34` 到 `10.0.11.162:443` TCP 建连成功，DNS 也能正确解析域名；
对照组（关闭端口、不可达 IP）均超时，结论可信。

### 修正 1：认证失败不是 401，而是 HTTP 200 + 业务码 10001

实测未认证时：

```
POST /api/Seat/confirm   → HTTP 200  {"code":10001,"message":"您尚未登录"}
POST /api/Space/cancel   → HTTP 200  {"code":10001,"message":"您尚未登录"}
```

而按 401 判定的实现（M2.5 我自己的改动，以及更早的原实现）**永远发现不了 token 失效**：
用户只会看到「预约失败: 您尚未登录」，不会被清理会话、也不会被引导重新登录，
后台监控任务则会一直重试到 2 小时上限。

修法：解析响应体里的业务码，`10001`（以及兜底匹配「尚未登录」文案）映射为 `TOKEN_EXPIRED`；
HTTP 401 判定保留为兜底。

### 修正 2：座位号是补零字符串

实测 `/api/Seat/seat` 返回 `"no":"001"`，而用户习惯输入 `"1"`。
`it.no == task.seatNo` 直接比较**永远匹配不上**，监控任务会一直显示「未找到座位」却不报错。

修法：新增 `seatNumberEquals()`，把补零写法与裸数字视为相同；
排序改用 `SeatNumberComparator`（数值排序，避免 `"10"` 排在 `"9"` 之前的错误顺序）。

### 修正 3：时段字段恒为 null，回落值是正常路径

实测 `/api/Seat/date`（带不带 `build_id` 都一样）：

```json
[{"day":"2026-09-18","times":[{"id":null,"status":1,"start":null,"end":null}]}, ...]
```

`id`/`start`/`end` 全是 null，只有 `day` 有效。也就是说代码里 `"1"` / `08:00` / `22:30`
的回落**不是边界处理，而是必经路径**。当前只返回今天与明天两天。

### 其余实测细节（已写入解析层注释与测试）

| 项 | 实测值 | 影响 |
|---|---|---|
| `type` | JSON 里是**字符串** `"1"` | 依赖隐式强转会让区域选不中；已改为显式转换 |
| `isValid` | 同一响应里既有字符串 `"1"` 也有数字 `1` | 未使用该字段，但说明类型不可假设 |
| 座位 `status` | 字符串 `"1"`=可约 / `"2"`=我已预约 / 其它=占用 | 未知状态按「被占用」处理，宁可漏约不可误约 |
| 座位字段 | 含 `point_x`/`point_y`/`width`/`height` | 真实座位图有坐标，当前 UI 未使用（可做平面图） |
| `/api/Seat/tree` | 徐特立馆 → 三层 → 视听学习空间(3) / 自然科学图书第一阅览室(4) … | 层级为 校区→楼层→区域，区域 `type=1` |

### 为可测试性做的结构调整

- 响应解析抽为顶层纯函数（`api/SeatResponseParser.kt`：`parseSeatTree` / `parseSeatDates` /
  `parseSeats` / `seatAuthFailure` / `intOrZero` / `errorText`），
  使**真实响应体可以直接作为测试输入**
- `seatNumberEquals` / `SeatNumberComparator` 放进 `model/Seat.kt`，同样是为了可测

### 测试：31 → 51 条

新增 3 个测试类共 20 条，**输入取自真实抓取的响应体**（而非手写样例 ——
手写样例只会迎合自己的假设，恰恰掩盖真实响应里的类型不一致）：

- `SeatResponseParserTest`（7）：真实树的字段/层级/区域判定、真实日期（null 时段）、
  真实座位的状态映射、`intOrZero` 的类型混合、`errorText` 的 msg/message 分支
- `SeatAuthFailureTest`（5）：真实未认证响应 → `TOKEN_EXPIRED`；业务错误不被误判为认证失效
- `SeatNumberTest`（8）：补零容错、非数字处理、数值排序

### 模拟器验证进度

已在 `Pixel_6_API_34` 上安装运行：App 启动正常（无崩溃）、底部 6 个 Tab 含「座」、
「座」页正确显示登录门禁。**进一步验证需要本人完成学校账号登录** ——
外层 `WithLoginStatus` 按 BIT101 登录状态门禁，未登录时整个「座」页不可达。

---

## 2026-09-17 M2.5 加固 + M3 工程质量 + M4 体验

### M2.5 401 自动登出加固（真机联调仍待做）

原实现有三个会导致「失效了但看不出来 / 自愈不了」的缝隙：

1. **拦截器只在「本地 token 非空」时才抛 `TOKEN_EXPIRED`**。本地 token 一旦被清空，
   服务端返回的 401 会被当成普通 HTTP 错误，401 自愈链路就断了。改为**所有 401 一律抛**——
   这个 client 只用于座位业务接口，出现 401 必然意味着会话失效。
2. **前台服务清空 token 时 UI 不知道**。`isLoggedIn` 只在 ViewModel 自己改动时更新，
   服务侧遇到 401 清空 token 后，界面仍显示「已登录」。新增 `SeatApi.tokenFlow`，
   ViewModel 订阅它，在「曾有 token → 变空」时同步状态（用「曾有过」做判据，
   避免启动初期误报）。
3. **`isLoggedIn` 的判据是 BIT101 登录状态**，不是 seatlib 会话。这两者是独立会话，
   会出现「UI 显示已登录、token 为空、接口实际无认证」的假象。改为以 seatlib token 为准，
   并新增 `bit101LoggedIn` 供 UI 区分两种引导：
   - 学校账号未登录 → 「登录」→ 跳全局登录页
   - 学校账号已登录但座位未授权 → 「授权座位系统」→ 直接换取会话 / 弹出 CAS WebView

同时新增会话失效提示（`authNotice`），在预约页与任务列表页展示，不再只是静默回到登录按钮。

`SeatApi.TOKEN_EXPIRED` 提为常量，替换散落三处的魔法字符串。

### M3.1 `.gitattributes`

新增，统一行尾：`gradlew` / `*.sh` 强制 LF，`*.bat` / `*.cmd` 强制 CRLF，
源码与配置显式声明 LF，二进制文件标记 `binary`（避免被行尾转换损坏）。

工作区里的 `gradlew` 原本是 CRLF（`core.autocrlf=true` 的检出结果），已修为 LF。

**注意一个环境限制**：本机 Git Bash（PortableGit 1.2.0）**即使行尾正确也无法执行 `./gradlew`** ——
它不会为无扩展名的 `java` 自动补 `.exe`，报 `No such file or directory`（而 `java.exe` 确实存在）。
这与行尾无关，属该 bash 的限制；本环境继续用 `./gradlew.bat`。

### M3.2 proguard 文件

`features/seat/build.gradle` 一直在引用 `proguard-rules.pro` 与 `consumer-rules.pro`，
但两个文件根本不存在。`minifyEnabled false` 时不会报错，一旦开 release 混淆就会踩坑。
已补齐；`consumer-rules.pro` 刻意留空并注明了原因（模块内没有依赖反射的入口）。

### M3.3 调试日志收敛

新增 `SeatLog`：按 `BuildConfig.DEBUG` 开关，并提供接受 lambda 的重载
（release 下连字符串拼接都不会发生）。需要在 `build.gradle` 显式启用 `buildConfig true`——
**AGP 8 起 library 模块默认关闭**，直接用 `BuildConfig.DEBUG` 会解析失败。

44 处 `Log.*` 全部改经此出口，并顺带处理了敏感信息：
- 原先的认证拦截器会把**完整 bearer token** 打进日志，改为不记录 token 本体
- `getSeatTree` / `confirmSeat` 原先打印完整响应体，已移除
- CAS ticket 改用 `SeatLog.mask()` 只留前 6 位与长度

### M3.4 + M3.5 抽取 CookieJar、复用 OkHttpClient

新增两个基础设施类：

- **`SeatCookieJar`**：`okhttp3.CookieJar` ↔ `java.net.CookieManager` 的转换。
  原先在 `SeatApi` / `SeatSession` / `SeatCasLogin` **三处各写一遍**，细节还有分歧
  （`maxAge` 类型、`domain`/`path` 兜底、是否设 `version`），现统一并补齐兜底。
- **`SeatHttp`**：共享客户端。`trySilentAuth()` 原先**每次调用**都新建 `OkHttpClient`，
  连接池与线程池全部白建；现在登录类请求共用 `session`，业务客户端从 `base()` 派生
  再追加认证拦截器。超时、协议、UA 三处原本不一致，现集中在 `base()`。

### 删除 `SeatCasLogin`

重构后它只剩两个纯转发的方法，没有存在价值：
`syncWebViewCookies()` → `SeatHttp`（cookie 桥的自然归属），
`trySilentAuth()` → `SeatSession`（会话管理的自然归属）。
同时移除 `SeatSession.jwtToken` —— 它是 `SeatApi.token` 的冗余副本，从未被读取过。
`SeatSession` 新增 `exchangeTicket()`，把原先内联在 ViewModel 里的 ticket 换取逻辑收敛回来。

### M3.6 单元测试（31 条，全部通过）

```
CasResponseTest           7 条
SeatTaskRepositoryTest   13 条
TaskSerializationTest     8 条
TaskStatusTest            3 条
```

- **任务 JSON 序列化**：往返一致性、未知枚举回落、脏数据不崩溃、字段缺失用默认值
- **任务状态机**：终态保护（SUCCESS 不能被改回 RUNNING）、`stopAll` 只影响在跑任务、
  `loadOnce` 的合并语义与「只执行一次」
- **CAS 响应解析**：回归 `member` 是对象而非数组的历史坑，
  并把「member 是数组时必须返回 null 而不是崩溃」固化下来

为支持测试：`TaskStatus.isTerminal` 从仓储的私有扩展提升到 `model/Task.kt`；
`parseCasUser` 抽为顶层函数。测试依赖显式加了 `org.json:json` ——
Android SDK 里的 `org.json` 在单元测试中是空壳，不替换会让所有解析类测试失效。

### M4.1 多日预约

日期 chip 原先固定为「今天 / 明天」两个，`/api/Seat/date` 返回的多日数据不可达。
改为读取服务端返回的可约日期（横向滚动，今天/明天显示为「今天」「明天」，其余显示日期），
接口未返回时回落到「今天/明天」避免空列表。

### M4.2 座位图信息与配色

- 抽出 **`SeatColors`** 作为配色与文案的**唯一来源**。此前图例与座位格各硬编码一份颜色，
  文案还不一致（图例「空闲」/ 格子「可约」）——图例存在的意义就是解释格子，
  两处独立演化迟早对不上，比没有图例更糟
- 图例补「已选中」（此前选中态的蓝色没有任何说明），改用 `FlowRow` 自动换行
- 加载失败增加「重试」按钮（原先只能退出去重进）
- 标题栏增加「换区域」直达入口（原先必须靠返回键）

### 顺带修复

`NewTaskScreen` 加载座位树时未检查登录态，未登录也会发请求并必然 401，现改为登录后才加载。

**验证**：`:features:seat:compileDebugKotlin` BUILD SUCCESSFUL；
`:features:seat:testDebugUnitTest` 31/31 通过。
**待真机验证**：401 自愈链路、后台保活、退避与结果通知、多日预约。

---

## 2026-09-17 M2.3 收尾：通知权限请求与预约结果通知

### 通知权限的运行时请求

前台服务的常驻通知此前只声明了权限、没有申请。新增 `ui/component/NotificationPermission.kt`：

- `hasNotificationPermission(context)`：Android 13 (API 33) 以下视为已授权（该权限从 33 才需要运行时申请）
- `rememberNotificationPermissionState()`：Compose 侧读取状态并封装申请动作，用 `rememberLauncherForActivityResult(RequestPermission())`，不引入新依赖
- 监听 `ON_RESUME` 重新核对 —— 用户可能去系统设置里手动开启后返回

接入两处：
- `NewTaskScreen`：点「创建任务」时若未授权则发起申请。**先建任务再申请** —— 权限只影响通知是否可见，不影响任务本身，不该因为一次授权拒绝而丢掉用户刚配好的任务
- `TaskListScreen`：存在活跃任务且未授权时，列表顶部显示常驻提示条（`errorContainer` 配色 + 「开启」按钮）。用常驻提示而非一次性 toast，因为这是持续状态

### 预约结果通知

此前只有「正在监控」的常驻通知，任务成功时它静默消失 —— 而抢到座位恰恰是最需要通知用户的时刻。新增独立的结果通知：

- 新渠道 `seat_result`（`IMPORTANCE_DEFAULT`）。与轮询进度分开：进度通知要安静（`IMPORTANCE_LOW`），结果通知要能提醒到人
- `syncJobs` 中在「任务离开执行集合」时触发，且**仅当该任务此前由本实例执行**（即存在于 `jobs`）—— 服务重建后列表里的历史任务不会被误报
- 只对 `SUCCESS` / `FAILED` 通知，`CANCELLED` 不通知（用户刚手动取消，再弹通知是噪音）
- 点击通知通过 `getLaunchIntentForPackage` 回到应用，不依赖具体 Activity 类名

### 修复：任务状态并发写导致丢失更新

`SeatTaskRepository` 原先用 `_tasks.value = _tasks.value.map { … }` 做读-改-写，**不是原子操作**。而 ViewModel（主线程）与服务（`Dispatchers.Default`）会并发写入：服务更新任务 A 状态的同时 ViewModel 新增任务 B，两者各自读到同一份旧列表，后写入的一方会**把另一方的变更整个丢掉**（表现为新任务凭空消失）。

改为 `MutableStateFlow.update {}`（内部 CAS 重试循环），所有变更路径（`add` / `updateStatus` / `cancel` / `stopAll`）统一使用。

同样地，`loadOnce()` 的 `@Volatile loaded` 标志只能防止重复读取，无法让并发调用方**等待**首次载入完成 —— 改为 `Mutex.withLock` 串行化，并把「覆盖」改为「合并」，避免载入期间新增的任务被冲掉。

### 修复：startForegroundService 的崩溃风险

Android 12+ 限制应用在后台启动前台服务，超限抛 `ForegroundServiceStartNotAllowedException`。原实现直接调用，异常会冒泡成崩溃。改为捕获并记日志 —— 任务本身仍在仓储里，用户回到前台时会再次尝试拉起。

**验证**：`:features:seat:compileDebugKotlin` BUILD SUCCESSFUL。
**待真机验证**：授权弹窗时机、拒绝后提示条是否出现、抢到座位时的结果通知、杀进程后自动续跑。

### 已知限制（待真机确认 / 后续处理）

- **Android 15 对 `dataSync` 前台服务有 6 小时/天上限**：单任务最长 2 小时在限内，但连续跑多个任务可能触顶
- 5-10 秒持续轮询 2 小时，电量消耗会比较明显

---

## 2026-09-17 M2.3 前台服务保活

### 为什么不用 WorkManager

原计划是「换 WorkManager」，但 **WorkManager 是为「可延迟任务」设计的** —— Doze / App Standby 会显著推迟执行（可能从 10s 拖到数分钟）。对需要 5-10 秒粒度抢座的应用来说等于功能失效。WorkManager 擅长的是「跨进程存活」，不是「准时执行」。

因此改用**前台服务**：可以持续执行，代价是一条常驻通知（也正好让用户知道正在后台抢座）。

### 架构调整：任务状态改为单一持有者

原先任务状态由 `SeatViewModel` 独享（内存 `_tasks` + 协程 `taskJobs`）。前台服务也要读写任务，两份状态必然分叉。因此：

- 新增 **`SeatTaskRepository`**（`@Singleton`）：任务状态的**唯一持有者**，负责内存缓存 + 落盘（单写入者串行写）。对外暴露只读 `tasks` 与 `add` / `cancel` / `updateStatus` / `stopAll` / `loadOnce`
- 新增 **`SeatMonitorService`**（`@AndroidEntryPoint` 前台服务，`dataSync` 类型）：**由仓储的任务流驱动** —— 收集 `repository.tasks`，为新增的活跃任务启动协程、为已结束的取消协程，无活跃任务时自行 `stopSelf()`
- `SeatViewModel` **不再自己轮询**：`addTask` 只写仓储并拉起服务，`cancelTask` 只改状态（服务的收集器会终止对应协程）。文件由 480 行降至 345 行

### 顺带解决

- **进程被杀后可自动续跑**：服务使用 `START_STICKY`，被系统重建后由 `onCreate` 从仓储恢复任务；ViewModel 冷启动时若发现未完成任务也会拉起服务。这补上了 M2.2 遗留的「恢复的任务只能标记为失败」的缺口
- 轮询、退避、2 小时上限、认证失效终止等逻辑整体迁入服务

### 新增权限与声明

`features/seat/src/main/AndroidManifest.xml`：
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / `POST_NOTIFICATIONS`
- `<service android:name="...SeatMonitorService" android:foregroundServiceType="dataSync" />`

通知图标使用系统内置 `android.R.drawable.stat_notify_sync`，避免为 seat 模块新增 `res` 目录。

### 修复：loadOnce 会覆盖内存中的新任务

`SeatTaskRepository.loadOnce()` 原先无条件用存储内容覆盖内存列表，而 ViewModel 与服务**都会调用**它 —— 若 UI 刚新增任务、服务随后加载到旧列表，新任务会被覆盖丢失。改为真正只加载一次（`@Volatile loaded` 标志）。

**验证**：`:features:seat:compileDebugKotlin` BUILD SUCCESSFUL。

### 已知限制（待真机确认 / 后续处理）

- ~~尚未申请 `POST_NOTIFICATIONS` 运行时权限~~ → 已在「M2.3 收尾」中补上
- **Android 15 对 `dataSync` 前台服务有 6 小时/天上限**：单任务最长 2 小时在限内，但连续跑多个任务可能触顶
- 5-10 秒持续轮询 2 小时，电量消耗会比较明显

---

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
