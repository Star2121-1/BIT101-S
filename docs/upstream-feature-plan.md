# 上游「未来功能设想」梳理与分支规划

> 来源：`BIT101-Android/README.md`（上游只读参照仓库，本地提交 `6d79f18`，版本 1.4.0）
> 整理时间：2026-09-20
> 状态：**调研完成，等待选择一个分支开工**

---

## 一、上游设想的完整清单

上游 README 里有**三处**「未完成」表述，第三处最容易被漏掉，但对我们的价值最大。

### 1.1「计划功能」（README 第 20–27 行）

| # | 原文 | 技术含义 |
|---|------|---------|
| 1 | 手动添加日程 | 在日程页手动建一条，写进本地库 |
| 2 | 手动修改课程表 | 手动微调从教务拉来的课程（补漏 / 改教室） |
| 3 | 桌面小组建显示课程和日程 | 「组件」笔误 → 桌面 Widget |
| 4 | 地图显示定位、导航功能 | 地图页加定位蓝点 + 路线导航 |

### 1.2「未来开发计划」（README 第 221–227 行）

| # | 原文 | 技术含义 |
|---|------|---------|
| 5 | 通过小组件在桌面线显示课程日程 | 与 3 重复提出（说明作者确实想做） |
| 6 | 使用生物识别提升安全性能 | 指纹 / 人脸解锁应用 |
| 7 | 使用 `NFC` 实现刷校园卡登录（**可行性未验证**） | 手机贴卡读卡号登录 |
| 8 | 使用加密方式管理 `Cookie` | 修作者自认的疏漏 |
| 9 | 其他 `BIT101` 平台功能升级 | 泛指，非具体项 |

### 1.3 散落在正文中的「自认技术债」（最容易被忽略）

| 位置 | 原文摘要 |
|------|---------|
| 第 85 行 | 「`cookie-store` 是使用**未加密**的 `SharedPreferences` 存储 `Cookie` 的，这也会带来安全隐患，**但由于时间限制，暂时还没有重写这部分的存储接口**」← 第 8 项的具体出处 |
| 第 193 行 | `minifyEnabled true` 后部分功能无法正常使用（反射类库不支持混淆），暂未配置排除规则 |
| 第 208 行 | Compose 性能优化原则一节，作者自己写「我超 突然发现交上去的文档这里没写完呜呜呜」 |

---

## 二、实测的代码现状（非推测）

以下均经 `grep` / 逐文件核对：

