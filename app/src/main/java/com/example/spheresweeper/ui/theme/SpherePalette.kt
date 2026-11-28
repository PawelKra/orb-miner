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
    /** Two short wavy strokes on every ocean cell, alpha included. */
    val wave: Int,
    /**
     * Heavy line along every land edge that borders the ocean. [coastCasing] is a wider line under it;
     * where the land is about as light as [coast], the casing keeps the coast visible. Alpha 0 draws none.
     */
    val coast: Int,
    val coastCasing: Int,
    /**
     * Small dot in the middle of a covered cell without any other mark, so that unknown cells read apart
     * from revealed ones. Its colour is the cell's lit fill with [coveredDotDarken] of black mixed in.
     * When [coveredDotLighten] is above 0 and mixing in that share of white instead gives the higher
     * contrast against the fill (on dimly lit dark cells), the dot takes the lighter colour.
     */
    val coveredDotDarken: Float,
    val coveredDotLighten: Float,
    /** Index = number of adjacent mines, index 0 unused; larger numbers use the last colour. */
    val numbers: IntArray,
    /** Soft shadow under digits; alpha 0 draws none. */
    val digitShadow: Int,
    val digitShadowAlpha: Float,
    /** Outline around arrows, padlocks and portal marks so they read on covered and revealed cells. */
    val decalOutline: Int,
    val decalOutlineAlpha: Float,
    val far: Int,
    /**
     * Dark line along the inner side of the far-colour band of a scope highlight, between the band and
     * the cell fill. Where the far colour is close to the fill, this line stands out against it instead.
     */
    val farOutline: Int,
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
    /**
     * Territory match: the colour of the cells each player owns, by player index, used in place of the
     * revealed colour (and of the covered colour under a found mine). The renderer shades it like a
     * revealed cell, with the shade raised to at least [playerCellMinShade]. A theme picks them so that
     * every number colour, [far] and [line] keep 4.5:1 or more against the shaded cell; the space theme
     * notes where the brightest spot of its gloss, drawn over the digits, lowers that.
     */
    val playerCells: List<Rgb>,
    /**
     * Lowest shade of a player's cell. Light themes set 1: their dark digits leave no room for a tint at
     * the dimmest revealed shade, so owned cells keep their full colour at every light and can carry a
     * visible tint at the same contrast. 0 shades them exactly like revealed cells.
     */
    val playerCellMinShade: Float,
    /**
     * Territory match: pennant and pole of the flag on a found mine, which lies on a player's cell
     * ([playerCells]). A theme picks them for 3.5:1 (pennant) and 4.5:1 (pole) or more against both
     * players' cells, since its single-player [flag] and [pole] may not stand out on them.
     */
    val ownedFlag: Int,
    val ownedPole: Int,
    /**
     * Territory match: the mine shown at the end of a match on a mine nobody found, over [lostMineCell];
     * 3:1 or more against that cell.
     */
    val unownedMine: Int,
) {
    init {
        require(covered.size >= 2) { "at least two covered colours" }
        require(playerCells.size >= 2) { "one cell colour per player" }
        require(numbers.size >= 2 && portals.isNotEmpty() && confetti.isNotEmpty())
        require(halo.size == 4 && pool.size == 2 && core.size == 3 && gloss.size == 3)
        require(win.size == 5 && explosion.size == 5)
    }

    /**
     * Outline of a revealed cell that shows nothing (no number, padlock or mark), so that a cleared area
     * nearly merges into one surface: [border] as it looks over [fill] (opaque ARGB), mixed
     * [EMPTY_CELL_BORDER_FILL_SHARE] of the way towards [fill]. The result is opaque.
     */
    fun emptyCellBorder(fill: Int): Int {
        val share = ((border ushr 24) / 255f) * (1f - EMPTY_CELL_BORDER_FILL_SHARE)
        return (0xFF shl 24) or
            (mixChannel(fill, border, 16, share) shl 16) or
            (mixChannel(fill, border, 8, share) shl 8) or
            mixChannel(fill, border, 0, share)
    }
}

/** Share of the cell's own fill in the outline of an empty revealed cell, see [SpherePalette.emptyCellBorder]. */
private const val EMPTY_CELL_BORDER_FILL_SHARE = 0.8f

/** Channel at bit [shift] of [from], moved [share] of the way towards the same channel of [to]. */
private fun mixChannel(from: Int, to: Int, shift: Int, share: Float): Int {
    val a = (from shr shift) and 0xFF
    val b = (to shr shift) and 0xFF
    return (a + (b - a) * share + 0.5f).toInt().coerceIn(0, 255)
}

/** ARGB int from a 0xAARRGGBB literal. */
internal fun argb(value: Long): Int = value.toInt()

/** [color] with its alpha replaced by [alpha] in 0..1. */
internal fun withAlpha(color: Long, alpha: Float): Int =
    ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (color.toInt() and 0xFFFFFF)
