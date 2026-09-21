# 组件相关的 R8 规则。
#
# 换成传统 AppWidgetProvider 后**不需要额外 keep 规则**：
# - BIT101WidgetProvider 由 AndroidManifest 声明，AGP 会自动保留
# - 页签点击走的是显式 Intent（类引用）+ 字符串 action，没有 Class.forName 反射
#
# （之前用 Glance 时必须 keep ActionCallback 子类 —— Glance 用
#  `Class.forName(className).getDeclaredConstructor().newInstance()` 实例化回调，
#  一旦混淆就静默失效。这段历史保留在此，提醒以后别在组件里引入反射按名找类。）

