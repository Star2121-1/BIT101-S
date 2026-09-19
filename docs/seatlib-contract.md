# seatlib 服务端契约与关键发现

> 本文记录 2026-09-17 ~ 09-18 通过**真实请求**（curl / Python / 真机 App）逐项核实的
> seatlib 行为。所有结论均为实测，非推测；每条都标注了验证方式。
> 联调或排查问题前先读这份文档——多处行为与直觉相反。

## 1. 接口认证语义

| 接口 | 需要认证 | 未认证时的真实响应 |
|------|---------|-------------------|
| `POST /api/Seat/tree` | 否 | 200 + 真实数据 |
| `POST /api/Seat/date` | 否 | 200 + 真实数据 |
| `POST /api/Seat/seat` | 否 | 200 + 真实数据 |
| `POST /api/Seat/confirm` | **是** | **HTTP 200** + `{"code":10001,"message":"您尚未登录"}` |
| `POST /api/Space/cancel` | **是** | **HTTP 200** + 同上 |
| `POST /api/cas/user` | — | 200 + `{"code":0,...,"member":[]}`（空数组）；已认证时 `member` 是**对象** |

**要点**：认证失败不是 HTTP 401，而是 200 + 业务码 10001。
按 `response.code == 401` 判定 token 失效永远不触发（早期版本踩过）。
App 内的判据：业务码 10001 → `TOKEN_EXPIRED` → 清 token 回门禁。

### ⚠️ token 失效会连带打挂「无需认证」的接口

带着**失效/伪造的 token** 请求 `POST /api/Seat/tree` → **HTTP 500 + 空 body**
（`text/html`）；同一请求**不带 token** 则 200 + 正常数据（2026-09-19 实测）。
`/api/Seat/confirm` 传不存在的 segment 也是同类「500 + 空 body」。

后果：App 若只按「业务码 10001」识别失效，就会在看到空响应时只报「加载失败」，
而 UI 仍认为已登录、不提供重新授权入口（表现为「新建预约」页一直失败）。
对策：这类接口加载失败时**主动用静默认证探一次会话**，探不通就清 token，
让 UI 回到授权门禁（见 `SeatViewModel.verifySessionOrLogout`）。

## 2. 时段 id（segment）—— confirm 恒 500 的根因

- `/api/Seat/date` 的 `times[].id` **只在 `build_id` 传「区域 id」时才非空**：
  - 实测：区域 3（视听学习空间）→ `id=354438`；区域 4（自然科学图书第一阅览室）→ `id=355533`
  - 不传 `build_id`、或传校区/楼层 id → `times[].id/start/end` **恒为 null**
- `/api/Seat/confirm` 的 `segment` 必须是该区域当日的真实时段 id：
  - 传 `"1"` 等不存在的 id → **HTTP 500（空响应体）**——PHP 直接崩，无错误信息
  - 传 null / 缺省 → 200 + `{"code":0,"msg":"请选择时段不能为空"}`
  - 传正确 id → 200 + `{"code":1,"msg":"预约成功~","time":"11:03-22:30","seat":"徐特立馆-三层-… 004"}`
- `seat_id` 必须是 **int**（字符串形态未验证通过）
- 官方 h5 前端（`/h5/assets/region.*.js`）的提交格式就是
  `{seat_id: 座位id, segment: timeList[timeIndex].id}`，与上述一致

## 3. 取消预约 —— 要预约记录 id，不是座位 id

- `/api/Space/cancel` 传 `{"seat_id": ...}` 恒返回 `{"code":0,"msg":"操作失败"}`
- 正确参数：`{"id": <预约记录id>}`，记录从 `POST /api/index/subscribe`（`{"type":"1"}`）查：
  每条含 `id`（记录 id）、`space`（座位 id）、`status`（`"2"`=未签到有效）、
  `no`（座位号）、`areaName`、`beginTime/endTime` 等
- 实测：预约记录 `3427299` 用 seat_id 取消失败，用 `{"id":"3427299"}` 取消成功
- subscribe 就是「我的预约」数据源，App 任务列表未来可直接对接

