# 代码盘点：我没注意到的功能 + 改进清单

> 时间：2026-09-22　｜　方法：两个探索代理分别通读**上游原版** `BIT101-Android`（main，1.4.0）
> 与**我们的 fork** `BIT101-seat`，关键结论我逐条回源核实（下文标 ✅ 的是我亲自验过的）
> 结论只覆盖「座位 / 组件 / 通知 / 课表 / DDL / 空教室 / 网的成绩查询」之外的**未知部分**

---

## 一、原来还有这些功能（我之前不知道或没细看）

### 1.1 社区板块（「话」）—— 一套完整的校园社区

| 能力 | 说明 | 位置 |
|---|---|---|
| 话廊四流 | 「关注 / 推荐 / 最新 / 最热」四个 Tab + 搜索页（含搜索历史、排序） | `features/gallery/` |
| 发帖 | 标题、正文、多图、**标签（≥2 个，可增删）**、**匿名开关**、**仅自己可见**、创作者声明 | `features/postedit/` |
| 帖子详情 | 点赞、评论与**多级回复**、评论排序（最新/最旧/高赞）、**长按图片保存到相册 `Pictures/BIT101`**、复制/外部打开、修改/删除/举报 | `features/poster/` |
| 举报 | 帖子与评论都可举报，选类型 + 补充说明 | `features/report/` |
| 消息中心 | 「系统 / 点赞 / 评论 / 关注」四分类 + 未读角标 + 分页 | `features/message/` |
| 用户页 | 个人主页、关注/粉丝列表、关注与互粉状态、隐藏用户、本人被关注数/关注数 | `features/user/` |

### 1.2 ⭐ 社区有「课程评价」——**网站里早就有；App 里只有 WebView 可达，没有原生页**

> ⚠️ **2026-09-22 澄清（用户指出后复核）**：社区**确实有课程评价**，
> 我第一版写「App 里零引用」指的是**原生 API 未被调用**，容易被误读成「没有这个功能」。
> 实际状态如下表 —— **功能在（网站端），只是 App 没把它原生做出来**。

✅ 复核证据（抓 `https://bit101.cn` 的前端包 `assets/index-*.js` 得到，非推测）：

| 证据 | 内容 |
|---|---|
| 网站导航标签表 | `{home:"主页", login:"登录", user:"我的", paper:"文章", course:"课程", score:"成绩", schedule:"课表", about:"关于", message:"消息", map:"地图", gallery:"话廊", …}` |
| 路由 | 网站有独立页面 `/course/`、`/score/`、`/schedule/`、`/paper/`、`/subscription/` |
| `/course/` 的依赖里含 **`assets/Rate-*.js`（评分组件）** | → 这就是**课程评价/评分页** |
| 权限 | `/course/` 与 `/score/` 都标了 `meta:{login:!0}` = **需要登录** |

也就是说：**在 App 里通过「网」页（WebView 打开 bit101.cn）能看到课程评价** ——
这应该就是你的印象来源。而 App **没有原生实现**：`features/` 与 `data/` 里
`CourseDetail`、`rate`、`commentNum` 这些字段**零引用**。

而 API 层早已备好（`api/.../bit101/CoursesApiService.kt`）：

```
GET /courses?search=          课程列表（返回 Course）
GET /courses/{id}             课程详情（返回 CourseDetail）
GET /courses/upload/url       课程资料上传链接
POST /courses/upload/log      上传结果回执
```

`Course` / `CourseDetail` 字段：`rate`（评分）、`commentNum`、`likeNum`、教师号/教师名。
评价正文可用通用接口 `GET /reaction/comments?obj=...`（帖子评论已在用这套）。

⚠️ 另：App 的 bit101 接口主机是 **`android.bit101.cn`**（`Options.kt`），
不是 `bit101.cn` —— 探测接口时别找错地方。

**所以这里的「新东西」不是「从零做课程评价」，而是：把网站已有的课程评价原生搬进 App**
（详见第三节 A1）。

### 1.3 ⭐ 成绩有**原生接口**，我们却一直在用 WebView

✅ 核实：`ScoreApiService`

```
GET /scores?detail=      成绩表（二维数组，第一行是表头，之后每行一门课）
GET /scores/report       可信成绩单 —— 返回**图片链接列表**
```

也就是说：**结构化成绩数据是能拿到的**（不用 WebView 抓页面），
所以「原生成绩页 + 加权平均分 / 学分绩点 / 学期趋势」在技术上完全可行；
`/scores/report` 还能拿学校盖章的成绩单图片。

（⚠️ 与你的约定不冲突：**组件仍然不显示成绩**，只做 App 内按需查看。）

### 1.4 其它之前没留意的能力

