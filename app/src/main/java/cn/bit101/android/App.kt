package cn.bit101.android

import android.app.Application
import cn.bit101.android.features.seat.SeatAppStartup
import cn.bit101.android.features.widget.WidgetAppStartup
import com.umeng.commonsdk.UMConfigure
import dagger.hilt.android.HiltAndroidApp
import org.conscrypt.Conscrypt
import java.security.Security

/**
 * @author flwfdd
 * @date 2023/3/16 23:19
 * @description _(:з」∠)_
 */
@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // 将打包的 Conscrypt 设为默认安全提供者，使旧系统支持 TLS 1.3
        Security.insertProviderAt(Conscrypt.newProvider(), 1)

        // 友盟初始化
        UMConfigure.preInit(this, "64692214ba6a5259c455c4ed", "BIT101")
        UMConfigure.init(
            this,
            "64692214ba6a5259c455c4ed",
            "BIT101",
            UMConfigure.DEVICE_TYPE_PHONE,
            ""
        )

        // 桌面小组件：把仓库实例交给组件（GlanceAppWidget 由系统实例化，不走注入），
        // 并排入周期刷新任务。内部已做容错，失败不影响 App 主流程。
        WidgetAppStartup.init(this)

        // 座位模块 → 小组件的数据推送。放在 App 启动而非「座」页面：
        // 用户不开座位页时任务仍在后台跑，组件也应能看到进度。
        SeatAppStartup.init(this)
    }
}