## 4. 字段类型陷阱（org.json 隐式转换会静默出错）

- `type` / `status` 是**字符串** `"1"` / `"2"`（同一响应里 `isValid` 混合字符串与数字）
- 座位号 `no` 是补零字符串（`"001"`）——用户输入 `"1"` 需按数值等价比较
  （App 内为 `seatNumberEquals`）
- `/api/Seat/date` 只返回**今天与明天**两天
- 座位响应含 `point_x/point_y/width/height`（真实座位图坐标，当前 UI 未用）
- 树是**嵌套 children** 结构：校区 → 楼层 → 区域（区域 `type="1"`），
  `parseSeatTree` 递归展平

## 5. 单会话（互踢）

- 同一账号在其他客户端登录 seatlib，**旧会话立即失效**
- 实测：脚本登录后 App 内预约立刻报 10001 → TOKEN_EXPIRED
- 推论：**联调时禁止 python 脚本与 App 并行登录同一账号**；
  用户在网页版登录也会踢掉 App

## 6. CAS 认证（纯 HTTP 流程，WebView 已非必需）

WebView 加载学校 SSO 登录页**不渲染表单**（模拟器 + 真机双端复现：
Angular 应用在跑、页脚渲染，但登录区空白，页面自身脚本抛错）。
已在 App 内用纯 HTTP 模拟整条链路（`SeatSession.loginWithCredentials`）：

```
1. GET  sso.bit.edu.cn/cas/login?service=<urlencode seatlib/api/cas/cas>
        → 从 HTML 取 <p id="login-croypto">（AES salt）与
          <p id="login-page-flowkey">（execution）
2. 密码与 captcha_payload 用 AES/ECB/PKCS5 加密（key = Base64 解码的 salt）
3. POST 表单（不跟随重定向）：
   username / password / execution / croypto / captcha_payload /
   type=UsernamePassword / geolocation="" / captcha_code="" / _eventId=submit
   → 302 Location = seatlib/api/cas/cas?ticket=ST-xxx
4. GET  ticket URL → 302 → /api/cas/cas（phpCAS 校验并落会话）
   GET  /api/cas/cas → 302 → /h5/index.html#/cas/?cas=<32位code>
5. POST /api/cas/user  {"cas": code}  → member.token 即 JWT
```

**关键坑**：整个流程必须用**隔离的 CookieJar**（每次登录全量干净）。
共享 jar 里的陈旧 phpCAS 会话会让第 4 步走岔——302 到
`login.bit.edu.cn/authserver/login` 而非下发 code。

- CAS 直登**不需要短信验证码**（BIT101 自己的登录才需要）
- 凭据登录必须先清态：salt/execution 每次都是新的

### 6.1 学校风控与短信二次验证（2026-09-19 实测，重要）

同一账号**短时间内多次登录后**，即使密码正确，CAS 也不返回 302，而是返回
**200 + 二次验证选择页**（`current-login-type=smsLogin`，可选短信 / 邮件 / 北理工扫码）。
这是学校的风控行为，会自行消退，但**客户端必须能处理**——否则用户看到的是
「密码正确却登录失败」。

页面里可直接读到的字段（决定了实现方式）：

| 元素 id | 含义 | 示例 |
|---|---|---|
| `login-page-flowkey` | 二次验证阶段的新 execution | `<uuid>_<base64 JWT>` |
| `user-id` / `user-object-id` | 平台侧用户 id（取手机号要用它） | `669fd5d4…` |
| `phone-number` | 绑定手机号（**完整**） | `176****0308` |
| `user-email-value` | 绑定邮箱 | `112025xxxx@bit.edu.cn` |
| `captcha-url` | 图形验证码（为空即不需要） | 空 |
| `riskSystemSwitch` | 风控引擎 | `USTC` |

二次验证的接口契约（取自官方前端 `cas-login-new` 的懒加载 chunk）：