| 能力 | 说明 |
|---|---|
| **页面自定义** | 设置里可**拖拽排序底栏、勾选显隐、指定主页**（「我的」不可隐藏） |
| **外观** | 动态取色（Android 12+）、暗黑三选（跟随/亮/暗）、自动旋转 |
| **版本更新** | 启动检查更新、更新说明、**忽略该版本**、**强制更新**（`features/versions/`） |
| **日志导出** | debug 版可把 logcat 导出成文件并系统分享（`setting/utils/LogExporter.kt`）—— 以后真机排障很有用 |
| **地图** | 自绘校园图（OSM 瓦片 + 10MB/7 天 OkHttp 缓存），右下 FAB 是「乡」（良乡）/「村」（中关村）快捷定位。**没有定位蓝点与导航**（上游计划里有，未做） |
| **WebVPN 验证** | `POST /user/webvpn_verify_init`、`/user/webvpn_verify`（校外访问网关校验） |
| **社区课程资料上传** | `/courses/upload/url` + 上传回执（可把课件传进社区） |
| **管理端接口** | `/manage/reports`、`/manage/bans`（管理举报与封禁，App 里只用了举报提交） |
| **开源声明生成** | license 插件生成 `assets/open_source_licenses.txt`，关于页可看 |

### 1.5 上游「有计划但没做」的（README 原文）

手动添加日程 ✅（已做）、**手动修改课程表** ❌、桌面组件 ✅（我们做了）、
地图定位与导航 ❌、生物识别 ❌、NFC 刷校园卡登录 ❌（不建议做）、
**Cookie 加密存储** ❌（上游明确「有意搁置」）。

---

## 二、可以改进的地方

### 2.1 顺手能修的小毛病（低风险，收益立竿见影）

| # | 问题 | 证据 |
|---|---|---|
| 1 | **未读角标为 0 时仍显示「0」** —— 应隐藏 `Badge` | ✅ `features/user/.../UserScreen.kt:186`，`Badge { Text(count.toString()) }` 无 `if (count > 0)` |
| 2 | **`api` 模块被写死 JDK 25**，其余模块是 17 —— 换机器/CI 必炸 | ✅ `api/build.gradle`：`JavaVersion.VERSION_25` + `toolchain(25)`；其余用 `versions.jvmTarget`（**这是我们早期为绕开本机环境埋的**，该还债） |
| 3 | **作息表默认值两处定义靠人工同步** —— `SettingDataStore` 的默认串 与 `config/.../TimeTableLogic.kt` 的 `FALLBACK_TIME_TABLE` | ✅ 两处内容一致但分开维护 |
| 4 | **消息默认停在「系统」Tab** —— 用户更关心点赞/评论 | ✅ `MessageViewModel.kt:31` `MessageType.SYSTEM` |
| 5 | **版本判定命名混乱**：`versionNumber` / `versionCode` / `minVersionCode` 混用，易误判强制更新 | `features/versions/UpdateDialog.kt`（多处）；另有只在 `versionCode==4` 才弹的 `VersionDialog`（`VersionDialog.kt`）已是**死代码** |
| 6 | **底栏「座」用的人像图标**，与座位语义不符 | `IndexViewModel` 给 Seat 页配的 icon |
| 7 | `docs/widget.md` 里仍写 `WidgetLogic.FALLBACK_TIME_TABLE` —— 已改为转发，路径描述过期 | 文档 |
| 8 | 未读计数一次刷新打两次请求 | `MessageViewModel.refreshMessages` → `reloadUnreadCounts` |

### 2.2 时区隐患（现在不出事，出事很难查）

- ✅ `features/common/.../DateTimeUtils.kt` 用 **UTC+8 硬偏移**算「x 分钟前」
- ✅ `features/schedule/.../DDLScheduleEditDialog.kt` 用 **`ZoneOffset.UTC`** 换算日期选择器

两个地方口径不同，且都假设了东八区。你在 +8 不会踩到，但**手机时区换到别处
（出国、手动改时区）就会出现一天偏差**。建议统一用 `ZoneId.systemDefault()` 并加单测。

### 2.3 重复实现（同一逻辑两套）

- 话廊「隐藏用户/机器人」过滤：`GalleryIndexViewModel` 与 `setting` 里的 `GalleryViewModel` 各写一份，用哨兵 `-1` 表匿名
- 登录态两套（BIT101 与 seatlib），登出清理分家（我们已在 `DefaultLoginStatus.clear()` 打过补丁）
- 作息表（见 2.1 第 3 条）

### 2.4 测试覆盖极不均

有真单测的只有：`data`（3）、`features/seat`（多家）、`features/notify`（23）、`features/widget`（54）。
**message / user / gallery / poster / report / postedit / map / login / versions / theme / setting / schedule / web 全是空模板。**
其中**最该补**的是：

- `versions` 的版本比较（判错会导致误强制更新，直接影响所有用户）
- `schedule` / `ddl` 的剩余时间与日期换算（与时区隐患耦合）
- `message` 的未读统计与分页

⚠️ 另：`data` 模块 **27 条 `DefaultLoginRepoTest` 长期失败**（MockK 桩返回 null → NPE），
文件自 2026-08-28 未动过 —— 属于既有问题，不是我们改坏的。

