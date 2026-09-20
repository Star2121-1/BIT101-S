# BIT101-S 开发记录

## 背景

将 BIT 座位预约功能集成进 BIT101-Android，复用已登录的學校会话，无需单独登录。

## 项目位置

```
F:\Agent_Work\BIT-102\BIT101-seat\        # 新仓库（独立开发）
F:\Agent_Work\BIT-102\BIT101-Android\     # 原项目（未修改）
GitHub: https://github.com/Star2121-1/BIT101-S
```

---

## 遇到的问题与解决方案

### 1. robocopy 过滤规则问题

**问题**：复制原项目到新目录时，`/XF` 参数无法正确过滤文件，导致大量 build 产物被复制进来。

**解决**：改用 PowerShell 脚本配合 robocopy，手动指定排除列表，只复制源文件。

```powershell
robocopy "源" "目标" /E /XF "*.gradle" "*.iml" /XD build .gradle .idea build\kotlin ...
```

---

### 2. settings.gradle 丢失

**问题**：复制过程中 `settings.gradle` 被 .gitignore 规则意外过滤掉了，导致 Gradle 无法识别多模块结构。

**解决**：从原项目手动复制 `settings.gradle`，并在其中新增 `include ':features:seat'`。

---

### 3. Gradle 缓存损坏（反复出现）

**问题**：首次编译时报错：
```
Could not read workspace metadata from .../groovy-dsl/.../metadata.bin
Could not read workspace metadata from .../transforms/.../metadata.bin
```
清除单个缓存目录后，下一个目录又报同样错误，形成连锁反应。

**解决**：一次性删除整个 `.gradle/caches` 目录：
```powershell
Remove-Item -LiteralPath 'C:\Users\asus\.gradle' -Recurse -Force
```

---

### 4. api 模块缺少 build.gradle

**问题**：`api/` 目录下只有源码，没有 `build.gradle`，导致 `project :api` 找不到可配置的变体。

**解决**：从原项目复制 `api/build.gradle`。

---

### 5. api 模块 JVM 版本不一致

**问题**：
```
Inconsistent JVM Target Compatibility Between Java and Kotlin Tasks
  compileJava (17) vs compileKotlin (25)
```
根因是原项目的 `build.gradle` 顶层 `versions` 中 `jvmTarget` 设为 17，但本机安装的是 JDK 25，Kotlin 编译器自动检测到 25 并覆盖。

**解决**：在 `api/build.gradle` 中显式指定 Kotlin 工具链使用 JDK 25：
```gradle
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
```

---

### 6. SeatApi.kt 缺少 IOException import

**问题**：
```
Unresolved reference 'IOException'
```
SeatApi.kt 中多处 `throw IOException(...)` 但忘记 import。

**解决**：添加 `import java.io.IOException`。

---

### 7. ModeSelector.kt 缺少 dp import

**问题**：
```
Unresolved reference 'dp'
```

**解决**：添加 `import androidx.compose.ui.unit.dp`。

---

### 8. NewTaskScreen.kt 缺少 TextButton import 和中文字符编码问题

**问题**：
```
Unresolved reference 'TextButton'
Unresolved reference '鍗?'   （中文字符乱码导致编译失败）
```

**解决**：
- 添加 `import androidx.compose.material3.TextButton`
- 将 dialog 文本中的弯引号 `"卷"` 改为普通文本 `卷`，避免编码问题

---

## 成果

- 编译成功：`BUILD SUCCESSFUL in 7m 30s`，686 个任务全部通过
- APK 生成：`app-debug.apk` (41MB)
- GitHub 仓库已推送，包含完整代码和 commit 记录
- 核心功能：座位树查询、图形化座位选择、单次/监控/优先三种预约模式、任务列表管理

---

## 待解决（不在本次范围）

> 本节的原始条目多数已在 2026-09-17 的 M1–M3 中处理，保留原文以便对照，处理结果见行内标注。

