# 上游「未来功能设想」梳理与分支规划

> 来源：`BIT101-Android`（上游只读参照仓库，`github.com/BIT101-dev/BIT101-Android`，默认分支 `main`）
> 整理时间：2026-09-20
> 状态：**调研完成，`feature/widget` 已开工**

---

## ⚠️ 修订说明（2026-09-20 二次核实）

第一版梳理**只看了本地快照的 README**，README 的「计划功能 / 未来开发计划」两节
**上游一直没更新过**（`main` 版与 v1.4.0 逐字相同），但**代码早已往前走了很多**。

用户提示「这个项目在此之后有很多人都提交过 PR 对其进行完善」后重新核查：

| 项 | 第一版结论 | **修订后结论** |
|---|---|---|
| 「手动添加日程」 | 待确认是否接线 | ✅ **上游 v1.4.0 就已实现并接线** |
| 上游分支 | `master` | **默认分支是 `main`**（另有 `feat/bitlogin`，见下） |
| 上游历史 | 本地快照仅 1 个提交 | **`main` 有 124 个提交**（已浅拉取核实） |
| 上游文档 | 只有 README | 历史上有 **`TODO.md`**（已在 `024715a` 被删，内容见下） |

`feat/bitlogin` 分支经比对是 **`main` 的 rebase 副本**（提交标题逐条相同、SHA 不同），
不含独有工作，可忽略。

---

## 一、上游设想的完整清单

上游 README 里有**三处**「未完成」表述，第三处最容易被漏掉，但对我们的价值最大。

### 1.1「计划功能」（README 第 20–27 行）

| # | 原文 | 技术含义 | 上游状态 |
|---|------|---------|---------|
| 1 | 手动添加日程 | 在日程页手动建一条，写进本地库 | ✅ **已实现** |
| 2 | 手动修改课程表 | 手动微调从教务拉来的课程（补漏 / 改教室） | ❌ 未做 |
| 3 | 桌面小组建显示课程和日程 | 「组件」笔误 → 桌面 Widget | ❌ 未做 |
| 4 | 地图显示定位、导航功能 | 地图页加定位蓝点 + 路线导航 | ❌ 未做 |

### 1.2「未来开发计划」（README 第 221–227 行）

| # | 原文 | 技术含义 | 上游状态 |
|---|------|---------|---------|
| 5 | 通过小组件在桌面线显示课程日程 | 与 3 重复提出（说明作者确实想做） | ❌ 未做 |
| 6 | 使用生物识别提升安全性能 | 指纹 / 人脸解锁应用 | ❌ 未做 |
| 7 | 使用 `NFC` 实现刷校园卡登录（**可行性未验证**） | 手机贴卡读卡号登录 | ❌ 未做（建议放弃，见 3.1） |
| 8 | 使用加密方式管理 `Cookie` | 修作者自认的疏漏 | ❌ **未做**（见下方核实） |
| 9 | 其他 `BIT101` 平台功能升级 | 泛指，非具体项 | — |

### 1.3 散落在正文中的「自认技术债」

| 位置 | 原文摘要 | 现状 |
|------|---------|------|
| README 第 85 行 | 「`cookie-store` 是使用**未加密**的 `SharedPreferences` 存储 `Cookie` 的…**暂时还没有重写这部分的存储接口**」 | ❌ 仍未做 |
| README 第 193 行 | `minifyEnabled true` 后部分功能异常，暂未配置排除规则 | ❌ 仍未做 |
| README 第 208 行 | Compose 性能优化原则一节作者自己写「我超 突然发现交上去的文档这里没写完呜呜呜」 | ❌ 仍未写 |

### 1.4 已删除的 `TODO.md`（**重要发现**）

上游曾有一份 `TODO.md`（在 `024715a ♻️ 清理LLM文档` 中被删除），
最后一版内容**不是功能设想，而是当时的工作清单**，且揭示了上游最痛的领域：

- **BIT-Login 登录迁移**（已完成，工具链升到 Kotlin 2.4.10 / AGP 8.13.2 / Gradle 8.13 / compileSdk 36）
- **学校 Cookie 一天过期导致 App 自动退出登录的修复**（已完成）
  - 核心设计：把「校验失败 → 直接登出」改为「校验失败 → 用已存学号密码**静默刷新整个会话**
    → 学校要求短信验证时弹验证码弹窗 → 刷新成功则重试原操作；仅确定性认证失败才登出」
  - 新增 `SmsCodeRequestHub`（验证码总线）+ 全局 `SmsCodeDialogHost`
  - `LoginRefreshResult { SUCCESS, NEEDS_INTERACTIVE, TRANSIENT, FAILED, BUSY }`
