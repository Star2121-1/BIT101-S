# 桌面小组件（features/widget）

> 对应分支：`feature/widget`
> 状态：**已合入 master，v1.6.1**（2026-09-21 界面重做）；模拟器实测通过，**真机待验**
> 属于上游设想第 3/5 项「桌面小组件显示课程和日程」

---

## 启用方法（用户视角）

1. **装好 v1.6.1 及以上的包**。组件会自己初始化数据源，但**本地库为空时只能显示空态**，
   所以仍需先在 App 里同步过课表 / DDL。
2. 回到桌面，**在空白处长按**（或双指捏合，视 ROM）→ 选 **「小组件 / Widgets」**。
3. 在列表里找到 **BIT101**（组件名显示「BIT101 课程日程」，**4×3** 大小，
   描述是「在桌面查看当日课程、待办 DDL 与座位预约」）。
4. **长按组件预览、拖到桌面松手**。少数 ROM（部分 MIUI / ColorOS 版本）
   支持直接点一下自动添加，点不动就改用长按拖动。
   📱 ZTE MiFavor（努比亚）实测路径：长按桌面 → 面板标题 **「My 组件」** →
   点搜索框输入 `BIT101` → 长按预览拖出。
5. **点顶部页签直接切页**（课程 / DDL / 座位）；右侧 **「刷新」** 键立即重读本地数据。

### 交互设计说明

| 元素 | 尺寸 | 说明 |
|------|------|------|
| 页签 ×3 | 各约 (宽-48dp)/3 × 36dp | 点哪页去哪页，不需要逐页翻 |
| 刷新 | 48dp × 36dp | 立即重读本地库并重绘 |

⚠️ **旧版（≤v1.6.0）是右上角 `‹ ›` 小箭头 + `● ○ ○` 指示点，已废弃。**
那个箭头触摸区只有约 20×20dp（远小于 48dp 的最小触摸目标），真机上点不中 ——
用户反馈的「翻到后两页就回不去了」就是它导致的。详见下方「为什么改成页签」。

### 各页数据从哪来

| 页 | 数据来源 | 需要先做什么 |
|---|---------|------------|
| 课程 | App 本地课表库 | 在 App 里同步过课表 |
| DDL | App 本地 DDL 库 | 在 App 里同步过 DDL |
| 座位 | 座位模块推送的快照 | 登录过座位系统、且有进行中/已预约的任务 |

组件**不联网**，只显示 App 已经拉到本地的数据。因此「刷新」键**只重读本地库**，
不会联网拉取新数据 —— 真正的同步由 App 负责。

### 什么时候会刷新

| 时机 | 说明 |
|------|------|
| 点「刷新」键 | 立即，用户手动触发 |
| App 内数据变化 | 快，但要求 App 在前台跑过 |
| 约每 30 分钟 | WorkManager 周期任务，主要保证跨零点换天 |
| 系统重启 / 组件重绑 | 自动重绘 |

⚠️ 部分国产 ROM（华为、小米、OPPO、vivo 等）会限制后台与自启动，
WorkManager 可能被延后几小时。若组件长时间不更新，把 BIT101 加进
**电池优化白名单 / 允许后台运行**即可。


---

## ⚠️ 平台限制：组件在华为鸿蒙上不可用（结论：无法通过「适配」解决）

**现象**：HarmonyOS 5 / 6 手机上，桌面里搜不到、也加不了 BIT101 组件。

**根因（已查证，非推测）**：

1. HarmonyOS 5（原 NEXT）起，内核换成自研微内核、**移除 AOSP**，
   桌面上那种「卡片」是 **ArkTS 开发的服务卡片（Form）**，
   和 Android 的 `AppWidgetProvider` 是两套完全不同的机制。
2. 鸿蒙 5/6 保留了一个**兼容容器**（卓易通 / 出境易，基于 LXC 容器跑 Android 运行时），
   所以 BIT101 的 APK 能在容器的文件夹里打开使用。
   但**容器是一个隔离的 Android 环境，不是鸿蒙桌面** ——
   容器里的 Android 应用**没有任何机制**把 `AppWidget` 挂到鸿蒙的桌面上。
   容器也没有「AppWidgetHost」，系统侧根本不认这个 provider。
