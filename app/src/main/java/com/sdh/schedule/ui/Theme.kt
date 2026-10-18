package com.sdh.schedule.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 参照小米日历的观感:浅灰背景 + 白色卡片 + 蓝色主色
val Blue = Color(0xFF4361EE)
val BlueDark = Color(0xFF2E4BC6)
val BlueSoft = Color(0xFFE8ECFD)
val BgLight = Color(0xFFF5F6F8)
val CardLight = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF1A1D26)
val TextSecondary = Color(0xFF8A8F9C)
val DividerColor = Color(0xFFEDEFF3)
val Danger = Color(0xFFE5484D)
val Green = Color(0xFF30A46C)
val Orange = Color(0xFFF5A524)

// 日程色板(日历账户/分类颜色)
//
// ⚠️ 长度会影响课表/日程的自动配色:取色算法是 `hash(名字) % Palette.size`,
// 所以色板越短,**不同课程撞色越多**(之前只有 7 色,15 门课几乎必然重复)。
// 现在扩到 24 色:前 7 个保持原样(不改已有配色),后面按色相均分补齐 17 个。
val Palette: List<Color> = buildList {
    addAll(listOf(Blue, Green, Orange, Danger, Color(0xFF8E4EC6), Color(0xFF0EA5E9), Color(0xFFEC4899)))
    for (i in 7 until 24) {
        // 每 15° 一个色相。饱和/明度要接近前面 7 个主题色(都很鲜艳),
        // 否则整片课表会显得发白(试过 S=0.52 太淡);卡片本身再用低透明度压成淡底。
        add(Color.hsv((i * 15f) % 360f, 0.72f, 0.90f))
    }
}

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = BlueSoft,
    onPrimaryContainer = BlueDark,
    secondary = BlueDark,
    background = BgLight,
    onBackground = TextPrimary,
    surface = CardLight,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFFF0F2F6),
    onSurfaceVariant = TextSecondary,
    outline = DividerColor,
    error = Danger
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7B95FF),
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF243063),
    onPrimaryContainer = Color(0xFFDDE3FF),
    background = Color(0xFF101218),
    onBackground = Color(0xFFEDEFF5),
    surface = Color(0xFF181B23),
    onSurface = Color(0xFFEDEFF5),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFA8AEBD),
    outline = Color(0xFF2C3140),
    error = Color(0xFFFF6B6B)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun AppTheme(
    darkMode: String = "system",
    content: @Composable () -> Unit
) {
    val dark = when (darkMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
