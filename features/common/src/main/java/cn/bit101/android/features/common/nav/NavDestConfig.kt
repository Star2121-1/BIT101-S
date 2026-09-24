package cn.bit101.android.features.common.nav

import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import cn.bit101.android.config.setting.base.AppRoutes

sealed interface NavDestConfig {
    val route: String
    val arguments: List<NamedNavArgument>

    companion object {
        val all = listOf(
            Index,
            Setting,
            Login,
            User,
            Poster,
            Post,
            Edit,
            Report,
            Message,
            Web,
            FollowList,
            CampusService,
        )

        fun fromRoute(route: String?): NavDestConfig? {
            return all.find { it.route == route }
        }
    }

    data object Index : NavDestConfig {
        override val route: String = "index"
        override val arguments: List<NamedNavArgument> = emptyList()
    }

    data object Setting : NavDestConfig {
        override val route: String = "setting?route={route}"
        override val arguments: List<NamedNavArgument> = listOf(
            navArgument("route") {
                type = NavType.StringType
                nullable = true
            }
        )
    }

    data object Login : NavDestConfig {
        // ⚠️ 路由字符串统一来自 AppRoutes（:config）—— 桌面小组件的「登录」按钮
        //    也用它，两边必须同源（组件不能依赖 :features，见 AppRoutes 的说明）
        override val route: String = AppRoutes.LOGIN
        override val arguments: List<NamedNavArgument> = emptyList()
    }

    data object User : NavDestConfig {
        override val route: String = "user/{id}"
        override val arguments: List<NamedNavArgument> = listOf(
            navArgument("id") { type = NavType.LongType },
        )
    }

    data object Poster : NavDestConfig {
        override val route: String = "poster/{id}"
        override val arguments: List<NamedNavArgument> = listOf(
            navArgument("id") { type = NavType.LongType },
        )
    }

    data object Post : NavDestConfig {
        override val route: String = "post"
        override val arguments: List<NamedNavArgument> = emptyList()
    }

    data object Edit : NavDestConfig {
        override val route: String = "edit/{id}"
        override val arguments: List<NamedNavArgument> = listOf(
            navArgument("id") { type = NavType.LongType },
        )
    }

    data object Report : NavDestConfig {
        override val route: String = "report/{type}/{id}"
        override val arguments: List<NamedNavArgument> = listOf(
            navArgument("type") { type = NavType.StringType },
            navArgument("id") { type = NavType.LongType },
        )
    }

    data object Message : NavDestConfig {
        override val route: String = "message"
        override val arguments: List<NamedNavArgument> = emptyList()
    }

    data object Web : NavDestConfig {
        override val route: String = "web/{url}"
        override val arguments: List<NamedNavArgument> = listOf(
            navArgument("url") { type = NavType.StringType },
        )
    }

    data object CampusService : NavDestConfig {
        override val route: String = "campus-service"
        override val arguments: List<NamedNavArgument> = listOf()
    }

    data object FollowList : NavDestConfig {
        override val route: String = "followlist/{type}"
        override val arguments: List<NamedNavArgument> = listOf(
            navArgument("type") { type = NavType.StringType },
        )
    }
}