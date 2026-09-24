package cn.bit101.android.data.repo

import cn.bit101.android.data.school.CampusNetInfo
import cn.bit101.android.data.school.CampusNetLogic
import cn.bit101.android.data.repo.base.CampusNetRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CampusNetRepo] 的实现：GET `http://10.0.0.55/cgi-bin/rad_user_info`。
 *
 * 经典 Srun 接口，免登录、免 cookie —— 但仅校园网内可达；
 * 校外请求会超时/拒绝 → 捕获后返回 null（UI 提示需校园网环境）。
 */
@Singleton
internal class DefaultCampusNetRepo @Inject constructor() : CampusNetRepo {

    private companion object {
        const val URL = "http://10.0.0.55/cgi-bin/rad_user_info"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchOnlineInfo(): CampusNetInfo? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                okhttp3.Request.Builder().url(URL).build()
            ).execute().use { resp ->
                CampusNetLogic.parse(resp.body?.string().orEmpty())
            }
        }.getOrNull()
    }
}
