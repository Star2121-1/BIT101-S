# 通知与提醒中心（features/notify）

> 设计日期：2026-09-22　｜　状态：**七类提醒全部落地**（上课 / DDL / 座位签到 / 考试 /
> **课表调整（补课）** / 出分 / 网费+流量），最新 v1.9.45。
> 渠道与文案的唯一出口是 `NotifyCenter`。

---

## 一、目标

App 里的数据早就齐了（课表、DDL、座位预约），但**没有任何主动提醒** ——
所有信息都要用户自己想起来去看。本模块把这些数据变成**按时的提醒**。

| 提醒 | 数据来源 | 默认提前量 | 本轮 |
|---|---|---|---|
| **上课提醒** | `course_schedule` + 课表设置里的时间表 | 提前 **10 分钟** | ✅ |
| **DDL 提醒** | `ddl_schedule` | 提前 **1 天** 与 **1 小时** | ✅ |
| **座位签到时限** | `Reservation.signInDeadline`（当日 +60 分钟 / 次日 9:00） | 提前 **15 分钟** | ✅ v1.7.1 |
| **出分提醒** | 成绩表差分（`ScoreRepo.syncAndDiff`，异步认证流程） | 即时（每日兜底 + 前台取到数就判，12h 限频） | ✅ v1.9.9 |
| **网费不足** | 校园网余额（深澜 `rad_user_info`，仅校内可达） | 余额 < **10 元**（硬编码） | ✅ v1.9.0 |
| **流量阈值** | 本月流量（同上） | **270 GB / 300 GB 各一次**，之后不再打扰 | ✅ v1.9.7 |
| 暂离将到期 | 座位规则（60 分钟；用餐时段 120 分钟） | 提前 10 分钟 | ⏳ 未做 |
| 抢座结果 | 已在 `SeatMonitorService` 里发（`预约结果` 渠道） | 即时 | ✅ 已有，不重复做 |

---

## 二、架构

```
features/notify
├── NotifyLogic.kt          纯逻辑：给定「课程/DDL/座位签到/考试/现在」，算出该排哪些提醒（全部可单测）
├── NotifyRepository.kt     取数：Room（课程/DDL）+ 课表设置里的时间表 + 座位签到（经接口）
├── SeatReminderSource.kt   **座位侧实现的接口**（数据方向；seat 提供实现，notify 只认识模型）
├── NotifyCenter.kt         渠道创建 + 发通知 + 点击跳转
├── NotifyScheduler.kt      WorkManager 排期（每类提醒一个 unique work）
├── NotifyWorker.kt         到点执行：**重新取数校验** → 发通知 → 记录已发
├── NotifySentStore.kt      已发记录（SharedPreferences，防重复打扰）
├── NotifyRepositoryHolder.kt  EntryPoint 兜底（Worker 不经过 Hilt 注入）
└── NotifyAppStartup.kt     App 启动时排一次期
```

依赖方向：`notify → (config, data)`；**`seat → notify`**（座位侧实现 notify 声明的
`SeatReminderSource` 并在 Hilt 里绑定）；`notify` **不依赖** `seat`/`widget`，不会成环。
`NotifyRepository` 注入的是 `SeatReminderSource` 这个**接口** —— 编译期不引用座位模块任何代码。

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
| `seat_reminder` | HIGH | 座位签到（错过会记违约，累计 5 次暂停 7 天） |
| `exam_reminder` | HIGH | 考试（只有一次机会，正文必须给考场与座位号） |
| `adjustment_reminder` | HIGH | 课表调整（补课日前一天 20:00；这是「课表变了」，按平时课表出门会走错） |

⚠️ **上课提醒也吃教学调整**（v1.9.47）：放假那天**不排**（课不上，提醒就是错的），
补课日按**被指定那天**的课表排（那天是要上课的）。见 [3.8]。
| `score_reminder` | DEFAULT | 出分（**不含分数**，用户定的隐私边界） |
| `netfee_reminder` | DEFAULT | 网费不足（每日至多一条） |
| `netflow_reminder` | DEFAULT | 校园网流量 270 / 300 GB（每周期至多两条） |
| （沿用座位侧）`预约结果` | DEFAULT | 抢座结果 —— **不新建渠道**，避免同一个 App 出现两套座位通知 |

### 3.5 权限

- `POST_NOTIFICATIONS` 已在 `features/seat` 的清单里声明（会合并进 App），
  notify 模块**再显式声明一次**（模块自包含，合并时去重）
- UI 侧复用现有的 `hasNotificationPermission(context)` / `rememberNotificationPermissionState()`；
  **本轮不新增权限弹窗**：设置里显示开关状态 + 一键去申请即可