- 登录流程：`SeatSession` 复用 BIT101 学校会话 Cookie，需先在全局登录页完成统一身份认证
  - ✅ 已修正认知：学校 Cookie 与 seatlib 的 phpCAS 是**两套独立会话**，Cookie 不互通。
    实际方案是 App 内 WebView 完成 CAS 后取 ticket 换 JWT，见 `README.md` 的登录流程一节
- MonitorWorker 后台保活：当前任务在进程被杀后丢失，后续可用 WorkManager 替代
  - ✅ 已用**前台服务**（`SeatMonitorService`）替代，未采用 WorkManager ——
    WorkManager 是为可延迟任务设计的，Doze / App Standby 会把执行推迟到分钟级，秒级抢座会失效
- Token 过期自动登出：已实现 401 → 清除 session 逻辑，需联调验证
  - ✅ 代码路径已加固（401 一律识别为 `TOKEN_EXPIRED`、token 变化可被 UI 观察、
    服务侧失效时终止在跑任务并提示重新登录）
- ✅ **真机联调已完成首轮**（2026-09-18/19）：预约 → 取消全链路在真机跑通，
  全程零崩溃。剩余待验证项见 `ROADMAP.md` 的「真机验证清单」

---

## 模拟器调试问题与修复

### 9. SeatViewModel Hilt 工厂缺失导致崩溃

**现象**：点击底部"座"按钮后 App 立即崩溃（SIG 9 Killed），UCrash 日志上传到 umeng.com。

**根因**：通过提取 crash log 文件发现：
```
java.lang.RuntimeException: Cannot create an instance of class cn.bit101.android.features.seat.SeatViewModel
Caused by: java.lang.NoSuchMethodException: cn.bit101.android.features.seat.SeatViewModel.<init> []
```
`SeatViewModel` 构造函数依赖 `LoginStatus` 参数，但缺少 `@HiltViewModel` 注解，Hilt 无法生成 ViewModel 工厂，最终走反射无参构造失败。

**解决**：
- 添加 `@HiltViewModel` 注解到 `SeatViewModel`
- 移除不兼容的 `@ViewModelScoped` 注解（`@HiltViewModel` 内部已处理生命周期）
- 在 `SeatScreen.kt` 中 `hiltViewModel()` 即可正确获取依赖注入的 ViewModel

```kotlin
// SeatViewModel.kt
@HiltViewModel  // ← 添加此注解
class SeatViewModel @Inject constructor(
    private val loginStatus: LoginStatus
) : ViewModel() { ... }
```

### 10. 模拟器无网络但崩溃日志成功上传

**现象**：模拟器 ping 8.8.8.8 不通，但 UCrash 仍成功上传 crash log 到 umeng.com。

**分析**：模拟器可以访问部分互联网（DNS 解析成功，HTTPS 连接正常），seatlib.bit.edu.cn 等 BIT 内网地址可通过模拟器网络访问。崩溃本身不是网络问题，而是 Hilt 依赖注入缺失。

**结果**：修复后 Seat 页面正常显示，包含预约类型选择、日期、校区/楼层/区域级联下拉、选择座位按钮等完整 UI。

---

## 测试结果（更新 2026-09-18）

- 构建：BUILD SUCCESSFUL
- 真机：PDNP05J000120402（小米手机，Android 16，school WiFi）
- API 直连 seatlib.bit.edu.cn 在真机上完全正常（HTTP 200）
- Seat 页面 UI 正常：日期选择、校区下拉、楼层下拉、预约模式选择均工作
- getSeatTree 返回 34 个节点（徐特立馆+中关村馆完整树）
- 模拟器：~~API 无法访问（学校防火墙封锁 TCP 443 到 10.0.0.0/8）~~
  → 该结论已推翻，模拟器可正常访问 seatlib，见下方「已知问题」

---

## 已知问题

### 模拟器网络问题

> ⚠️ **本节结论已被推翻（2026-09-18 实测）**，保留原文仅作对照：
> 模拟器 `Pixel_6_API_34` 到 `seatlib.bit.edu.cn`（10.0.11.162:443）**TCP 建连成功**，
> DNS 也能正确解析；对照组（关闭端口、不可达 IP）均超时，结论可信。
> **模拟器可以用于联调。** 早期「连不上」的真相是
> **seatlib 服务端 TLS 证书链不完整**（只发叶证书），客户端已加证书兜底，与防火墙无关。