| 结论 | 证据 |
|------|------|
| **9 项设想代码里一行都没有** | 全仓库 `grep -ril "glance\|appwidget\|BiometricPrompt\|NfcAdapter"` **零命中** |
| **NFC 权限也未申请** | `app/src/main/AndroidManifest.xml` 仅 4 个权限：`ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `READ_PHONE_STATE` / `INTERNET` |
| **设想 1、2 已有现成脚手架** | `features/common/.../component/schedule/CustomScheduleDialog.kt`（详情 + 编辑表单）与 `ScheduleUtils.kt`（`toEntity()`、`addScheduleToSystemCalendar()`）已存在 |
| **但手动日程并未接线** | `CustomScheduleEntity` / `CustomScheduleDao` 存在且已被 `:data` 编入 Room；**`CustomScheduleDao` 在 `CourseSchedule.kt` 中被引用**（有 `showCustomScheduleDetail` / `addEditCustomScheduleState` 等），需进一步确认入口是否已暴露给用户 |
| **加密存储已就绪** | `config` 模块已依赖 `androidx.security:security-crypto:1.0.0`，`EncryptedPreferencesItem` 已封装 AES256-SIV/GCM 读写；`seat_token` 已走加密路径 |
| **Cookie 仍为明文** | `Preferences.kt`：`COOKIE_PREFERENCES_STORE = SharedPreferencesCookieStore(context, "cookie")` ← 明文库 |
| **课程同步是「全删全插」** | `DefaultCoursesRepo.saveCourses()` 第一行就是 `deleteAllCourses()`；调用点两处：`CourseScheduleViewModel.forceRefreshCourses()`、`CalendarViewModel`（第 206 行） |

### ⚠️ 由此发现的额外风险：Cookie 明文存储的影响面比作者说的大

明文 `SharedPreferences("cookie")` 里装的是**学校统一身份认证的会话 Cookie**，
而不是座位模块的 JWT（后者已在 `EncryptedPreferencesItem` 里）。

也就是说第 8 项的价值不止是「隐患」二字 —— 它是**登录态可被同设备其他应用读取**。
在已 root 或有备份导出能力的设备上，等于学号会话直接泄露。

---

## 三、逐项可行性评估

| # | 设想 | 可行性 | 主要障碍 | 独立分支？ |
|---|------|--------|---------|-----------|
| 1 | 手动添加日程 | ✅ 高 | 有现成 Dialog 与 `addScheduleToSystemCalendar`；需确认入口是否已开放 | ✅ |
| 2 | 手动修改课程表 | ⚠️ 中高 | **不能改 `course_schedule` 表** —— `saveCourses()` 会 `deleteAllCourses()` 全量覆盖，手改必被冲掉。可行做法是新增「用户覆盖层」表（见 4.3） | ✅ |
| 3/5 | 桌面小组件 | ✅ 高 | 用 Glance（Compose 写法）；**难点是刷新** —— 组件进程独立，需 `WorkManager` 周期任务或数据变更时 `updateAll` | ✅ |
| 4 | 地图定位 / 导航 | ⚠️ 中 | 需 `ACCESS_FINE_LOCATION` 运行时权限；导航要么引第三方 SDK 要么自算步行路线；瓦片依赖作者自建代理 `map.bit101.flwfdd.xyz` | ✅（独立栈） |
| 6 | 生物识别 | ✅ 高 | 引 `androidx.biometric`；**需决策**：锁 App 入口时，前台服务的座位监控是否继续跑 | ✅ |
| 7 | NFC 刷校园卡登录 | ❌ **低** | 见下方专述 | ❌ 不建议 |
| 8 | 加密管理 Cookie | ✅ 高 | 需自实现加密版 `CookieStore` 接口替换 `cookie-store`；**风险：迁移不当会让全部用户掉登录态** | ✅ |
| 9 | 平台功能升级 | — | 非具体项 | — |

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
  ├── feature/widget              桌面小组件
  ├── feature/schedule-edit       手动日程 + 手动课程
  ├── feature/security            Cookie 加密 + 生物识别
  └── feature/map-navigation      地图定位导航（最后做）
```

- 每个分支**独立完成、独立验证**（编译 + 单测 + 模拟器/真机实测）后合回 `master`。
- 合并前 master 应先打 tag（如 `v1.5.3`）作为回退点。
- 版本号改动**只在合并回 master 时做**，分支内不动 `build.gradle` 的 `versions` 块，
  避免多分支冲突。
- 座位模块的既有约定（`SeatLog` 统一日志、纯逻辑抽 `*Logic` 便于单测、
  `testDebugUnitTest` 必须全绿）在后续所有分支中沿用。

### 4.1 分支 A —— `feature/widget`（桌面小组件）

**为什么排第一**：9 项里**唯一一个能独立交付、且用户每天都用得上**的功能；
与座位模块零耦合，不碰任何既有逻辑。

**做法**
- 新增 `features/widget` 模块（Glance：`androidx.glance:glance-appwidget`）。
- 数据源直接用 **Room**（`CoursesDao` / `DDLScheduleDao` 已落盘），
  组件**不自己联网** —— 只读本地，由 App 同步时触发刷新。
- 卡面内容：今日课程（节次 / 教室）+ 近期 DDL。
- 刷新：`WorkManager` 周期任务兜底（跨零点换天）+ App 内数据变更时主动 `updateAll`。
- `AndroidManifest` 声明 `AppWidgetProvider` + `appwidget-provider` 元数据。

**验收**：桌面能显示今日课程；跨零点自动换天；App 内刷新课表后组件同步更新。

**风险**：小组件在不同厂商 ROM（尤其华为 / 小米）上的限流与白名单策略差异较大，
`WorkManager` 最小周期为 15 分钟，不能依赖它做准点刷新。

### 4.2 分支 B —— `feature/schedule-edit`（手动日程 + 手动课程）

