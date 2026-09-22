# DDL 换源：新平台调研结论 + 实施计划表

> 调研时间：2026-09-22　｜　调研方式：**只读抓取前端静态资源 + 接口探测**（未登录任何账号、未使用任何凭据）
> 归档：`.workbuddy/archive/ddl-research/`（config.js / main.js / casapi 页面等原始材料）
>
> ✅ **2026-09-22 下午更新：第一阶段已完成** —— 已用作者授权的账号**真实登录两个平台**并实测接口，
> 结论与字段记录见 **`docs/ddl-source-contract.md`**。关键结论：
> - **eclass 是纯 cookie 会话**（无需 token 头）→ 与 seatlib 的「WebView 登录 + 共享 CookieManager」**完全同构，最省事**
> - aita 是 `cookie _token` + `Authorization: Bearer`
> - 会话凭据与浏览器 profile **已全部销毁**，仓库与记忆中**无任何凭据残留**（已 grep 验证）

---

## 一、两个平台的实测结论

### 1. 延河课堂「课程中心」`https://aita.yanhekt.cn/portal/#/`

| 项 | 实测结果 |
|---|---|
| 门户首页 | **公开可访问**（无需登录，热门课程/知识空间/智能体都是公开数据） |
| 校内租户 | `TENANT_ID: 21`，域名 `.yanhekt.cn`（延河课堂是商业化平台，多租户部署） |
| 登录入口 | `https://aita.yanhekt.cn/casapi/index.php?r=auth/login&auType=cmc&tenant_code=21&forward=<当前页>` |
| 登录方式 | **CAS（`auType=cmc`）** → 跳学校统一身份认证 → 回跳后把会话写进 cookie |
| 会话凭据 | **cookie `_token`**（前端 `On()` 从 cookie 正则抽取，接口用它作 `Authorization: Bearer <_token>`） |
| 前端技术 | Vue SPA，密码在客户端加密（页面加载 `encryption.js` / `jsencrypt.js` / `aes.min.js`） |

**已挖到的关键接口**（从前端主包 `static/index-*.js` 提取，非猜测）：

| 接口 | 方法 | 用途 | 鉴权 |
|---|---|---|---|
| `/coursesourceapi/course/todo` | GET | **课程待办（DDL 的核心候选）** | `Bearer _token` |
| `/courseapi/v2/course-live/get-my-course-day` | GET | **我的当日课程** | `Bearer _token` |
| `/courseapi/v3/course-resource/list-sources` | GET | 课程资源来源列表 | 公开 |
| `/courseapi/v3/course/subscribes`、`/course/subscribe` | GET/POST | 我订阅的课程目录 | 部分需 tenant/user 头 |
| `/messageapi/api/msg/lists`、`/msg/info` | GET | 消息通知（可能含作业提醒） | `Bearer _token` |
| `/courseapi/v3/ibit-auth/exchange-badge` | POST | i北理认证交换 | `Bearer _token` |
| `/courseapi/v3/portal-home-setting/*` | GET | 门户配置/推荐 | 公开 |

**未鉴权探测**：`/coursesourceapi/course/todo` 与 `/courseapi/v2/...` 直接请求均返回 **HTTP 403**（需要 `_token`），
`/casapi` 返回 200（登录页）。→ **必须先完成登录才有数据**。

✅ **好消息**：这套「CAS 登录 → 拿 cookie → 带 Bearer 调 REST」的流程，
和我们座位模块给 seatlib 做的**完全同构**（`SeatSession` + `SeatHttp` 那套可复用）。

### 2. 课程动态 / 学习资料 `https://zy-eclass.bit.edu.cn/user/index#/`

