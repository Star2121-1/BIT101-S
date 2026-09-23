# CHANGES

## 2026-09-23 v1.7.6 DDL/动态页大改：点条目跳进 App 并定位、页序调整、动态页设置

用户提的 6 项一次做完（W1–W5；W6 的乐学入口在设置页里）。

### 1. 「学士帽」改成弹窗二选一（W1）

以前点它直接开延河课堂首页。乐学其实还能用，所以现在**弹窗问一句**：
**延河课堂**（2026 年起的作业与资料来源）/ **乐学**（旧平台，可能要求重新登录）。

- 新增 `data/.../school/LexueUrls.kt`：乐学主页地址按「校内 / 校外 WebVPN」**两套表**选
  （照 SDK 的做法走 `Config.Urls`），取不到才回退静态配置；纯逻辑回退链有 4 条单测
- ⚠️ 乐学**没有会话检查接口**，所以跳过去可能先落到登录页 —— 弹窗文案里如实说了

### 2. 动态页新增「设置」（W2）

仿 DDL 页右下角 FAB → 新设置页（路由 `activity`）：
- **只看作业**（默认**关**，即默认显示资料/公告）、**条数上限**（30/60/100，默认 60）、
  **显示已过期条目**（默认开）
- 动作项：**延河课堂**（会话状态 + 打开登录页）、**乐学**（打开主页）
- 改设置**即时生效**（ViewModel 用 combine 订阅），改上限会重取

⚠️ 顺带修了两个真问题：①取数与过滤的顺序 —— 以前按混合列表先截断再过滤，
开「只看作业」会「吃不饱」（现在按 `fetchLimit` 放大取数、过滤后再截断，
并新增「打开只看作业时重取一次」）②最后一条被 FAB 压住（底部留白 24 → 88dp）

### 3. 页序：DDL 与动态挨着（W3）

- **组件**：课程 / DDL / **动态** / 座位（动态上移到 DDL 后面）
- **App 课表页**：课表 / DDL / **动态** / 空教室

⚠️ 组件页号原先存的是**索引**，直接换位会让老用户「组件自己跳页」且毫无报错。
所以改成**存页名**（`PageKind.name`），读到旧 int 时按**旧顺序**
（课程/DDL/座位/动态）还原语义并写回 —— **幂等**；页签点击的 extra 也换成页名，
连升级瞬间的陈旧点击都兼容（Provider 同时接受 int 与页名）。
**模拟器实测**：把老格式 `<int value="2"/>` 写进 prefs → 组件停在**座位**页
（不是新序的「动态」），且 prefs 被改写成 `<string>SEAT</string>`。

### 4. 点条目 → 跳进 App 对应 tab（W4）

- 组件 DDL 行、动态行点一下 = **打开 App 并停在对的 tab**（DDL / 动态），
  以前 DDL 行是"就地勾选"、动态行只到课表第一个 tab
- **删掉 DDL 页的「打开 DDL」动作键**（条目本身就能进 App 了，重复）
- 组件里**不再提供勾选**：一个 collection 只能挂一个点击模板，没法「整行跳转 +
  小图标勾选」按视图分流。勾选所需的数据（`toggleDdlUid`）与模板
  （`ddlToggleTemplate`）都**保留**着，将来做「勾选模式」（点动作键把整页切成勾选语义）
  可以直接接上 —— 顺带说明：**RemoteViews 没有长按 API**，所以「长按勾选」做不出来
- 实现：`GotoRequest` 扩成「route + tab + 定位键」，并**下沉到 `features:common`**
  （`features:schedule` 不能反向依赖 `:features`）；`TabPager` 支持外部指定 tab
  （冷启动用 `initialPage`、已在前台走副作用）

### 5. 精确定位到那一条（W5）

- 条目带**自描述定位键**：`ddl:<uid>` / `activity:<id>` —— 两个列表在同一个 Pager 里
  都活着，键不带归属会互相消费（DDL 页把动态的请求吃掉）
- App 侧：DDL / 动态列表提升 `LazyListState`，按定位键滚过去再消费掉
- ⚠️ DDL 列表是**分区**的（两个标题行占下标），下标换算抽成 `DdlListOrder` + 5 条单测
  —— 这是最容易「滚到隔壁那条」的地方

### 验证

- 单测：widget **64**、schedule `DdlListOrderTest` **5**、data `EclassActivityLogicTest` **28**
  / `EclassDdlLogicTest` **20**、`EclassActivityCacheTest` **5**、`LexueUrlsTest` **4**
- **模拟器实测**：页序与页签 ✓、老页号迁移 ✓（上面 3 的实测）、DDL 页无「打开 DDL」键 ✓、
  点条目打开 App 且**数据库 `done` 不再被改动** ✓（语义确实从勾选变成跳转）
- ⚠️ 未能在设备上验证的部分：App 侧「停在对的 tab / 滚到那一条 / 学士帽弹窗 / 动态设置页」
  —— 模拟器没登录 BIT101 与延河课堂，课表页停在登录门禁，看不到这些界面

## 2026-09-23 v1.7.5 自检修 bug：桌面组件的点击一个都不生效 + DDL 被登录态挡住

一轮系统自检（跑全量单测 + 逐文件读代码 + **模拟器实测**）逮到 3 个真 bug，全部修好并验证。

### 1. 🐛🐛 **桌面组件上点任何条目都没反应**（最严重）

列表条目的点击模板用了 `PendingIntent.FLAG_IMMUTABLE`，而 RemoteViews collection 的
fill-in 机制要求模板**可变**：宿主（桌面进程）拿到模板后调
`send(context, code, fillInIntent)` 合并 extras，Android 12 起对不可变 PendingIntent
这样做会抛 `IllegalArgumentException`，**异常被宿主吞掉、没有任何日志**。

后果：点 DDL 条目不会勾选、点课程/座位条目不会打开 App —— v1.7.3 做的
「点 DDL 勾选」和更早的「点课程行打开课表」**一直是死的**。

- 两个模板（打开 App / 切换完成状态）改 `FLAG_MUTABLE`；页签 / 刷新 / 动作键
  这些不带 fill-in 的仍保持不可变
- ⚠️ 换 mutability 时连 `data` 一起换（`…/list` → `…/list2`）：同一 key 不允许
  既有可变又有不可变的记录共存，否则抛
  "Cannot create both immutable and mutable PendingIntents with the same key"

**模拟器实测**：点 DDL 行 → 数据库 `done` 0→1 且组件就地重绘成「已完成」；
`bit101_goto=seat` 的跳转把 App 停在了「座」页。

### 2. 🐛 **「App 里有 DDL、桌面组件不显示」的真因**

之前以为是「只看未来 14 天 + 隐藏已完成」（v1.7.3 已修），但模拟器一测就暴露了另一条：
组件把 DDL 页**整体挂在 BIT101 登录态上**，未登录就整页换成「未登录 BIT101」。
而 DDL 的数据自 v1.7.0 起来自**延河课堂 + 用户手动添加**，全在本地库，
App 里的 DDL 页从不问登录态 —— 两边行为不一致，正好是用户的原话。

- DDL 页不再看登录态（登录引导只留给**数据确实依赖该会话**的页：
  课程页→BIT101、动态页→延河课堂、座位页→seatlib）
- 单测加了回归用例（未登录 BIT101 时 DDL 页照常显示本地条目）

### 3. 🐛 **组件每次重绘都在打网络**

动态页的数据是网络请求（1 次课程列表 + 每门课 1 次），而它挂在 `WidgetRepository.load()` 里 ——
于是**点一次页签、勾一次 DDL** 都要等一轮完整请求，网络差时像卡死，还白耗流量。

- 新增 `EclassActivityCache`（纯逻辑 + 5 条单测）：10 分钟 TTL 内复用，
  只有显式刷新（刷新键 / 周期任务）才 `forceEclass` 真拉
- 顺带修：刷新键**注释说拉「我的预约」但代码里只装了桥、没真拉**，现在真的拉了

### 4. 其他

- `WebViewCookieSync` 逐条容错：单条坏 cookie 不再让整次同步（也就是整个延河课堂会话）失败
- `BIT101WidgetProvider` 类注释仍是「三页」→ 改四页；补上安全边界说明
  （receiver 是 `exported="false"`，**实测** `adb shell am broadcast` 打不进来）
- `docs/widget.md` 增补上面 3 条坑；`docs/ddl-source-contract.md` 记录 `Course.url` 值未实测

### 验证

- `WidgetLogicTest` 60 → **61**、`EclassActivityCacheTest` **5**、座位 145、通知 36 全过
- 模拟器（debug 包，`run-as` 灌真实数据）：DDL 分栏渲染 ✓、点条目勾选 ✓、
  页签切换 ✓、4 页签齐全 ✓、跳转路由 ✓
- release 包装到真机并核对 `versionName=1.7.5`

