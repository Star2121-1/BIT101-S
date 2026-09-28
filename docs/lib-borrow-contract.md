# 图书馆「我的借阅」链路契约（2026-09-28 实测）

> 目标：把**借阅到期提醒**做进 App —— 官方零提醒、逾期罚钱，属「真实损失」类。
> 与座位（`seatlib`）不同，这是**另一套系统**，但**登录同源**。
> 全部结论来自真实请求与登录后的浏览器录制，**不是推测**。

---

## 1. 主机与平台

| 主机 | 作用 | 备注 |
|---|---|---|
| `lib.bit.edu.cn` | 图书馆**门户** | 超星智慧门户（`Server: CXS/2.4.1`） |
| `mylib.bit.edu.cn` | **个人空间**（我的图书馆） | 同一套超星门户，`websiteId=162085` |
| `login.bit.edu.cn` | CAS 登录入口 | ⚠️ **只是跳转站** |
| `sso.bit.edu.cn` | **真正的 CAS 登录页** | 表单必须提交到这里 |
| `nlibvpn.bit.edu.cn` | 图书馆校外访问 VPN | 本次未用 |
| `ss.zhizhen.com` | 统一检索（超星发现） | 与借阅无关 |

⚠️ **不是** 汇文/图创那类传统 OPAC —— 是超星「智慧门户」，页面全是 JS 外壳 + XHR。

## 2. 登录链路（★ 关键：与 App 现有 CAS 同源）

```
GET https://lib.bit.edu.cn/login
  → 302 https://login.bit.edu.cn/cas/login?service=<URL编码的 lib 回调>
  → 302 https://sso.bit.edu.cn/cas/login?service=<同一个回调>   ← 真实登录页
POST 该 URL（见下方表单字段）
  → 302 https://lib.bit.edu.cn/sso/login/3rd?wfwfid=2398&refer=<...>&ticket=ST-****
  → 200（会话 cookie 落在 lib.bit.edu.cn）
```

`service` 参数（原样，已 URL 编码）：
```
https://lib.bit.edu.cn/sso/login/3rd?wfwfid=2398&refer=https%3A%2F%2Flib.bit.edu.cn
```

CAS 表单字段（与 seatlib 完全一致，AES-ECB + base64，盐取自页面）：
```json
{
  "username": "<学号>",
  "password": "<AES(pwd, salt)>",
  "execution": "<#login-page-flowkey 的文本>",
  "croypto": "<#login-croypto 的文本，即 salt>",
  "captcha_payload": "<AES('{}', salt)>",
  "type": "UsernamePassword",
  "geolocation": "", "captcha_code": "", "_eventId": "submit"
}
```

### ⚠️ 三个必须记住的坑

1. **必须 POST 给重定向之后的真实登录页**（`sso.bit.edu.cn`）。
   `login.bit.edu.cn` 是跳转站：POST 到它会被 302 成 GET，**表单直接丢掉** ——
   表现为「没有任何报错，但就是没登上」。这个坑实际踩过一次。
2. 登录**占用一次 CAS 登录额度**。学校 CAS 短时间多次登录会触发风控
   （要求短信二次验证、冷却约 8 小时）⇒ **探针只跑一次，别反复跑**。
3. 登录**不影响 seatlib 会话**（不同 service 的不同 ticket），但也别同时高频登录。

## 3. 会话判据

```http
GET https://lib.bit.edu.cn/engine2/header/user-info
GET https://mylib.bit.edu.cn/engine2/header/user-info
```
未登录：
```json
{"code":1,"data":{"uid":null},"status":200}
```
已登录：`data.uid` 为非空字符串（另有 `uname` / `realname` / `roleid`）。
⇒ **判据只看 `data.uid` 是否为空**，**不要**用 HTTP 状态码（两者都是 200）。

## 4. 「我的借阅」页面

```
https://mylib.bit.edu.cn/page/330841/show
```
- 页面 `title` / `description` 都是 **「我的借阅」**
- `pageId=330841`、`websiteId=162085`、`jsonId=334668`、`wfwfid=2398`
- **数据是服务端渲染 / XHR 拉取**：未登录时页面上完全没有借阅内容

### 4.1 两个引擎（appId）

| appId | 名称 | 更多链接 |
|---|---|---|
| `1715437` | **当前借阅** | `/engine2/m/3B2D77BA56B6E7A3?p=330841` |
| `1697337` | **历史借阅** | `/engine2/m/E77D9320D41DA2B5?p=330841` |

个人空间首页（`mylib.bit.edu.cn/`）另有一个「图文」引擎
（`eng-graphic-style151`）只显示**计数**：`当前借阅 N` / `历史借阅 M`。

### 4.2 数据接口（★ 这是要接的）

```
GET {mylib}/application/{appId}/data
      ?sversion={sversion}&mobile=1&wfwfid=2398&websiteId=162085&pageId=330841
```
- `appId` = `1715437`（当前借阅）或 `1697337`（历史借阅）
- `sversion` 从页面内联变量 `var sversion = '...'` 取（示例 `20260928388`）
- 另有整页接口 `/page/330841/all-request?head=3&w=162085&sversion=...&mobile=1&wfwfid=2398`
  与 `/webjson/334668/content-cache?w=162085&sversion=...`

