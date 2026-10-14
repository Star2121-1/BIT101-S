# BIT101-S 开发记录与环境

> 这份文档现在只放两样东西：**① 本机特有、重复踩就会浪费半天的环境怪癖**（最值钱的部分）
> **② 历史排障的索引**（压成一张表，需要时顺藤摸瓜）。
> 逐版本的改动记录不在这里 —— 见 [CHANGES.md](CHANGES.md)。

## 背景与项目位置

在 BIT101-Android（上游，只读参照）之上做的独立演进版：座位预约、桌面小组件、提醒中心、
NFC 校园卡、校园服务、自习推荐等。**上游现不再 merge**，理由见 [docs/grid-ui-plan.md](docs/grid-ui-plan.md)。

```
F:\Agent_Work\BIT-102\BIT101-seat\        # 本仓库（origin: github.com/Star2121-1/BIT101-S）
F:\Agent_Work\BIT-102\BIT101-Android\     # 上游快照（只读，未修改）
```

---

## 环境怪癖（本机特有，**构建/调试前先扫一眼**）

### 构建

- **构建一律免沙箱 + 前台跑**：`./gradlew --console=plain :app:assembleRelease`；
  成败只看 stdout 里的 `BUILD SUCCESSFUL`（release 约 1~6 分钟；超时会被自动转后台，别重复起任务）。
  长命令**必须后台跑 + 日志落盘**（后台任务的输出记录会消失）。
- **依赖下载慢到卡死**：直连 Maven Central 的大 jar（如 `kotlin-gradle-plugin`）实测只有 ~14 KB/s，
  构建会挂在 `Downloading ...` 几十分钟。`settings.gradle` 已**前置阿里云镜像**
  （`maven.aliyun.com/repository/{public,google,gradle-plugin}`，实测 ~1.8 MB/s）。
- **代理是负担**：`~/.gradle/gradle.properties` 里的 Clash 代理当前**注释掉了** ——
  代理没开时配着它会让依赖解析全失败（报 `Plugin com.android.application was not found`，极具误导性）；
  而代理开着时直连反而更快，大文件走代理会断流。
- **Gradle「负面缓存」**：网络失败期间的 "not found" 会记进 `caches/modules-2/metadata-*`，
  网络恢复后仍报 `Could not find <库>`（URL 明明 200）→ 把 `metadata-*` **整个 `mv` 移开**重建；
  **不要用 `--refresh-dependencies`**（会让插件解析再次失败）。
- **缓存目录损坏（已出现 10+ 次）**：`caches/<gradle 版本>/{groovy-dsl,transforms}` 的 `metadata.bin`
  偶发丢失 → **两个目录一起移开** + `./gradlew --stop` 后重建。⚠️ 只对单个模块跑 `--offline`
  不够：**必须联网跑一次全量**（debug 变体缺 `androidx.test:*` 之类会连不上）；
  **沙箱内联网即可，不必免沙箱**。`transforms` 重建耗时较长属正常。
- **`rm -rf` 会静默失败**：本机对大目录/被占用目录是 fail-closed（stderr 有 `[SAFE_DELETE_FAIL_CLOSED]`，
  **rc=0 但文件原封不动**）→ 要删就先 `mv` 改名移开。
- **被沙箱创建的文件带限制性 ACL**（之后写它会拒绝访问）→
  `icacls 'F://Agent_Work//BIT-102//BIT101-seat' /reset /T /C /Q`（秒级，别删 build 目录重编）。
- **写盘偶发被吞**：`.workbuddy` 下新建文件、gradle `metadata.bin`、git refs 都被吞过 →
  关键产物写后**复核存在**。

### 设备与调试

- **厂商压制 logcat**：ZTE/nubia（本机真机 NP05J）会把第三方 App 的 `Log.d/i` 全吞掉 ——
  功能在跑但一条日志都没有。**调试走界面**：把诊断信息渲染成界面文本（或用
  `getExternalFilesDir(null)/x`，adb 可读 `/sdcard/Android/data/<pkg>/files/`）。