## 2026-09-23 v1.7.4 「动态」点条目 → 直接落到**那门课**的页面

承接 v1.7.3：当时动态点开只能到延河课堂**首页**。用户要的是"点了能跳过去看"，
所以这次把目标改精确。

单条活动**自身**的 URL 服务端没给过（抓包时 activities 里没有可用地址），
于是改用**课程列表里的 `url` 字段**——动态本来就发生在课程里，落到课程页更近一层。

- `EclassActivity` 新增 `targetUrl`，由纯逻辑 `openUrlOf()` 算好，**保证非空可用**
- 取数链路（`DefaultEclassRepo`）把 `course.url` 一并带进来；App 动态页点击直接用它
- ⚠️ **只接受 `http(s)://` 开头的完整地址**：`url` 的真实取值没被印证过
  （抓包响应前 400 字符就截断了，`url` 在后面），万一是相对路径，直接丢给 WebView
  只会白屏 —— 这种情形一律回退首页。**宁可少走一步，也不要白屏**
- 单测 `EclassActivityLogicTest` 14 → **18**（新增 4：课程页优先 / 非法值回退 /
  映射后每条都带目标 / 老调用点不传参也不崩）；`WidgetLogicTest` 60 条保持全过

## 2026-09-23 v1.7.3 DDL 不再限时 + 分两栏可勾选 + 新增「动态」（桌面组件也到 4 页）

依用户反馈：
> 课程中心改成延河课堂吧，然后是乐学其实还在使用，我现在里面有两个 DDL，
> 但是没在桌面上显示出来。之后是延河课堂其实会有一些动态，你可以在里面显示一下，
> 点击之后可以跳转。

### 1. 改名：课程中心 → **延河课堂**

来源标签（`DdlSource.displayName`）、DDL 页入口按钮、设置页副标题统一改名。

### 2. 🐛 修「App 里有 DDL、桌面组件不显示」

两个原因叠加，都修了：

| 原因 | 处理 |
|---|---|
| 组件只看**未来 14 天**（外加往回 7 天），而用户那两条是 **16 天**和 **41 天**后 | 改为**取全部 DDL，不再限时**（新增 `DDLScheduleRepo.getAllDDL()`） |
| 已完成的条目**完全不显示**（那两条被勾上了） | 改为**分两栏**显示：未完成在上、已完成在下 |

> 教训记一笔：组件比 App 更严格地筛数据，用户看到的就是「同步坏了」。
> 两处口径不一致时，宁可让组件多显示（可滚动），也不要静默丢掉。

### 3. DDL 分区 + 点条目切换完成状态

- 列表顶部加栏目标题行「未完成 · 3」「已完成 · 2」（组件与 App 一致）
- **组件里点一条 DDL = 勾选/取消**：走广播回 `BIT101WidgetProvider`
  （`ACTION_ITEM_TAP`），**不打开 App** —— 在桌面勾一下不该把人拽进 App
  - ⚠️ 一个列表只能挂一个点击模板，所以模板**按页切换**：DDL 页用广播模板，
    其余页保持「打开 App」。顺带给 DDL 页加了「打开 DDL」动作键作为进 App 的入口
  - ⚠️ 没有用「广播里再 `startActivity`」的写法：Android 10 起后台启动 Activity
    会被系统拦掉，而 `PendingIntent.getActivity` 由系统代发不受限
- 已完成的条目弱化显示、不写剩余时间、不标紧急

### 4. 新增「动态」（延河课堂的课程动态）

- **App**：课表页（卷）从 3 个 tab 变 4 个 —— 课表 / DDL / 空教室 / **动态**
- **桌面组件**：从 3 页变 4 页 —— 课程 / DDL / 座位 / **动态**
  （⚠️ 动态页**追加在最后**：页号按索引持久化，插队会让既有用户跳页）
- 数据：`EclassRepo.fetchActivities()` —— 与 DDL 共用一次取数
  （`fetchRawActivities`），只是不筛作业、按时间倒序
- `EclassActivityLogic`（纯逻辑，**14 条单测**）：分类（作业/资料/公告/动态）、
  该用哪个时间（作业看截止、资料看发布时间）、倒序排序、相对时间文案
- 点一条动态 → App 内 WebView 打开延河课堂
  ⚠️ 目前只能落到首页：抓包时没拿到单条活动的稳定 URL（课程对象里的 `url`
  就是首页地址），等确认规则后再精确跳转

### 5. 验证

- 单测：`WidgetLogicTest` **60 条**（新增 11 条：分栏/栏目行不可点/切换 uid/
  已完成弱化/远期不限时/动态页/四页顺序/动作键）、`EclassActivityLogicTest`
  **14 条**、`NotifyLogicTest` 36 条 —— 全绿
- 编译：data / schedule / widget / notify / app 全通


## 2026-09-23 v1.7.2 出分提醒（通知里不含分数）

成绩数据其实早就有：BIT101 的 `GET /scores` 一直在 api 模块里注册着，
但**全仓零调用** —— 本版把它接上，做出分提醒。

### 1. 行为

- 检查时机：**App 启动** + **每日周期任务**（出分没有确定的未来时刻，
  不适合排期，属于「某天突然多了几行」的事件）
- 有新课出分 → 弹一条汇总通知（固定 id，多次出分只更新同一条）：
  - 一门：`出分了 / 「操作系统」成绩已发布`
  - 多门：`出分了 / 操作系统、数据结构 等 3 门课成绩已发布`
- 点通知 → 跳「网」页（成绩页在 bit101.cn/score/，那里自动填学号密码）

### 2. ⚠️ 隐私边界（用户明确要求）

**通知里只有课名、没有分数** —— 由 `ScoreLogic.summaryText` 生成，
单测锁死（`文案绝不包含分数`）。想看分数必须点进 App。

### 3. 防御式解析（真实形状未验证）

`/scores` 的响应形状自相矛盾（注释说二维表、字段却是 `ArrayList<String>`），
且从未被调用过。所以：

- 模型改为通用 `JsonElement`，解析在 `ScoreLogic`（纯逻辑，**14 条单测**）：
  表头按关键词定位（课程/成绩/学期列），**找不到表头就返回空、绝不猜列含义**
- 兼容两种行形状（数组的行 / 字符串的行按空白切列）
- ⚠️ 已知局限：字符串行的英文课名（含空格）会被切错列 —— 但错误方向安全：
  多半解析不出 → 不提醒，而不是误报

### 4. 快照与差分

- 快照存 `filesDir/score_snapshot.json`（`{课程|学期: 分数}`），单写者、整文件读写
- **首次运行只建基线、不发通知** —— 否则第一次打开就把历史成绩全报一遍
- 只认「快照里没有的键」；改分不重新提醒（那要展示分数差才有意义，
  会突破隐私边界）
- 拉不到 / 解析失败 / 未登录 → 返回空、快照保持原样：绝不因一次失败清掉基线

### 5. 验证

- `ScoreLogicTest` **14/14**（解析/列序/字符串行/无表头/空表/差分/首建基线/
  改分/快照回读/文案与隐私）
- 编译 data / notify / setting / app 全通
- 模拟器实测：`score_reminder` 渠道已创建 ✓；未登录时无基线文件 = 不误报 ✓
- ⚠️ 端到端未验证（需要 BIT101 登录 + 真实出分）

### 6. 已知限制

- 通知点击只到「网」页而非成绩页 —— `GotoRequest` 只认底栏页路由，
  Web 路由带 url 参数走不了这条通道
- 解析的列映射靠表头关键词，若服务端表头用词不同则静默不提醒（等真实响应校正）


## 2026-09-23 v1.7.1 座位签到提醒（通知中心的第三条源）

预约了座位却忘了刷卡 → 记一次违约 → 累计 5 次暂停 7 天。这是整套 App 里后果最重、
又最容易忘的一件事，本版把它接进提醒中心。

### 1. 新增座位签到提醒

- `ReminderKind.SEAT_SIGN_IN`：独立于 DDL 单独成类 —— 后果不同（违约 vs 扣分），
  渠道与优先级也不同
- `SeatReminderSource`（**notify 声明接口**，`DefaultSeatReminderSource` 由座位模块实现）
  → 依赖方向仍是 `seat → notify`，提醒中心不认识 `ReservationRecord`
- 通知渠道 `seat_reminder`（**IMPORTANCE_HIGH**）：错过要记违约，值得在锁屏就能看到
- 默认提前 **15 分钟**，可在设置里选 5 / 15 / 30 / 60 分钟

### 2. ⚠️ 与上课提醒**刻意相反**的一条规则

上课提醒过了时刻**不补发**（「10 分钟后上课」迟发就变成假话）；
座位签到提醒只要**截止还没到就必须发** —— 用户还能走过去刷卡。

所以提前量落在过去时，`at` 被抬到「现在」立刻发，而不是丢弃。
通知正文写**绝对截止时刻**（`请在 11:00 前刷卡`），因此早发晚发都不会说错话。

