package cn.bit101.android.features.setting.viewmodel

import cn.bit101.android.config.common.SettingItem
import cn.bit101.android.config.setting.base.AppPalette
import cn.bit101.android.config.setting.base.AppSkin
import cn.bit101.android.config.setting.base.DarkThemeMode
import cn.bit101.android.config.setting.base.ThemeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * [ThemeViewModel] 的外观切换接线单测（P5-C）。
 *
 * 锁三件「接错了界面不报错、用户换了色没反应」的静默故障：
 * ① **appPalette 透传**：ViewModel 暴露的必须是 [ThemeSettings.appPalette] 同一实例
 *    （自己 new 一个 = 设置页改的是另一个源，主题层永远收不到）；
 * ② **初始值透传**：DataStore 里是什么，flow 首发射就是什么；
 * ③ **setAppPalette 写入透传**：调了 set 之后设置项的值必须真的变
 *    （写成空实现 / 写死 Warm = 用户选任何色都「换不动」，且无任何报错）。
 */
class ThemeViewModelTest {

    private class FakeSettingItem<T>(initial: T) : SettingItem<T> {
        private val state = MutableStateFlow(initial)

        // narrow 成 MutableStateFlow：测试要读 .value 做同步断言（接口只给 Flow）
        override val flow: MutableStateFlow<T> = state
        override suspend fun get() = state.value
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private class FakeThemeSettings(
        initialPalette: AppPalette = AppPalette.Warm,
        initialSkin: AppSkin = AppSkin.Classic,
        initialDarkMode: DarkThemeMode = DarkThemeMode.System,
    ) : ThemeSettings {
        override val darkThemeMode = FakeSettingItem(initialDarkMode)
        override val appSkin = FakeSettingItem(initialSkin)
        override val appPalette = FakeSettingItem(initialPalette)
        override val dynamicTheme = FakeSettingItem(false)
        override val autoRotate = FakeSettingItem(false)
    }

    @Before
    fun setUp() {
        // withScope 走 viewModelScope（Dispatchers.Main.immediate），单测须先装主调度器
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun appPalette_透传同一设置项实例() {
        val settings = FakeThemeSettings()
        val vm = ThemeViewModel(settings)
        // 自己 new 一个 SettingItem 冒充 = 设置页与主题层各读各的源，本条立刻红
        assertSame(settings.appPalette, vm.appPalette)
    }

    @Test
    fun appPalette_初始值透传() {
        val settings = FakeThemeSettings(initialPalette = AppPalette.Warm)
        val vm = ThemeViewModel(settings)
        runBlocking {
            assertEquals(AppPalette.Warm, vm.appPalette.flow.first())
        }
    }

    @Test
    fun appPalette_非默认初始值也透传() {
        val settings = FakeThemeSettings(initialPalette = AppPalette.Classic)
        val vm = ThemeViewModel(settings)
        runBlocking {
            assertEquals(AppPalette.Classic, vm.appPalette.flow.first())
        }
    }

    @Test
    fun setAppPalette_写入透传到设置项且flow反映() {
        val settings = FakeThemeSettings(initialPalette = AppPalette.Warm)
        val vm = ThemeViewModel(settings)
        runBlocking {
            vm.setAppPalette(AppPalette.Brand)
            // withScope 内部切 Dispatchers.IO 异步写，等它落地（2s 足够；空实现会超时即红）
            withTimeout(2_000) {
                settings.appPalette.flow.first { it == AppPalette.Brand }
            }
            assertEquals(AppPalette.Brand, settings.appPalette.flow.value)
        }
    }

    @Test
    fun setAppPalette_不碰其他轴() {
        val settings = FakeThemeSettings(
            initialPalette = AppPalette.Warm,
            initialSkin = AppSkin.Classic,
            initialDarkMode = DarkThemeMode.System,
        )
        val vm = ThemeViewModel(settings)
        runBlocking {
            vm.setAppPalette(AppPalette.Brand)
            withTimeout(2_000) {
                settings.appPalette.flow.first { it == AppPalette.Brand }
            }
            // 三轴正交：切配色不应顺手改形态/暗黑（曾担心的「一个 setter 写多项」故障）
            assertEquals(AppSkin.Classic, settings.appSkin.flow.value)
            assertEquals(DarkThemeMode.System, settings.darkThemeMode.flow.value)
            assertEquals(false, settings.dynamicTheme.flow.value)
            assertEquals(false, settings.autoRotate.flow.value)
        }
    }
}
