package cn.bit101.api.model.http.bit101

// 成绩查询，需要登录
class GetScoreDataModel private constructor() {
    /**
     * 成绩表。
     *
     * ⚠️ 注释说 data 是「二维表（第一行表头，其后每行一门课）」，但原模型声明的是
     * `ArrayList<String>` —— 真实形状**未经验证**（App 里此前从未调用过这个接口，
     * 无法确认服务端返回的是二维数组、字符串数组还是别的）。
     *
     * 因此这里改成通用的 [JsonElement]，解析交给
     * `data/score/ScoreLogic`（对各种形状做防御式处理，解析不出就返回空，
     * **绝不猜列含义**）。等真机抓到真实响应后再收紧类型。
     */
    data class Response(
        val data: com.google.gson.JsonElement? = null,
    )
}