### 3. 排期时机

签到提醒是在**排期那一刻**按当时的预约算出来的。刚约上的座位在上次排期时还不存在，
不重排就永远等不到提醒。因此在两处主动触发重排：

- `SeatWidgetPublisher.periodicRefresh()`：后台刷新「我的预约」成功后（每 10 分钟节流）
- `SeatViewModel.refreshMyReservations()`：App 内刷新成功后

### 4. 取数细节

- 只取 `status == "2"`（有效未签到）且截止时刻未过的记录
- 读数据前按 **5 分钟**节流刷新（比组件的 10 分钟更短：用稍旧数据算出错误截止时刻的
  代价比多发一次请求大得多）
- 未登录/会话失效 → 返回空列表，**不报错不弹窗**（这条源是「有就更好」）

### 5. 验证

- `NotifyLogicTest` **36/36**（新增 11 条：定时/不补发/立刻发/开关/窗口/多时段/
  去重/改提前量/键解析/文案降级/三源混排）
- 编译：config / notify / seat / setting / widget / app 全通


## 2026-09-23 v1.7.0 DDL 换源：乐学 → 课程中心（eclass）

学校 2026 年起用「课程中心」（延河课堂，`zy-eclass.bit.edu.cn`）替代乐学下发作业与
学习资料。乐学链路失效后 DDL 页实际是空的 —— 本版新增课程中心作为**主数据源**，
乐学源保留（历史数据仍有价值），两者并存。

### 1. 新增 eclass 数据链路

- `EclassApiService`（Retrofit）：`/api/user/recently-visited-courses`（课程列表）
  + `/api/courses/{id}/activities`（课程动态）
  ⚠️ 会话是**纯 cookie**（用户在 WebView 走统一身份认证后拿到），挂在带 cookieJar 的
  `schoolClient` 上即可，不需要额外 token 头
- `EclassDdlLogic`（**纯逻辑，20 条单测**）：作业判定 / 时间解析 / 映射
- `EclassRepo` + `DefaultEclassRepo`：课程列表 → 逐课程并发拉动态 → 映射
- `WebViewCookieSync`：把 WebView 登录后的 cookie 同步到 OkHttp 的 cookie store

### 2. ⚠️ 判定作业的方式：用「字段存在性」，不枚举 type

资料与作业在同一张表里，靠 `type` 与若干字段区分。**刻意不猜 `type` 的枚举值** ——
实测时开学第 4 周课程里只有资料、没有任何作业，**拿不到作业的 type 真实值**；
猜错就是「一个作业都收不到」且极难排查。改判作业特有字段是否存在：

```
带 submit_times / is_review_homework / late_submission_count  →  作业
只有标题和附件、没有截止时间                                   →  资料
```

唯一已知的 `type` 取值是资料的 `"material"`，直接排除。判定**保守**：
宁可漏一条，也不要把资料混进 DDL 列表。

### 3. 接入与 UI

- `DDLScheduleViewModel.updateEclassDdl()`：沿用乐学源的「不存在插入、存在更新」策略，
  **保留 `done`**（用户勾掉的完成状态不被一次同步冲掉）
- `DdlSource`（data 层，App 与组件共用）：`group` → 中文名（课程中心 / 乐学 / 自定义）
  + 可否编辑 —— 同步来的条目改了也会被下次同步覆盖，故只允许编辑自定义项
- DDL 页右下角新增「课程中心」FAB → App 内 WebView 打开登录页
  ⚠️ 必须用 App 内 WebView：只有它与我们共用的 CookieManager 互通，
  用系统浏览器登录的话 App 拿不到会话
- **修组件 bug**：DDL 行首以前直接显示 `group` 原文，组件上会出现「lexue 第三次作业」

### 4. 验证

- 编译：api / data / schedule / widget / app **全通**
- 单测：`EclassDdlLogicTest` **20/20**
  （其中 `没有 endTime 时回落到 visibleEndAt` 逮到实现的真 bug：
  `isHomework` 只看 `endTime`，而 `deadlineOf` 会回落到 `visibleEndAt`，两处口径不一致）
- ⚠️ **真机端到端未验证** —— 需要在 App 里登录课程中心（WebView），
  且当前账号尚无作业，所以只能验证「能拉到课程列表」这一步

### 5. 未做 / 已知限制

- `/api/todos`（个人待办）**当前恒为空**（未布置作业时无数据），故未纳入；
  等有作业时再补
- 课程列表用的是「最近访问的课程」（`/api/courses` 返回 403，`/api/user/courses`
  与 `/api/course-list` 都不存在）。若将来发现漏课，再找更全的入口
- 作业的 `type` 真实值仍未知 —— 等有第一份作业时用 `adb run-as` 读真实响应校正

## 2026-09-22 v1.6.9 提醒设置页 + 清掉 8 个小毛病 + 座位页自动静默重登

### 1. 提醒设置页（v1.6.8 欠的 UI）

`我 → 设置 → 提醒设置`：总开关、上课提醒（开关 + 提前量 5/10/15/20/30 分钟）、
作业截止提醒（开关 + 提前 1 天 / 1 小时）、以及**系统通知权限状态**
（权限被拒时给一键申请 —— 设置里全开着但没权限的话什么都不会弹，必须说清楚）。

⚠️ 每次改动都会**立即重排**：已排的 WorkManager 任务按旧设置定下了时刻，
不重排就「改了提前量却不生效」；关总开关则取消全部已排任务。

### 2. 清掉 8 个小毛病

| # | 问题 | 处理 |
|---|---|---|
| 1 | 未读角标为 0 仍显示「0」 | 角标改为仅 `> 0` 时渲染 |
| 2 | `api` 模块被写死 JDK 25（其余模块 17，换机器/CI 必炸） | 改回 `versions.*`（17）+ `kotlinOptions.jvmTarget` |
| 3 | 作息表默认值两处手写、靠人工同步 | 默认值改由 `FALLBACK_TIME_TABLE` 生成（单一来源） |
| 4 | 消息中心默认停在「系统」Tab | 改为默认「点赞」 |
| 5 | 版本判定命名混乱（`versionNumber` vs 服务端 `versionCode/minVersionCode`） | 统一为 `AppVersion.versionCode`；`4.kt` → `Version4Dialog.kt` 并注明它**不是死代码**（1.2.0 升级用户的一次性重登入口） |
| 6 | 底栏「座」用人像图标 | 换成 `EventSeat` |
| 7 | `docs/widget.md` 里作息表路径已过期 | 更新 |
| 8 | 消息未读计数一次刷新打两个请求 | 只拉「分开计数」，总数由四类求和 |

### 3. 通知权限组件上移到 `features:common`

原先在 `features:seat` 里，现在设置页（提醒）也要用 —— 移到 `features:common/helper/
NotificationPermission.kt`，座位侧两个页面改用新路径。避免 setting 反向依赖 seat。

### 4. 座位页：进入时自动尝试一次静默续期

此前只有点「授权座位系统」才会续期，冷启动进页面不恢复 —— 用户看到「登录已失效」
就以为必须重新授权。现在进座位页会自动试一次（`SeatViewModel.autoRenewSilently()`），
**绝不会弹 WebView**，失败保持原样由用户决定。每次进程只试一次，不反复撞风控。

### 5. 验证

`NotifyLogicTest` 23/23、`WidgetLogicTest` 54/54、`features:seat` 单测全过；
`assembleDebug` / `assembleRelease` 通过；v1.6.9 已装真机。
⚠️ 设置页的**渲染**未在真机确认（截屏时手机处于锁屏），留给用户点一下
`我 → 设置 → 提醒设置` 验证。


## 2026-09-22 v1.6.8 通知与提醒中心（新增 `features/notify`）

在此之前 App 里所有数据都要用户自己想起来看 —— 课表、DDL、座位预约全是「静态的」。
本版把本地数据变成**按时的提醒**。

### 1. 新模块 `features/notify`

- `NotifyLogic`（**纯逻辑，23 条单测**）：算出「此刻该排哪些提醒」——
  提醒时刻、窗口过滤、周次过滤、去重键、过期/已完成过滤，全部可单测
- `NotifyRepository`：读设置 + 读本地数据（**走 data 的公开仓库接口**，
  因为 DAO 是 `internal` 的），组装策略后交给逻辑层
- `NotifyCenter`：通知渠道与发送。渠道分两个 —— `class_reminder`（DEFAULT）、
  `ddl_reminder`（HIGH，作业截止更紧急）；座位侧原有的「预约结果」渠道保持不动
- `NotifyScheduler`：每条提醒一个**一次性 WorkManager 任务**（唯一名 = 去重键），
  另有每天一次的周期性重排兜底（用户一周不开 App 也不会断）
- `NotifySentStore`：已发记录（SharedPreferences，同步读），防重复打扰；
  只保留最近 14 天，避免跑一年攒下几千条
