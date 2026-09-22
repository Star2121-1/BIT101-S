# 新平台接口契约（延河课堂 aita / 课程动态 eclass）

> 实测时间：2026-09-22　｜　方式：自动化浏览器**真实登录**后，在页面上下文调用接口取证
> ⚠️ **本文档不含任何 cookie / token / 密码**；所有凭据仅在当次会话内存中使用，浏览器 profile 已销毁

---

## 一、延河课堂 aita（`aita.yanhekt.cn`）—— 新课程中心

### 认证

| 项 | 值 |
|---|---|
| 登录入口 | `https://aita.yanhekt.cn/casapi/index.php?r=auth/login&auType=cmc&tenant_code=21&forward=<目标页>` |
| 实际流程（实测） | 该入口 → 平台自有登录页 `/yjlogin/` → 选择学校「北京理工大学」→ 点击登录 → **直接复用学校 SSO 会话**（已登录 sso.bit.edu.cn 时无需再输密码）→ 回跳 `https://aita.yanhekt.cn/portal/#/` |
| 会话凭据 | **cookie `_token`**（实测长度 639 字符，域 `.yanhekt.cn`） |
| 调用方式 | HTTP 头 `Authorization: Bearer <_token>` |
| 租户 | `tenant_code=21`（写死在 config.js：`TENANT_ID: 21`） |

> ⚠️ 平台另有**自有账号密码 + 图形验证码**登录形态（`/yjlogin/` 的账号密码框 + canvas 验证码）。
> 走学校 SSO 更省事，但 SSO 不可用时验证码会成为障碍 —— **App 侧应让用户在 WebView 里自己完成登录**。

### 已实测接口

| 接口 | 方法 | 鉴权 | 实测结果（2026-09-22） |
|---|---|---|---|
| `/coursesourceapi/course/todo` | GET | `Bearer _token` | **200** `{"code":200,"message":"","data":{"list":[],"total":0}}` ← **无参数即返回全部待办，本账号当前为空** |
| `/coursesourceapi/course/todo?page=1&limit=20&status=0` | GET | `Bearer _token` | 200，同上（参数不影响结构） |
| `/messageapi/api/msg/lists` | GET | `Bearer _token` | 200，但 **`status` 参数必填**（缺参返回 `code:202 参数status是必须的`） |
| `/courseapi/v2/course-live/get-my-course-day` | GET | 仅 Bearer | ⚠️ **403 Auth Forbidden** —— 该接口可能还需要其它头（`tenant`/`Tenant-Id`），**待补** |
| `/courseapi/v3/course/subscribes` | GET | 仅 Bearer | ⚠️ 403，同上 |

**待办返回结构**（可作为 DDL 数据源）：

```json
{ "code": 200, "message": "", "data": { "list": [], "total": 0 } }
```

⚠️ `data.list` 的真元素形状**尚未取到**（该账号当前 total=0）。
需要在你**有作业待办**时再抓一次；或换一个有一定作业量的账号验证。

---

## 二、课程动态 eclass（`zy-eclass.bit.edu.cn`）

### 认证

| 项 | 值 |
|---|---|
| 未登录表现 | 302 → `sso.bit.edu.cn/cas//login?service=<Keycloak broker 地址>` |
| 身份体系 | **Keycloak**（`zy-identity.bit.edu.cn/auth/realms/bit`）+ CAS broker |
| 登录方式（页面实测） | 用户名密码 / 短信验证码 / 通行密钥 WebAuthn / 邮件验证码 / i北理扫码（+ 微信、企业微信、Office365、UKey 等外部 IdP） |
| 会话 | **纯 cookie 会话**（实测：登录后无需任何 token 头，`fetch('/api/...')` 默认带 cookie 即 200） |
| ⚠️ 重要 | **本次登录未触发二次验证**（一次密码即通过）。但这依赖风控，**不能假设每次都这样** |

✅ **这是最好的消息**：eclass 用**纯 cookie 会话**，与我们座位模块给 seatlib 做的
「WebView 内登录 → 共享 CookieManager → OkHttp 直接请求」**完全同构**，
不需要 JWT 交换那一层。Cookie 失效时的自动续期可复用 `SeatSession` 的思路。

### 已实测接口（全部 200，仅需 cookie）

| 接口 | 用途 | 实测 |
|---|---|---|
| **`/api/todos`** | **个人待办（DDL 首选源）** | 200 `{"todo_list":[]}` ← 本账号当前为空；`?status=all&page=1&limit=50` 同样 200 |
| `/api/user/recently-visited-courses` | 我最近访问的课程 | 200，返回 `visited_courses[]`，含 `id` / `course_code` / `department.name` / `current_user_is_member` |
| **`/api/courses/{id}/activities`** | **课程动态（资料/作业/考试等活动）** | 200 `{"activities":[...]}`；实测「操作系统」课程返回 2 条，`type="material"` |
| `/api/courses/{id}/modules` | 课程章节模块 | 200（课程页加载时自动调用） |
| `/api/courses/{id}/exams`、`/submitted-exams`、`/exam-scores` | 考试 / 已交 / 成绩 | 课程页加载时调用（结论：**考试与成绩也在这个平台**） |
| `/api/courses/{id}/classroom-list`、`/interactions`、`/modules/rollcalls`、`/lti-tools`、`/nav-setting`、`/course/{id}/my-completeness` | 课堂/互动/点名/导航/完成度 | 课程页加载时调用 |
| `/api/announcement`、`/api/alert/messages`、`/api/bulletins/latest`、`/api/org-bulletin/bulletins/latest` | 公告 / 提醒 / 通知 | 200（`alert/messages` 与 `bulletins/latest` 当前返回空数组） |
| `/api/config`、`/api/orgs/1/lang-settings` | 平台配置 | 200 |
| `/statistics/api/user-visits` | 访问统计 | 200 |