- **换包必须 `assembleRelease`**：debug 包 `install -r` 会回 `Success` 却**静默回滚** ⇒
  判据是 `dumpsys package` 的 `versionName/versionCode` 或 `pm path` 的 mtime/size（**别信 Success**）。
- **Cygwin 路径转换**：`adb shell cat /sdcard/x.xml` **必须加引号**，否则 `/sdcard/...`
  会被当 Windows 路径（报 `cat: C:/Users/.../sdcard/x.xml: No such file`）。
- **启动 Activity 的正确路径**是 `cn.bit101.android.features.MainActivity`
  （不是 `cn.bit101.android.MainActivity`）；用错会被**静默忽略**、停在桌面。
  查法：`adb shell cmd package resolve-activity --brief <包名>`。
- **底部页签的文字中心正好落在手势区**（真机 1504×2400 下 y≈2272）⇒ 点击要用**图标**（y≈2200）；
  且当前所在页签不可点。冷启动会落在**上次停留的页签**，不一定在首页。
- **模拟器经常自然退出**（qemu 静默死、无崩溃日志）→ 端到端验证优先用**真机**。
- **真机插拔频繁**：拔线后 `adb install` 会报 `device 'xxx' not found`；
  APK 照样出、照样提交推送，在回复与记忆里写明「未装（拔线）」即可。
- **JavaExec 探针的中文输出是乱码**（stdout 走 GBK）→ 让探针把页面**写文件**再本地读。

---

## 历史排障索引（已解决，保留备查）

| # | 现象 | 根因 / 结论 | 现在还有用吗 |
|---|---|---|---|
| 1 | 复制项目时 build 产物全被带进来 | `robocopy /XF` 过滤不生效 → 改用 Explicit 排除列表 | 一次性 |
| 2 | `settings.gradle` 丢失、多模块识别不了 | 被 `.gitignore` 规则误过滤 → 手动补回 | 一次性 |
| 3 | Gradle 缓存损坏（反复） | `groovy-dsl`/`transforms` 的 `metadata.bin` 被外部删除 | ✅ 见上方「环境怪癖」 |
| 4 | `project :api` 找不到可配置变体 | `api/` 缺 `build.gradle` → 从上游复制 | 一次性 |
| 5 | `compileJava(17) vs compileKotlin(25)` 不一致 | 顶层 `jvmTarget=17`，本机 JDK 25 被 Kotlin 自动探测覆盖 → `api/build.gradle` 显式指定 JDK 25 工具链 | 环境相关 |
| 6~8 | `Unresolved reference: IOException / dp / TextButton` | 缺 import；另有一次中文字符乱码导致编译失败 | 一次性 |
| 9 | 点「座」立即崩溃（SIG 9） | `SeatViewModel` 缺 `@HiltViewModel` → Hilt 生成不了工厂，反射无参构造失败 | ✅ 有普适性 |
| 10 | 模拟器「连不上 seatlib」 | **结论已推翻**：不是防火墙，而是 **seatlib 服务端 TLS 证书链不完整**（只发叶证书），客户端已加兜底证书 ⇒ **模拟器可用于联调** | ✅ 见 seatlib-contract |
| 11 | 登录后「座」仍显示"需要登录" | 学校 Cookie 与 seatlib phpCAS **两套独立会话**；且 `member` 是**对象不是数组**（`optJSONArray` 恒为 null） | ✅ 有普适性 |
| 12 | 注入的模拟任务状态被推进成终态 | 前台服务在跑会真实轮询 → 做静态 UI 验证前先 `am force-stop` | ✅ 验证技巧 |

第 11 条的两处细节（会话不互通、`member` 是对象）现在也在源码 KDoc 与
[docs/seatlib-contract.md](docs/seatlib-contract.md) 里；本文只留索引。

---

## 当前构建与测试

```bash
./gradlew.bat :app:assembleRelease     # 发布包
./gradlew.bat test                     # 全量单测（debug+release 两变体）
./gradlew.bat :data:testDebugUnitTest  # 单模块
```

单测基线：**924 条 0 失败**（`:test` 跑两变体，日志里为 1848）。分布与真机验证记录见
[README.md](README.md) 的「测试状态」。
