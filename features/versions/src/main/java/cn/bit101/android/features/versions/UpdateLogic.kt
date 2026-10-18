package cn.bit101.android.features.versions

/**
 * 更新提示判定的纯逻辑（可单测）。对应「更新通道：降级提示」缺陷修复
 * （v1.12.0-alpha.12 / versionCode 1013）。
 *
 * 两个分支的语义（⚠️ 勿混——它们回答的是不同问题）：
 * - **强制更新**：`装机号 < minVersionCode` 时一律提示。
 *   `min_version_code` 是服务端宣布的「**最低可运行版本**」——装机号低于它，
 *   意味着客户端对学校接口/协议已不可用，必须更新。此分支不看服务端最新号、
 *   也不看用户忽略值（回答「能不能用」）。
 * - **常规提示**：仅当「服务端最新号 > 装机号」**且**「服务端最新号 > 已忽略值」
 *   时提示。前者=确实有比本机新的版本，后者=用户没有忽略到该号或更新的号
 *   （回答「有没有新的」）。
 *
 * ⚠️ 历史缺陷（本对象修复的）：原显示判据只有 `服务端号 > 已忽略值`
 * （已忽略值默认 -1L，恒真），**从未与服务端号/装机号比较** ⇒ 服务端数据陈旧
 * （如服务端号 7、用户装机 91/1011）时，从未点过「忽略」的用户会看到
 * 「更新到 1.4.1」的降级提示（url 的 http 还会被强制改写为 https，大概率 404）。
 * 完整真值表见 [UpdateLogicTest]。
 */
internal object UpdateLogic {

    /** 强制更新：装机号低于服务端最低可运行版本。 */
    fun isForceUpdate(
        installedVersionCode: Long,
        minVersionCode: Long,
    ): Boolean = installedVersionCode < minVersionCode

    /** 常规提示：服务端确有更新（> 装机号）且用户未忽略到该号及以上。 */
    fun hasRegularUpdate(
        serverVersionCode: Long,
        installedVersionCode: Long,
        ignoredVersionCode: Long,
    ): Boolean = serverVersionCode > installedVersionCode &&
        serverVersionCode > ignoredVersionCode

    /**
     * 完整判据（强更 ∪ 常规）：用于测试与文档；调用点 UI 用上面两个原语
     * （UI 还要叠加「本次会话未关闭」的 show/dismissed 门槛，不在这层）。
     */
    fun shouldPrompt(
        serverVersionCode: Long,
        installedVersionCode: Long,
        minVersionCode: Long,
        ignoredVersionCode: Long,
    ): Boolean = isForceUpdate(installedVersionCode, minVersionCode) ||
        hasRegularUpdate(serverVersionCode, installedVersionCode, ignoredVersionCode)
}