- **登录界面持续转圈 —— `loginMutex` 重入死锁修复**（已完成）
  - 根因：`kotlinx.coroutines.sync.Mutex` **非重入**，401 拦截器在持锁流程内
    `runBlocking { refreshLogin() }` 重入同一把锁 → 循环等待死锁
  - 修法：拦截器改用非阻塞 `tryRefreshLogin()`（`Mutex.tryLock()`），占用则返回 `BUSY`
- **当前任务：会话续期功能真机回归验证**（未完成，9 项待真机验证）

**明确列在「不在本次范围内」的**：图形验证码 UI、启动/前台主动会话校验、
可重入锁重构、**「重写 Cookie 存储加密方案」**、后端协议改动。

> ⚠️ 这条对本项目有直接价值：上游明确把「重写 Cookie 存储加密方案」**排除在当前范围外**，
> 说明短期内不会被上游做掉 —— 我们做 `feature/security` 不会撞车，但也意味着
> **无法指望从上游合并这个修复**。

---

## 二、实测的代码现状（非推测）

以下均经 `git ls-tree` / `git show` / `grep` 逐文件核对。

| 结论 | 证据 |
|------|------|
| **上游 `main` 上仍未实现小组件 / 生物识别 / NFC** | `git ls-tree -r --name-only FETCH_HEAD \| grep -iE "glance\|widget\|biometric\|nfc"` **零命中**（124 个提交之后依然为零） |
| **「手动添加日程」上游已完整实现** | `features/schedule/.../course/CourseSchedule.kt` 第 225/250/256 行：`onAddSchedule` → `showAddScheduleDialog` → `AddEditScheduleDialog`；入口是课表页右下角 `Icons.Rounded.Add` 的 **FAB**（`CourseScheduleCalendar.kt:397`） |
| **我们仓库同样已继承该功能** | 同一套代码在我们仓库里完好（`CustomScheduleDialog.kt` 存在，`CourseSchedule.kt` 引用一致） |
| **NFC 权限也未申请** | `app/src/main/AndroidManifest.xml` 仅 4 个权限：`ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `READ_PHONE_STATE` / `INTERNET` |
| **加密存储已就绪** | `config` 模块已依赖 `androidx.security:security-crypto:1.0.0`，`EncryptedPreferencesItem` 已封装 AES256-SIV/GCM 读写；`seat_token` 已走加密路径 |
| **Cookie 仍为明文** | `Preferences.kt`：`COOKIE_PREFERENCES_STORE = SharedPreferencesCookieStore(context, "cookie")` ← 明文库 |
| **课程同步是「全删全插」** | `DefaultCoursesRepo.saveCourses()` 第一行是 `deleteAllCourses()`；调用点两处：`CourseScheduleViewModel.forceRefreshCourses()`、`CalendarViewModel:206` |
| **数据库版本 = 2**，含 4 张表 | `BIT101Database.kt`：`course_schedule` / `exam_schedule` / `custom_schedule` / `ddl_schedule`，已配 `AutoMigration(1→2)` |

### ⚠️ 由此发现的额外风险：Cookie 明文存储的影响面比作者说的大

明文 `SharedPreferences("cookie")` 里装的是**学校统一身份认证的会话 Cookie**
（不是座位模块的 JWT，后者已在 `EncryptedPreferencesItem` 里）。

此外 —— 结合上游 `TODO.md` 的内容 —— 上游**明确把「重写 Cookie 存储加密方案」
排除在会话续期任务范围外**。也就是说这个疏漏是「已知、有意识搁置」的，
不是遗漏。第 8 项的价值因此更高（没人会替我们做）。

结合明文 Cookie 的另一个隐患：上游刚做完「Cookie 一天过期 → 静默刷新」的机制，
明文库里的会话 Cookie 一旦被读取，等于**学号会话 + 自动续期能力**双双泄露。

---

## 三、逐项可行性评估

| # | 设想 | 上游状态 | 可行性 | 主要障碍 | 独立分支？ |
|---|------|---------|--------|---------|-----------|
| 1 | 手动添加日程 | ✅ **已实现** | — | **无需再做**（我们已继承） | ❌ 取消 |
| 2 | 手动修改课程表 | ❌ | ⚠️ 中高 | **不能改 `course_schedule` 表** —— 见 4.2 覆盖层设计 | ✅ |
| 3/5 | 桌面小组件 | ❌ | ✅ 高 | 刷新机制（组件进程独立）；厂商 ROM 限流 | ✅ **已开工** |
| 4 | 地图定位 / 导航 | ❌ | ⚠️ 中 | 需定位运行时权限；导航需自算或引 SDK；瓦片依赖自建代理 | ✅ |
| 6 | 生物识别 | ❌ | ✅ 高 | 需决策：是否拦截后台座位监控 | ✅ |
| 7 | NFC 刷校园卡登录 | ❌ | ❌ **低** | 见 3.1 | ❌ 不建议 |
| 8 | 加密管理 Cookie | ❌ | ✅ 高 | 需自实现加密 `CookieStore` + **迁移**（否则全体掉登录态） | ✅ |
| 9 | 平台功能升级 | — | — | 非具体项 | — |

### 3.1 关于第 7 项（NFC）—— 建议放弃，理由三条

1. **作者自己标注了「可行性未验证」，而这个标注是准确的。** 刷校园卡登录必须读出实体卡的
   卡号（UID）或扇区数据，再拿它去换会话。但学校认证系统接受的是**学号 + 密码**，
   不是卡号 —— 中间那层「卡号 → 学号」映射表只在一卡通中心，我们拿不到。
2. **即便能读卡，也只是把卡号当密码用**，安全性不升反降（卡号可被近距离嗅探复制），
   与第 6 项「提升安全性能」的方向正好相反。
3. **硬件层面还受卡类型限制**：多数校园卡是加密 CPU 卡，需要厂商密钥才能读扇区；
   普通 App 只能读到 UID，且部分机型在 NFC 天线位置 / 协议上根本读不到。

**结论：放弃，或退化为「用 NFC 触发一次已有的密码直登」** —— 但后者相比指纹解锁毫无优势。

---

## 四、分支规划

### 4.0 分支工作流约定

```
master（= 可发布状态，随时能打 APK）
  ├── feature/widget              桌面小组件          ← 已开工
  ├── feature/schedule-edit       手动课程（覆盖层方案）
  ├── feature/security            Cookie 加密 + 生物识别
  └── feature/map-navigation      地图定位导航（最后做）