- `NotifyAppStartup`：启动时建渠道 + 重排 + 挂周期任务；`NotifyRepositoryHolder`
  用 EntryPoint 兜底（Worker 不经 Hilt 注入）

### 2. 两类提醒

| 提醒 | 默认 | 文案 |
|---|---|---|
| 上课 | 提前 **10 分钟** | `10 分钟后上课` / `09:55-12:20 · 计算机视觉 · 综教B301` |
| DDL | 提前 **1 天** 与 **1 小时** | `作业即将截止` / `操作系统第三次作业 · 今天 23:59 截止` |

**设计取舍**：

- **不用精确闹钟**（`SCHEDULE_EXACT_ALARM` 要额外权限且耗电）。WorkManager 在 Doze
  下可能晚几分钟，因此通知正文写的是**绝对时间**（「09:55-12:20」），
  而不是「还有 10 分钟」—— 相对时间会因延迟变成假话
- **worker 到点必须二次校验**：排期与执行之间用户可能删课、把 DDL 标成已完成、
  或老师改期。只有仍然成立才发（`NotifyRepository.stillValid`）
- **改提前量视为新提醒**：去重键里带提前量，用户从 10 分钟改成 20 分钟后应按新策略再提醒
- **今天没课不误报**：周次判不出来时**不按周次过滤**（宁可多提醒也不漏），
  但提醒时刻已过去的一律不补发

### 3. 顺带

- 把「节次 ↔ 时刻」的纯逻辑从 `features/widget` 提到 **config 模块**
  （`TimeTableLogic.kt`）—— 组件与提醒共用一份，不写两套；组件的对外 API 保持不变
- `POST_NOTIFICATIONS` 在 notify 模块清单里再声明一次（模块自包含，合并去重）

### 4. 验证

- `NotifyLogicTest` **23/23**
- `assembleDebug` / `assembleRelease` 通过
- **真机实测**：渠道已创建；`dumpsys jobscheduler` 排出 10 条提醒任务，
  延迟换算成钟点为 **09:45 / 13:10 / 15:05 / 18:20**，正好是该用户真实课表
  （09:55 / 13:20 / 15:15 / 18:30）提前 10 分钟；强制触发一条后通知栏出现
  「10 分钟后上课 / 09:55-12:20 · 计算机视觉 · 综教B301」

### 5. 待续

- 设置页 UI（「提醒」分组）—— 数据层已就绪（`NotifySettings` + 6 个 DataStore 键）
- 座位签到时限 / 暂离将到期提醒的座位侧接入（预留 `seat → notify` 方向）

## 2026-09-22 v1.6.7 小组件跨天规则再收窄

用户：`过了最后一个时段即使明天没课也切换`。
`pickDay` 去掉「明天有课才切」的检查 —— 晚于今天最后一个时段就切明天
（明天没课时显示明天的整段空闲，底部日期栏标明「明天」）。今天没课仍停在今天。
单测 54/54。

## 2026-09-22 v1.6.6 小组件：今天没课不切明天 + 点课程打开课表

- **跨天规则收窄**：今天没课不再切明天，停在今天显示整段空闲
- **点课程条目打开课表**：列表挂 `PendingIntentTemplate` + 课程行
  `setOnClickFillInIntent`（RemoteViews collection 的标准做法，只有带 fillInIntent
  的行可点）；空闲/DDL/座位行不可点
  ⚠️ 组件条目**没有双击语义**（一次点击即触发），所以做成了单点打开
- 单测 54/54

## 2026-09-22 v1.6.5 座位页实时状态 + 一键预约 + 规则展示

- **座位页按实时状态显示**（新增 `SeatStatusLogic`，纯逻辑）：
  已预约→`剩 48 分钟刷卡`（复用 `Reservation.signInDeadline` 的当日 60 分钟 /
  次日 9:00 规则）；使用中→`已用 1 小时 32 分`；暂离→`保留 60 分钟`
  （用餐时段 11-12 / 16-17 点按规则延长到 120 分钟）
  ⚠️ **阶段文案以服务端 `statusname` 为准**（官方前端也只特判 `status=="2"`），
  本地只用关键词判断行为，不按数字编造状态
- **组件「一键预约」**：不开 App 直接下单（`SeatWidgetBridge` 反向动作桥 +
  `DefaultSeatWidgetBridge` 实现），结果写进快照 `notice`（60 秒自动消失）；
  选座规则与后台抢座**共用** `SeatStatusLogic.pickTargetSeat`
- **`SeatReservationRepository`**：「我的预约」唯一持有者（此前只在 ViewModel 内存，
  组件拿不到）；Publisher 改为 `combine(tasks, records)` + 10 分钟节流后台刷新
- **修「老要重新授权」的真实缺口**：token 被清空后自动续期永远触发不了
  （此前只在「token 还在但服务端拒绝」路径续期）。现在 token 变空会先静默重登
- App 列表页底部加「查看座位预约规则」入口（`SeatRulesDialog`）
- 单测 **198/198**（seat 145 + widget 53）


## 2026-09-21 v1.6.4 小组件：可上下滑动的全天行程 + 空闲时段 + 跨天切换

真机 v1.6.3 试用后的需求：一天要能看全、空档要标出来、日期要写清楚。

### 1. 固定 3 行 → 可上下滑动的列表

用户要看「一天所有时段」，3 行放不下。RemoteViews **不支持 ScrollView**，
只能用 collection 机制（`ListView` + `RemoteViewsService`）：

- 新增 `WidgetListService`：它的 `RemoteViewsFactory` 提供条目视图
- `widget_root.xml` 的内容区改成 `ListView`（`0dp + weight=1`，写 `wrap_content`
  会让它撑开到全部条目高度、滚动失效）
- `manifest` 加 `<service android:exported="true"
  android:permission="android.permission.BIND_REMOTEVIEWS">` —— 桌面在别的进程，
  必须能跨进程 bind；权限把调用方限制在系统/桌面
- 下发后补 `notifyAppWidgetViewDataChanged()`，否则列表沿用缓存的旧条目

连带**删掉了 `WidgetLogic.rowsForHeight()` 与那套按高度降档的逻辑**：
列表自己滚动，不再需要按高度裁行数（也就不再有「读 MIN 还是 MAX」的坑）。

### 2. 空闲时段：标注 + 自动合并

`WidgetLogic.buildDayBlocks()` 把一天铺成完整时间轴：相邻的空闲小节合并成
**一段**（3 节空档显示成一行「空闲 09:55-12:20」，而不是三行刷屏）。
整天没课给一整段空闲。空闲行用次要色（`WidgetLine.muted`），不与课程抢视觉。

⚠️ 课程节次**超出时间表范围**时（学校新增节次而设置没更新）不被吞掉 ——
扫描范围扩到最后一节，时间显示为空也不漏课。

### 3. 跨天切换 + 底部日期栏

`WidgetLogic.pickDay()`：

| 情况 | 显示 |
|------|------|
| 现在早于今天第一个时段 | 今天，列表从头开始 |
| 现在落在某个时段内 | 今天，**自动滚到那个时段** |
| 现在晚于今天最后一个时段 | 明天 |
| 今天一节课都没有 | 明天 |

⚠️ 只有明天**有课**时才切过去，否则会出现「今晚看明天、明天还是一片空白」。
⚠️ 底部必须标出是哪一天（`今天 · 9月21日 周一 · 第3周` / `明天 · 9月22日 周二`），
不标就会被当成日期 bug。**显示明天时不高亮**任何一节。

### 4. 自动定位到当前时段

`WidgetLogic.scrollIndexOf()` 算出「现在所处的块」的下标，
`rv.setScrollPosition()` 应用。课间会落在空闲块上，同样能定位。

⚠️ 每次重绘都会重新应用 —— 用户手动滑到别处后，任何一次重绘都会把位置拉回
当前时段。这是**有意**的（组件是"扫一眼看现在"的东西）。

### 5. 顺带

- 组件选择器预览图按新排版重画（多了底部日期栏、高亮行带淡色块底）
- 修掉 `styles.xml` 里 XML 注释含 `--` 导致的资源编译失败

### 验证（模拟器，灌入周一 5 门 + 周二 1 门课）

`WidgetLogicTest` **53/53**；列表渲染出「空闲 / 3-5节 操作系统（绿色+`▶`+底块）/
6-7节 / 8-10节 / 11节 / 空闲 / 13节」，可上下滑动，点刷新自动滚回当前时段；
把设备时区推到 20:57（晚于最后一节下课时间）后切到明天，底部标注
「明天 · 9月22日 周二」且不高亮。

---

## 2026-09-21 v1.6.3 小组件登录引导 + 座位会话自动续期 + 课程排版两行式

> ⚠️ 补记：本次变更当时只写了提交信息、漏写本文件（v1.6.4 时补上）。

