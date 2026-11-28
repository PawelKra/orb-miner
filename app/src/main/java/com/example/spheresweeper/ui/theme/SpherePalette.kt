package com.example.spheresweeper.ui.theme

/** Colour with float channels in 0..1, used where the renderer does lighting maths. */
class Rgb(val r: Float, val g: Float, val b: Float) {
    companion object {
        val BLACK = Rgb(0f, 0f, 0f)

        /** From an RGB or ARGB literal such as 0xFF7F96AB; alpha is ignored. */
        fun hex(value: Long): Rgb {
            val v = value.toInt()
            return Rgb(((v shr 16) and 0xFF) / 255f, ((v shr 8) and 0xFF) / 255f, (v and 0xFF) / 255f)
        }
    }
}

/**
 * How the renderer shades the cells. Every cell gets light = [ambient] + [diffuse] * lambert at its
 * centre; each cell kind then maps that light to its own shade (flat part plus a lit part) so that a
 * theme can choose between strong modelling (space) and a soft, nearly flat look (pastels).
 */
class SphereLighting(
    val ambient: Float,
    val diffuse: Float,
    /** Covered cells: colour * (coveredFlat + coveredLit * light) + specular * coveredGloss + rim * [rim]. */
    val coveredFlat: Float,
    val coveredLit: Float,
    val coveredGloss: Float,
    val rim: Rgb,
    /** Revealed cells: colour * (revealedFlat + revealedLit * light) + [revealedLift] + specular * revealedSheen. */
    val revealedFlat: Float,
    val revealedLit: Float,
    val revealedSheen: Float,
    val revealedLift: Rgb,
    /** Ocean: colour * (oceanFlat + oceanLit * light) * jitter + broad sheen and glint in [oceanGlossTint]. */
    val oceanFlat: Float,
    val oceanLit: Float,
    val oceanSheen: Float,
    val oceanGlint: Float,
    val oceanJitter: Float,
    val oceanGlossTint: Rgb,
    val oceanRim: Rgb,
)

/**
 * Everything the ball renderer draws in colour. Plain ARGB ints and float colours only, so the
 * renderer can build its paints and shaders from it once in its constructor.
 */
class SpherePalette(
    /** Covered-cell colours from the south pole to the north pole, evenly spaced over the latitude. */
    val covered: List<Rgb>,
    /** Revealed-cell colour; null derives it from the covered colour, desaturated by [revealedDesaturation]. */
    val revealed: Rgb?,
    val revealedDesaturation: Float,
    /** Revealed colour as one swatch for previews (theme picker). */
    val revealedSwatch: Int,
    val lighting: SphereLighting,
    /** Cell outline, alpha included. */
    val border: Int,
    /** Ocean colour at the south and at the north pole. */
    val oceanSouth: Rgb,
    val oceanNorth: Rgb,
    /** Index = number of adjacent mines, index 0 unused; larger numbers use the last colour. */
    val numbers: IntArray,
    /** Soft shadow under digits; alpha 0 draws none. */
    val digitShadow: Int,
    val digitShadowAlpha: Float,
    /** Outline around arrows, padlocks and portal marks so they read on covered and revealed cells. */
    val decalOutline: Int,
    val decalOutlineAlpha: Float,
    val far: Int,
    val line: Int,
    val wallCore: Int,
    val wallGlow: Int,
    val hint: Int,
    val lock: Int,
    val lockText: Int,
    val flag: Int,
    val pole: Int,
    val wrongFlag: Int,
    val wrongPole: Int,
    val mine: Int,
    val mineOnRed: Int,
    val mineHighlight: Int,
    /** Fill of the mine that ended the game. */
    val explodedCell: Rgb,
    /** Fill of a mine that cost a life earlier. */
    val detonatedCell: Rgb,
    /** Fill of the mines shown after a loss. */
    val lostMineCell: Rgb,
    /** Fill of a flag on a safe cell after a loss. */
    val wrongFlagCell: Rgb,
    /** Screen tint right after a mine goes off. */
    val tint: Int,
    /** Portal pair colours, indexed by pair. */
    val portals: IntArray,
    val confetti: IntArray,
    /** Radial gradient stops behind the ball, at 0, 0.6, 0.7 and 1 of the halo radius. */
    val halo: IntArray,
    /** Light pool (or shadow) under the ball, centre to edge. */
    val pool: IntArray,
    /** Ball core behind the cells, lit from the upper left, at 0, 0.55 and 1. */
    val core: IntArray,
    /** Gloss over the ball's upper left, at 0, 0.45 and 1. */
    val gloss: IntArray,
    /**
     * True draws the gloss over the cell fills only, with digits, icons, line rows and walls on top of
     * it; dark digits on light cells then keep their contrast under the gloss. False draws the gloss
     * over everything.
     */
    val decalsOverGloss: Boolean,
    /** Win ring, at 0, 0.62, 0.72, 0.86 and 1. */
    val win: IntArray,
    /** Explosion flash, at 0, 0.18, 0.45, 0.75 and 1. */
    val explosion: IntArray,
) {
    init {
        require(covered.size >= 2) { "at least two covered colours" }
        require(numbers.size >= 2 && portals.isNotEmpty() && confetti.isNotEmpty())
        require(halo.size == 4 && pool.size == 2 && core.size == 3 && gloss.size == 3)
        require(win.size == 5 && explosion.size == 5)
    }
}

/** ARGB int from a 0xAARRGGBB literal. */
internal fun argb(value: Long): Int = value.toInt()

/** [color] with its alpha replaced by [alpha] in 0..1. */
internal fun withAlpha(color: Long, alpha: Float): Int =
    ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (color.toInt() and 0xFFFFFF)
