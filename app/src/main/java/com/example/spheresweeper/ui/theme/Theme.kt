package com.example.spheresweeper.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The theme of the screens below [SphereSweeperTheme]. */
val LocalGameTheme = staticCompositionLocalOf { GameThemes.default }

/** Screen colours of the current theme. */
val themeColors: UiPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalGameTheme.current.ui

/**
 * Style of numbers that change while they are shown (timer, counters, scores): the theme's font with
 * tabular figures where the font has them, so the digits keep their width.
 */
@Composable
@ReadOnlyComposable
fun numberTextStyle(sizeSp: Float, color: Color): TextStyle {
    val font = LocalGameTheme.current.font
    return TextStyle(
        color = color,
        fontSize = (sizeSp * font.scale).sp,
        fontWeight = font.weight ?: FontWeight.Bold,
        fontFamily = font.family,
        fontFeatureSettings = font.numberFeatures,
    )
}

private fun colorSchemeFor(theme: GameTheme): ColorScheme {
    val c = theme.ui
    val base = if (theme.dark) {
        darkColorScheme(
            primary = c.primary,
            onPrimary = c.onAccent,
            secondary = c.secondary,
            onSecondary = c.onAccent,
            tertiary = c.gold,
            onTertiary = c.onAccent,
            background = c.backgroundBottom,
            onBackground = c.textPrimary,
            surface = c.panel,
            onSurface = c.textPrimary,
            surfaceVariant = c.panelTop,
            onSurfaceVariant = c.textSecondary,
            outline = c.panelBorder,
            error = c.danger,
            onError = c.onAccent,
        )
    } else {
        lightColorScheme(
            primary = c.primary,
            onPrimary = c.onAccent,
            secondary = c.secondary,
            onSecondary = c.onAccent,
            tertiary = c.gold,
            onTertiary = c.onAccent,
            background = c.backgroundBottom,
            onBackground = c.textPrimary,
            surface = c.panel,
            onSurface = c.textPrimary,
            surfaceVariant = c.panelTop,
            onSurfaceVariant = c.textSecondary,
            outline = c.panelBorder,
            error = c.danger,
            onError = c.onAccent,
        )
    }
    val o = c.schemeOverrides ?: return base
    return base.copy(
        onPrimary = o.onPrimary,
        primaryContainer = o.primaryContainer,
        onPrimaryContainer = o.onPrimaryContainer,
        onSecondary = o.onSecondary,
        secondaryContainer = o.secondaryContainer,
        onSecondaryContainer = o.onSecondaryContainer,
        onTertiary = o.onTertiary,
        onError = o.onError,
    )
}

/**
 * Type scale in the theme's font. A bundled font comes in one weight, so every style uses it; system
 * fonts keep the bold, semibold and normal weights of the scale. Body text starts from the Material
 * default style, so it keeps its line height and letter spacing; system fonts also keep its family.
 */
private fun typographyFor(font: ThemeFont): Typography {
    val family = font.family
    val s = font.scale
    fun weight(default: FontWeight): FontWeight = font.weight ?: default
    val bodyLarge = Typography().bodyLarge.merge(
        TextStyle(
            fontFamily = if (font is ThemeFont.Bundled) family else null,
            fontWeight = font.weight,
            fontSize = (16 * s).sp,
            lineHeight = (24 * s).sp,
        ),
    )
    return Typography(
        displaySmall = TextStyle(fontFamily = family, fontWeight = weight(FontWeight.Bold), fontSize = (34 * s).sp, letterSpacing = 0.5.sp),
        headlineSmall = TextStyle(fontFamily = family, fontWeight = weight(FontWeight.Bold), fontSize = (24 * s).sp),
        titleMedium = TextStyle(fontFamily = family, fontWeight = weight(FontWeight.SemiBold), fontSize = (17 * s).sp),
        titleSmall = TextStyle(fontFamily = family, fontWeight = weight(FontWeight.SemiBold), fontSize = (14 * s).sp, letterSpacing = 1.2.sp),
        bodyLarge = bodyLarge,
        bodyMedium = TextStyle(fontFamily = family, fontWeight = weight(FontWeight.Normal), fontSize = (14 * s).sp),
        bodySmall = TextStyle(fontFamily = family, fontWeight = weight(FontWeight.Normal), fontSize = (12 * s).sp),
        labelLarge = TextStyle(fontFamily = family, fontWeight = weight(FontWeight.SemiBold), fontSize = (15 * s).sp),
    )
}

@Composable
fun SphereSweeperTheme(theme: GameTheme, content: @Composable () -> Unit) {
    val colorScheme = remember(theme) { colorSchemeFor(theme) }
    val typography = remember(theme) { typographyFor(theme.font) }
    CompositionLocalProvider(LocalGameTheme provides theme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}
