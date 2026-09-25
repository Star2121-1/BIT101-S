package cn.bit101.android.config.setting.base

/**
 * App 顶层导航路由的**单一来源**。
 *
 * 为什么放在 `:config`：桌面小组件（`features/widget`）要跳转到这些页面，
 * 但它不能依赖 `:features` —— `MainActivity` / `NavDestConfig` 都在那边，
 * 而 `:features` 又反过来依赖 `features/widget`，会成环。
 * 所以路由字符串下沉到这里，`NavDestConfig` 与组件两侧引用同一个常量，
 * 改路由只改一处，不会出现「改了一边、另一边静默失效」。
 */
object AppRoutes {

    /**
     * 登录页（`NavDestConfig.Login.route` 与之一致）。
     *
     * 小组件的「登录」按钮跳这里：学校会话（课程/DDL 数据源）在这里建立。
     * 注意座位系统的登录是另一套（seatlib），入口在座位页自身的门禁里。
     */
    const val LOGIN = "login"

    /**
     * 成绩页（`NavDestConfig.Score.route` 与之一致）。
     *
     * 出分提醒点进来要**直接落到成绩页**（不是 Web 首页）—— 通知由 `features/notify`
     * 发出，它同样够不着 `:features`，所以路由也下沉到这里。
     *
     * ⚠️ 必须是**无参路由**：`MainApp` 的顶层兜底用 `NavDestConfig.fromRoute` 做**精确匹配**
     * （`it.route == route`），参数化路由（如 `web/{url}`）匹配不上，兜底不会触发。
     */
    const val SCORE = "score"
}