学校防火墙封锁了所有到内网 IP（10.0.0.0/8）的 TCP 443 端口。尝试过：
- 路由器静态路由：未生效（Windows 防火墙优先）
- Node.js HTTPS 代理（port 8443）：握手失败（TLS alert）

**结论**：~~模拟器无法用于 seat 功能调试，必须使用真机 + school WiFi。~~
→ 已推翻，见上方说明。

### 构建环境的其他坑（2026-09-20 补记）

- **shell 是 Cygwin**（不是 Git Bash）：盘符前缀必须用 `/cygdrive/c/...`；
  Android SDK 在 `/cygdrive/c/Users/asus/AppData/Local/Android/Sdk/`。
  `cmd //c` 不会执行、`taskkill //F` 报「无效参数」—— 杀进程请走 PowerShell
  （`Stop-Process -Name qemu-system-x86_64 -Force`）。
- **Gradle 缓存**：`C:\Users\asus\.gradle` 是 Cygwin 的映射盲区（bash 看不到），
  清理缓存必须用 Windows 原生工具（PowerShell `Remove-Item`）。
- **模拟器会「卡死成 offline」**：`qemu-system-x86_64` 进程还在，但 `adb shell`
  全部挂起（`timeout 20 adb shell echo hi` 直接超时）。`adb kill-server` /
  `adb reconnect offline` 都救不回来，**只能强杀后用 `-no-snapshot-load` 重启**。
- **启动 Activity 的正确路径**是 `cn.bit101.android.features.MainActivity`
  （不是 `cn.bit101.android.MainActivity`）；用错会被静默忽略、停在桌面。
  查法：`adb shell cmd package resolve-activity --brief <包名>`。
- 应用会把种子数据覆写：注入任务后若前台服务在跑，`RUNNING` 会被真实轮询推进成终态。
  需要静态 UI 验证时先 `am force-stop` 再注入。

---

## 自动登录修复（2026-08-29）

### 问题：登录后 Seat 显示"需要登录"

**现象**：用户已在 BIT101 登录（有学号密码），进入 Seat 页面点击"选择座位"或"列表"时弹出"需要登录"提示。

**根因分析**：
1. `seatApi.token` 判断登录状态 — 但 seatlib 是独立的 phpCAS 系统，BIT101 的学校 Cookie 无法自动建立 seatlib session
2. `authenticateSeatlib()` 静默认证失败（cookie 不互通）
3. 登录判断用 `seatApi.token.isNotEmpty()` 为 false，UI 显示"需要登录"

**解决方案**：
1. **监听 `LoginStatus.status`**：BIT101 登录后自动触发 seatlib 认证
2. **三级认证策略**：
   - 先尝试 `authenticateSeatlib()`（cookie 静默认证，成功则无需密码）
   - 失败则尝试 `seatSession.login(sid, password)`（完整 CAS 登录，使用 BIT101 存储的学号密码）
3. **修复 `SeatSession.kt` 的 JSON 解析**：API 返回 `member` 是单个对象而非数组，原代码用 `optJSONArray` 始终为 null
4. **`isLoggedIn` 改为 `StateFlow`**：通过 `seatApi.token.isNotEmpty()` 驱动 UI 响应

```kotlin
// SeatSession.kt — 修复前（错误）
val memberArr = json.optJSONArray("member")  // → null，member 是对象不是数组

// SeatSession.kt — 修复后（正确）
val member = json.optJSONObject("member")    // → 正确获取 token
if (member != null && !member.isNull("token")) {
    val token = member.optString("token", "")
    ...
}
```

**结果**：
- 用户首次登录 BIT101 后进入 Seat 页面，自动完成 seatlib CAS 认证，无需手动输入密码
- `isLoggedIn` 在 token 设置后立即更新，所有 UI 正确响应
- 已推送至 GitHub: `9a741f4`