真机 v1.6.2 试用后的反馈：字体/按钮再好一些、组件要能看出登录状态、
图书馆座位系统仍有问题（取消报错、老要重新授权）。

### 1. 课程行改「两行式」

旧版单行挤 `[节次+课程名] [时间] [教室]`，组件拖窄后课程名被截成「开…」。
改为：第 1 行「标记 + 课程名」独占全宽（可换行到 2 行），
第 2 行「时间 + 地点」靠右。时间与地点都为空时第 2 行整行隐藏。

### 2. 显示窗口：放得下就全天

旧版「从当前那节往后取」导致下午看组件只剩最后一节课（真机 16:26 只显示 1 门）。
改为**默认显示当天全部课程**；课多到放不下时才开窗，且以焦点为中心。

### 3. 组件检测登录态

未登录时整页换成「未登录 BIT101 / 未登录座位系统 + 登录按钮」——
本地库可能是几天前同步的，会话失效时继续展示会让人以为数据是新的。
新增 `AppRoutes.LOGIN` 常量下沉到 `:config`（组件不能依赖 `:features`），
与 `NavDestConfig.Login` 同源。读取登录态失败按**已登录**处理（fail-open）。

### 4. 座位会话自动续期（`SeatAutoLogin`）

「登录一次就别再打扰我」的缺口在这里：cookie 一过期就没救了，只能人工重登，
而**学号密码其实一直加密存在本地**（`DefaultLoginRepo` 每次登录都写）却从没被自动用过。

- 新增 `SeatAutoLogin`：cookie 静默认证 → 失败则用保存的凭据自动重登
- `SeatApi.authed`：认证失效自动续期一次并重试，取消/预约/我的预约全走它
- ⚠️ 凭据登录**限时 25 秒** —— `SsoLogin` 的短信回调会无限期挂起等用户输入，
  后台静默续期撞上二次验证会永久卡死；超时即取消并回退手动登录
- 成功 30 秒 / 失败 5 分钟冷却（反复打 SSO 会触发学校风控）

### 5. 座位错误信息人话化

- 不再把内部常量 `TOKEN_EXPIRED` 当文案弹给用户（真机出现过「取消失败: TOKEN_EXPIRED」）
- 修掉「取消失败: 取消失败:」双重前缀（API 不再自带前缀，UI 只拼一次）
- `cancelSeat` 不再用 `getOrNull()` 吞掉拉列表失败的真实原因
  （此前网络/会话问题全被伪装成「未找到该预约记录」，把排查方向指错）
- 取消路径补 `handleApiError`（此前死 token 一直留着，每次取消都失败）

### 验证

`WidgetLogicTest` 41/41；seat / features / app 全部编译通过；
模拟器实测未登录时课程/座位页分别显示登录引导，点「登录」App 直接落在登录页。

---

## 2026-09-21 v1.6.2 小组件：高度可缩 + 当前课程高亮 + 座位页直达入口

真机 v1.6.1 试用后的四点反馈，除「宽度可调」已满意外逐条实现。

### 1. 高度可缩到约一半（原来拖不小）

`minResizeHeight` 180dp → **110dp（4×2）**，并给内容行数加了三档自适应
（`WidgetLogic.rowsForHeight`）：

| 高度 | 行数 |
|------|------|
| ≥ 140dp（全高 180dp） | 3 行 |
| ≥ 100dp（含最小高度 110dp） | 2 行 |
| 更矮 | 1 行 |

判定做成纯函数并单测覆盖。**数据与布局共用同一个 limit**
（`BIT101WidgetProvider.render()` 算一次，同时传给取数与建视图），
避免「取 3 行只画 2 行」或「画了没取到的行」。

### 2. ⭐ 实时高亮「正在上 / 马上要上」的那节 + 显示上课时间

- 新增时间列：一行结构变为 `[节次] 课程名 | 上课时间 | 教室`，
  `09:55-12:20` 独立成列（塞进课程名会被省略号吃掉）
- 高亮规则（`WidgetLogic.focusOf`）：
  **正在上 → 绿色加粗 + `▶`**；课间 **→ 下一节橙色加粗 + `▶`**；
  今天上完 → 不高亮
- **显示窗口跟着当前时刻走**：14:12 时窗口从第 6-7 节开始，不会停在上午的早课；
  今天上完则停在末尾几节
- 组件很矮只显示 1 行时，那一行仍然是高亮的那节

⚠️ **时间表取的是「课表设置里的时间表」**（`CourseScheduleSettings.timeTable`），
不是硬编码 —— 用户可在 App 里自行编辑，学校改作息也只需改设置；
只有读取失败才退回内置的学校官方默认表。

### 3. 座位页新增「立即预约」

点了打开 App 并**直接落在座位页**。规则是「有空余行位就显示」：
空态时显示（拖矮到 2 行时让位给按钮、隐藏「暂无预约」文字），
1–2 行内容时也显示，占满 3 行时隐藏（否则会被裁掉半截）。

跳转链路：组件 → Intent extra `bit101_goto` → `MainActivity.onCreate` /
`onNewIntent` → `GotoRequest` → `IndexScreen`。**两个时机都要处理**：
只做 `onCreate` 的话，App 在后台时点按钮只会被唤到前台、不跳页。
目标页取值与 `PageShowOnNav` 同源（不硬编码 route 字符串）。

### 4. 顺带清理

- 删掉 Glance 时代的死代码 `dayChanged` / `isClassHours` / `dateSubtitle`
  （只有测试引用、生产路径无人调用）及其测试
- 删掉 `LocalDate.atStartOfDay()` 扩展（stdlib 本就有，且未被使用）
- 类注释里残留的 `provideGlance` / Glance 表述改为 RemoteViews 现状

### 验证

`:features:widget:testDebugUnitTest` **32/32 通过**；`assembleDebug` 通过。
高亮与时间列的判定（含「正在上」「课间下一节」「今天上完」「换成用户自定义
时间表」）都有单测；渲染与交互在模拟器实测。

---

## 2026-09-21 v1.6.1 桌面小组件界面重做（依真机反馈）

真机（NP05J）试用后反馈：字体太小不好看、翻页按钮太小点不中、
**切到后两页就回不去了**、没有刷新入口。逐条处理。

### 0. ⭐ 渲染方案重写：弃用 Glance，改回传统 RemoteViews

**这是本轮最关键的改动**，其它几项都是它的附属。

排查「切过去回不来」时发现：点击回调**执行了**、页号**写入成功了**
（可直接读存储验证），`update()` 也**没抛异常**，但 `provideGlance`
**一次都没被调用** —— 渲染压根没发生，界面停在旧内容上。

读 Glance 源码确认了原因：

```kotlin
// GlanceAppWidget.kt
internal suspend fun update(context, appWidgetId, options) {
    sessionManager.runWithLock {
        if (!isSessionRunning(...)) { startSession(...); return@runWithLock }
        session.updateGlance()          // ← 只是发一个事件
    }
}
// AppWidgetSession.kt
suspend fun updateGlance() { sendEvent(UpdateGlanceState) }
```

`update()` 只负责**驱动 Session**，真正的重绘交给**异步的 Session 事件循环**
（跑在 WorkManager 的 `SessionWorker` 里）。对**已经存在的 Session**，
这条路径不保证重新执行 `provideGlance` —— 日志里 `SessionWorker` 报了 SUCCESS，
画面却没变。

**升级 Glance 1.1.1 无效**；把页号从 Glance state 改存普通 SharedPreferences
也无效（排除「state 被缓存」的猜测）。

于是改为 **`AppWidgetProvider` + `RemoteViews`**：点击回调里读数据 →
构建 RemoteViews → `AppWidgetManager.updateAppWidget()`。
**同步 API，调用即生效**，中间没有任何异步层。

- 新增 `BIT101WidgetProvider`（onUpdate / onReceive / onDeleted）
- 新增 `WidgetViews`（RemoteViews 构建 + PendingIntent）
- 新增 `res/layout/widget_root.xml`、`widget_styles.xml`、页签/卡片背景 drawable
- 删除 `BIT101Widget.kt`（Glance 实现）、移除 glance 依赖
- 附带修正：`WidgetPageStore`（页号，SharedPreferences）、
  `WidgetRepositoryHolder` 独立成文件并支持用 `@EntryPoint` 兜底取仓库

⚠️ **PendingIntent 的 `data` 必须唯一**：唯一性只看 `requestCode` +
`Intent.filterEquals`，而 **filterEquals 不比较 extras** —— 只靠 extras 区分
目标页会让几个页签被判成同一个 PendingIntent 互相覆盖。

### 1. 交互重做：`‹ ›` 小箭头 → 顶部三页签 + 刷新键

旧版的 `‹`/`›` 是 16sp 字符 + 8dp padding，实际触摸区约 **36×20dp**，
远小于 Android 规范的 48dp 最小值，两个箭头还紧挨着 —— 真机上极难点中，
「回不去」就是这么来的。