```
1. POST {sso}/cas/api/protected/sms/getPhoneNumberByUserId
   body = URL 加密（RSA 公钥 + AES），headers: hasCrypto=true、privateKey=<加密后的 key>
   → 解出 {tel, maskTel}    ← tel 是后续要用的手机号标识，不是页面上的明文
2. POST {sso}/cas/api/protected/sms/publicNoToken/sendSmsCode
   {"phone": tel, "businessNo": "0008"}
3. POST {sso}/cas/api/protected/sms/checkToken
   {"phone": tel, "token": <验证码>, "delete": false, "trustDevice": false}
4. 用**二次验证页的 flowkey** 作为 execution 重新提交登录表单：
   username / password=<验证码> / type=smsLogin / _eventId=submit /
   geolocation="" / execution=<flowkey> / captcha_code="" / trustDevice=false
   → 这次才 302 下发 ticket
```

**三个踩过的坑**：

1. **`/api/...` 的真实前缀是 `/gate`**（前端 base href = `/gate/public/cas-login-new/`），
   而 `protected` 类路径要求 **CSRF 头**：`Csrf-Key` = 32 位随机串，
   `Csrf-Value` = MD5(base64(key) 对半切开再拼回原串)。缺它一律 **401 `Invalid request`**。
2. **`/linkid/...` 会被前端改写成 `/sso-extend/...`**（拦截器里硬编码的替换列表）。
3. **风控引擎 USTC 需要指纹**：`POST {sso}/ustc-rba-front/fp`（浏览器指纹 JSON）换
   `responsetoken`，再作为加密的 `risk_payload` 随登录表单提交。缺指纹会得到
   **412 / errorCode 60008**——且**换任何参数组合都一样**，这是判断「不是参数问题是前置条件问题」的关键信号。

> 结论：**不要自己拼这套流程**。App 已依赖 `com.github.BIT101-dev.BIT-Login:bit-login:v4.0.2`，
> 其 `SsoLogin.login(username, password, callbackUrl, smsCodeCallback)` 已实现上述全部
> （风控指纹、CSRF、URL 加密、二次验证页解析、短信发码与校验）。座位模块直接复用该库，
> 把 `callbackUrl` 指定为 `https://seatlib.bit.edu.cn/api/cas/cas` 即可。

## 7. TLS 证书链缺陷（服务端配置问题，建议向学校反馈）

- `seatlib.bit.edu.cn` 的服务器**只下发叶证书、不下发中间证书**
  （openssl verify code 21：unable to verify the first certificate）
- 同证书同签发者，`login.bit.edu.cn` / `www.bit.edu.cn` 均下发完整 3 张链
  （叶 → Thawte TLS RSA CA G1 → DigiCert Global Root G2）并验证通过
  → 是 seatlib 单独的配置缺陷（nginx `ssl_certificate` 漏拼中间证书）
- 影响：Windows/curl 会按 AIA 自动补链所以能过；**Android 不补链**，
  任何干净的 Android 客户端（OkHttp、WebView）TLS 握手必失败——
  这就是早期误判「模拟器连不上 seatlib / 被防火墙封锁」的真相
- 客户端兜底：`features/seat/res/raw/thawte_tls_rsa_ca_g1.pem`（中间证书，
  已与学校服务器下发的链密码学比对验证）+
  `res/xml/network_security_config.xml`（**仅对 seatlib.bit.edu.cn** 追加信任锚）

## 8. 官方前端接口参考

- h5 站点：`seatlib.bit.edu.cn/h5/`（根路径 302 过去）；主包
  `assets/index.1662019816941.js`，座位业务在 `assets/region.*.js` 与
  `assets/seat-select.*.js` 等分包
- `/api/Seat/*` 全家桶（tree/date/seat/confirm）只在 region 分包里；
  `Space/checkout|cancel|signin|leave` 在主包
- 值得关注的未用接口：`/api/Seat/qr_book_check`、`/api/Seat/qr_change_seat`、
  `/api/Seat/touch_qr_books`、`/api/Seminar/*`（研讨间）、`/api/Enter/*`
- `booking_rules`：每天 6:00 起可预约当日/次日；当日预约需 60 分钟内刷卡签到；
  未签到记违约 1 次；累计 5 次违约暂停 7 日；每天可取消 2 次

