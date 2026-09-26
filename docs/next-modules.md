# 下一批模块规划（2026-09-22）

> ⚠️ **进度已迁移**：本文件是当时的规划与核对记录（保留作历史）。
> 这里提到的「手动修改课程表」**已实现**（overlay 覆盖层，见 `docs/course-overlay.md`）；
> 当前待办与已关闭项请看 [next-round-plan.md](next-round-plan.md)，功能总览看 [../README.md](../README.md)。

> 承接 `docs/upstream-feature-plan.md`（上游创想梳理 + 分支 A–D 规划）。
> 本篇是**用户 2026-09-22 反馈后的重新校准**：用户指出的几处「其实已经有了」经核对属实，
> 已从待办中划掉；同时记录了新发现的模块与调研结论。

---

## 一、用户指正的核对结果（全部经代码核实）

| 用户说法 | 核对结果 | 证据 |
|---|---|---|
| 成绩查询**已经实现了**，在「网」里面 | ✅ **属实** | 网页面是 WebView（`WebViewModel.BASE_URL = "https://bit101.cn"`），进入 `bit101.cn/score/` 时**自动填充学号密码**（`WebScreen.kt:158-159`） |
| 组件**不再显示成绩**（隐私考虑） | ✅ 已采纳 | 组件保持无成绩页（当时三页，**现为四页：课程/DDL/动态/座位**）。成绩只在 App 内按需查看 |
| 空教室查询**已经有了**，在课程表第一页 | ✅ **属实** | `ScheduleScreen.kt:24-25`：课表页第二个 tab 就是「空教室」（`FreeClassroomSearch`），后端 `SchoolClassroomService` + `DefaultFreeClassroomRepo` + 设置页 `FreeClassroomSettingPage` |
| 课表编辑（手动修改课程表）**不知道有没有** | ❌ **没有实现** | 全仓 `grep -i "overlay\|自定义课程\|customCourse"` 零命中；上游 `main` 同样没有（见 `upstream-feature-plan.md` 3 节） |
| 手动添加日程 | ✅ 已有（上游继承） | `custom_schedule` 表 + 课表页右下角 `+` FAB（`CustomScheduleDialog`）—— 注意这与「修改课程表」是两件事 |

**结论**：`feature/schedule-edit` 的范围仍然是「**手动修改课程表**」（overlay 覆盖层方案，
见 `upstream-feature-plan.md` 4.2），但它的优先级可以往后放 —— 用户当前更看重通知。

---

## 二、DDL 换源（重要：数据源已失效）

### 现状链路（已核实）

```
乐学 (Moodle) ──BIT-Login SDK──> SchoolLexueService.getCalendarUrl() / getCalendar()
                                    │
                                    └──> ddl_schedule 表（event.group = "lexue"）
                                            └──> DDL 页 + 桌面组件 DDL 页
```

- `api/.../SchoolLexueService.kt`：`getCalendarUrl()` 拿日历订阅地址、`getCalendar()` 拉 ICS 事件
- `DDLScheduleDetailDialog.kt:108`：`if (event.group == "lexue") "乐学" else "自定义"`
  —— 也就是说**换源要动的不只是网络层，还有 group 标识与展示文案**
- `Options.kt` 里三个环境都指向 `lexue.bit.edu.cn`（含 webvpn 代理地址）

### 用户反馈

> 学校现在**不用乐学了**，换成了一个新的网站；这一点**连 BIT101 原创作者也没改**。

### 因此要调研的问题（**需要用户提供新平台网址**）

1. 新平台的**登录方式**：是否仍走学校统一身份认证（CAS）？`BIT-Login` SDK 能否复用？
2. 新平台是否提供**日历订阅 / ICS 导出**（乐学是 ICS，所以当时实现成本低）？
   - 有 → 复用现有 ICS 解析，改动最小
   - 没有 → 只能抓它的作业列表接口，需要自己写解析 + 登录会话
3. 是否有**官方 App / 小程序**可参照（有时可从其网络请求反推接口）
4. **WebVPN 访问**：校外是否必须走 `webvpn.bit.edu.cn`
5. 兼容策略：**旧乐学数据要保留**（老 DDL 不能丢），新源作为追加 group（如 `"newplatform"`）
   → 需要 `ddl_schedule` 的 group 维度支持多源并存

⚠️ 这是一项**调研优先**的任务：在拿到新平台网址并确认是否有可用的数据出口前，
不建议动代码（很可能白做）。建议单独开 `feature/ddl-source` 分支。

---

## 三、候选新模块清单