新布局：`[课程][DDL][座位]  刷新`

| 元素 | 尺寸 | 行为 |
|------|------|------|
| 页签 ×3 | 各 ≈ (宽 − 48dp) / 3，高 36dp | **点哪页去哪页**，不必逐页翻 |
| 刷新 | 48dp × 36dp | 立即重读本地库并重绘 |

每个页签的 `PendingIntent` 用**各自的 `data`**
（`bit101://page/<appWidgetId>/<page>`）来区分 —— 见开头第 0 节的说明。

### 2. 字号整体放大（用户主诉）

| 位置 | 旧 | 新 |
|------|----|----|
| 主行 | 14sp | **16sp** |
| 次行 | 12sp | **14sp** |
| 行首标记 / 行尾 | 10sp | **13sp** |
| 空态文案 | 12sp | **15sp** |

组件尺寸相应从 **4×2 改为 4×3**（minWidth 180→250dp，minHeight 110→180dp），
否则放不下「页签 + 3 行」。

### 3. 新增刷新键

`RefreshAction` → 重读 Room 并重绘。⚠️ **只重读本地库、不联网** ——
组件始终不联网，真正的数据同步由 App 负责。

点击后同步重绘：`WidgetViews.build()` → `AppWidgetManager.updateAppWidget()`。

### 4. 组件不再依赖「App 是否被打开过」

`WidgetRepositoryHolder.ensureRepository()`：取不到仓库时用 Hilt `@EntryPoint`
现取一次。以前必须先手动打开 App 组件才有数据，现在系统拉起组件进程时
（`Application.onCreate` 跑完）就能自己拿到。

### 5. 兼容性加固（为其他 ROM 铺路）

- **`previewImage` 由 vector 改为 PNG 位图** —— 部分 ROM（尤其华为）的组件
  选择器只渲染位图，矢量会显示成空白。新增
  `res/drawable-xxhdpi/widget_preview.png`（750×540，4×3 版式）
- **刷新键不用 `↻` / `⟳` 字符**：部分系统字体缺这两个字形，会渲染成豆腐块
  （生成预览图时已实际撞上），改用中文「刷新」
- `consumer-rules.pro` 里记了一条历史教训：Glance 用
  `Class.forName(className)` 反射实例化回调，**一旦开 R8 混淆点击就静默失效**。
  改 RemoteViews 后不需要 keep 规则（Provider 由 manifest 引用、点击走显式
  Intent），但这段注释留着提醒「别在组件里按类名反射」

### 未做：鸿蒙适配（结论是做不到）

真机反馈「华为鸿蒙 6 添加不了组件」。查证后确认这不是适配问题：
HarmonyOS 5/6 移除 AOSP，桌面卡片是 **ArkTS 服务卡片（Form）**；
APK 只能跑在卓易通/出境易的**兼容容器**里，容器不是鸿蒙桌面，
**没有任何机制**把 `AppWidget` 挂上去（HarmonyOS 7 连容器都会去掉）。

要出卡片只能另做鸿蒙原生应用。完整分析见
[docs/widget.md](docs/widget.md) 的「平台限制」一节。

---

## 2026-09-20 v1.6.0 桌面小组件（新增 `features/widget`）

上游设想第 3/5 项「通过小组件在桌面显示课程日程」。**新功能，不影响既有页面**。

### 显示内容

三页，右上角 `‹ ›` 翻页 + `● ○ ○` 指示点（Glance 没有 Pager，横向滑动也会被
桌面手势吃掉，故改用按钮）：

| 页 | 内容 | 空态 |
|---|------|------|
| 课程 | 今日课程按节次升序（主行 1 条 + 次行 2 条） | 今日无课 |
| DDL | 未完成项按到期升序，**已过期的仍显示**并标红 | 暂无待办 |
| 座位 | 进行中的抢座任务，补已预约记录 | 暂无预约 |

### 架构：单向数据桥，组件不依赖座位模块

`features/widget` **不依赖** `features:seat`；座位侧 `SeatWidgetPublisher`
主动把「要显示的行」写进 `SeatWidgetSnapshot`（SharedPreferences）。
收益：组件进程拉起不会连带初始化座位模块的重依赖；没开座位功能时另两页照常。

「尝试 N 次」这类字段怎么解释只有座位模块知道，故组装逻辑留在座位侧。

### 三路刷新

| 途径 | 触发 | 缺陷 |
|------|------|------|
| `WidgetUpdater.refresh()` | 业务侧显式调用 | 要求 App 前台跑过 |
| `WidgetRefreshWorker`（WorkManager 30min） | 跨零点兜底 | 最小 15min，Doze 下可能几小时 |
| `updatePeriodMillis` | 设为 **0**，不用 | 最小 30min 且不精确 |

### 防住的坑（均有单测）

- 周次按 `[n]` **完整标记**匹配 —— `contains("1")` 会让第 1 周命中 `[11]`/`[21]`
- 教学周未知时**跳过**周次过滤 —— 宁可多显示，不能显示错的空态（用户会以为没同步）
- 已过期 DDL 显示「已过期」而非负数剩余时间

### 工程改动

- 新增模块 `features/widget`（Glance 1.1.0 + work-runtime 2.9.0）
- `app/build.gradle` 显式依赖 `features:widget` 与 `features:seat`
  （`:features` 用 `implementation` 依赖子模块、不向上传递，而 `App.kt` 需要在
  `Application.onCreate` 做启动接线）
- `App.kt` 新增 `WidgetAppStartup.init()` 与 `SeatAppStartup.init()`，均内部容错

### 验证

模拟器实测：provider 注册 → 选择器显示「BIT101 课程日程 4×2」→ 拖到桌面渲染
正常 → `›` 翻页生效（`●○○`→`○●○`）→ 杀进程后页号保持 → WorkManager 任务
SUCCESS → 无崩溃无 ANR。单测 25/25，`assembleDebug` 通过。
详见 [docs/widget.md](docs/widget.md)。

---

## 2026-09-20 预约任务列表页体验优化（用户选 C 档：连结构一起调整）

用户从三个选项中选定：**连结构一起调整** + 显示**尝试次数与最近尝试时间**
+ 终态**折叠进「已结束」分组，可展开**。

### 1. 结构重排（本轮的核心）

「我的预约」与「预约任务」**不是并列关系，而是因果关系** —— 任务抢到座位后会
**变成**上面的一条「我的预约」。因此任务区默认**只显示进行中**的，
已结束的收进可展开的「已结束 N」分组 —— 否则几十条死任务会把真正在跑的那个埋掉。

页面顺序：通知权限横幅 → 我的预约（带更新时间 + 刷新键）→ 预约任务（仅进行中）→ 已结束（折叠）

### 2. 排序规则（`TaskListLogic.sort`）

```
compareByDescending { !status.isTerminal }   // 一级键：进行中必须浮顶
  .thenByDescending { createdAt }            // 组内：新的靠前
  .thenBy { id }                             // 稳定兜底，防同秒创建抖动
```

⚠️ **不能只按 `createdAt` 排**：一个三天前建立、仍在跑的监控任务，会被今天新建的、
已经失败的终态任务压到下面 —— 用户最需要看的反而看不到。所以「是否进行中」必须是一级键。

### 3. 三层「存活证据」（判断后台是否还活着的唯一依据）

后台任务可能被系统杀进程、退避等待或断网而长时间没有结果，光看徽章上的「运行中」
用户无从判断它到底还在不在干活。三项各有独立的呈现规则：

| 信息 | 来源 | 缺失时的处理 |
|---|---|---|
| **已等待 N** | `createdAt` | `<= 0`（老数据）→ 隐藏该段 |
| **尝试 N 次** | `markAttempt` 累加 | **永远显示，含 0** —— 「尝试 0 次」本身就是异常信号 |
| **最近尝试：N 前** | `lastAttemptAt` | `<= 0` → 显示「**尚未尝试**」而非静默省略（同样是异常信号） |

`TaskListLogic.livenessText(createdAt, attempts, lastAttemptAt, now)` 三合一，
**刻意从 Composable 抽到纯逻辑** —— 这段拼接文案原本 inline 在 `TaskCard` 里完全测不到。
⚠️ 老数据缺 `createdAt` **不能连累尝试次数**（次数是唯一能看到的进展信号）。

### 4. 模型 / 仓库层

- `ReservationTask` 新增 `createdAt` / `attempts` / `lastAttemptAt`，JSON 双向序列化。
  老数据用 `optLong`/`optInt` 回落 0 —— 否则会算出「已等待 56 年」这种由 1970 纪元来的鬼时间。
- 新增 `TaskStatus.isActive`（与 `isTerminal` 互为反面，但语义分开更好读）。
- `SeatTaskRepository.markAttempt(id)`：**终态不累加**（与 `updateStatus`/`cancel` 一致的防护，
  否则已结束的卡片上数字一直跳，误导用户以为还在跑）。