### 3.6 考试提醒：与座位签到**方向相反**

考试数据随课表一起同步（`exam_schedule` 表），但它是**一次性机会**，所以策略特意做得不一样：

| | 座位签到 | 考试 |
|---|---|---|
| 「时刻已过」（`at < now`） | **立刻补发** —— 截止前刷卡还来得及补救 | **不补发** —— 「1 小时后开考」迟发就是假话，都开考了只会添乱 |
| 提醒窗口 | 单一（截止前 N 分钟） | **两个**：考前一天 + 考前 N 分钟 |
| 正文内容 | 座位号 + 绝对截止时刻 | 时间 · 课名 · 考场 · **座位号** |
| 渠道重要性 | HIGH | HIGH（考试迟到无法补救，必须锁屏可见） |

去重键 `exam:{课程号或课名}:{日期}:{窗口}` —— ⚠️ **刻意不带具体时刻**：
服务端把 08:00 微调成 08:30 时，若键里带时刻就会判成「新提醒」而重复打扰一次。
`stillValid` 也只比**日期**、不比时刻与考场（临时换考场照样得去）。

考试**不按教学周过滤**：日期是服务端给的绝对日期，与第几周无关。

### 3.7 课表调整提醒（补课）：与考试**同向**，但触发源完全不同

唯一一条**不来自本地 Room 表**的提醒：数据源是 `TeachingAdjustmentRepo`（教学安排调整，
免登录、12h TTL、**静默降级**）。

| | 说明 |
|---|---|
| 时机 | 补课日**前一天 20:00** 固定钟点（不写「提前 N 小时」：20:00 是睡前，用户真在刷手机） |
| 范围 | **只报 `DayPlan.MakeUp`，不报放假**（缺课是真实损失；放假连续 7 天会变成连发一周的打扰） |
| 时刻已过 | **不补发** —— 补课当天才弹「按周四上课」是句废话，人都该出门了 |
| 标题 | **到点时按实际日期重算**（「明天」→「今天」）；已过补课日 → 返回 null = 不发 |
| 正文 | 绝对日期，如 `10/10（周六）按周四课表上课` |
| 渠道 | `adjustment_reminder`（HIGH） |
| 去重键 | `adjustment:{日期}` —— 宁可漏一次，也不要让用户按错的课表出门：取不到调整数据就**不发** |

⚠️ **取不到数据时不发，而不是按缓存发** —— 课表页「取不到就静默降级」降的是**展示**，
这里降级会变成**主动发出的一条错通知**。两者不是一回事。

⚠️ **中文星期/日期文案只有一份**：`data/school/TeachingAdjustment.kt` 的顶层
`weekdayCn()` / `dateLabelCn()`。`features:notify` 不能依赖 `features:schedule`，
抄两遍的结果是「周四」vs「周4」这种**单测各锁各的、谁也不会红**的静默漂移
（写这条功能时真踩了：标题写「明天按**四**课表上课」、正文写「按**周四**课表上课」，
靠单测才发现）。已补一条「标题与正文写法必须一致」的断言守着。

---

### 3.8 上课提醒也要看教学调整（放假不排 / 补课按指定那天）

`NotifyLogic.effectiveWeekday(date, planOf)` 是**唯一一份**答案：

- 补课 ⇒ 被指定那天的星期几
- 放假 ⇒ `null` = 那天没课
- 没覆盖 ⇒ 当天自己的星期几

为什么两个方向都不能漏：补课日不按指定那天排 ⇒ **那天一条提醒都没有，
而那天恰恰要上课**；放假那天照排 ⇒ **课根本不上却弹出「10 分钟后上课」**，
那是错的提醒，比不发更糟（用户会白跑一趟教室）。

⚠️⚠️ **两处必须用同一个函数，改一处不改另一处等于没改**：
`classReminders`（排期）与 `NotifyRepository.classRefreshed`（到点二次校验）。
后者原本按 `date.dayOfWeek.value` 查「那天还有课吗」—— 补课日去查周四的课必然查不到，
**排出来的提醒会在到点被自己判成「没课」而静默丢掉**。

⚠️ `NotifyRepository.plan()` 取教学调整的条件是 `adjustmentEnabled || classEnabled`
—— 别写成只有 `adjustmentEnabled` 才取。

