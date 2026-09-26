package cn.bit101.android.features.map

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.bit101.android.config.setting.base.MapSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.api.scrollTo
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.ui.state.MapState
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
internal class MapViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val mapSettings: MapSettings
) : ViewModel() {
    private val TAG = "MapViewModel"

    // 缓存配置
    private val cacheTime = 7 * 24 * 60 * 60
    private val cacheFile = File(context.externalCacheDir, "map_cache")
    private val cacheSize = 10 * 1024 * 1024L
    private val cache = Cache(cacheFile, cacheSize)

    // 地图数据 瓦片服务配置
    private val httpClient = OkHttpClient.Builder()
        .cache(cache)
        .build()
    private val tileStreamProvider = TileStreamProvider { row, col, zoomLvl ->
        val url = "https://map.bit101.flwfdd.xyz/tile/$zoomLvl/$col/$row.png"
        try {
            val request = Request.Builder().url(url)
                .cacheControl(CacheControl.Builder().maxAge(cacheTime, TimeUnit.SECONDS).build())
                .addHeader("User-Agent", "BIT101") //防止被拦截
                .build()
            val response = httpClient.newCall(request).execute()
            response.body?.byteStream()?.buffered()
        } catch (e: Exception) {
            Log.i(TAG, "Get tile error: $e $url")
            null
        }
    }

    // 地图状态
    val state by mutableStateOf(
        MapState(19, 67108864, 67108864).apply {
            addLayer(tileStreamProvider)
            enableRotation()
        })

    // 预定义位置
    data class Position(val x: Double, val y: Double, val scale: Float)

    /**
     * 当前定位到的校区（按钮据此高亮）。
     *
     * 读的是快照状态，所以 Compose 里直接读它就会跟着重组。
     */
    private val currentCampusState = mutableStateOf(MapCampus.ALL.first())
    val currentCampus: MapCampus get() = currentCampusState.value

    /** 切到某个校区：记下当前校区 + 把地图滚过去。 */
    fun goTo(campus: MapCampus) {
        currentCampusState.value = campus
        scrollTo(Position(campus.x, campus.y, campus.scale))
    }

    fun scrollTo(position: Position) {
        viewModelScope.launch {
            state.scrollTo(position.x, position.y, position.scale)
        }
    }

    // 地图缩放倍率
    val mapScaleFlow = mapSettings.scale.flow
    fun setMapScale(scale: Float) {
        viewModelScope.launch {
            mapSettings.scale.set(scale)
        }
    }

    init {
        // 进页面默认落在第一个校区（改版前是良乡，见 MapCampus.ALL 的顺序约定）
        goTo(MapCampus.ALL.first())
    }
}