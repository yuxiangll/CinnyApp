package top.s0cl.cinny.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Community as MiuixCommunity
import top.yukonga.miuix.kmp.icon.extended.Email as MiuixEmail
import top.yukonga.miuix.kmp.icon.extended.Home as MiuixHome
import top.yukonga.miuix.kmp.icon.extended.Messages as MiuixMessages
import top.yukonga.miuix.kmp.icon.extended.Settings as MiuixSettings

enum class MainDestination(
    val title: String,
    val summary: String,
) {
    Home("首页", "普通房间列表"),
    Direct("私信", "私信与联系人"),
    Spaces("空间", "Space 列表与 Lobby"),
    Inbox("收件箱", "通知与邀请"),
    Me("我的", "账号与设置"),
    ;

    val icon: ImageVector
        get() = when (this) {
            Home -> MiuixIcons.Regular.MiuixHome
            Direct -> MiuixIcons.Regular.MiuixMessages
            Spaces -> MiuixIcons.Regular.MiuixCommunity
            Inbox -> MiuixIcons.Regular.MiuixEmail
            Me -> MiuixIcons.Regular.MiuixSettings
        }
}