**活动（`activities[]`）元素的关键字段**（实测字段名，非推测）：

```
id, title, type, course_id, module_id, sort,
start_time, end_time,               ← 起止时间（资料类为 null）
visible_start_at, visible_end_at,   ← 可见窗口（资料类为 null）
is_closed, is_started, is_in_progress, published, publish_mode,
completion_criterion, completion_criterion_key, completion_criterion_value,
submit_times, late_submission_count, score_type, can_show_score, score_published,
uploads, canvas_answer_and_explanation, forum_count, prerequisites,
is_review_homework, intra_rubric_id, inter_review_named, ...
```

- `type` 实测见到 `material`（资料）；字段集中出现 `submit_times` / `late_submission_count` /
  `score_*` / `is_review_homework` → **作业类是同一张表的不同 `type`**（客户端按 type 过滤）
- `end_time` 即「截止时间」→ **DDL 的直接数据来源**

⚠️ 实测局限：本账号在「操作系统」以外的 5 门课 `activities` 返回空，
且全部课程当前**没有作业类活动**（开学第 3 周）。因此
**作业类活动的 `type` 取值（如 `homework`/`assignment`）仍待你下次有作业时确认**。

---

## 三、结论：两条源怎么选

| 维度 | 延河课堂 aita | eclass 课程动态 |
|---|---|---|
| 认证难度 | 中（CAS/SSO + Bearer token；有验证码兜底形态） | **低**（纯 cookie，与 seatlib 同构） |
| DDL 接口 | `/coursesourceapi/course/todo` | `/api/todos` + `/api/courses/{id}/activities` |
| 实测数据 | 待办为空 | 待办为空；活动有资料 2 条 |
| 额外价值 | 当日课程、消息 | **考试安排 + 成绩**（`exams` / `exam-scores`）、课程资料 |
| 建议角色 | **辅助源**（有数据时并入） | **主源**（更同构、数据面更广） |

**建议**：以 **eclass 为主源**（cookie 会话直接复用现有基建），
aita 的 `course/todo` 作为补充源一起并入 ——
两个平台的 DDL 都映射到同一张 `ddl_schedule`，用 `group` 区分来源、按标题+截止时间去重。

---

## 四、遗留待办（下次抓取）

1. **有作业时**再抓一次 `/api/todos` 与 `/api/courses/{id}/activities`，确认：
   - 作业类 `type` 的准确取值
   - `end_time` 的格式（时间戳还是字符串）与是否有「未提交/已提交」标志
2. aita `/courseapi/v2/course-live/get-my-course-day` 的 403 是缺哪个头（试 `tenant` / `Tenant-Id`）
3. eclass 是否有**跨课程的作业汇总**接口（避免逐课程 N 次请求）；`/api/todos` 很可能就是它
4. 会话**有效期**与失效表现（cookie 多久过期、失效时返回 401 还是跳登录页）

---

## 2026-09-23 实测补充（Playwright 有头会话内抓取）

### 接口确认结果

| 接口 | 结果 | 说明 |
|---|---|---|
| `GET /api/todos` | **200** `{"todo_list":[]}` | 当前无待办 → **项字段仍未知** |
| `GET /api/user/recently-visited-courses` | **200** | ⭐ **课程列表主源** |
| `GET /api/courses` | **403** 您没有权限完成此操作 | 不可用 |
| `GET /api/user/courses` | 404 | 不存在 |
| `GET /api/course-list` | 404 | 不存在 |
| `GET /api/classes` | 200 `{"classes":[]}` | 空 |

### 课程对象字段（`visited_courses[]`，实测）

```
id: int                   // 课程 id —— 用于 /api/courses/{id}/activities
name: str                 // 课程名，如「操作系统」
course_code: str          // 教务课号
course_type: int
credit_state: { credit_remaining:int, status:str }
current_user_is_member: bool
department: { name: str } // 开课学院
imported_from: str
org_id: int
teaching_unit_type: str
url: str
course_attributes: { teaching_class_name: str|null }
cover / grade / klass
```

### 首页实际调用的 /api 接口（17 个）

`/api/activities/is-locked`、`/api/air-credit/user/courses/ai-ability`、`/api/alert/messages`、
`/api/announcement`、`/api/bulletins/latest`、`/api/classes`、`/api/config`、`/api/course-list`、
`/api/courses`、`/api/org-bulletin/bulletins/latest`、`/api/orgs/{id}/lang-settings`、
`/api/orgs/{id}/welcome-notification`、`/api/todos`、`/api/uploads/{id}/modified-image`、
`/api/user/courses`、`/api/user/recently-visited-courses`、`/statistics/api/user-visits`

### ⚠️ 仍未确认的（及其原因）

- **作业类活动的 `type` 取值** —— 第 4 周课程里只有资料，没有作业
- **`/api/todos` 项字段** —— 返回空列表
- 前端 JS 里搜 `todo_list` / `homework` / `deadline` / `end_time` 等关键词
  **只命中无关代码**（日期选择器文案、sentry 上报）—— 作业页面的 chunk 是
  **懒加载**的，首页不下载，所以静态搜不到

### 结论：改以 activities 为主源

`GET /api/courses/{id}/activities`（字段见本文档前文实测）作为 DDL 主数据源，
判定作业用**字段存在性**而非枚举 `type`：

```
有 submit_times / is_review_homework / score_* 之类字段  ⇒  作业
仅标题 + 附件、无截止时间                              ⇒  资料
```

字段细节在**真机验证阶段**用 `adb shell run-as cn.bit101.android.debug` 读取
真实响应校正（debug 包可 run-as，不需要 root）。
