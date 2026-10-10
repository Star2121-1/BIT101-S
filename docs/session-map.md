# 会话与认证地图（2026-10-11 盘点 + 收敛）

> 起因：用户问「登录认证是不是写得比较分散，能不能统一」。
> 结论**反直觉**：凭据只有一套，真正分散的是「怎么用会话」。本文记录盘点结论与
> 这次实际收敛了什么、**故意没做什么**（免得下次有人再来"统一"一遍）。

---

## 一、一套凭据 → 3 个会话产物

```
                  学号 + 密码
                       │
      ┌────────────────┼────────────────┐
      ▼                ▼                ▼
 BIT101 站点会话    学校 CAS cookie    seatlib JWT
 (fakeCookie/JWT)  (CookieManager)    (SeatLoginStatus)
      │                │                │
  BIT101 API    课表/成绩/空教室/      座位 API
                一卡通/图书馆/eclass
```

| 产物 | 存在哪 | 谁吃它 |
|---|---|---|
| BIT101 站点会话 | `LoginStatus.fakeCookie` | BIT101 API |
| 学校 CAS cookie | `LoginStatus.cookieManager`（**全局唯一一份**） | 课表 / 成绩 / 空教室 / 一卡通 / 图书馆 / eclass / 座位 |
| seatlib JWT | `SeatLoginStatus.token` | 座位 API（用 CAS cookie 换） |

⚠️ **seatlib 的 JWT 不要并进 `LoginStatus`**：「座位登了但 BIT101 没登」是**真实存在**的
状态，并成一个就表达不了了。同理「BIT101 登了但学校的课表接口没权限」也存在。

## 二、与上游 `BIT101-Android` 的关系

- `config/user/base/LoginStatus.kt` 与上游 **逐字一致**（`diff` 为空）
- 上游只有 BIT101 一套会话；我们多出来的校内数据源，全靠**共享 CAS cookie** 实现
  —— 这是这套设计能成立的关键：**只登录一次，其余靠 cookie 打通**

⚠️ **`LoginStatus` 接口不要改**（别加 `seatToken` 之类的字段）：
与上游一致本身是资产，改了以后跟上游的 diff 就永远合不上。

## 三、登录动作只有一处

**真正的 CAS 登录**：`cn.bit101.bitlogin.sso`（BIT-Login SDK）+ App 内 WebView。

- 座位模块早期手写过一份「取 salt → AES → POST 表单」，**已废弃**
  （`SeatSession` 注释：缺风控与二次验证）。⇒ **CAS 表单加密只有一份**
  （`data/common/AESUtils`，供 `DefaultLoginRepo`）
- ⚠️ 登录**占用一次 CAS 额度**，短时间多次会触发风控（短信二次验证、冷却约 8 小时）
  ⇒ 探针只跑一次，别反复跑

## 四、这次实际收敛了什么

| # | 做了什么 | 为什么 |
|---|---|---|
| 1 | `AppHttpClients`：`school()` / `plain()` | 此前 4 个 repo 各建一份 `OkHttpClient`，连 `cookieJar(JavaNetCookieJar(...))` 都抄了两遍。抄出来的差异**看不出来**（都能跑），代价在某个站点变慢时才付：表现是「这个页面特别容易失败」，排查时会先怀疑解析、怀疑登录态，最后才想到超时不一致 |
| 2 | `SessionCleanup.clearAll()` | 此前 `DefaultLoginStatus.clear()` 顺手清了 seatlib 的 token 与任务。方向对（学校会话失效 ⇒ phpCAS 会话也失效），但**位置错**：`config` 不该知道「座位有哪些数据、存在哪个键里」 |

⚠️ **第 1 条只做「消除重复」，没改超时数值**：各 repo 的超时是各自调过的
（学校门户 15/20、内网接口 5/5），原样保留。统一超时是另一件事 ——
顺手统一会把「内网秒回」和「门户要等 20 秒」这两种真实差异抹掉。

## 五、故意**没做**的（别再提议）

| 提议 | 为什么不做 |
|---|---|
| 把 seatlib JWT 并进 `LoginStatus` | 两套独立会话，并了表达不了「只登了一边」的真实状态 |
| 把学校 SSO 抽成接口 | 本质就是「WebView 登一次、cookie 共享」，加接口只多一层间接，反而更容易漏同步 |
| 改 `LoginStatus` 接口本身 | 与上游逐字一致是资产（见第二节） |
| 「登录判据统一入口」 | **已经是单一源**：`loginStatus.status`（一个 `SettingItem<Boolean>`），5 处直接读同一个 Flow。再加抽象纯属多一层 |
| 集中 cookie 同步的 URL 列表 | 实现**已经只有一份**（`WebViewCookieSync`，eclass/图书馆/一卡通/座位共用）；各 URL 常量归属各自的 `*Logic` 是对的（`CampusCardLogic.HOME_URL` 同时是「登录一卡通」按钮的目标，必须在同一处） |

## 六、加新数据源时的清单

1. 先问：**它吃的是学校 CAS cookie 吗？** 是 ⇒ 不用新登录，接 `LoginStatus.cookieManager`
2. 取数前调 `WebViewCookieSync.sync(...)`（传入该站点的 URL）——
   **跳系统浏览器 = cookie 不回 App**，这是最容易踩的一条（v1.9.3 修过一卡通）
3. 客户端用 `AppHttpClients.school()` / `plain()`，别自己 `OkHttpClient.Builder()`
4. 域名判据一律走 `SchoolDomains`，别就地写 `contains("bit.edu.cn")`
5. 需要清登录态时用 `SessionCleanup.clearAll()`，别直接 `loginStatus.clear()`
