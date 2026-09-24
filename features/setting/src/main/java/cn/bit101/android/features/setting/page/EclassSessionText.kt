package cn.bit101.android.features.setting.page

/** 延河课堂会话状态文案 —— `null`（还没检查完）也要能显示。 */
internal fun eclassSessionText(alive: Boolean?): String = when (alive) {
    null -> "会话状态：检查中…"
    true -> "会话状态：已登录"
    false -> "会话状态：未登录，点右侧去登录"
}
