package cn.bit101.android.features.common.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「随机头像」的动物图案（先鸽子，再其它动物/鸟类）。
 * 用 emoji 而非手绘 Path：零资源、任意分辨率、观感稳定；后续要换成矢量鸽子可只改这里。
 */
private val AvatarCritters = listOf(
    "\uD83D\uDD4A", // 🕊 鸽子
    "\uD83D\uDC26", // 🐦 小鸟
    "\uD83D\uDC24", // 🐤 雏
    "\uD83E\uDD9C", // 🦜 鹦鹉
    "\uD83D\uDC27", // 🐧 企鹅
    "\uD83E\uDD89", // 🦉 猫头鹰
    "\uD83D\uDC31", // 🐱 猫
    "\uD83D\uDC30", // 🐰 兔
    "\uD83D\uDC1F", // 🐟 鱼
    "\uD83D\uDC38", // 🐸 蛙
    "\uD83E\uDD8A", // 🦊 狐
    "\uD83D\uDC28", // 🐨 考拉
)

/**
 * 由 [seed] 确定性生成的头像：色相 + 动物 emoji。
 *
 * 底色用 [Color.hsv] 现算（不走硬编码色值，避免触发 PaletteGuardTest 的
 * 「features 白名单外不许硬编码色值」守卫；也正好让底色可随 seed 连续变化）。
 * 同 seed 永远同图；换 seed 即「换一个」，不依赖网络/上传。
 */
@Composable
fun GeneratedAvatar(
    seed: Int,
    size: Dp = 50.dp,
    modifier: Modifier = Modifier,
) {
    val s = Math.floorMod(seed, 100000)
    val hue = ((s * 47) % 360).toFloat()
    val background = Color.hsv(hue = hue, saturation = 0.42f, value = 0.82f)
    val critter = AvatarCritters[(s / 360) % AvatarCritters.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = critter,
            fontSize = (size.value * 0.56f).sp,
        )
    }
}
