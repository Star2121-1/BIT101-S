package cn.bit101.android.data.school

/**
 * 校园网/一卡通抓取的**最近一次诊断信息**（单例暂存，详情页直接显示）。
 *
 * 为什么不用 logcat：ZTE 等厂商会压制第三方应用的 debug/i 级日志，
 * adb 看不到 —— 放 UI 里才是可靠的观测通道。稳定后可整体移除。
 */
object CampusDebugStore {

    /** 校园网（Srun rad_user_info）最近一次抓取说明。 */
    @Volatile
    var netDebug: String = ""

    /** 一卡通（dkykt 首页）最近一次抓取说明。 */
    @Volatile
    var cardDebug: String = ""
}