| 项 | 实测结果 |
|---|---|
| 未登录访问 | **被 Keycloak 拦截**：跳 `sso.bit.edu.cn/gate` → `zy-identity.bit.edu.cn/auth/realms/bit/broker/cas-client/endpoint` |
| 身份体系 | **Keycloak**（`/auth/realms/bit/...`），走 CAS broker 与学校 SSO 联邦 |
| 支持的登录方式 | 用户名密码 / 短信 / **WebAuthn 通行密钥** / **i北理扫码** / 邮件验证码；另有微信、企业微信、Office365、UKey 等外部 IdP |
| 业务前端 | **登录后才下发**（未登录只能拿到 Keycloak 登录页），因此**接口清单目前无法静态挖掘** |
| 现有工具链适配性 | ⚠️ **关键不确定性**：`BIT-Login` SDK 目前处理的是 CAS + 短信二次验证（含 USTC 风控指纹），
而 eclass 是 **Keycloak OIDC**，SDK 能否直接吃下这条链路**需要实测** |

⚠️ 因此 eclass 的接口与鉴权是**下一阶段必须补齐的调研**，而且**需要你配合一次**（见第四节）。

---

## 二、总体技术路线（推荐）

```
                      ┌─ 复用现有 BIT-Login SDK（CAS+短信二次验证，已验证可用）
学校统一身份认证 ──────┤
   sso.bit.edu.cn     └─ WebView 内完成登录 → 拦 cookie/回调
                                    │
        ┌───────────────────────────┴───────────────────────────┐
        ▼                                                       ▼
  aita 延河课堂（casapi→cookie _token）              eclass（Keycloak 会话）
  GET /coursesourceapi/course/todo                 待捕获（作业/资源/动态）
        └───────────────────────────┬───────────────────────────┘
                                    ▼
                    新增 DDL 源适配层（Source Adapter）
                    统一映射 → ddl_schedule（多 source group）
                                    ▼
                      DDL 页 / 桌面组件 DDL 页 / 通知提醒中心
```

**设计要点（沿用本项目既有约定）**

1. **一个源一个 Adapter**，接口统一（`suspend fun fetch(): List<DdlItem>`），
   便于乐学（旧）/ aita / eclass **多源并存**，也便于以后再加源。
2. **`ddl_schedule` 的 group 维度扩成多源**：现有只区分 `"lexue"` 与自定义，
   新增 `"aita"` / `"eclass"`。**旧乐学数据必须保留**（不能一次性删库）。
3. **纯逻辑抽 `*Logic`**：源合并、去重（同一作业在不同源出现）、排序、临期分级，
   全部写成可单测的纯函数 —— 沿用 `SeatStatusLogic` / `WidgetLogic` 的做法。
4. **凭据一律走加密存储**：新平台的 token/cookie 用 `EncryptedPreferencesItem`
   （与 `seat_token` 同一套 AES256 密钥），**不进明文**。
5. **不做「自动替用户登录」的越权设计**：登录一律走 WebView 让用户自己完成，
   与座位模块一致（也避免触发风控）。

---

## 三、实施计划表

| 阶段 | 目标 | 交付物 | 验收标准 | 风险 |
|---|---|---|---|---|
| **P0 调研收尾** ✅ **基本完成** | 补齐 eclass 的接口与鉴权；确认 aita `course/todo` 的返回字段 | `docs/ddl-source-contract.md`（**已产出**，接口/鉴权/字段实测记录） | 两个平台各有**真实响应样本**落档 | ⏳ 仅剩「作业类活动的 `type` 取值」待有作业时再抓 |
| **P1 认证打通** | 让 App 能拿到两个平台的有效会话 | `AitaSession`（CAS/SSO → cookie `_token`）、`EclassSession`（Keycloak cookie 会话）；WebView 登录页 + 会话持久化 | 冷启动后能静默复用会话；失效能提示并重新登录；**不触发风控** | aita 有验证码形态兜底；eclass cookie 有效期未知 |
| **P2 数据接入** | 新增 DDL 源，多源并存 | `SchoolAitaService` / `SchoolEclassService` + `DdlSourceAdapter` + Room `version 2 → 3`（或复用 group 字段，视 P0 结论） | 真实拉到我账号的 DDL 列表；旧乐学数据仍在；去重生效 | 接口可能改版；分页/时间范围参数未知 |
| **P3 UI 接入** | App DDL 页 + 桌面组件 DDL 页显示新源 | DDL 来源标签（延河课堂 / 课程动态 / 自定义）、点条目跳对应平台课程页 | 来源可区分；点击能跳到正确位置；组件不再显示空 DDL | 组件跨进程打开外部 URL 需用浏览器 Intent（已有先例） |
| **P4 通知联动** | 与「通知与提醒中心」合并 | DDL 截止前提醒（1 天 / 1 小时） | 提醒去重（同一 DDL 不重复打扰）；开关可控 | 依赖 P4 的通知基建，建议与通知中心同批做 |
| **P5 兼容与收尾** | 乐学源降级策略；文档 | `docs/ddl-source-contract.md` 定稿、CHANGES、README | 乐学不可用时**不报错、不空白**，只显示其它源 | 乐学若彻底下线，保留开关供老用户 |

