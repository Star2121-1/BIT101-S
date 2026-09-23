# 下一轮改动计划（2026-09-23 用户确认后梳理）

> 承接 `2026-09-23.md` 的调研结论。本文件是**待实施的方案**，逐项标了要动的文件、风险与验证方式。
> 用户已拍板的方向记在「决策汇总」；仍待确认的记在文末。

---

## 一、决策汇总

| # | 项 | 决定 |
|---|---|---|
| 1 | 组件页序 | 迁移为 **课程 / DDL / 动态 / 座位**（页号改存 `kind.name`，旧索引迁移一次） |
| 2 | 组件点条目 | 跳进 App：DDL 行 → App 的 **DDL** tab；动态行 → App 的 **动态** tab；**删掉「打开 DDL」动作键**；组件内**不再勾选** DDL |
| 3 | 精确定位 | **分两步**：先「落到对的 tab」；跳转载体里预留 `focusKey`，第二步再做「滚到那一条 + 高亮」 |
| 4 | App tab 序 | 课表 / **DDL / 动态** / 空教室 |
| 5 | 动态页设置 | 仿 DDL 页：右下加设置 FAB + 新设置页（含乐学、延河两个来源 + 展示项） |
| 6 | 学士帽按钮 | 改**弹窗二选一**（乐学主页 / 延河课堂主页）；乐学地址优先取 SDK 活动地址，回退静态配置 |
| 7 | 乐学动态 | 先做 **C**（动态页加「乐学」WebView 入口）；**B**（抓包调研新接口）作为后续独立任务；**A 不做** |

---

## 二、工作项（按实施顺序）

### W1 学士帽按钮 → 弹窗二选一（最小、独立）
- `features/schedule/.../ddl/DDLSchedule.kt:203-215`：`onClick` 从直接 `openWebPage(EclassDdlLogic.LOGIN_URL)`
  改为弹 `AlertDialog`，两项：「延河课堂主页」→ `EclassDdlLogic.LOGIN_URL`；「乐学主页」→ 乐学地址
- 乐学地址取值：优先 `Config.Urls.active["lexue"]`（SDK 的活动地址，校外 webvpn 也对），
  取不到回退 `ApiUrlOption.lexueUrl`（现在是**死配置、全仓零引用**）
- ⚠️ 已知限制：乐学**没有会话检查**，跳过去可能是登录页 —— 弹窗文案里如实说明
- 验证：模拟器点按钮 → 弹窗出现、两个选项分别打开对应页面（`dumpsys window` 看 WebView 的 URL）

### W2 动态页设置（新设置页 + 设置项）
- 仿 DDL 页：`EclassActivityScreen.kt` 右下加一个 42dp 设置 FAB → `NavDest.Setting("activity")`
- 新增设置项最小 4 文件：`SettingDataStore`（key）→ `setting/base/*Settings.kt`（字段）→
  `Default*Settings`（绑定）→ `setting/page/*Page.kt` + `viewmodel/*VM.kt`
- 提议的设置项（**待确认，见文末**）：
  1. **延河课堂**：显示会话状态 + 「打开登录页」按钮（复用 `EclassRepo.isSessionAlive()`）
  2. **乐学**：显示「可打开主页」+ 「打开乐学主页」按钮（无会话检查接口，只能给入口）
  3. 展示范围：只看作业 / 全部动态（默认全部）
  4. 条数上限：30 / 60 / 100（默认 60，对齐 `EclassRepo.DEFAULT_ACTIVITY_LIMIT`）
  5. 是否显示已过期条目（默认显示）
  6. 「立即刷新」（清掉动态缓存重拉）
- 验证：模拟器进设置页 → 改一项 → 返回动态页看生效；单测覆盖「设置→展示」的纯逻辑

### W3 页序调整（组件迁移 + App 列表顺序）
- **App**：`ScheduleScreen.kt:21-29` 列表顺序改为 课表/DDL/动态/空教室（`TabPager` 的选中态是
  `rememberPagerState`、**不持久化** → 重排零风险）
- **组件**：`PageKind` = COURSE/DDL/SEAT/ACTIVITY → 目标顺序 COURSE/DDL/ACTIVITY/SEAT
  - ⚠️ 页号**按索引**存在 `WidgetPageStore`，重排后老用户的 index 2/3 语义互换（静默错页）
  - 迁移做法：`WidgetPageStore` 改存 `kind.name`；读到**旧 int** 时按**旧枚举顺序**映射成 kind，
    再转成新索引写回（一次性、幂等）。旧枚举顺序表要写成常量并在注释里锁死
  - 涉及：`WidgetPageStore`（读写+迁移）、`WidgetViews`（`build`/`renderTabs`/`pagePendingIntent`）、
    `WidgetListService`、`BIT101WidgetProvider`、`WidgetLogic.build`、`WidgetRepositoryHolder`、相关单测
