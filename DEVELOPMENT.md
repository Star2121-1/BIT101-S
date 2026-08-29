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

- 登录流程：SeatSession 复用 BIT101 学校会话 Cookie，需先在全局登录页完成统一身份认证
- MonitorWorker 后台保活：当前任务在进程被杀后丢失，后续可用 WorkManager 替代
- Token 过期自动登出：已实现 401 → 清除 session 逻辑，需联调验证

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

## 测试结果（更新 2026-08-29）

- 构建：BUILD SUCCESSFUL in 55s
- 真机：PDNP05J000120402（小米手机，Android 16，school WiFi）
- API 直连 seatlib.bit.edu.cn 在真机上完全正常（HTTP 200）
- Seat 页面 UI 正常：日期选择、校区下拉、楼层下拉、预约模式选择均工作
- getSeatTree 返回 34 个节点（徐特立馆+中关村馆完整树）
- 模拟器：API 无法访问（学校防火墙封锁 TCP 443 到 10.0.0.0/8），需通过真机调试

---

## 已知问题

### 模拟器网络问题

学校防火墙封锁了所有到内网 IP（10.0.0.0/8）的 TCP 443 端口。尝试过：
- 路由器静态路由：未生效（Windows 防火墙优先）
- Node.js HTTPS 代理（port 8443）：握手失败（TLS alert）

**结论**：模拟器无法用于 seat 功能调试，必须使用真机 + school WiFi。