3. HarmonyOS 7 计划**彻底移除**这套兼容容器（只跑 HAP 原生应用）。

**结论**：这不是组件元数据写错了，也不是缺少某个权限或属性 ——
在现有 Android 工程里改任何代码都做不到。**不要在这上面浪费时间。**

### 可行路径（都需要另起一个工程）

| 方案 | 说明 | 代价 |
|------|------|------|
| **鸿蒙原生应用 + 服务卡片** | ArkTS/ArkUI 写 HAP，用 Form 做桌面卡片。数据复用现有 HTTP 接口（BIT101 与 seatlib 的服务端契约都是现成的） | 等于**第二个客户端**：登录（含学校 SSO 二次验证）+ 课表 / DDL / 座位三个数据源都要重写。需要 DevEco Studio + 华为开发者账号，且**本机没有鸿蒙 SDK，无法构建与验证** |
| **元服务（Atomic Service）** | 免安装，适合轻量卡片，但仍需 ArkTS 开发与上架 | 同上，且元服务能力受限 |
| **常驻通知代替卡片** | 在容器里用 Android 通知显示今日课程/DDL，通知能进鸿蒙通知中心 | 容器内通知**不可靠**（社区反馈存在漏通知、延迟），且不是「桌面卡片」的体验 |

**建议**：把「鸿蒙端」当作独立项目立项，而不是本仓库的一个分支 ——
它不是代码适配问题，是产品线问题。真要做，先确认能否拿到 DevEco Studio
构建环境与华为开发者账号。

---

## 一、它显示什么

三页，**点顶部页签直接切换**，右侧有「刷新」键：

| 页 | 内容 | 空态文案 |
|---|------|---------|
| 课程 | 今天的课，按节次升序。主行 = 最近一节，次行 = 后两节 | 今日无课 |
| DDL | 未完成的 DDL，按到期升序。过期未完成项**仍然显示**并标红 | 暂无待办 |
| 座位 | 进行中的抢座任务 → 补已预约的记录 | 暂无预约 |

每页最多 **3 行**（1 主 + 2 次），4×3 组件高度下字号可以放到 16sp 主行 / 14sp 次行。

---

## 二、为什么不能「左右滑动」，以及为什么改成页签

### 横滑做不到（两个硬约束）

1. **RemoteViews 没有 Pager / 滚动容器** —— 组件是跨进程下发的只读快照，
   只能做静态布局 + 点击回调。
2. **横滑手势会被桌面吃掉** —— 就算能做到，桌面本身也用横滑翻页。

### 旧版的 `‹ ›` 箭头是错的（v1.6.0 → v1.6.1 的修复）

v1.6.0 用「右上角 `‹ ›` 字符 + `● ○ ○` 指示点」代替，结果真机反馈
**「翻到后两页就回不去了」**。根因有三层：

1. **触摸目标太小**：一个 16sp 的 `‹` 字符加上 8dp padding，实际触摸区约
   36×20dp，远小于 Android 规范的 48dp 最小值；两个箭头还紧挨着，
   极易点空或点错。
2. **点击必须落在各自独立的 View 上**：PendingIntent 的唯一性只看
   `requestCode` + `Intent.filterEquals`，而 **filterEquals 不比较 extras** ——
   把点击挂在裸 `Text` 上、只靠 extras 区分目标页，很容易被判定成同一个
   PendingIntent 而互相覆盖。
3. 页数变化时还要先算 pageCount 再取模，逻辑链更长。

改成**三页签**后：每页签宽 ≈ (组件宽 − 48dp) / 3、高 36dp，触摸面积是原来的
十几倍；而且**点哪页去哪页**，不存在「必须一页页翻回去」。

