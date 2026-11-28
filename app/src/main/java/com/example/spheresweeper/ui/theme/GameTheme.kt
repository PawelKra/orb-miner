package com.example.spheresweeper.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat

/** What fills the screen behind the content. */
enum class Backdrop {
    /** Vertical gradient from [UiPalette.backgroundTop] over [UiPalette.backgroundMid] to [UiPalette.backgroundBottom]. */
    GRADIENT,

    /** The same gradient with two faint nebulae and a twinkling starfield. */
    STARS,
}

/** A theme's typeface for Compose text and for the digits the ball renderer draws on its canvas. */
sealed class ThemeFont {
    /** Compose font family of every text style. */
    abstract val family: FontFamily

    /** Weight of every text style, or null to keep the weights of the default type scale. */
    abstract val weight: FontWeight?

    /** Size factor of the type scale; evens out fonts with a small or large x-height. */
    abstract val scale: Float

    /** OpenType features of numbers that change while they are shown, such as "tnum". */
    abstract val numberFeatures: String

    /** Typeface for the ball's digits. */
    abstract fun canvasTypeface(context: Context): Typeface

    /** One static font file in res/font, used for all text in its own weight. */
    class Bundled(
        @FontRes private val resId: Int,
        override val weight: FontWeight,
        override val scale: Float = 1f,
    ) : ThemeFont() {
        override val family: FontFamily = FontFamily(Font(resId, weight))

        // Tabular lining figures: the bundled serif fonts may default to old-style figures.
        override val numberFeatures: String = "tnum, lnum"

        override fun canvasTypeface(context: Context): Typeface =
            ResourcesCompat.getFont(context, resId) ?: Typeface.SERIF
    }

    /** System fonts: the default family in Compose and a named family on the canvas. */
    class System(private val canvasFamily: String, private val canvasStyle: Int) : ThemeFont() {
        override val family: FontFamily = FontFamily.Default
        override val weight: FontWeight? = null
        override val scale: Float = 1f
        override val numberFeatures: String = "tnum"

        override fun canvasTypeface(context: Context): Typeface = Typeface.create(canvasFamily, canvasStyle)
    }
}

/**
 * Colours of the Compose screens. Accent roles are named after the space theme they started in
 * (primary was cyan, secondary violet); every theme fills them with its own hues of similar lightness,
 * so that text in an accent colour stays readable on [panel] and [onAccent] stays readable on an accent.
 */
class UiPalette(
    val backgroundTop: Color,
    val backgroundMid: Color,
    val backgroundBottom: Color,
    /** Nebulae of the [Backdrop.STARS] backdrop; unused by gradient backdrops. */
    val nebulaPrimary: Color,
    val nebulaSecondary: Color,
    val primary: Color,
    val secondary: Color,
    val blue: Color,
    val gold: Color,
    val success: Color,
    val danger: Color,
    val heart: Color,
    /** Campaign branches drawn in the colour the ball uses for their feature. */
    val walls: Color,
    val lines: Color,
    val portals: Color,
    val locks: Color,
    /**
     * Territory match: each player's colour for text and marks, by player index, in the hue of the
     * player's cells on the ball ([SpherePalette.playerCells]). Text in it keeps 4.5:1 or more on the
     * background and on [panel].
     */
    val players: List<Color>,
    /** Board-size badges in the free-play list, from the smallest to the largest board. */
    val boardAccents: List<Color>,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val panel: Color,
    val panelTop: Color,
    val panelBorder: Color,
    /** Text on a filled accent button. */
    val onAccent: Color,
    /** Thumb of a switch that is on. */
    val switchThumb: Color,
    /** Alpha of the accent fill of a completed campaign level; the level number sits on it in [textPrimary]. */
    val completedFill: Float,
    /** Strength of the soft glow around buttons. */
    val glow: Float,
    /**
     * Text in a campaign branch colour (level labels, level numbers), or null to write it in the branch
     * colour itself. Light themes set darker shades here so that the text keeps 4.5:1 on the background
     * and on the tinted level nodes, while fills and outlines keep the lighter branch colours; a dark
     * theme may set lighter shades for the same reason.
     */
    val branchText: BranchTextColors? = null,
    /**
     * Material colour scheme roles that differ from the ones [UiPalette] derives (on-accent colours and
     * containers); null derives all of them.
     */
    val schemeOverrides: SchemeOverrides? = null,
    /** Colour of the activity window behind the content; matches the theme's style in themes.xml. */
    val windowBackground: Color = backgroundBottom,
)

/** Darker text shades of the campaign branch colours, one per branch. */
class BranchTextColors(
    val trunk: Color,
    val walls: Color,
    val far: Color,
    val lines: Color,
    val mixed: Color,
    val portals: Color,
    val locks: Color,
    val islands: Color,
)

/** Material colour scheme roles a theme sets explicitly instead of deriving them from [UiPalette]. */
class SchemeOverrides(
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val onTertiary: Color,
    val onError: Color,
)

/**
 * One look of the game: name, light or dark, backdrop, font, ball colours and screen colours. Themes
 * are registered in [GameThemes.all].
 */
class GameTheme(
    /** Stable identifier stored in the preferences. */
    val id: String,
    @StringRes val nameRes: Int,
    /** Dark backgrounds: light system bar icons and a dark Material colour scheme. */
    val dark: Boolean,
    /** Style in themes.xml with this theme's window colour, used for the starting window. */
    @StyleRes val windowStyle: Int,
    val backdrop: Backdrop,
    val font: ThemeFont,
    val sphere: SpherePalette,
    val ui: UiPalette,
)