### 2.5 上游自述、仍未解决的三件事

1. **Cookie 用未加密 SharedPreferences 存**（装的是学校统一身份认证会话）——上游明确「有意搁置」，谁也指望不上，只能我们自己做（`ROADMAP` 里的 `feature/security`）
2. **release 未开混淆**（`minifyEnabled=false`，反射库冲突未解）→ 包体与反编译保护都吃亏
3. **WebView 恢复只回到页面/位置，输入内容丢失**（上游 README 自述）

---

## 三、我的想法：接下来做什么

### 优先级 A —— 用现成数据做高价值功能（不需要新登录、不碰外部依赖）

1. **课程评价：把它从「网站」搬进 App（原生）** ⭐⭐
   现状是「网站有、App 只能开 WebView 看」。原生化的收益：
   - 课表里点课程 → **直接看到这门课的社区评分与评价**（现在是 WebView 里翻网站，还要自己搜课名）
   - 与本地课表打通：自动匹配「我这学期选的课」的评价，**按分数排序看哪几门值得选**
   - 支持点赞/评论（复用 `ReactionApiService`），可搜索（`/courses?search=`）
   工作量主要在 UI；数据与鉴权都现成。
2. **成绩增强（不只是「原生成绩页」）** ⭐⭐
   `/scores` 已经是结构化数据，WebView 只是把它画出来。真正的新价值在于**算与提醒**：
   - **出分提醒**（接刚做好的通知中心）⭐ —— 定时/打开 App 时拉一次 `/scores`，
     与上次快照比对，**有新课出分就推送**「操作系统出分了」。
     （隐私：可在设置里选「通知里显示分数 / 只说科目名」；组件不碰成绩）
   - **绩点与加权**：加权平均分、平均学分绩点、按学期趋势、单科明细
   - **保研/预警视角**：按目标绩点反算「剩余课程需要多少分」
   - **成绩单导出**：`/scores/report` 的盖章成绩单图片可保存相册/分享
   - **与课表联动**：本学期哪些课还没出分；课表课程详情里显示该课已出分

### 优先级 B —— 把我们做过的东西收尾

3. **通知设置页**（数据层已就绪，只差 UI）+ 座位签到 / 暂离提醒
4. **第 2.1 节的 8 个小毛病**（一次提交清掉，成本很低）
5. **进座位页自动静默重登**（2026-09-22 真机发现的缺口）

### 优先级 C —— 工程健康度

6. **测试补齐**：versions 版本比较、schedule/ddl 时间换算、message 未读统计
7. **时区统一**（2.2）
8. **Cookie 加密**（`feature/security`，风险最高，放最后，且必须做迁移）

### 暂不建议

- **地图定位/导航**：瓦片依赖作者自建代理，稳定性未知；导航还得自己算路线，投入产出比低
- **NFC 刷校园卡登录**：上游自己标了「可行性未验证」，实际上学校认证要的是学号+密码，卡号换不来会话（详见 `docs/upstream-feature-plan.md` 3.1）

---

## 四、之前筹划好、还没做的（截至 2026-09-22）

| 项 | 状态 | 卡点 |
|---|---|---|
| **DDL 换源**（乐学 → 新平台） | 调研完成、未动代码 | `docs/ddl-source-contract.md` 已写明：认证与接口都摸通了，**只差「作业类活动的 `type` 取值」** —— 需等你某门课真的布置作业，再抓一次即可定死 |
| 校园卡 / 消费查询 | 未开始 | 需先探一卡通接口是否可达（可能要 WebView 兜底） |
| 手动修改课程表（overlay 方案） | 未开始 | 方案已设计（`docs/upstream-feature-plan.md` 4.2），需 DB v2→v3 迁移 |
| 通知设置页 | 数据层就绪、UI 未做 | 无 |
| 座位签到 / 暂离提醒 | 未开始 | 需座位登录态（你的座位会话目前是失效状态） |
| 进座位页自动静默重登 | 未开始 | 2026-09-22 真机发现；补一个 `LaunchedEffect` 即可 |
| 8 个顺手小毛病（2.1 节） | 未开始 | 无，成本很低 |
| 测试补齐 / 时区统一 | 未开始 | 无 |
| Cookie 加密（`feature/security`） | 未开始 | 风险最高，须做迁移，建议放最后 |
| 地图定位导航 / NFC 登录 | **建议不做** | 见 2.5 与 `upstream-feature-plan.md` 3.1 |

## 五、想问你的

1. **课程评价原生化**要不要做？（网站已有，做的是「搬进 App + 与课表联动」）
2. **成绩这块你想要的「新东西」**，我上面列了 5 条，其中**「出分提醒」**是我最看好的
   —— 如果同意，我想先确认：通知里**要不要直接写分数**（隐私取舍）？
3. 之前那批筹划（上面那张表），**要不要我把「通知设置页 + 8 个小毛病 + 进座位页自动重登」
   打包成一个小版本先清掉**？这三件成本低、见效快，清完再上大功能。