### 4.3 响应体结构（★ 2026-09-28 已抓全，解析器据此实现）

`/application/<appId>/data` 返回的**不是**干净 JSON，而是 **JSON 套 HTML、HTML 里的
`<script>` 再内嵌一段 JS 数组**：

```
{"code":1,"data":{"div":"<html>…<script>…this.checkHasData([{…},{…}]);…</script>…</html>"}}
```

**判据（实测，别改）**：

- 有数据 → `checkHasData([{...},{...}])`
- 没有数据 → `checkHasData([])`
- 两种情况下锚点 `checkHasData(` **都存在** ⇒ **锚点找不到 = 门户改版 = 必须报失败**，
  绝不能当成「0 本」（否则真正该提醒时反而告诉用户「你没借书」）
- 数组本身是**合法 JSON**，可直接解析
- 每行形如
  `{"0":{"value":"书名","key":"题名"},"1":{"value":"…","key":"作者"},…,"id":null}`
  —— **每个单元格自带字段名**，所以按 key 取值、**不依赖列顺序**
- `code != 1`、`data` 为 null、`div` 为空 ⇒ 一律按「失败」处理

⚠️ gson 的 `getAsJsonObject("data")` 在成员为 JSON `null` 时会抛 `ClassCastException`，
必须自己判 `isJsonObject`（**单测已锁**，实现里踩过）。

### 4.4 字段（实测列头，按此顺序）

| 列 | 示例值 |
|---|---|
| 题名 | （书名） |
| 作者 | `(日) 涌井良幸, 涌井贞美著` |
| ISBN | `978-7-5153-6161-1` |
| 馆藏地 | `良乡自然科学图书第二阅览室` |
| 借阅日 | `2024-08-20 11:24:28` |
| 应还日 | `2024-09-19 11:24:28` |

引擎配置里 `"key"` 依次为：`题名` / `作者` / `ISBN` / `馆藏地` / `借阅日` / `应还日`。
**日期是 `yyyy-MM-dd HH:mm:ss` 字符串。**

✅ **已解决（2026-09-28）**：结构见 4.3；解析器 `LibBorrowLogic` 已按真实响应实现，
并锁进 **23 条单测**（含「空数组 = 0 条」「锚点缺失 = 失败」「字段顺序打乱」
「日期解析失败不丢记录」「通知不含书名」）。
**纪律不变：取不到就显示「暂时取不到」，绝不显示 0 条。**

## 5. 借阅规则（官方读者指南口径，用于文案与提醒阈值）

- 借期 **30 天**；本科可借 **10 册**，研究生 **20 册**
- 每本可**续借 1 次**（教工 5 次），每次 30 天
- **续借须在到期前 10 天之内**办理；**过期图书与被预约图书不接受续借**
- 寒暑假前到期的书**按期归还**（假期计入借期）；寒暑假期间应还的**自动延期至开学后**
- 预约图书到馆后**保留 3 天**；每人限预约 2 本，预约有效期 30 天

## 6. 探针脚本（都在 `.workbuddy/archive/tools/`）

| 脚本 | 用途 | 是否登录 |
|---|---|---|
| `lib_probe.py` | 登录 + 会话验证 + 抓门户外壳 HTML | ✅ 一次 |
| `lib_browser_probe.py` | 登录 + 注入无头 Chrome + **录制全部网络请求** | ✅ 一次 |
| `lib_borrow_probe.py` | 登录 + 打开「我的借阅」+ 存 DOM | ✅ 一次 |
| `lib_borrow_capture.py` | 登录 + **全量抓包 + 抓响应体**（定位数据到底由谁返回） | ✅ 一次 |
| `lib_borrow_json.py` | 登录 + 直打数据接口打印原始 JSON（**结构就是靠它定下来的**） | ✅ 一次 |

运行环境：`C:\Users\asus\.workbuddy\binaries\python\envs\default\Scripts\python.exe`
（该 venv 内装了 `requests` / `pycryptodome` / `websocket-client`）。

### 无头 Chrome + CDP 的两个坑（踩过，务必照抄）

1. **必须加 `--remote-allow-origins=*`** —— Chrome 111+ 否则以
   `403 Forbidden` 拒绝本机的 WebSocket 握手。
2. **必须连「页面级」端点**（`/json/list` 里 `type=="page"` 的 `webSocketDebuggerUrl`）——
   用 `/json/version` 给的是**浏览器级**端点，发 `Page/Runtime/Network` 命令
   一律返回 `-32601 wasn't found`。

## 7. 落地设计（待实施）

- **数据层**：`LibApi`（复用现有 CAS 会话机件）+ `BorrowRecord` 模型 + 容错解析
- **UI**：借阅卡片（当前借阅数 / 最近应还日）+ 明细列表；「座 → 列表」或「我」页入口
- **提醒**：**应还日前 N 天**（默认 3 天）推一次，**过期后每日一次**
  —— 走 `NotifyCenter` 新渠道，与座位/流量提醒同一套去重纪律
  （⚠️ **首次只建基线、不通知**，见 MEMORY 铁律 6）
- **隐私**：借阅书目不出本机；通知正文**只写「有 N 本即将到期」+ 最早应还日**，
  **不写书名**（与「出分不含分数」同一条边界）
