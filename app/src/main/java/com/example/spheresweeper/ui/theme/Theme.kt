package com.example.spheresweeper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Deep-space palette shared by the menu, the HUD and the background. */
object SpaceColors {
    val SpaceTop = Color(0xFF0B1238)
    val SpaceMid = Color(0xFF070A22)
    val SpaceBottom = Color(0xFF02030A)
    val NebulaViolet = Color(0xFF6B3BD6)
    val NebulaTeal = Color(0xFF1AA7B8)

    val Cyan = Color(0xFF4FE3FF)
    val Blue = Color(0xFF4F7BFF)
    val Violet = Color(0xFFA77BFF)
    val Gold = Color(0xFFFFD166)
    val Success = Color(0xFF5CF2A8)
    val Danger = Color(0xFFFF4D6A)
    val Heart = Color(0xFFFF6FA8)

    val TextPrimary = Color(0xFFE9EDFF)
    val TextSecondary = Color(0xFF9AA3CF)
    val TextMuted = Color(0xFF6A7299)

    val Panel = Color(0xFF141A44)
    val PanelTop = Color(0xFF1C2460)
    val PanelBorder = Color(0xFF3A4590)
}

private val SphereColorScheme = darkColorScheme(
    primary = SpaceColors.Cyan,
    onPrimary = Color(0xFF00222B),
    primaryContainer = Color(0xFF123B5C),
    onPrimaryContainer = SpaceColors.TextPrimary,
    secondary = SpaceColors.Violet,
    onSecondary = Color(0xFF1C0A40),
    secondaryContainer = Color(0xFF2E1F66),
    onSecondaryContainer = SpaceColors.TextPrimary,
    tertiary = SpaceColors.Gold,
    onTertiary = Color(0xFF2B1D00),
    background = SpaceColors.SpaceBottom,
    onBackground = SpaceColors.TextPrimary,
    surface = SpaceColors.Panel,
    onSurface = SpaceColors.TextPrimary,
    surfaceVariant = SpaceColors.PanelTop,
    onSurfaceVariant = SpaceColors.TextSecondary,
    outline = SpaceColors.PanelBorder,
    error = SpaceColors.Danger,
    onError = Color(0xFF2B0008),
)

private val SphereTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 0.5.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 1.2.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
)

@Composable
fun SphereSweeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SphereColorScheme,
        typography = SphereTypography,
        content = content,
    )
}
