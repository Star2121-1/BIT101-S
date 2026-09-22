# 通知与提醒中心（features/notify）

> 设计日期：2026-09-22　｜　分支：`feature/notify`　｜　状态：**Phase 1 已完成并在真机验证通过（v1.6.8）**

---

## 一、目标

App 里的数据早就齐了（课表、DDL、座位预约），但**没有任何主动提醒** ——
所有信息都要用户自己想起来去看。本模块把这些数据变成**按时的提醒**。

| 提醒 | 数据来源 | 默认提前量 | 本轮 |
|---|---|---|---|
| **上课提醒** | `course_schedule` + 课表设置里的时间表 | 提前 **10 分钟** | ✅ |
| **DDL 提醒** | `ddl_schedule` | 提前 **1 天** 与 **1 小时** | ✅ |
| 座位签到时限 | `Reservation.signInDeadline`（当日 +60 分钟 / 次日 9:00） | 提前 15 分钟 | ⏳ 预留 API，座位侧接入 |
| 暂离将到期 | 座位规则（60 分钟；用餐时段 120 分钟） | 提前 10 分钟 | ⏳ |
| 抢座结果 | 已在 `SeatMonitorService` 里发（`预约结果` 渠道） | 即时 | ✅ 已有，不重复做 |

---

## 二、架构

```
features/notify
├── NotifyLogic.kt          纯逻辑：给定「课程/DDL/现在」，算出该排哪些提醒（全部可单测）
├── NotifyRepository.kt     取数：Room（课程/DDL）+ 课表设置里的时间表
├── NotifyCenter.kt         渠道创建 + 发通知 + 点击跳转
├── NotifyScheduler.kt      WorkManager 排期（每类提醒一个 unique work）
├── NotifyWorker.kt         到点执行：**重新取数校验** → 发通知 → 记录已发
├── NotifySentStore.kt      已发记录（SharedPreferences，防重复打扰）
├── NotifyRepositoryHolder.kt  EntryPoint 兜底（Worker 不经过 Hilt 注入）
└── NotifyAppStartup.kt     App 启动时排一次期
```

依赖方向：`notify → (config, data)`；**`seat → notify`**（座位侧调用提醒 API）；
`notify` **不依赖** `seat`/`widget`，不会成环。

### 为什么用 WorkManager 而不是 AlarmManager

- **不用精确闹钟**：`SCHEDULE_EXACT_ALARM` 在 Android 12+ 需要额外权限、且很耗电，
  而上课提醒早几分钟晚几分钟都无妨 —— 不为此牺牲电池。
- WorkManager 的一次性任务在 Doze 下可能**延后几分钟**，这是可接受的，
  所以：
  - 排期时刻**比实际提醒时刻早 1 分钟**，留出调度余量；
  - 通知正文里**写明确的钟点**（如「09:55 操作系统 · 文萃楼I404」），
    用户看到的永远是绝对时间，不依赖通知到达的时刻。

---

## 三、关键设计决策

### 3.1 提醒的「时刻」如何算

- 上课提醒时刻 = 该节课**第一节的开始时刻** − 提前量（时间表来自
  `CourseScheduleSettings.timeTable`，读不到时用 `FALLBACK_TIME_TABLE`）
- DDL 提醒时刻 = `ddl.time` − 提前量（两个窗口各一条）
- **只排未来 7 天内的提醒**（再远的等下次重排），避免一次性入队几百个任务

### 3.2 去重键（防重复打扰）

```
course:{课程号}:{日期}:{起始节次}:{提前量}     例 course:CS30004:2026-09-24:3:10
ddl:{uid}:{窗口}                            例 ddl:lexue-123:1d / ddl:lexue-123:1h
```

已发记录在 worker 真正发出后写入；**同键只发一次**。
重排（应用启动 / 数据变更）时不会重复发 —— 这是必须的，否则每次打开 App 都会轰炸。

### 3.3 worker 到点必须**重新取数校验**

排期到执行之间，用户可能：删了课、改了课表、把 DDL 标记为已完成、DDL 改期。
所以 worker 执行时**重新读一次当前数据**，只有仍然满足条件才发：

- 课程：当天该节次仍有这门课（且周次包含当周）
- DDL：仍未完成（`done == false`）且**截止时间还没过**（过期就不打扰了）
- 任何一项取数失败 → **静默跳过**，绝不抛异常（沿用组件侧的原则）

