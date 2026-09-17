# 本模块对下游（app）生效的混淆规则。
#
# 当前刻意保持为空：座位模块没有依赖反射的入口 ——
#   - Hilt 生成的代码由 Hilt 自身的规则覆盖
#   - ReservationTask 的 JSON 序列化是手写的（org.json），非反射
#   - WebView 未注入 @JavascriptInterface（CAS 登录靠 URL 拦截，不用 JS 桥）
# 若将来引入反射序列化或 JS 桥接，规则应加在这里而不是 proguard-rules.pro ——
# 后者只在本模块自身构建时生效，app 打包时不会应用。