```

- 每个分支**独立完成、独立验证**（编译 + 单测 + 模拟器/真机实测）后合回 `master`。
- 合并前 master 应先打 tag（当前基线 `v1.5.3`）作为回退点。
- 版本号改动**只在合并回 master 时做**，分支内不动 `build.gradle` 的 `versions` 块，
  避免多分支冲突。
- 既有约定在后续所有分支中沿用：`SeatLog` 统一日志出口、
  纯逻辑抽 `*Logic` 便于单测、`testDebugUnitTest` 必须全绿。

### 4.1 分支 A —— `feature/widget`（桌面小组件）← 已开工

**为什么排第一**：唯一一个能独立交付、且用户每天都用得上的功能；
与座位模块零耦合，不碰任何既有业务逻辑。

**用户需求（2026-09-20 明确）**
> 展示**当日最近的课程**，可以**滑动切换**看其他信息，比如 DDL、座位预约等。

即：**一个可翻页的小组件**，多页轮播，而非固定一页。

**做法**
- 新增 `features/widget` 模块（Glance：`androidx.glance:glance-appwidget`）。
- 数据源用 **Room**（`CoursesDao` / `DDLScheduleDao` / `CustomScheduleDao` 已落盘），
  组件**不自己联网** —— 只读本地，由 App 同步时触发刷新。
- 卡面内容（用 Glance 的 `LazyColumn` + 拖动切换，或 `AppWidget` 多页）：
  - **第 1 页（默认）**：当日课程 —— 节次、课程名、教室、时间
  - **第 2 页**：近期 DDL —— 标题 + 剩余时间（未来 N 天）
  - **第 3 页**：座位预约 —— 当前预约的签到时限 / 进行中的抢座任务
- 刷新：`WorkManager` 周期任务兜底（跨零点换天）+ App 内数据变更时主动 `updateAll`。
- `AndroidManifest` 声明 `AppWidgetProvider` + `appwidget-provider` 元数据。

**Glance 的已知约束（开工前须知）**
- Glance 组件**不能跑任意 Compose UI**，只用 `GlanceAppWidget` 的那套 `*Modifier`；
  没有 `Pager` —— 滑动切页要靠 `LazyColumn`（Glance 有）+ 视觉提示，或做多个 widget 尺寸。
- **点击事件**只支持 `actionRunCallback` / `actionStartActivity`，交互能力远弱于 App 内。
- `WorkManager` 最小周期 **15 分钟**，不能依赖它做准点刷新。

**验收**：桌面显示当日课程；可切换到 DDL / 座位页；跨零点自动换天；
App 内刷新课表后组件同步更新。

**风险**：小组件在不同厂商 ROM（尤其华为 / 小米）上的限流与白名单策略差异较大。

### 4.2 分支 B —— `feature/schedule-edit`（手动修改课程表）

> ⚠️ 第一版规划里本分支含「手动添加日程」，**已核实那是上游早已实现的功能，且我们已继承**
> —— 课表页右下角 `+` FAB 即是入口。故本分支范围**收窄为只做「手动修改课程表」**。

**核心设计：覆盖层（overlay）方案**

`course_schedule` 表会被 `saveCourses()` **整体删除重建**，因此**绝不能**直接往里写手改数据。
采用覆盖层：

```
新增表 course_override(
    id, term, courseNumber, weekday, startSection,
    action,          -- HIDE / MODIFY / ADD
    name, teacher, classroom, weeks, campus,   -- action=MODIFY/ADD 时生效
)
```

渲染时把 `course_schedule`（同步所得）与 `course_override`（用户手改）做一次**归并**：

- `HIDE`：按「课程号 + 星期 + 起始节次」匹配 → 同步课程不显示
- `MODIFY`：匹配到则用覆盖值替换字段
- `ADD`：直接追加一条

这样**同步照常全量覆盖 `course_schedule`，用户改动毫发无伤**。
归并逻辑抽成纯函数（如 `CourseMergeLogic.merge(base, overrides)`）以便单测覆盖 ——
沿用座位模块 `TaskListLogic` 的做法。

**数据库变更**：`BIT101Database` 需 `version = 2 → 3` + `AutoMigration(2, 3)`。

**验收**：手改课程后强制刷新课表，改动仍在；可撤销改动（删除覆盖项）回到同步原值。

**风险**：匹配键若太松（如只按课程名）会误伤同名课程；
若太紧（含教室）则在教务改教室后匹配失效。建议用「课程号 + 星期 + 起始节次」。

### 4.3 分支 C —— `feature/security`（Cookie 加密 + 生物识别）

把第 8、6 项合成一个分支，因为两者同属安全主题、且改动面都在 `config` 模块。

**Cookie 加密**
- 自实现 `EncryptedCookieStore` 替换 `Preferences.COOKIE_PREFERENCES_STORE`
  （`cookie-store` 库的 `CookieStore` 是接口，可自行实现；底层存储改用
  `EncryptedSharedPreferences`，与 `seat_token` 同一套密钥）。
- **迁移策略（必须做）**：首次启动时把明文库 `"cookie"` 读出 → 写入加密库 → 清除明文。
  不做迁移 = 所有用户莫名掉登录态。
- ⚠️ **依赖上游机制的注意点**：上游刚实现「Cookie 过期 → 静默刷新」，
  它读写的是同一份 `cookieStore`。替换存储实现时**必须保证
  `SchoolCookieStore`（SDK Cookie → `java.net.HttpCookie` 的桥）仍然工作**，
  否则会打断上游的自动续期链路。
- ⚠️ **此分支风险最高**，建议放在最后做，且合并前必须做一次完整的
  「登录 → 冷启动 → 课表同步 → 座位预约」全链路回归。

**生物识别**
- 引 `androidx.biometric:biometric`，在 `MainActivity` 启动时拦一道。
- **决策：不拦截后台座位监控**（用户 2026-09-20 已确认「听你的」）。
  理由：抢座是后台行为、不涉及隐私展示；只锁 UI 入口。
  即解锁失败/取消时前端不显示内容，但 `SeatMonitorService` 照常轮询。

### 4.4 分支 D —— `feature/map-navigation`（地图定位导航，最后做）

技术栈独立（`MapCompose` + 定位权限），与其他分支无共享改动，但难度明显最高。
放在最后，因为它依赖的瓦片服务（作者自建代理 `map.bit101.flwfdd.xyz`）稳定性未知，
且导航路线需要自己算或引第三方 SDK。

---

## 五、建议执行顺序

| 顺序 | 分支 | 理由 |
|------|------|------|
| 1 | `feature/widget` | 最快见效果、零耦合、用户感知最强 —— **已开工** |
| 2 | `feature/schedule-edit` | 范围已收窄为「手动改课程」，核心是覆盖层设计 |
| 3 | `feature/security` | 收益大但风险最高（Cookie 迁移可致全体掉登录态），放后面有回退空间 |
| 4 | `feature/map-navigation` | 独立栈、难度最高，最后单独攻 |
| — | ~~「手动添加日程」~~ | **上游已实现，我们已继承，无需再做** |
| — | ~~NFC~~ | **放弃**（见 3.1） |

---

## 六、待确认事项

1. ~~「手动添加日程」现状~~ → **已核实：上游 v1.4.0 即已实现并接线，入口是课表页右下角 `+` FAB**
2. ~~生物识别是否应拦截后台座位监控？~~ → **已定：不拦截，只锁 UI 入口**
3. ~~小组件卡面希望展示哪些字段？~~ → **已定：可滑动多页，第 1 页当日课程、第 2 页 DDL、第 3 页座位预约**