每个页签的 `PendingIntent` 用**各自的 `data`** 区分
（`bit101://page/<appWidgetId>/<page>`）—— 见 4.4 的说明。

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
│ BIT101WidgetProvider.onUpdate / onReceive            │
│   ├─ WidgetPageStore.read() 读当前页号                │
│   ├─ WidgetRepositoryHolder.load()                   │
│   │    ├─ 取不到时用 EntryPoint 自取一次（不依赖 App） │
│   │    ├─ CoursesRepo  ─┐                            │
│   │    ├─ DDLScheduleRepo├─ Room（只读本地，不联网）  │
│   │    └─ SeatWidgetSnapshot ────────────────────────│
│   └─ WidgetViews.build() → 同步 updateAppWidget()     │
└──────────────────────────────────────────────────────┘
```

### 依赖方向是单向的

`features/widget` **不依赖** `features:seat`；反而是 seat 通过
`SeatWidgetSnapshot`（普通 SharedPreferences）主动推数据给组件。

这样：
- 组件拉起时不会连带初始化座位模块的重依赖（OkHttp / Hilt 图）
- 座页显示的行怎么解释（什么叫「尝试 3 次」）只有座位模块知道，
  组装逻辑留在座位侧的 `SeatWidgetPublisher` 是合理的

### 刷新途径

| 途径 | 触发 | 覆盖场景 | 缺陷 |
|------|------|---------|------|
| **「刷新」键** | 用户点击 | 想立刻看到最新本地数据 | 只重读本地库，不联网 |
| `WidgetUpdater.refresh()` | 业务侧显式调用 | 刚同步完课表 / 座位状态刚变 | 要求 App 在前台跑过 |
| `WidgetRepository.refresh()` | 同上的仓内入口 | — | — |
| `WidgetRefreshWorker` | WorkManager 30min | 跨零点换天 | 最小 15min，Doze 下可能几小时 |
| `updatePeriodMillis` | 设为 **0**，不用 | — | 最小 30min 且不精确，不如 WorkManager 可控 |


---

## 四、渲染方式：为什么最终不用 Glance

### 4.1 结论

组件用**传统 `AppWidgetProvider` + `RemoteViews`**，不是 Glance。

点击回调里读数据 → 构建 `RemoteViews` → `AppWidgetManager.updateAppWidget()`。
**这是同步 API，调用即生效**，没有中间层。

### 4.2 用 Glance 时踩的坑（教训，别再走一遍）

第一版用 Glance 1.1.0 实现，功能都在，但**点击页签后画面不更新** ——
用户看到的就是「点了没反应、切过去就回不来」。逐层排查的结论：

| 事实 | 证据 |
|------|------|
| 点击回调**执行了** | `ActionCallback.onAction` 里的日志有输出 |
| 页号**写入了** | `run-as` 读 `files/datastore/appWidget-<id>.preferences_pb`，值确实变了 |
| `update()` **没抛异常** | `runCatching { widget.update(context, glanceId) }` 返回成功 |
| **但 `provideGlance` 从未被调用** | `provideGlance` 开头的日志一次都没出现 |

读源码（`glance-appwidget-1.1.0-sources.jar`）后确认：

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

`update()` 只是**驱动 Session**，真正的重绘交给**异步的 Session 事件循环**
（跑在 WorkManager 的 `SessionWorker` 里）。对**已经存在的 Session**，
这条路径不保证重新执行 `provideGlance` —— 日志里 `SessionWorker` 报了 SUCCESS，
但渲染压根没发生。

**升级到 Glance 1.1.1 同样无效**；把页号改存普通 SharedPreferences 也无效
（排除「state 被缓存」的猜测）。

### 4.3 Glance 的其他坑（若将来再用，先看这几条）

- `getAppWidgetState` 有两组同名重载：**用顶层**
  `(context, definition, glanceId): T`；用 `GlanceAppWidget` 的扩展版只会报
  「Cannot infer type for type parameter 'T'」，完全看不出根因
- `TextStyle.fontSize` 的类型是 compose 的 `TextUnit`，要
  `import androidx.compose.ui.unit.sp`；**没有** `androidx.glance.unit.sp`
- Glance 里没有 `Icon`，只能拿文字当图标 —— 而 `↻` 这类箭头字符
  **部分系统字体没有字形**，真机会渲染成豆腐块（生成预览图时实际撞上过），
  所以刷新键用了中文「刷新」
- `GlanceAppWidget` 由系统实例化，不走 Hilt 注入，取依赖要用 `@EntryPoint`
- ⚠️ Glance 用 `Class.forName(className).getDeclaredConstructor().newInstance()`
  实例化 `ActionCallback`：**一旦开启 R8 混淆，点击会静默失效**
  （无提示、无崩溃日志）

### 4.4 RemoteViews 方案的约束（现行实现）

- 布局只能用 `LinearLayout` / `FrameLayout` / `RelativeLayout` / `GridLayout`
  加 `TextView` 这类系统控件，**不能用 Compose、ConstraintLayout、自定义 View**
- 点击必须由代码 `setOnClickPendingIntent()` 绑定，XML 里写 `onClick` 无效
- ⚠️⚠️ **PendingIntent 的 `data` 必须唯一**：唯一性只看
  `requestCode` + `Intent.filterEquals`，而 **filterEquals 不比较 extras** ——
  只靠 extras 区分的话，几个页签会被判定为同一个 PendingIntent 互相覆盖，
  表现就是「只有最后一个页签能用」
- 颜色写固定值，不要用 `?attr/colorSurface`：RemoteViews 是在**桌面的进程**里
  inflate 的，取不到我们 App 的主题属性
- 单向数据流：RemoteViews 是跨进程的只读快照，点击只能通过广播回本进程，
  重新构建整张 RemoteViews 再下发


## 五、已抽取的可测逻辑

组件的 UI 无法在 JVM 单测里跑，所以把聚合逻辑全抽进 `WidgetLogic`
（纯函数，`now` / `today` 显式传入）。单测 `WidgetLogicTest` **25 条**。

重点覆盖的行为：

- `weeksContains` —— `weeks` 字段形如 `[1][2][3]`，必须按完整标记匹配。
  用 `contains(week.toString())` 会让第 1 周匹配到 `[11]`、`[21]`
- `weekOf(firstDay, today)` 返回 `-1` 表示未知；此时 **不按周过滤**课程，
  否则一屏全空，用户会误以为没同步
- `remainText` —— 已过期必须输出「已过期」，不能给负数

---

## 六、验证清单

### v1.6.1（RemoteViews 版，2026-09-21）

- [x] `:features:widget:compileDebugKotlin` 通过
- [x] `WidgetLogicTest` 25/25 通过
- [x] `assembleDebug` 全量构建成功
- [x] 模拟器：选择器显示「BIT101 课程日程 / **4 × 3**」
- [x] 模拟器：拖到桌面渲染正常（三个页签 + 高亮选中态 + 空态文案）
- [x] 模拟器：**点「座位」→ 3 秒内切到「暂无预约」**（不再需要任何额外重绘）
- [x] 模拟器：**点回「课程」、再点「DDL」都即时生效** —— 「回不去」已修
- [x] 模拟器：点「刷新」无崩溃
- [ ] 真机（NP05J）复测三页签切换与刷新
- [ ] 真机：厂商 ROM 对 WorkManager 的限流
- [ ] 真机：跨零点自动换天
- [ ] 真机：App 内同步课表后组件内容更新

### 手工测试组件的三个坑（踩过，别再踩）

1. **`am force-stop <包名>` 会让组件点击失灵**：force-stop 让组件缓存副本里的
   `PendingIntent` 作废，而 Launcher 仍用缓存，logcat 里连广播记录都没有。
   不是代码缺陷（重加组件或等一次真实重绘即恢复）。**测交互时不要先 force-stop**。
2. **`force-stop` Launcher 同理**：它会让 Launcher 重启并重新绑定组件，
   期间点击可能失效。要"重新绑定"就用别的方式。
3. **覆盖安装（`install -r`）后点击可能失灵一段时间**，直到 Launcher 重新拉取
   RemoteViews。**要得到可靠结论就用「卸载 → 安装 → 重新添加组件」的干净流程。**
4. **`uiautomator dump` 读到的组件层级会滞后于实际渲染** —— 判断界面变化请以
   `screencap` 截图为准。

### 一个可靠的诊断手法：直接读存储

排查「点了没反应」时，与其猜界面，不如**直接读页号**：

```bash
adb shell run-as cn.bit101.android.debug \
  base64 files/shared_prefs/bit101_widget_page.xml
```

一眼就能区分是「点击没触发」「写入没成功」还是「渲染没跟上」——
这次定位 Glance 的问题就是靠它把范围从三段缩到一段。
