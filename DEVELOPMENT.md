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
