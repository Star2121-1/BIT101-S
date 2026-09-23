# 手动修改课程表（覆盖层）设计

> 2026-09-23 起实现。数据层在 `data`，UI 在 `features/setting`（页面「手动修改课程表」）。

## 一、为什么不能直接改 `course_schedule`

`CoursesRepo.saveCourses()` 是**全量覆盖**（先 `deleteAllCourses()` 再插入）：

```kotlin
// DefaultCoursesRepo
override suspend fun saveCourses(courses: List<CourseScheduleEntity>) = withContext(Dispatchers.IO) {
    database.coursesDao().deleteAllCourses()
    database.coursesDao().insertCourses(courses)
}
```

也就是说，用户手动改在原始表里的东西，**下一次课表同步就会被冲掉**。
所以原始表保持"教务说什么就是什么"，用户的修改记在单独的 `course_overlay` 表里，
**读取时合并**。

## 二、数据流

```
course_schedule（教务，同步时全量覆盖）──┐
                                        ├─► CourseOverlayLogic.merge() ─► 用户看到的课表
course_overlay（用户的手动修改）────────┘        （App 表格 / 桌面组件 / 上课提醒）
```

合并挂在 `DefaultCoursesRepo.getCoursesFromLocal*()` 的**读路径**上：

- 调用方（App 表格、桌面组件、通知排期）都走这三个方法，
  **谁也不用知道覆盖层的存在**，也就不会有人漏掉合并
- ⚠️ `getCoursesFromLocal(term, week)` **不再用 SQL 的 `weeks LIKE` 过滤**：
  那个过滤作用在原始表上，用户把周次改到本周之后原始行可能压根查不出来 →
  合并时没有基准、修改在那一周凭空消失。现在先取整学期（含合并），再在 Kotlin 侧按周过滤。

## 三、⚠️⚠️ 锚点（anchor）与显示值必须分开

覆盖层要同时回答两个问题：

| 问题 | 字段 |
|---|---|
| 我改的是**哪一节原课**？ | `anchorNumber` / `anchorWeekday` / `anchorStartSection` |
| 改完之后**长什么样**？ | `number` / `weekday` / `startSection` / `name` / `classroom` … |

**第一版把它们合成了一套字段**（自然键 = 课程号 + 星期 + 开始节次），结果
**「改上课时间」根本表达不出来**：改完时间键就变了 → `merge` 匹配不上原课、
修改被静默忽略（同事写 UI 时发现：只能把星期/节次设成只读来绕）。

现在锚点独立：改时间只动显示值，`keyOf(overlay)` 取锚点，匹配照样成立。
写 UI 时**必须**用 `CourseOverlayLogic.withDisplay(...)` 改显示值 ——
它不给锚点留入口，不可能"手滑"改掉。

锚点也**不能用 `course_schedule.id`**：同步是"删光再插"，行 id 每次都变。

## 四、三种意图与优先级

| kind | 语义 | 合并行为 |
|---|---|---|
| `EDIT` | 改这节课 | 用显示值替换原课，**保留原始行 id**（UI 列表 key 稳定） |
| `HIDE` | 隐藏（课退了但教务还在） | 该节课**不出现**；**优先级最高**（先改后藏也算藏） |
| `ADD` | 新增（教务漏了 / 旁听） | 追加到末尾，`id = -overlayId`（**负数 = 本地新增**，UI 据此区分可删/可恢复） |

- 匹配不上的覆盖层（教务把课删了/改了时间）→ 当次**忽略但不删数据**：
  教务可能只是临时改一下，留着下次还能接上
- 认不出的 `kind` → 跳过（脏数据不至于崩）
- `ADD` 的课被再编辑 → `upsert` 会**保持 ADD**（变成 EDIT 会找不到基准而从课表消失）

## 五、写入

只走 `CourseOverlayRepo`（`data/repo/base/CourseOverlayRepo.kt`）：

- `upsert`：按「类型 + 锚点」找已有条目，存在则更新（同一节课只留一条修改）
- `delete(id)`：恢复原样 / 撤销新增

⚠️ 任何时候都**不要**去写 `course_schedule`。

## 六、验证

- `CourseOverlayLogicTest` **15 条**（改/藏/加、优先级、匹配不上、锚点与显示值分离、
  `withDisplay` 不动锚点）
- 数据库 v2 → v3 用 `AutoMigration`（只新增一张表）；schema 已导出 `1/2/3.json`