### 3.4 通知渠道

| 渠道 | 重要性 | 用途 |
|---|---|---|
| `class_reminder` | DEFAULT | 上课提醒 |
| `ddl_reminder` | HIGH | 作业截止（更紧急，允许提醒到人） |
| （沿用座位侧）`预约结果` | DEFAULT | 抢座结果 —— **不新建渠道**，避免同一个 App 出现两套座位通知 |

### 3.5 权限

- `POST_NOTIFICATIONS` 已在 `features/seat` 的清单里声明（会合并进 App），
  notify 模块**再显式声明一次**（模块自包含，合并时去重）
- UI 侧复用现有的 `hasNotificationPermission(context)` / `rememberNotificationPermissionState()`；
  **本轮不新增权限弹窗**：设置里显示开关状态 + 一键去申请即可

---

## 四、设置项（config 模块）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `notify_enabled` | Boolean | true | 总开关 |
| `notify_class_enabled` | Boolean | true | 上课提醒 |
| `notify_class_lead_minutes` | Long | 10 | 上课提前量（分钟） |
| `notify_ddl_enabled` | Boolean | true | DDL 提醒 |
| `notify_ddl_day_enabled` | Boolean | true | 提前 1 天 |
| `notify_ddl_hour_enabled` | Boolean | true | 提前 1 小时 |

（放在 `config` 模块，沿用 `SettingDataStore` + `SettingItem` 的既有模式，
便于设置页统一渲染。）

---

## 五、验证方式

1. `NotifyLogicTest`：**23 条全过** —— 提醒时刻计算、窗口边界、跨天、周次过滤、
   去重键、过期/已完成过滤、时间表越界、自定义时间表、文案格式
2. 编译 + `assembleDebug` / `assembleRelease` 通过
3. **真机实测（2026-09-22，v1.6.8）**：
   - 装到真机启动后，`dumpsys notification` 里出现我们创建的两个渠道
     （`class_reminder` / `ddl_reminder`）→ 说明启动接线与 EntryPoint 都通了
   - `dumpsys jobscheduler` 里排出了 **10 条提醒任务**，延迟分别是
     `+17h57m / +21h22m / +23h17m`，以及 1 天、2 天、5 天后的同一批
     → 换算成钟点是 **09:45 / 13:10 / 15:05 / 18:20**，
     正好等于该用户真实课表的各节课（09:55 / 13:20 / 15:15 / 18:30）**提前 10 分钟**
   - 用 `cmd jobscheduler run -f <pkg> <jobId>` 强制触发一条 → 通知栏出现
     **「10 分钟后上课」/「09:55-12:20 · 计算机视觉 · 综教B301」**，
     与该用户周三课表（09:55 计算机视觉 @ 综教B301）完全一致
     → **取数 → 排期 → 二次校验 → 发通知 → 文案 全链路验证通过**

---

## 六、后续（不在本轮）

- **设置页 UI**：加「提醒」分组（总开关 / 上课提醒 + 提前量 / DDL 两个窗口）+
  权限状态与一键申请。*数据层已就绪*（config 的 `NotifySettings` + 6 个 DataStore 键），
  只差页面渲染
- 座位签到时限 / 暂离将到期提醒的**座位侧接入**（`seat → notify` 调用）
- DDL 换源完成后（见 `docs/ddl-migration-plan.md`），提醒自动跟着新源走

## 七、踩坑记录

- **`data` 模块的 DAO 是 `internal`**，跨模块拿不到 → 提醒取数必须走
  `CoursesRepo` / `DDLScheduleRepo` 这些**公开仓库接口**（Flow 用 `.first()` 取一次）
- 新建的 feature 模块**别照抄 widget 的 build.gradle**：里面的
  `org.jetbrains.kotlin.plugin.compose` 会让编译期报
  「Compose Compiler requires the Compose Runtime」，而通知模块不需要 Compose
- 真机上 `dumpsys jobscheduler | grep` 会**因为管道缓冲被截断**（看起来像「没有任务」）。
  正确做法：先 `dumpsys > /sdcard/x.txt` 落盘，再 grep
- 本机**模拟器系统服务已损坏**（`Can't find service: package`，冷启动/wipe 均无效），
  真机验证是唯一可行路径；顺手发现真机 `logcat` 读不出内容（厂商限制），
  调试只能靠 `dumpsys` 这类可观测状态
