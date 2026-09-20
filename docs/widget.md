# 桌面小组件（features/widget）

> 对应分支：`feature/widget`
> 实现状态：**已实现并编译通过**（2026-09-20），待模拟器/真机实测
> 属于上游设想第 3/5 项「桌面小组件显示课程和日程」

---

## 一、它显示什么

三页，**用右上角 `‹ ›` 翻页**，顶部 `● ○ ○` 是当前页码：

| 页 | 内容 | 空态文案 |
|---|------|---------|
| 课程 | 今天的课，按节次升序。主行 = 最近一节，次行 = 后两节 | 今日无课 |
| DDL | 未完成的 DDL，按到期升序。过期未完成项**仍然显示**并标红 | 暂无待办 |
| 座位 | 进行中的抢座任务 → 补已预约的记录 | 暂无预约 |

每页最多 **3 行**（1 主 + 2 次），这是 4×2 组件高度下还能看清的上限。

---

## 二、为什么不能「左右滑动」

用户最初的想法是横向滑动切页。技术上行不通，有两个硬约束：

1. **Glance 没有 Pager / HorizontalPager** —— 它是 RemoteViews 的封装，
   只能做静态布局 + 点击回调，不存在滚动容器。
2. **横滑手势会被桌面吃掉** —— 就算能做到，桌面本身也用横滑翻页。

所以用「翻页按钮 + 指示点」实现对等体验。

---

## 三、数据流

```
App 进程
┌──────────────────────────────────────────────────────┐
│ App.onCreate                                         │
│   WidgetAppStartup.init()                            │
│     ├─ EntryPoint 取 WidgetRepository → Holder.install│
│     └─ WidgetRefreshWorker.schedule()   （30min 兜底）│
│   SeatAppStartup.init()                              │
│     └─ SeatWidgetPublisher.start(scope)              │
│          └─ repository.tasks.collect { ... }          │
│               └─ SeatWidgetSnapshot.write()  ──┐      │
└────────────────────────────────────────────────┼──────┘
                                                 │ SharedPreferences
桌面组件（同一进程，由系统拉起）                    ▼
┌──────────────────────────────────────────────────────┐
│ BIT101Widget.provideGlance(context, id)              │
│   ├─ getAppWidgetState<Preferences> 读当前页号        │
│   ├─ Repository.load()                               │
│   │    ├─ CoursesRepo  ─┐                            │
│   │    ├─ DDLScheduleRepo├─ Room（只读本地，不联网）  │
│   │    └─ SeatWidgetSnapshot ────────────────────────│
│   └─ provideContent { WidgetContent(data, page) }    │
└──────────────────────────────────────────────────────┘
```

### 依赖方向是单向的

`features/widget` **不依赖** `features:seat`；反而是 seat 通过
`SeatWidgetSnapshot`（普通 SharedPreferences）主动推数据给组件。

这样：
- 组件进程拉起时不会连带初始化座位模块的重依赖（OkHttp / Hilt 图）
- 座页显示的行怎么解释（什么叫「尝试 3 次」）只有座位模块知道，
  组装逻辑留在座位侧的 `SeatWidgetPublisher` 是合理的

### 三路刷新（都不完美，所以重叠使用）

| 途径 | 触发 | 覆盖场景 | 缺陷 |
|------|------|---------|------|
| `WidgetUpdater.refresh()` | 业务侧显式调用 | 刚同步完课表 / 座位状态刚变 | 要求 App 在前台跑过 |
| `WidgetRefreshWorker` | WorkManager 30min | 跨零点换天 | 最小 15min，Doze 下可能几小时 |
| `updatePeriodMillis` | 设为 **0**，不用 | — | 最小 30min 且不精确，不如 WorkManager 可控 |

---

## 四、Glance API 的坑（改动前必读）

这几条每一条都实际编译失败过，不是推测。

### 4.1 `getAppWidgetState` 有两组同名重载

Glance 1.1.0 同时提供：

```kotlin
// ① 顶层函数 —— 用这个
suspend fun <T> getAppWidgetState(
    context: Context, definition: GlanceStateDefinition<T>, glanceId: GlanceId
): T

// ② GlanceAppWidget 的扩展 —— 别用
suspend fun <T> GlanceAppWidget.getAppWidgetState(context: Context, glanceId: GlanceId): T
```

②少了 definition 参数，**返回类型是完全未约束的泛型 `T`**。
如果误用 ②，编译器只会报：

```
Cannot infer type for type parameter 'T'. Specify it explicitly.
```

看不出根因（会让人以为是自己漏写了泛型）。正确写法：

```kotlin
val prefs: Preferences = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
```

写 state 同理，用**顶层三参数**重载而不是带 definition 的那组 ——
后者要求整读整写返回新对象，容易覆盖别人的 key：

```kotlin
updateAppWidgetState(context, glanceId) { prefs ->
    prefs[PAGE_KEY] = newPage
}
```

### 4.2 `fontSize` 用的是 Compose 的 `sp`，不是 Glance 的