## 四、设置项（config 模块）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `notify_enabled` | Boolean | true | 总开关 |
| `notify_class_enabled` | Boolean | true | 上课提醒 |
| `notify_class_lead_minutes` | Long | 10 | 上课提前量（分钟） |
| `notify_ddl_enabled` | Boolean | true | DDL 提醒 |
| `notify_ddl_day_enabled` | Boolean | true | 提前 1 天 |
| `notify_ddl_hour_enabled` | Boolean | true | 提前 1 小时 |
| `notify_seat_enabled` | Boolean | true | 座位签到提醒 |
| `notify_seat_lead_minutes` | Long | 15 | 签到提前量（分钟） |
| `notify_exam_enabled` | Boolean | true | 考试提醒 |
| `notify_exam_day_enabled` | Boolean | true | 考前一天（固定提前 24 小时） |
| `notify_exam_lead_minutes` | Long | 60 | 考试提前量（分钟）；选项从 30 起（考试要提前到场） |
| `notify_adjustment_enabled` | Boolean | true | 课表调整（补课）提醒；**没有提前量**（固定补课日前一天 20:00） |
| `notify_score_enabled` | Boolean | true | 出分提醒 |

（放在 `config` 模块，沿用 `SettingDataStore` + `SettingItem` 的既有模式，
便于设置页统一渲染。）

---

## 五、验证方式

1. `NotifyLogicTest`：**73 条全过**（v1.9.45 起）+ `NetFlowLogicTest` 4 条 ——
   提醒时刻计算、窗口边界、跨天、周次过滤、去重键、过期/已完成过滤、时间表越界、
   自定义时间表、文案格式；考试部分单独覆盖**双窗口**、**过时不补发**、
   **键不含时刻**（服务端微调时间不重复提醒）；课表调整部分覆盖**只报补课不报放假**、
   **过时不补发**、**标题按到点实际日期重算**、**键只有日期且能往返取回**、
   **标题与正文的星期几写法一致**
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
4. **真机实测（2026-10-09，v1.9.45，课表调整提醒）** —— 用**开关做可证伪对照**：
   - 当天 10/09、补课日 10/10 ⇒ 提醒应落在**今晚 20:00**。
     `dumpsys jobscheduler` 里确实有一条落在 20:00:00（其余条都能对上真实课表的
     课前 10 分钟：09:45 / 13:10 / 15:05 / 18:20）
   - **关掉「补课提醒」开关** → 该条**消失**（16 → 15 条，其它一条未动）；
     **打开** → 它**回来**。⇒ 设置 → 策略 → 排期 → WorkManager 整条链路通
   - ⚠️ 只验到「排期正确 + 开关可控」，**没验到「到点真的弹出通知」**

---

## 六、后续（不在本轮）

- ~~设置页 UI~~ ✅ **已在 v1.6.9 完成**：`我 → 设置 → 提醒设置`，
  含权限状态与一键申请；每次改动都会立即重排（`NotifyAppStartup.reschedule`）
- ~~座位签到时限提醒~~ ✅ **已在 v1.7.1 完成**（`SeatReminderSource` + `seat_reminder` 渠道）
- ~~考试提醒~~ ✅ **已在 v1.9.14 完成**（`exam_reminder` 渠道 + 考前一天/考前 N 分钟双窗口；
  配套的「考试安排」列表入口在课表页右下角的日历图标）
- ~~课表调整（补课）提醒~~ ✅ **已在 v1.9.45 完成**（`adjustment_reminder` 渠道；
  补课日前一天 20:00，只报补课）
- ⚠️ **仍未真机验证**：补课提醒**真的弹出来那一刻** —— v1.9.45 只验到
  「排期落进 `dumpsys jobscheduler` 且开关可控」（见第五节）。开发机没保持在 20:00 开机。
- 暂离将到期提醒（`seat → notify`）：暂离保留 60/120 分钟，超时自动释放 —— 未做
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
- **失败原因别丢**（v1.9.15）：出分检查走认证主机的异步挑战流程，服务端把失败原因写在响应体的
  `error` 字段里（如 `用户名或密码错误 [status=401, risk=ustc-token, …]`）。此前模型里没这个字段、
  又把 `status=failed` 一律归成「网络失败」→ 用户看到「稍后重试」，而**重试永远不会成功**。
  ⚠️ 顺带一个白费：`start` 已经 `failed` 时还进了轮询循环，白跑 25 次 × 400ms。
  **查法**：用**假凭据**直接 `POST /api/jwb/bit101/score` 就能看到服务端真实响应
  （零风险，碰不到真实账号）—— 比在 App 里反复点、或拿真账号试可靠得多。
  分类要按「下一步动作」切：`failed`（要改密码）≠ `expired`（下次还有机会）≠ `waiting_sms`（要人工验证）。
