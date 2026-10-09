package cn.bit101.android.features.nfc

import android.content.Context

/**
 * 「贴卡即用」两个设置项的本机读写：开不开、贴了去哪个页面。
 *
 * 与 [SavedCardStore] 用同一套写法（`SharedPreferences` + 只存小字符串），
 * 理由也一样：本模块不引 Hilt 注入链，这几个值也用不上 DataStore 的 Flow。
 * 但**独立成一个 prefs 文件** —— 混在一起的话，「忘掉这张卡」会把用户的开关一起清掉。
 */
internal object NfcTapStore {

    private const val PREFS = "bit101_nfc_tap"

    /** 开关。⚠️ 默认**关**：这是要常驻监听的，不经用户同意不该默认耗电。 */
    private const val KEY_ENABLED = "enabled"

    private const val KEY_ROUTE = "route"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_ENABLED, false) }.getOrDefault(false)

    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching { prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply() }
    }

    /** 用户选的目标页 route；没选过是 `null`（由 `NfcTapLogic.target` 退回默认页）。 */
    fun targetRoute(context: Context): String? =
        runCatching { prefs(context).getString(KEY_ROUTE, null) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    fun setTargetRoute(context: Context, route: String) {
        runCatching { prefs(context).edit().putString(KEY_ROUTE, route).apply() }
    }
}