`androidx.glance.text.TextStyle.fontSize` 的类型是
`androidx.compose.ui.unit.TextUnit`，所以要 `import androidx.compose.ui.unit.sp`。
**没有** `androidx.glance.unit.sp` 这个东西。

### 4.3 Glance 里没有 Icon

不能用 `androidx.compose.material3.Icon`，也不能画矢量。
指示点用 `● ○` 字符、翻页用 `‹ ›` 字符，省一份矢量资源也免去
Glance 端的资源加载限制。

### 4.4 `GlanceAppWidget` 由系统实例化，拿不到 Hilt 注入

`AppWidgetProvider` 不走 `@AndroidEntryPoint`。
标准解法是 `@EntryPoint` + `EntryPointAccessors.fromApplication`
（见 `WidgetAppStartup`）。

⚠️ 绝对不要在组件渲染期间访问尚未初始化的东西 —— `provideGlance` 里
任何异常都会让系统显示一片空白，**没有 crash 日志**。本项目所有
组件侧读取都套了 `runCatching`，仓库未就绪时返回「加载中…」数据。

### 4.5 Glance 与 App 同进程

不要写成「不同进程不同 classloader」。Glance 会带入自己版本的
`compose.runtime`，升级 Glance 前必须确认它要求的 Compose 版本，
否则会被动升/降级主工程的 Compose。

---

## 五、已抽取的可测逻辑

Glance 的 UI 无法在 JVM 单测里跑，所以把聚合逻辑全抽进 `WidgetLogic`
（纯函数，`now` / `today` 显式传入）。单测 `WidgetLogicTest` **25 条**。

重点覆盖的行为：

- `weeksContains` —— `weeks` 字段形如 `[1][2][3]`，必须按完整标记匹配。
  用 `contains(week.toString())` 会让第 1 周匹配到 `[11]`、`[21]`
- `weekOf(firstDay, today)` 返回 `-1` 表示未知；此时 **不按周过滤**课程，
  否则一屏全空，用户会误以为没同步
- `remainText` —— 已过期必须输出「已过期」，不能给负数

---

## 六、验证清单

- [x] `:features:widget:compileDebugKotlin` 通过
- [x] `WidgetLogicTest` 25/25 通过
- [x] `:features:seat:compileDebugKotlin` 通过（新增 publisher 后）
- [x] `assembleDebug` 全量构建成功（Hilt 图 / Manifest 合并）
- [x] 模拟器：系统识别 provider（`dumpsys appwidget` 有 `BIT101WidgetReceiver`）
- [x] 模拟器：选择器里显示「BIT101 课程日程 / 4 × 2 / 在桌面查看当日课程、待办 DDL 与座位预约」
- [x] 模拟器：拖到桌面渲染正常（`●○○` 指示点 + 标题 + `‹ ›` + 「今日无课」）
- [x] 模拟器：点 `›` 切页生效（`●○○ 课程 今日无课` → `○●○ DDL 暂无待办`）
- [x] 模拟器：页号跨进程持久（`am force-stop` 后仍是 DDL 页）
- [x] 模拟器：`WidgetRefreshWorker` 执行结果 SUCCESS
- [x] 模拟器：全程无崩溃、无 ANR
- [ ] 真机：厂商 ROM 对 WorkManager 的限流（已知风险）
- [ ] 真机：跨零点自动换天
- [ ] 真机：App 内同步课表后组件内容更新

### ⚠️ 手工测试的坑：`am force-stop` 会让组件点击失灵

用 adb 验证时如果执行过 `adb shell am force-stop <包名>`，**该组件上所有点击（含
翻页按钮）会失效**，且 logcat 里连广播记录都没有 —— 因为 force-stop 使组件
缓存的 RemoteViews 里那批 `PendingIntent` 作废，而 Launcher 仍在用缓存副本。

这不是代码缺陷（重新绑定组件、或等一次真实重绘即恢复；真实用户极少
force-stop）。**测组件交互时不要先 force-stop**；若必须，之后要重加组件。

另注：`adb shell uiautomator dump` 读到的组件层级**会滞后于实际渲染**，
翻页这类验证请以 `screencap` 截图为准，别只看 dump。

### ⚠️ 本机 git 环境的坑（提交前必读）

- **带斜杠的分支名无法用 `git branch` / `git update-ref` 创建**（返回 rc=0 但
  不写文件），必须先 `mkdir -p .git/refs/heads/feature` 再手工
  `echo <sha> > .git/refs/heads/feature/<name>`
- **`git commit` 可能写入提交对象却不更新 ref**：表现为 `git log` 报
  「your current branch has no commits yet」，但 `git fsck` 能看到
  `dangling commit <sha>` —— 用该 sha 手工写回 ref 即可
- 若出现「invalid sha1 pointer / bad object」，说明对象库被清空：
  `git fetch origin --tags --force` 可从远端恢复（远端有则一定能恢复）
- 失败的 `git checkout` 可能**清空整个工作区**，`git reset --hard HEAD` 可复原
  （前提是改动都已提交）