**脚手架已存在**：`CustomScheduleDialog.kt` + `ScheduleUtils.kt` + `CustomScheduleDao`。

**先做一件事：确认现状。** `CustomScheduleDao` 已被 `CourseSchedule.kt` 引用，
需要先跑一遍 App 确认「手动添加日程」到底是不可用、还是入口藏得深。
若已可用，则本分支只剩「手动课程」一项。

**手动课程的算法（关键设计）**

`course_schedule` 表会被 `saveCourses()` **整体删除重建**，因此**绝不能**直接往里写手改数据。
采用**覆盖层（overlay）**方案：

```
新增表 course_override(
    id, term, courseNumber, weekday, startSection, endSection,
    action,          -- HIDE / MODIFY / ADD
    name, teacher, classroom, weeks, campus,   -- action=MODIFY/ADD 时生效
)
```

渲染时把 `course_schedule`（同步所得）与 `course_override`（用户手改）做一次**归并**：

- `HIDE`：按「课程号 + 星期 + 节次」匹配，匹配到的同步课程不显示
- `MODIFY`：匹配到则用覆盖值替换字段
- `ADD`：直接追加一条

这样**同步照常全量覆盖 `course_schedule`，用户改动毫发无伤**。
归并逻辑抽成纯函数（如 `CourseMergeLogic.merge(base, overrides)`）以便单测覆盖 ——
沿用座位模块 `TaskListLogic` 的做法。

**验收**：手改课程后强制刷新课表，改动仍在；可撤销改动（删除覆盖项）回到同步原值。

**风险**：匹配键若选得太松（如只按课程名）会误伤同名课程；
若选得太紧（含教室）则在教务改教室后匹配失效。建议用「课程号 + 星期 + 起始节次」。

### 4.3 分支 C —— `feature/security`（Cookie 加密 + 生物识别）

把第 8、6 项合成一个分支，因为两者同属安全主题、且改动面都在 `config` 模块。

**Cookie 加密**
- 自实现 `EncryptedCookieStore` 替换 `Preferences.COOKIE_PREFERENCES_STORE`
  （`cookie-store` 库的 `CookieStore` 是接口，可自行实现；底层存储改用
  `EncryptedSharedPreferences`，与 `seat_token` 同一套密钥）。
- **迁移策略（必须做）**：首次启动时把明文库 `"cookie"` 读出 → 写入加密库 → 清除明文。
  不做迁移 = 所有用户莫名掉登录态。
- ⚠️ **此分支风险最高**，建议放在最后做，且合并前必须做一次完整的
  「登录 → 冷启动 → 课表同步 → 座位预约」全链路回归。

**生物识别**
- 引 `androidx.biometric:biometric`，在 `MainActivity` 启动时拦一道。
- **需用户决策**：锁屏时 `SeatMonitorService` 是否继续跑？
  （倾向：继续跑 —— 抢座是后台行为，不涉及隐私展示；只锁 UI 入口。）

### 4.4 分支 D —— `feature/map-navigation`（地图定位导航，最后做）

技术栈独立（`MapCompose` + 定位权限），与其他分支无共享改动，但难度明显最高。
放在最后，因为它依赖的瓦片服务（作者自建代理）稳定性未知，
且导航路线需要自己算或引第三方 SDK。

---

## 五、建议执行顺序

| 顺序 | 分支 | 理由 |
|------|------|------|
| 1 | `feature/widget` | 最快见效果、零耦合、用户感知最强 |
| 2 | `feature/schedule-edit` | 脚手架现成、工作量最小；核心是覆盖层设计 |
| 3 | `feature/security` | 收益大但风险最高（Cookie 迁移可致全体掉登录），放后面有回退空间 |
| 4 | `feature/map-navigation` | 独立栈、难度最高，最后单独攻 |
| — | ~~NFC~~ | **放弃**（见 3.1） |

---

## 六、待确认事项

1. 「手动添加日程」现状 —— 是已可用（只是入口难找），还是完全未接线？
2. 生物识别是否应拦截后台座位监控？
3. 小组件卡面希望展示哪些字段（今日课程 / 近期 DDL / 空教室 …）？