## 9. 座位底图与状态配色（官方前端的真实做法，2026-09-19 实测）

`POST /api/seat/map` body `{"id": "<区域id>"}` → 返回**每个区域一整套底图**，按座位状态分色：

```json
{"code":1,"data":{
  "free":  "https://seatlib.bit.edu.cn/home/images/web/area/4/seat-free.jpg",
  "book":  "…/area/4/seat-book.jpg",
  "close": "…/area/4/seat-close.jpg",
  "leave": "…/area/4/seat-leave.jpg",
  "use":   "…/area/4/seat-use.jpg"}}
```

- 图片 **1920×1080**（约 150KB/张），内容 = 房间轮廓 + 每个座位的编号方块 + 插座/墙/地插标注 + 标题 + 图例
- 用 `{"area": …}` / `{"area_id": …}` 调用会返回 `{"code":0,"msg":"Error"}`，**必须是 `id`**
- 楼层的 `image_url`（如 `…/area/2/floor.jpg`，1365×768）是**楼层平面图**（各区域以绿块标注），
  可用于「选择区域」时帮助认路；区域的 `image_url` 实测 404/500，座位级底图只能走 `/api/seat/map`

### 座位坐标 = 底图百分比

每个座位都带 `point_x / point_y / width / height`（字符串，实测 55/55 全部有值）：

- 实测范围：`x 10.2~82.8`、`y 15.9~90.2`、`w 2.12~3.07`、`h 3.49~5.11`
- 官方前端（`assets/seat-map.*.js`）的用法就是**按背景图宽高取百分比**：
  `left = point_x * imgW / 100`、`width = width * imgW / 100`（高度同理）
- 因此底图 + 坐标可直接还原真实房间布局，并可把百分比区域当作**点击热区**
- 同一响应还带 `status_name`（如 `"空闲"`/`"已预约"`），比数字更稳定

### 状态 → 底图映射（取自官方前端代码）

| status | 含义 | 底图 |
|---|---|---|
| `1` | 空闲 | `free` |
| `2` / `10` / `11` | 已预约 | `book` |
| `7` | 临时离开 | `leave` |
| `6` / `8` / `9` | 在用 | `use` |
| `3` / `4` / `5` | 暂停使用 | `close` |

## 10. 预约规则（`/api/index/booking_rules` 原文要点）

- 每天 **6:00** 起可预约**当日或次日**座位；账号密码 = 学校统一身份认证
- 预约**当日**：需在开始后 **60 分钟内**刷卡入馆（系统自动签到）
- 预约**次日**：需在次日 **9:00 前**刷卡入馆
- **每天可取消预约 2 次**（「我的中心 → 我的预约 → 取消预约」）
- 预约后未签到 → 记违约 1 次；**各类违约累计 5 次 → 暂停预约权 7 天**
- 临时离开：座位保留 60 分钟（用餐时段 11:00-12:00 / 16:00-17:00 延长至 120 分钟），
  超时未回 → 自动释放并记违约
- 出馆刷卡 → 座位自动释放
- 开放时间 08:00-22:30（系统配置 `start 06:00 / end 23:59`，`/api/Seat/date` 返回的时段为 08:00-22:30）

## 11. 联调环境备忘（真机 NP05J / Android 16）

- 校园网内宿主机与模拟器均可达 seatlib（`10.0.11.162:443`）；
  模拟器内用 `nc -w 5 <ip> 443 < /dev/null` 测连通（ping 不通只是 ICMP 不转发）
- 真机 logcat 被 ROM 禁用（缓冲区仅 2 行）→ 诊断靠
  `uiautomator dump`（坐标/文本）+ 截图 + 服务端脚本复现；
  `dumpsys notification` / `dumpsys activity services` 可用
- 密码框聚焦触发 FLAG_SECURE，截屏全黑属正常，dump 仍可读文本
- 复测脚本：`F:/Agent_Work/BIT-102/.workbuddy/tmp_cert/seatlib.py`
  （登录 + 鉴权头封装，凭据走参数，不含硬编码）