- `SeatTaskRepository.clearFinished(): Int`：过滤终态，返回清除条数。
- `SeatTaskRepository.remove(id)`：**刻意不做「只能删终态」的限制** —— 用户就是想扔掉一个
  在跑的任务时也该生效，服务的收集器会发现它消失而终止对应协程。
- `SeatMonitorService` 在 `confirmSeat` **之前**调 `markAttempt` —— 只要真发出了预约请求
  就算一次，无论成败。

### 5. UI 细节

- **下拉刷新**：本项目 material3 是 `1.2.0-rc01`，**没有 `PullToRefreshBox`**（要 1.3.0+），
  只能用旧的 `PullToRefreshContainer` + `rememberPullToRefreshState` + `nestedScroll` 组合。
- **单一 `nowTick` 每秒 tick** 驱动所有相对时间（已等待 / 最近尝试 / 签到倒计时 / 更新时间）——
  各处各自 `LaunchedEffect` 会有多份定时器不同步。
- **签到倒计时**：未超时用 `tertiaryContainer`、超时用 `errorContainer`。
  只写「请在 10:55 前刷卡」用户还得自己换算，紧迫感差很多；**恰好到点即判超时**，
  不能显示「还剩 0 分钟」（两者对用户的行动指引完全不同）。
- 「更新时间」显示在区块标题下 —— 静止的列表既可能「确实没变」也可能「刷新早失败了」，
  用户无从区分。
- 取消确认框改「再想想 / 确认取消」并说明不影响已预约的座位。
- 未登录页文案改「登录后即可管理座位预约」/「开通座位系统权限」。

### 6. 单测 89 → **123 条全过**（新增 34）

- `TaskListLogicTest`（新文件，24 条）：排序不变式与边界（`createdAt=0` 沉底、
  时钟回拨不产生负数、同刻创建稳定序）、`livenessText` 四种组合、倒计时到点即超时。
- `SeatTaskRepositoryTest` +8：`markAttempt` 累加 / 终态忽略 / 未知 id 无操作；
  `clearFinished` 只删终态 / 空列表返回 0；`remove` 不分状态。
- `TaskSerializationTest` +2：存活字段往返、老 JSON 无字段回落 0。

### 7. 验证

- 模拟器实测截图确认：分区结构、「已结束 N」折叠 + 一键清除、
  任务卡的时间段 / 座位 / 模式标签 / 状态徽章 / 删除按钮均正确渲染。
- 登录门禁临时绕过代码**已全部还原**并复测（点「座」标签重新落到登录页）。
- 版本号 1.5.2 → **1.5.3**（versionCode 9 → 10）。
- ⚠️ 未在真机复验：进行中任务卡的存活信息行（用单测覆盖替代 —— 登录会触发学校 SSO 风控冷却）。

## 2026-09-20 座位图第三轮：初始缩放定为 **1.8 倍**（用户实测后选定）

上一节把底图改回「整图可见」（`scale = 1`）后，用户复看截图仍认为「有点奇怪」——
根因是 16:9 横图在竖屏按宽度贴合时**图高只占可视区约 1/3**（模拟器 1080×2400 实测
底图仅 609px 高，可视区约 1850px），图上下各留白近 620px，像一张小图漂在灰底上。

### 三轮演进（全部依用户实测反馈，勿擅自回退）
| 轮次 | 方案 | 结果 |
|---|---|---|
| 一 | `scale=1` 按宽度贴合（初版） | 图只占屏高 1/3，上下大留白，用户：「布局比较奇怪」 |
| 二 | 纵向铺满（约 3 倍） | 留白没了，但横向只剩房间约 1/3 宽，认路要一直拖，**用户否决** |
| 三 | 回到整图可见 | 图太小仍觉得奇怪，**用户仍不满意** |
| **四（定稿）** | **折中 1.8 倍** | 图占屏高约 60%，纵向留白降到 75px，横向各裁图宽 11%，**用户选定** |

### 定稿参数（`INITIAL_SCALE = 1.8f`）
- 模拟器 1080×1244 视口实测：图 **1944×1093.5**，`offsetX = -432`、`offsetY = 75.25`
- 纵向留白 318px → **75px**；横向裁切：左右各 432px（= 图宽 11%、视口宽 40%，**两个分母别混**）
- **双向居中**（`offsetX/offsetY` 均取 `(视口 - 图) / 2`）：图比视口大时该值为负，
  即把图往左上推 —— 左右裁切量相等才不会显得「偏向一边」
- 房间左右两侧本来就是纯色墙，裁掉 11% 不影响主体座位区，认路与看细节兼顾

### 单测同步更新（仍 78 条全过）
- `SeatMapViewportTest` 的 3 条初始视口用例从「整图可见」改写为「折中缩放」：
  锁 `scale == 1.8`、双向居中（左右裁切量必须相等）、纵向留白 < 318px、横向裁切 < 图宽 25%
- ⚠️ 首版断言写错（把「占视口宽的 40%」当成「< 15%」），实际分子分母混淆，
  已修正为「≤ 图宽 25%」并在注释里点明两个分母的区别

## 2026-09-20 座位图体验二轮调整（依用户实测反馈）

上一轮的「纵向铺满、零留白」被用户否决（「有点奇怪」），本轮改回整图可见，并继续压布局。

### 1. 底图初始显示改为**整图完整可见**
- `scale = 1`（按宽度贴合，底图 16:9），水平贴左、垂直居中
- 上一版为「纵向铺满」，把图放大到横向只能看到房间约 79%，认路要一直左右拖，
  且一进门就是放大态、失去整体空间感。整图可见更适合「先看全貌、再放大找座位」的动线
- 代价：竖屏下上下各留白约 318px（`offsetY = (viewH - imageH) / 2`），
  这是 16:9 横图在竖屏的必然结果，不是布局 bug
- ⚠️ **本轮结论已被用户再次否决**，最终定为 1.8 倍折中，见上一节

### 2. 顶栏蓝边进一步变窄：48dp → **40dp**
- 48dp 是 Material 的可点击区下限，但那条栏里只有返回键需要它
- 改为整条 40dp，`IconButton` 自己撑到 40dp（≥36dp 可点下限），标题字号 `titleMedium → titleSmall`
- 同时把顶栏容器改用 `Modifier.size(TOP_BAR_HEIGHT)` 收窄返回键，视觉上蓝边明显变细

### 3. 底部「任务列表」栏贴底 —— 找到真正的空隙来源
- 现象：底部操作条看着仍浮在半空、下面有空隙
- 根因（**不是** 圆角问题，虽然圆角也在加重观感）：本模块嵌在 `IndexScreen` 的
  `Scaffold` 内，而 App 全局底栏（80dp）是用 `Modifier.padding(bottom=)` 给 `NavHost`
  让位的，**并没有消费 window insets** —— 于是内层 `Scaffold` 拿到的 `contentWindowInsets`
  仍带着导航栏高度，`paddingValues` 底部凭空多出约 48dp
- 修正：详情页（座位图）显式传 `contentWindowInsets = WindowInsets(0)`，
  由内层自己（`TopAppBar` / `statusBarsPadding` / `bottomBar`）负责系统栏
- 同时把底部两条工具栏的 `MaterialTheme.shapes.large` 圆角改为 `RectangleShape` 直角，
  并在内容行加 `navigationBarsPadding()`：贴底工具栏用圆角会在两下角露出背景，显得「浮着」

### 4. 「立即预约」在未选座位时点击给出轻提示
- 此前 `enabled = selectedSeat != null`，灰态点击**毫无反馈**，容易被当成卡住
- 改为按钮保持可点，点下去弹 `Snackbar`「请先在图上点选一个座位」（无需确认的轻提示）
- 仍保留 `isBusy` 期间的禁用，避免重复提交

### 5. 视口几何抽为纯函数 + 14 条单测（**补上此前无法验证的缩放逻辑**）
- `initialViewport()` / `applyTransform()` / `ViewportState` 从 `SeatMapCanvas` 内联代码抽出
- 动机：双指缩放**无法用 adb 模拟**（`input swipe` 只能单指），上一轮「以双指中心缩放」
  实际从未被真正测过，只靠读代码保证
- 覆盖的不变式：锚点不漂移（含非中心锚点）、MIN/MAX 截断时按实际倍率重算、
  `zoom == 1` 退化为纯平移、图小于视口时自动居中、放大后拖动不越界
- ⚠️ 测试中确认了一个**刻意的取舍**：边界钳制优先级**高于**锚点不变式 ——
  锚点解算若会把图拉出边界，则宁可让锚点漂移，否则会露出画布底色。已在测试里显式记录
- 参数刻意用散开的 Float 而非 `Offset`，避免纯 JVM 单测把 compose-ui 拖进 test classpath

单测 **78 条全过**（原 64 + 新增 14）。

## 2026-09-20 座位图四项体验修复