- 验证：单测锁「旧索引 2（原座位）→ 迁移后仍是座位页」；模拟器实测老页号不跳页

### W4 组件点条目 → 跳进 App 对应 tab
- **载体**：`GotoRequest` 从「一个 route 字符串」扩成 `GotoTarget(route, tab?, focusKey?)`；
  `MainActivity.EXTRA_GOTO` 保持不变，新增 `EXTRA_TAB` / `EXTRA_FOCUS`（组件侧带上）
- **App 侧接参**：`TabPager` 增加 `initialPage`；`ScheduleScreen` 观察 `GotoRequest`，
  用 `LaunchedEffect` 在**已组合**的情况下 `animateScrollToPage(tab)`（`onNewIntent` 路径必需），
  消费后清掉请求（沿用现有 `consume()` 约定）
- **组件侧**：
  - DDL 行：补 `openRoute`（带 DDL tab），并把该页的点击模板从 `ddlToggleTemplate`（广播勾选）
    换回 `listTapTemplate`（打开 App）——**组件不再提供勾选**
  - 动态行：`openRoute` 从 `"schedule"` 改成「schedule + 动态 tab」
  - 删掉 `WidgetLogic.actionOf` 里的 DDL 分支（「打开 DDL」动作键）
  - ⚠️ 一个 collection 只能挂一个点击模板：DDL 页改用 `listTapTemplate` 后，
    `ddlToggleTemplate` 与 `ACTION_ITEM_TAP` 就没有调用方了（是否保留见文末）
- 验证：模拟器点 DDL 行 / 动态行 → App 停在对应 tab；单测锁 `openRoute` 取值与动作键为 null

### W5 精确定位到那一条（第二步）
- 组件侧：`WidgetLine` 加 `focusKey`（DDL = `uid`，动态 = `eclass:{id}`），
  经 `setOnClickFillInIntent` 的 extras 传下去（机制现成 —— DDL 勾选本来就是这么传的）
- App 侧：DDL / 动态列表各自**提升 `LazyListState`**，按 focusKey 找到索引后 `scrollToItem`
  - ⚠️ DDL 列表是**分区**的（「未完成 · n」/「已完成 · n」各一个 header 行 + 条目），
    索引要把 header 算进去（这是最容易错的地方，要有单测）
  - （可选）顺手做「高亮 2 秒」或「自动打开详情弹窗」——后者要把
    `DDLScheduleDetailDialog` 的显隐状态从页面内 `remember` 提升出来，是额外一层
- 验证：模拟器从组件点第 5 条 → App 滚到第 5 条；分区边界（第一条第 ⼀ 个已完成项）也要测

### W6 乐学入口（C）+ 后续调研（B）
- C：动态页顶部加「乐学」入口（图标按钮 / 一行提示），点了 `openWebPage(乐学主页)`；
  成本极低、不碰登录
- B（独立任务，不在本轮）：调研乐学（Moodle）能不能拿到公告/课程动态
  - 现状：我方对乐学**只有 ICS 日历**（`SchoolLexueService.getCalendarUrl/getCalendar`），
    无 webservice/announcement 调用
  - 需要：抓包确认数据出口 + 用户配合登录一次（SDK 走全新 SSO，有二次验证/风控）
  - 若走不通 → 就停在 C

---

## 三、风险与纪律（复用已有教训）

- 组件的点击模板要接 fill-in ⇒ **必须 `FLAG_MUTABLE`**，改 mutability 时 `data` 一起换
- 页序迁移**必须幂等**：重复读不会把页号越迁越偏（迁移后写入 kind 名，再读就不再走迁移分支）
- 组件重绘**不许顺带打网络**（动态数据沿用 `EclassActivityCache`）
- 同一文件多次 Edit 必须串行；改完 grep 复核
- 每项都在模拟器实测（灌数据 + DB 断言），release 后装真机

---

## 四、仍待确认

1. **动态页设置项**最终清单（上面 1–6 项要不要增减？「只看作业」这类开关默认值？）
2. **W5 精确定位**：现在就做，还是先做 W1–W4 上线、看实际手感再定？
3. **`ACTION_ITEM_TAP` / `ddlToggleTemplate`**：组件不再勾选后，这条广播要不要连着删掉？
   （我倾向**保留代码但不再使用**会留死代码，**删掉**更干净；若要保留「长按勾选」之类
   的替代交互，则留着）
