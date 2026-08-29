# BIT101-S

基于 [BIT101-Android](https://github.com/BIT101-dev/BIT101-Android) 扩展的座位预约功能模块。

---

## 与原项目不同点

| 维度 | BIT101-Android | BIT101-S（本仓库） |
|------|---------------|-------------------|
| 新增 Tab | 5个：卷、图、网、话、我 | +1个：**座**（座位预约） |
| 登录方式 | BIT-Login SDK 登录后自动持有学校 Cookie | **复用同一份学校 Cookie**，无需再次输入密码 |
| 座位 API 认证 | 无 | OkHttp + Bearer JWT，JWT 来自 seatlib CAS 认证 |
| 独立登录 | 无 | `SeatSession` 提供备用登录路径（CAS → JWT），用于测试或离线场景 |

---

## 使用方式

1. 打开 APP，进入 **"卷"** 页面，使用学号+密码登录 BIT101（完成学校统一身份认证）
2. 切换到底部 **"座"** 标签页
3. 进入后可进行以下操作：

| 页面 | 功能 |
|------|------|
| **预约** | 选择校区→楼层→区域、日期，查看座位图，点击空位预约 |
| **列表** | 查看当前所有预约任务状态 |

### 三种预约模式

| 模式 | 说明 |
|------|------|
| 单次预约 | 指定座位号，立即查询并尝试预约 |
| 监控预约 | 定时轮询目标座位，空出后自动预约 |
| 优先预约 | 定时轮询区域内所有空闲座位，优先抢最早可用的 |

---

## 原理

### 登录态共享（方案 A）

```
BIT101 登录
    ↓
BIT-Login SDK → 写入 LoginStatus.cookieManager (CookieManager)
    ↓
SeatApi 注入同一个 cookieManager
    ↓
访问 seatlib.bit.edu.cn 时自动携带学校 Cookie
    ↓
POST /api/cas/user 换取 JWT → Authorization: bearer{token}
```

座位 API 不需要独立登录，BIT101 登录成功后学校会话已存在，直接复用即可。

### CAS 备用登录（SeatSession）

当学校 Cookie 不存在或失效时，`SeatSession` 提供完整 CAS 登录流程：
1. GET `sso.bit.edu.cn/cas/login` → 解析 HTML 提取 `salt` 和 `execution`
2. AES/ECB 加密密码（key = Base64(salt)）
3. POST 表单完成认证，CAS 返回 ticket URL
4. 跟随重定向获取 phpCAS 内部 code
5. POST `seatlib.bit.edu.cn/api/cas/user` 换取 JWT

### 座位数据结构

```
API: seatlib.bit.edu.cn/api/Seat/tree   → 校区/楼层/区域三级树（type=1 为区域）
API: seatlib.bit.edu.cn/api/Seat/date   → 可用时间段
API: seatlib.bit.edu.cn/api/Seat/seat   → 座位列表（含状态）
API: seatlib.bit.edu.cn/api/Seat/confirm → 确认预约
API: seatlib.bit.edu.cn/api/Space/cancel → 取消预约
```

---

## 模块结构

```
features/seat/
├── api/
│   ├── SeatApi.kt        # OkHttp 封装，共享 school cookies
│   └── SeatSession.kt    # CAS 登录流程（备用）
├── model/
│   ├── Seat.kt           # 座位、时段数据
│   ├── Task.kt           # 预约任务（模式/状态）
│   └── Area.kt           # 座位树节点
├── ui/
│   ├── component/        # SeatGrid、CascadingDropdown、ModeSelector、ErrorCard
│   └── screen/           # SeatMapScreen、NewTaskScreen、TaskListScreen
├── SeatViewModel.kt      # MVVM 状态管理（Hilt 注入）
└── SeatScreen.kt         # 入口，内部 NavHost 管理三页导航
```

---

## 构建

```bash
cd F:\Agent_Work\BIT-102\BIT101-seat
./gradlew assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

---

## 测试状态

| 项目 | 状态 |
|------|------|
| Gradle 编译 | ✅ 通过 |
| Hilt 依赖注入 | ✅ 修复（`@HiltViewModel`） |
| 座位页面 UI 渲染 | ✅ 正常（模拟器 Pixel_6_API_34） |
| Seat 按钮点击响应 | ✅ 正常 |
| 真实座位数据加载 | ⏳ 需网络连接到 seatlib.bit.edu.cn |
| 登录态复用 | ⏳ 需先在全局登录页完成 BIT101 登录 |

### 已知问题

- 模拟器无 WiFi 时点击 Seat 按钮会触发网络请求超时，但不再崩溃（之前因 Hilt 工厂缺失崩溃）
- 真实预约需在学校网络或通过 VPN 连接到 seatlib.bit.edu.cn