### 1. 底图改为**五张状态图叠加**（此前只画了 free 一张）
服务端 `/api/seat/map` 返回五张房间图（free/book/close/leave/use），每张图上**只有对应
状态的座位是亮的**。早期只渲染了 `free`，导致被预约/在用/暂停的座位在图上完全不存在，
看起来「整个房间全是空闲」，丢失了最关键的状态信息。

改为按 `close → free → book → use → leave` 顺序叠加。实测（拉取五张原图逐像素比对）：
**五张图的底部图例区完全一致**（同坐标同像素值），叠加不产生重影，因此**直接使用服务端
自带的图例**，不再自绘（原先自绘的图例位置与随缩放漂移的服务端图例对不上，屏幕上出现两条）。

### 2. 双指缩放改为**以双指中心为锚点**
原先 `detectTransformGestures` 只累加 `pan` 与 `scale`，而变换原点是 `TransformOrigin(0,0)`，
于是无论手指捏在哪里，视觉上都在**以左上角为中心**缩放。

修正：接收 `centroid` 并按锚点不变式重算平移 ——
记 `p = (centroid - offset) / scale`（变换前的视图坐标），则
`新 offset = centroid - p × newScale = centroid - (centroid - offset) × realZoom`。
单指平移时 `realZoom == 1`，退化为纯拖动。另外补上缩放后的平移钳制，避免拖出图外露白。

### 3. 座位图页布局压缩
| 位置 | 改动 |
|---|---|
| 顶栏 | `TopAppBar`（64dp 起）→ 自绘 48dp 栏，省出约 16~20dp |
| 信息条 | 横向滚动（要左右滑动才看得到「空闲」「换区域」）→ `FlowRow` 自动换行，一屏全显 |
| 底部操作条 | 从 `Column` 末尾移到 Scaffold 的 `bottomBar` 槽位，紧贴底边 |
| 座位图区 | `weight(1f)` 撑满可视区；底图初始纵向铺满、零留白（⚠️ 已被用户否决，见上一节改为整图可见） |

### 4. 清理
- 删除 `SeatColors.legendBackdrop`（自绘图例方案的遗留，已不需要）
- `SeatMapCanvas` 的 `imageUrl` 参数改为 `images: SeatMapImages?`（叠图需要整组图）

## 2026-09-19 短信二次验证 + 模拟器端到端复验（M5 收尾）

### 1. 支持学校短信二次验证（复用官方 BIT-Login 库）
- 学校在风控触发时，CAS 返回 **200 + 二次验证页**而不是 302。此前手写的 CAS 流程
  不具备风控与二次验证能力，密码正确也只能报错退出。
- 依赖 `com.github.BIT101-dev.BIT-Login:bit-login:v4.0.2`（App 本就通过 `:api` 依赖，**不增体积**），
  `SeatSession` 删掉手写的「取 salt → AES 加密 → POST 表单 → 手跟 302」，
  改用 `SsoLogin(smsCodeCallback).login(callbackUrl = seatlib CAS)`。
- 新增 `api/SeatSmsChallenge.kt`：用 `CompletableDeferred` 挂起登录流程，`StateFlow` 让界面接管。
- `CasLoginScreen` 加验证码弹窗；**按返回键/点外部不再静默取消登录**。

### 2. 日期默认值按服务端对齐
初始选中日期是设备本地今天，而可约日期来自服务端（北京时间）。设备时区不一致时
（模拟器 UTC 就会落到「昨天」）会用服务端已不接受的日期查座位树 —— 表现为下拉里
只剩个别常年开放的区域。服务端日期就绪后自动拉回第一个可约日期。

### 3. 修复「去座位图选座再返回，表单被整体重置」
`remember` 状态在离开组合时丢失，表现为预约模式回到「单次预约」、校区/楼层/区域全清，
刚选的座位无处安放、任务建不出来。改为 `rememberSaveable`（只存 id，回来用树查回节点），
并让加载副作用**只在日期真正变化时**才清空（否则重新进入页面也会清）。

### 4. 修复导航偶发失效（Compose 经典坑）
`SeatScreen` 在**组合期间**直接调用 `navController.navigate(...)`，跳转时灵时不灵 ——
这就是多次「选座按钮点了没反应」的根因。改为放进 `LaunchedEffect`。

### 5. 监控/优先任务失败时显示服务端原因
只写「预约失败，继续尝试」时用户无从判断：可能是尚未到 6:00 开抢时间、
也可能被别人抢走或当日取消次数用尽，处理方式完全不同。

### 6. 修复「我的预约」时段显示错乱
`endTime.take(11).takeLast(5)` 这种固定切片，对真实格式 `2026-09-19 22:30:00`
会切成 `9-19 `。改为按分隔符解析。

### 7. 模拟器端到端复验（真机/模拟器）
凭据登录（含短信二次验证）→ 座位图 → **真实预约 051 成功** → 「我的预约」显示
签到时限（`今日预约 · 请在 11:55 前刷卡签到`）与取消次数（`今天还剩 2 次`）→
确认取消成功（服务端复核为 0 条）。

## 2026-09-19 UI 优化（M5）：底栏压缩 / 真实座位底图 / 图上选座 / 我的预约

### 1. 底栏压缩（用户反馈：底部两个选项占位太大）
- `SeatScreen` 原先自造了一条 `NavigationBar`（预约/列表两个带图标 item ≈ 80dp），
  叠在 App 全局底栏之上，手机上形成「内容 / 操作条 / 模块栏 / 全局栏」四层
- 改为 **32dp 胶囊分段控件**（纯文字，位于内容顶部），座位图等详情页自动隐藏

### 2. 座位图重做：真实房间底图 + 坐标热区（用户反馈：方格太大、显示不全）
- 接入 `POST /api/seat/map {"id": 区域id}`：返回 `free/book/close/leave/use` 五张
  1920×1080 状态底图（房间轮廓 + 座位编号 + 插座 + 图例 + 标题）
- 用座位自带的 `point_x/point_y/width/height`（**百分比**，55/55 全有值）把座位定位成热区
- 新增 `SeatMapCanvas`：初始自动缩放到座位密集区、双指缩放拖动、
  点按用「坐标换算 + 半径容差」命中（座位视觉方块仅 10dp 量级，做可点元素既点不中又抢事件）
- 无底图的区域回落**密集方格**（56dp）并按**座位号数值**排序（原先是接口乱序）
- 状态映射扩到服务端全部取值（1/2,10,11/6,8,9/7/3,4,5），并区分「他人预约」与「我已预约」
  （服务端不区分归属，用 `/api/index/subscribe` 交叉标记）

### 3. 监控/优先改为图上点选座位（用户反馈：手输座位号不方便）
- 监控：图上**单选**，被占用的座位也能点（那正是要等的目标）
- 优先：图上**多选**并按点选顺序作为优先级；不选 = 不限座位
- 表单不再有座位号输入框，改为「选择目标座位/选择偏好座位」+ 已选展示
- 任务模型新增 `preferredSeats`（有序），服务端轮询时按优先级依次抢

### 4. 「我的预约」+ 签到时限 + 取消失数（规则驱动）
- 列表页新增「我的预约」（`/api/index/subscribe`）：座位、区域、时段，
  以及**签到时限提醒**（当日预约 60 分钟内、次日预约 9:00 前；未签到记违约，累计 5 次停用 7 天）
- 取消改为按**预约记录 id**，取消前提示「每天最多 2 次、今天还剩 N 次」

### 5. 认路与选区域
- 选中楼层后显示官方**楼层平面图**（可放大），各阅览室以绿块标注
- 区域从下拉改为**芯片**：一屏看全，且避开 Compose 下拉在手机上「弹窗渲染在字段上方」的坑

### 6. 修复：失效 token 会打挂「无需认证」的接口
- 实测：带**失效/伪造 token** 请求 `/api/Seat/tree` → **HTTP 500 + 空 body**；
  不带 token 却是 200 + 正常数据。此时拿不到 10001 业务码，原有 `handleApiError` 判定不出来，
  表现为「新建预约」页只报加载失败、UI 仍认为已登录、不给重新授权入口
- 现在加载失败会**主动探一次会话**，失效即清 token，UI 回到授权门禁
  （`SeatViewModel.verifySessionOrLogout`）

### 7. 已知外部限制：学校 CAS 可能要求二次验证
- 短时间多次登录后，CAS 的密码直登会返回 **200 + 二次验证页面**（短信/邮件/扫码）。
  已识别该页面并给出明确提示「学校要求二次验证，当前版本不支持，请稍后再试」，
  不再笼统报「认证失败」

### 验证
- 单测 **64 条全过**（新增座位状态分组、底图选择、坐标解析、我的预约与签到时限）
- 模拟器实测：新表单布局、真实底图渲染、**座位点选命中**（按钮显示「预约 028」）、
  失效 token 自愈回门禁、二次验证提示文案

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