**顺序建议**：P0（等你配合一次）→ P4 通知中心（不依赖新源，可先做）→ P1 → P2 → P3 → P5。
即：**通知中心可以先开工，不阻塞在 DDL 换源上**。

---

## 四、需要你配合的两件事

### ① eclass 接口抓取（5 分钟，最省事的方式）

在电脑上登录 `https://zy-eclass.bit.edu.cn/user/index#/` 之后：

1. 按 `F12` 打开开发者工具 → 切到 **Network** 标签 → 勾选 **Fetch/XHR**
2. 刷新页面，进入 **课程动态 / 作业** 那一页（把「作业列表」也点开一次）
3. 在 Network 里找到返回作业/动态数据的请求，**右键 → Copy → Copy as cURL**
4. 把内容发我（**发之前请把凭据脱敏**：`Cookie:` / `Authorization:` / `token` 的值替我删掉或改成 `xxx`）

> 我只需要**请求地址 + 参数 + 响应结构**，不需要你的任何凭据。
> 另外麻烦告诉我：**你平时在哪看作业截止时间** —— eclass 的课程动态？还是延河课堂的待办？（决定 P2 以哪个为主源）

### ② 延河课堂的「课程待办」确认

登录 `https://aita.yanhekt.cn/portal/#/` 后，看个人中心/消息里是否有「**待办 / 作业**」入口，
截个图给我即可 —— 用于确认 `/coursesourceapi/course/todo` 是否就是 DDL 主源。

---

## 五、结论（一句话）

**延河课堂（aita）完全可做** —— 它的「CAS 登录 + cookie + Bearer REST」结构和我们已有的 seatlib 方案同构，
接口也已经挖到（`/coursesourceapi/course/todo`、`/courseapi/v2/course-live/get-my-course-day`）；
**eclass 需要你帮我抓一次接口**（它被 Keycloak 挡着，静态挖不到）。

这两件事都不阻塞**通知与提醒中心**，那个可以立刻开工。

---

## 2026-09-23 进度更新：P1–P3 已实现（v1.7.0）

| 阶段 | 状态 |
|---|---|
| P0 调研收尾 | ✅ 完成（接口契约见 `docs/ddl-source-contract.md`） |
| P1 认证打通 | ✅ eclass 用**纯 cookie**（WebView 登录 + `WebViewCookieSync`），无需 token 交换 |
| P2 数据接入 | ✅ `EclassRepo`：课程列表 → 逐课程 activities → `EclassDdlLogic` 映射；**多源并存**（乐学源保留） |
| P3 UI 接入 | ✅ `DdlSource` 显示中文来源；DDL 页加「课程中心」FAB；同步来的条目不可编辑 |
| P4 通知联动 | ⏸ 通知中心本来就读 `ddl_schedule`，换源后自动跟着走，但**带作业的场景未在真机确认** |
| P5 收尾 | ⏸ 乐学源的降级文案未动 |

### ⚠️ 唯一没做实的部分

**作业的 `type` 真实取值仍未拿到** —— 开学第 4 周课程里只有资料。

已按「字段存在性」绕开（见 CHANGES v1.7.0 第 2 节），但**这属于权宜之计**。
等账号里有第一份作业后应做一次校正：

```bash
# 前提：真机装 debug 包并已登录课程中心
adb shell run-as cn.bit101.android.debug cat files/eclass_activities.json
```

把真实响应补进 `docs/ddl-source-contract.md`，再回头确认 `EclassDdlLogic.isHomework`
是否命中作业、是否会误收资料。