| # | 模块 | 现状 | 可行性 | 依赖 / 风险 | 建议分支 |
|---|---|---|---|---|---|
| N1 | **DDL 换源（新平台）** | 源已失效 | ⚠️ 取决于调研 | 需要新平台网址；可能无数据出口 | `feature/ddl-source` |
| N2 | **通知与提醒中心** ⭐用户点名 | 无统一调度 | ✅ 高 | WorkManager 已有基建；需通知渠道 + 权限（Android 13+ POST_NOTIFICATIONS） | `feature/notify` |
| N3 | **校园卡 / 消费查询** ⭐用户点名 | 无 | ⚠️ 中 | 需确认一卡通接口是否可访问（可能要 WebView 兜底）；用户也认为「比较麻烦」 | `feature/card` |
| N4 | 手动修改课程表（overlay） | 无 | ⚠️ 中高 | 需 DB v2→v3 + AutoMigration；覆盖层归并逻辑 | `feature/schedule-edit` |
| N5 | 地图定位 / 导航 | 无 | ⚠️ 中 | 定位权限；瓦片依赖作者自建代理，稳定性未知 | `feature/map-navigation` |
| N6 | Cookie 加密 + 生物识别 | 无 | ✅ 高但**风险最高** | 迁移没做好会让全体掉登录态 | `feature/security` |
| ~~N7~~ | ~~组件显示成绩~~ | — | ❌ **不做** | 用户明确：隐私考虑，且 App 内「网」已有成绩查询 | — |
| ~~N8~~ | ~~空教室查询~~ | ✅ 已有 | — | 课表页第二个 tab | — |
| ~~N9~~ | ~~手动添加日程~~ | ✅ 已有 | — | 上游继承 | — |
| ~~N10~~ | ~~NFC 刷校园卡登录~~ | — | ❌ 放弃 | 见 `upstream-feature-plan.md` 3.1 | — |

### N2「通知与提醒中心」的可行范围（推荐先做）

现有数据全都齐了，缺的只是**统一的通知调度**：

| 提醒 | 数据来源 | 触发时机 |
|---|---|---|
| 上课提醒 | `course_schedule` + 时间表（`WidgetLogic.FALLBACK_TIME_TABLE` 已有节次↔时刻映射） | 上课前 N 分钟（可配 5/10/15） |
| DDL 提醒 | `ddl_schedule` | 截止前 1 天 / 1 小时（可配） |
| 座位签到时限 | **已有 `Reservation.signInDeadline`**（当日 +60 分钟 / 次日 9:00 前） | 到期前 15 分钟 |
| 暂离超时 | 座位规则（默认 60 分钟、用餐时段 120 分钟） | 保留期结束前 10 分钟 |
| 预约任务结果 | `SeatTaskRepository` 任务状态（已有 `SUCCESS` 等终态） | 抢到座立刻通知 |

技术要点：
- 统一走 **WorkManager 一次性任务**（按提醒时刻精确排期）+ 一个 `NotificationCenter` 出口
- 每个提醒用**稳定的业务 key** 去重（如 `course-<课程号>-<日期>-<节次>`），避免重绘/重启后重复打扰
- Android 13+ 需 `POST_NOTIFICATIONS` 运行时权限；组件里已有 `NotificationPermission` 组件的先例可复用
- 纯逻辑（「什么时候该提醒」）抽成 `NotifyLogic` 便于单测 —— 沿用 `*Logic` + 单测的项目约定

---

## 四、建议执行顺序（更新版）

| 顺序 | 项 | 理由 |
|---|---|---|
| 1 | **N2 通知与提醒中心** | 用户点名；数据全就绪、零外部依赖；每天用得上 |
| 2 | **N1 DDL 换源调研** | 源已失效属「功能退化」，但必须先拿到新平台网址才能动手 |
| 3 | **N3 校园卡 / 消费** | 用户点名；先做接口可行性探测（可能需 WebView 兜底） |
| 4 | N4 手动修改课程表 | 之前收窄过的需求，方案（覆盖层）已设计好 |
| 5 | N6 Cookie 加密 + 生物识别 | 收益大、风险最高，留足回退空间 |
| 6 | N5 地图定位导航 | 独立栈、难度最高，最后单独攻 |

### 零散待办（可随手修）

- **进座位页不自动触发静默重登**：目前只有点「授权座位系统」才会尝试续期，
  进页面本身不触发（2026-09-22 真机验证时发现）。可在页面 `LaunchedEffect` 里补一次
  `ensureSeatlibSession()` 的静默尝试。
- 服务端配置 `seatcancel`（预约开始 X 分钟后不可取消）的具体值仍未拿到，规则弹窗里不写死数字。

---

## 五、需要用户提供 / 决策的信息

1. **新 DDL 平台的网址**（以及你平时从哪看作业截止时间：网页 / 小程序 / App？）
2. 通知提醒的默认策略：上课提前几分钟、DDL 提前多久（先按 10 分钟 / 1 天实现，可后续在设置里调）
3. 校园卡功能期望看到什么：余额 / 消费明细 / 充值入口（充值需跳转，通常只能 WebView）
