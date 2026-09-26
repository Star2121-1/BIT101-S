package cn.bit101.api.option

data class ApiUrlOption(
    val bit101Url: String,
    val androidUrl: String,
    val jwmsUrl: String,
    val jwcUrl: String,
    val jxzxehallappUrl: String,
    val lexueUrl: String,
    /**
     * 延河课堂（eclass）。学校 2026 年起用它替代乐学下发作业与学习资料。
     *
     * 校外访问走统一身份认证（Keycloak）而非 WebVPN 隧道，故三个环境同址。
     * 默认值让既有构造点无需改动。
     */
    val eclassUrl: String = "https://zy-eclass.bit.edu.cn",
    /**
     * BIT101 的**认证/代理主机**（网页版 store 里的 `bit_login_url`）。
     *
     * 教务类数据（成绩）由它的 `/api/jwb/...` 转发，与业务 API（[bit101Url]）**不是同一台**：
     * 实测同路径在 bit101Url 上是 404。三个环境同一地址，故给默认值省掉构造点改动。
     */
    val loginBit101Url: String = "https://login.bit101.flwfdd.xyz",
)
