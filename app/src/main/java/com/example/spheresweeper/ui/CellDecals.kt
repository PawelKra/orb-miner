package com.example.spheresweeper.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import com.example.spheresweeper.math.MathUtils
import com.example.spheresweeper.ui.theme.SpherePalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paints the marks that sit on a cell: digits, the far ring, the line number and arrow (or the dash of
 * a line whose row holds no mine), the portal
 * mark, closed and open padlocks, flags, mines, the dot of a plain covered cell and the waves of an
 * ocean cell. The ball renderer and flat previews (such as a
 * legend of cell types) share it, so both show exactly the same drawings.
 *
 * Every draw function works in decal space, independent of any sphere or board:
 * - the origin is the cell centre, x points right and y points down;
 * - [HALF_SIZE] (100) decal units equal the cell's inscribed radius, the distance from its centre to
 *   the nearest edge, so the square of +-[HALF_SIZE] on both axes touches the cell's edges.
 *
 * The caller sets the canvas matrix that maps decal space onto the cell, for a flat cell of inscribed
 * radius r centred at (x, y): translate(x, y), then scale(r / HALF_SIZE). Each function leaves the canvas
 * matrix as it found it. Upright marks (digits, padlocks) read correctly with y down; marks with a
 * direction ([drawLineArrow]) point towards -y and are turned by the caller.
 *
 * [alpha] (0..1) multiplies the opacity of the whole mark. Colours, outlines and the digit shadow come
 * from [palette]; numbers use [typeface]. Glyph metrics, paths and paints are built once in the
 * constructor, the draw functions do not allocate.
 *
 * @param maxLockCount largest count a closed padlock can show; larger counts show this value.
 */
class CellDecals(
    private val palette: SpherePalette,
    typeface: Typeface,
    maxLockCount: Int = DEFAULT_MAX_LOCK_COUNT,
) {

    private val numberColors = palette.numbers
    private val portalColors = palette.portals
    private val lockCountMax = max(0, maxLockCount)

    /** Closed padlock shackle, open at the bottom where it meets the body. */
    private val shacklePath = Path().apply {
        moveTo(-LOCK_SHACKLE_HALF_WIDTH, LOCK_BODY_TOP)
        lineTo(-LOCK_SHACKLE_HALF_WIDTH, LOCK_SHACKLE_ARC_Y)
        arcTo(
            -LOCK_SHACKLE_HALF_WIDTH, LOCK_SHACKLE_ARC_Y - LOCK_SHACKLE_HALF_WIDTH,
            LOCK_SHACKLE_HALF_WIDTH, LOCK_SHACKLE_ARC_Y + LOCK_SHACKLE_HALF_WIDTH,
            180f, 180f, false,
        )
        lineTo(LOCK_SHACKLE_HALF_WIDTH, LOCK_BODY_TOP)
    }

    /** Small open padlock shackle below the number of an opened lock: the right leg stops short of the body. */
    private val openShacklePath = Path().apply {
        moveTo(-OPEN_SHACKLE_HALF_WIDTH, OPEN_BODY_TOP)
        lineTo(-OPEN_SHACKLE_HALF_WIDTH, OPEN_SHACKLE_ARC_Y)
        arcTo(
            -OPEN_SHACKLE_HALF_WIDTH, OPEN_SHACKLE_ARC_Y - OPEN_SHACKLE_HALF_WIDTH,
            OPEN_SHACKLE_HALF_WIDTH, OPEN_SHACKLE_ARC_Y + OPEN_SHACKLE_HALF_WIDTH,
            180f, 180f, false,
        )
        lineTo(OPEN_SHACKLE_HALF_WIDTH, OPEN_SHACKLE_RIGHT_END)
    }

    /** Line-cell arrowhead pointing up (towards -y). */
    private val arrowPath = Path().apply {
        moveTo(0f, -LINE_ARROW_TIP)
        lineTo(LINE_ARROW_HALF_WIDTH, -LINE_ARROW_BASE)
        lineTo(0f, -LINE_ARROW_NOTCH)
        lineTo(-LINE_ARROW_HALF_WIDTH, -LINE_ARROW_BASE)
        close()
    }

    /** Two short waves of one sine period each, left of and above the centre, then right of and below it. */
    private val wavePath = Path().apply {
        addWave(WAVE_A_X, WAVE_A_Y)
        addWave(WAVE_B_X, WAVE_B_Y)
    }

    /** Flag pennant. */
    private val pennantPath = Path().apply {
        moveTo(FLAG_POLE_X, FLAG_TOP)
        lineTo(FLAG_TIP_X, FLAG_TIP_Y)
        lineTo(FLAG_POLE_X, FLAG_PENNANT_BOTTOM)
        close()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        this.typeface = typeface
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = WAVE_STROKE
    }

    /** Opacity of the palette's wave colour, multiplied by the alpha of [drawWaves]. */
    private val waveAlpha = (palette.wave ushr 24) / 255f

    /** Per-number offsets that put the centre of the glyph's ink bounds on the decal origin. */
    private val digitX = FloatArray(MAX_NUMBER + 1)
    private val digitY = FloatArray(MAX_NUMBER + 1)

    /** Per-number scale that fits the number inside the ring of a far cell. */
    private val farScale = FloatArray(MAX_NUMBER + 1)

    /** Per-number scale that keeps the number of a line cell clear of its arrow. */
    private val lineScale = FloatArray(MAX_NUMBER + 1)

    /** Texts, centring offsets and scales of the counts a padlock can show, indexed by the count. */
    private val lockText = Array(lockCountMax + 1) { it.toString() }
    private val lockTextX = FloatArray(lockCountMax + 1)
    private val lockTextY = FloatArray(lockCountMax + 1)
    private val lockTextScale = FloatArray(lockCountMax + 1)

    init {
        // Digit size: ink height of the digits is a fixed share of the inscribed diameter.
        val bounds = Rect()
        textPaint.textSize = 100f
        textPaint.getTextBounds(DIGITS[8], 0, 1, bounds)
        val inkHeight = max(1, bounds.height()).toFloat()
        textPaint.textSize = 100f * DIGIT_INK_HEIGHT / inkHeight
        for (n in 1..MAX_NUMBER) {
            val text = DIGITS[n]
            textPaint.getTextBounds(text, 0, text.length, bounds)
            digitX[n] = -(bounds.left + bounds.right) * 0.5f
            digitY[n] = -(bounds.top + bounds.bottom) * 0.5f
            val halfDiagonal = 0.5f * sqrt((bounds.width() * bounds.width() + bounds.height() * bounds.height()).toFloat())
            farScale[n] = min(FAR_MAX_SCALE, FAR_TEXT_RADIUS / max(1f, halfDiagonal))
            lineScale[n] = min(LINE_MAX_SCALE, LINE_TEXT_RADIUS / max(1f, halfDiagonal))
        }
        for (n in 0..lockCountMax) {
            val text = lockText[n]
            textPaint.getTextBounds(text, 0, text.length, bounds)
            lockTextX[n] = -(bounds.left + bounds.right) * 0.5f
            lockTextY[n] = -(bounds.top + bounds.bottom) * 0.5f
            lockTextScale[n] = min(
                LOCK_TEXT_MAX_SCALE,
                min(LOCK_TEXT_WIDTH / max(1, bounds.width()), LOCK_TEXT_HEIGHT / max(1, bounds.height())),
            )
        }
    }

    /** Number [n] (1..[MAX_NUMBER]) of a plain cell, centred on the origin, in the palette's colour for [n]. */
    fun drawDigit(canvas: Canvas, n: Int, alpha: Float) {
        if (n !in 1..MAX_NUMBER) return
        val x = digitX[n]
        val y = digitY[n]
        val text = DIGITS[n]
        drawDigitShadow(canvas, text, x, y, alpha)
        textPaint.color = MathUtils.withAlpha(numberColors[min(n, numberColors.size - 1)], alpha)
        canvas.drawText(text, 0, text.length, x, y, textPaint)
    }

    /**
     * Far cell: ring in the far colour (gold), and number [n] inside it in the same colour, scaled to fit
     * the ring. A number outside 1..[MAX_NUMBER] (a zero) shows the ring alone.
     */
    fun drawFar(canvas: Canvas, n: Int, alpha: Float) {
        ringPaint.strokeWidth = FAR_RING_STROKE
        ringPaint.color = MathUtils.withAlpha(palette.far, alpha * FAR_RING_ALPHA)
        canvas.drawCircle(0f, 0f, FAR_RING_RADIUS, ringPaint)
        if (n !in 1..MAX_NUMBER) return
        canvas.save()
        val s = farScale[n]
        canvas.scale(s, s)
        val text = DIGITS[n]
        val x = digitX[n]
        val y = digitY[n]
        drawDigitShadow(canvas, text, x, y, alpha)
        textPaint.color = MathUtils.withAlpha(palette.far, alpha)
        canvas.drawText(text, 0, text.length, x, y, textPaint)
        canvas.restore()
    }

    /**
     * Number [n] (1..[MAX_NUMBER]) of a line cell in the line colour (turquoise), scaled down so it stays
     * clear of the arrow drawn by [drawLineArrow].
     */
    fun drawLineNumber(canvas: Canvas, n: Int, alpha: Float) {
        if (n !in 1..MAX_NUMBER) return
        canvas.save()
        val s = lineScale[n]
        canvas.scale(s, s)
        val text = DIGITS[n]
        val x = digitX[n]
        val y = digitY[n]
        drawDigitShadow(canvas, text, x, y, alpha)
        textPaint.color = MathUtils.withAlpha(palette.line, alpha)
        canvas.drawText(text, 0, text.length, x, y, textPaint)
        canvas.restore()
    }

    /**
     * Line-cell arrowhead in the line colour, between the number and the edge at -y: the tip lies at
     * y = -92, the base at y = -60. The caller turns decal space so that -y points at the edge the
     * counted row starts through.
     */
    fun drawLineArrow(canvas: Canvas, alpha: Float) {
        // An outline keeps the arrow readable on light and dark cells alike.
        outlinePaint.strokeWidth = LINE_ARROW_OUTLINE
        outlinePaint.color = decalOutline(alpha)
        canvas.drawPath(arrowPath, outlinePaint)
        iconPaint.color = MathUtils.withAlpha(palette.line, alpha)
        canvas.drawPath(arrowPath, iconPaint)
    }

    /**
     * Line cell whose row holds no mine: a short horizontal dash in the line colour, dimmed, centred on
     * the cell. It says "this line counts nothing" without a digit that could read as a neighbour count,
     * and without the arrow, so no row is pointed at.
     */
    fun drawEmptyLine(canvas: Canvas, alpha: Float) {
        iconStroke.color = decalOutline(alpha)
        iconStroke.strokeWidth = EMPTY_LINE_STROKE + EMPTY_LINE_OUTLINE
        canvas.drawLine(-EMPTY_LINE_HALF_LENGTH, 0f, EMPTY_LINE_HALF_LENGTH, 0f, iconStroke)
        iconStroke.color = MathUtils.withAlpha(palette.line, alpha * EMPTY_LINE_ALPHA)
        iconStroke.strokeWidth = EMPTY_LINE_STROKE
        canvas.drawLine(-EMPTY_LINE_HALF_LENGTH, 0f, EMPTY_LINE_HALF_LENGTH, 0f, iconStroke)
    }

    /**
     * Padlock of a closed lock: shackle, body and [count] (the cells still to reveal before it opens)
     * inside the body. [count] is clamped to 0..maxLockCount. [scale] shrinks the whole padlock about the
     * origin; line cells use [LINE_LOCK_SCALE] so it stays clear of the arrow.
     */
    fun drawLock(canvas: Canvas, count: Int, scale: Float, alpha: Float) {
        val left = count.coerceIn(0, lockCountMax)
        canvas.save()
        if (scale != 1f) canvas.scale(scale, scale)
        // An outline keeps the padlock readable on covered and revealed cells alike.
        val outline = decalOutline(alpha)
        iconStroke.color = outline
        iconStroke.strokeWidth = LOCK_SHACKLE_STROKE + LOCK_OUTLINE
        canvas.drawPath(shacklePath, iconStroke)
        outlinePaint.color = outline
        outlinePaint.strokeWidth = LOCK_OUTLINE
        canvas.drawRoundRect(
            -LOCK_BODY_HALF_WIDTH, LOCK_BODY_TOP, LOCK_BODY_HALF_WIDTH, LOCK_BODY_BOTTOM,
            LOCK_BODY_CORNER, LOCK_BODY_CORNER, outlinePaint,
        )
        iconStroke.color = MathUtils.withAlpha(palette.lock, alpha)
        iconStroke.strokeWidth = LOCK_SHACKLE_STROKE
        canvas.drawPath(shacklePath, iconStroke)
        iconPaint.color = MathUtils.withAlpha(palette.lock, alpha)
        canvas.drawRoundRect(
            -LOCK_BODY_HALF_WIDTH, LOCK_BODY_TOP, LOCK_BODY_HALF_WIDTH, LOCK_BODY_BOTTOM,
            LOCK_BODY_CORNER, LOCK_BODY_CORNER, iconPaint,
        )
        canvas.translate(0f, (LOCK_BODY_TOP + LOCK_BODY_BOTTOM) * 0.5f)
        val s = lockTextScale[left]
        canvas.scale(s, s)
        val text = lockText[left]
        textPaint.color = MathUtils.withAlpha(palette.lockText, alpha)
        canvas.drawText(text, 0, text.length, lockTextX[left], lockTextY[left], textPaint)
        canvas.restore()
    }

    /**
     * Small faint open padlock below the number of a revealed cell whose lock has opened, at y = 59..90,
     * clear of the digit ink.
     */
    fun drawOpenLock(canvas: Canvas, alpha: Float) {
        val color = MathUtils.withAlpha(palette.lock, alpha * OPEN_LOCK_ALPHA)
        iconStroke.color = color
        iconStroke.strokeWidth = OPEN_SHACKLE_STROKE
        canvas.drawPath(openShacklePath, iconStroke)
        iconPaint.color = color
        canvas.drawRoundRect(
            -OPEN_BODY_HALF_WIDTH, OPEN_BODY_TOP, OPEN_BODY_HALF_WIDTH, OPEN_BODY_BOTTOM,
            OPEN_BODY_CORNER, OPEN_BODY_CORNER, iconPaint,
        )
    }

    /**
     * Portal mark in the colour of [pair] (0-based pair index; negative draws nothing). [full]: a ring
     * with a dot in the middle, for covered cells without a flag. Otherwise a thin ring along the cell
     * edge that leaves room for a number or flag. Pairs beyond the palette's colours repeat them, every
     * other round with a hollow dot.
     */
    fun drawPortal(canvas: Canvas, pair: Int, full: Boolean, alpha: Float) {
        if (pair < 0) return
        val color = portalColors[pair % portalColors.size]
        val outline = decalOutline(alpha)
        if (full) {
            iconPaint.color = MathUtils.withAlpha(color, alpha * PORTAL_FILL_ALPHA)
            canvas.drawCircle(0f, 0f, PORTAL_RING_RADIUS, iconPaint)
            ringPaint.color = outline
            ringPaint.strokeWidth = PORTAL_RING_STROKE + PORTAL_OUTLINE
            canvas.drawCircle(0f, 0f, PORTAL_RING_RADIUS, ringPaint)
            ringPaint.color = MathUtils.withAlpha(color, alpha)
            ringPaint.strokeWidth = PORTAL_RING_STROKE
            canvas.drawCircle(0f, 0f, PORTAL_RING_RADIUS, ringPaint)
            iconPaint.color = outline
            canvas.drawCircle(0f, 0f, PORTAL_DOT_RADIUS + PORTAL_OUTLINE * 0.5f, iconPaint)
            if ((pair / portalColors.size) % 2 == 0) {
                iconPaint.color = MathUtils.withAlpha(color, alpha)
                canvas.drawCircle(0f, 0f, PORTAL_DOT_RADIUS, iconPaint)
            } else {
                ringPaint.strokeWidth = PORTAL_DOT_RADIUS * 0.6f
                canvas.drawCircle(0f, 0f, PORTAL_DOT_RADIUS * 0.7f, ringPaint)
            }
        } else {
            ringPaint.color = MathUtils.withAlpha(color, alpha * PORTAL_EDGE_ALPHA)
            ringPaint.strokeWidth = PORTAL_EDGE_STROKE
            canvas.drawCircle(0f, 0f, PORTAL_EDGE_RADIUS, ringPaint)
        }
    }

    /**
     * Portal mark of [pair] on a cell with a lock, in every state of the lock: the thin edge ring of
     * [drawPortal] without [full], open at the bottom where the small open padlock of [drawOpenLock]
     * reaches the edge. The closed padlock and its count, or the number, keep the middle of the cell.
     * Upright like the padlocks, so that the gap stays below the open padlock.
     */
    fun drawPortalAroundLock(canvas: Canvas, pair: Int, alpha: Float) {
        if (pair < 0) return
        val color = portalColors[pair % portalColors.size]
        ringPaint.color = MathUtils.withAlpha(color, alpha * PORTAL_EDGE_ALPHA)
        ringPaint.strokeWidth = PORTAL_EDGE_STROKE
        // Angles run clockwise from +x with y down, so 90 degrees is straight below the centre.
        canvas.drawArc(
            -PORTAL_EDGE_RADIUS, -PORTAL_EDGE_RADIUS, PORTAL_EDGE_RADIUS, PORTAL_EDGE_RADIUS,
            90f + PORTAL_LOCK_GAP_HALF_ANGLE, 360f - 2f * PORTAL_LOCK_GAP_HALF_ANGLE, false, ringPaint,
        )
    }

    /**
     * Flat flag icon: thin pole and short base line in [poleColor], triangular pennant in [pennantColor]
     * (ARGB, their alpha is replaced by [alpha]). The palette's pair is [SpherePalette.pole] and
     * [SpherePalette.flag]; a wrong flag after a loss uses [SpherePalette.wrongPole] and [SpherePalette.wrongFlag].
     */
    fun drawFlag(canvas: Canvas, poleColor: Int, pennantColor: Int, alpha: Float) {
        iconStroke.strokeWidth = FLAG_STROKE
        iconStroke.color = MathUtils.withAlpha(poleColor, alpha)
        canvas.drawLine(FLAG_POLE_X, FLAG_TOP, FLAG_POLE_X, FLAG_BASE_Y, iconStroke)
        canvas.drawLine(FLAG_BASE_LEFT, FLAG_BASE_Y, FLAG_BASE_RIGHT, FLAG_BASE_Y, iconStroke)
        iconPaint.color = MathUtils.withAlpha(pennantColor, alpha)
        canvas.drawPath(pennantPath, iconPaint)
    }

    /**
     * Flat spiked ball in [color] (ARGB, its alpha is replaced by [alpha]) with a small highlight dot.
     * The palette uses [SpherePalette.mine], or [SpherePalette.mineOnRed] on a red cell.
     */
    fun drawMine(canvas: Canvas, color: Int, alpha: Float) {
        iconStroke.strokeWidth = MINE_SPIKE_STROKE
        iconStroke.color = MathUtils.withAlpha(color, alpha)
        for (k in 0 until SPIKES) {
            canvas.drawLine(0f, 0f, SPIKE_COS[k] * MINE_SPIKE_LENGTH, SPIKE_SIN[k] * MINE_SPIKE_LENGTH, iconStroke)
        }
        iconPaint.color = MathUtils.withAlpha(color, alpha)
        canvas.drawCircle(0f, 0f, MINE_BODY_RADIUS, iconPaint)
        iconPaint.color = MathUtils.withAlpha(palette.mineHighlight, alpha)
        canvas.drawCircle(-MINE_BODY_RADIUS * 0.36f, -MINE_BODY_RADIUS * 0.36f, MINE_BODY_RADIUS * 0.22f, iconPaint)
    }

    /**
     * Small dot in the middle of a covered cell that shows no other mark. [fill] is the cell's fill as
     * drawn (opaque ARGB); the dot is that fill darkened by the palette's coveredDotDarken, or lightened
     * by coveredDotLighten where that stands out more against it.
     */
    fun drawCoveredDot(canvas: Canvas, fill: Int, alpha: Float) {
        iconPaint.color = MathUtils.withAlpha(coveredDotColor(fill), alpha)
        canvas.drawCircle(0f, 0f, COVERED_DOT_RADIUS, iconPaint)
    }

    /**
     * Two short wavy strokes of an ocean cell in the palette's wave colour. They stay inside the
     * inscribed circle, so they never cross into a neighbouring cell.
     */
    fun drawWaves(canvas: Canvas, alpha: Float) {
        wavePaint.color = MathUtils.withAlpha(palette.wave, alpha * waveAlpha)
        canvas.drawPath(wavePath, wavePaint)
    }

    /** Colour of the dot on a covered cell with fill [fill], see [drawCoveredDot]. */
    private fun coveredDotColor(fill: Int): Int {
        val r = ((fill shr 16) and 0xFF) / 255f
        val g = ((fill shr 8) and 0xFF) / 255f
        val b = (fill and 0xFF) / 255f
        val keep = 1f - palette.coveredDotDarken
        val dark = MathUtils.argb(1f, r * keep, g * keep, b * keep)
        val lighten = palette.coveredDotLighten
        if (lighten <= 0f) return dark
        val light = MathUtils.argb(1f, r + (1f - r) * lighten, g + (1f - g) * lighten, b + (1f - b) * lighten)
        // Contrast ratios as in WCAG: (lighter + 0.05) / (darker + 0.05).
        val fillLevel = luminance(fill) + 0.05f
        val darkContrast = fillLevel / (luminance(dark) + 0.05f)
        val lightContrast = (luminance(light) + 0.05f) / fillLevel
        return if (darkContrast >= lightContrast) dark else light
    }

    /** Appends one sine period centred at ([cx], [cy]) to this path as a polyline. */
    private fun Path.addWave(cx: Float, cy: Float) {
        for (i in 0..WAVE_SEGMENTS) {
            val t = i.toFloat() / WAVE_SEGMENTS
            val x = cx + (t * 2f - 1f) * WAVE_HALF_LENGTH
            val y = cy + sin(t * 2f * PI.toFloat()) * WAVE_AMPLITUDE
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }

    /** Soft shadow under a number at decal position (x, y); themes without a shadow skip it. */
    private fun drawDigitShadow(canvas: Canvas, text: String, x: Float, y: Float, alpha: Float) {
        if (palette.digitShadowAlpha <= 0f) return
        textPaint.color = MathUtils.withAlpha(palette.digitShadow, alpha * palette.digitShadowAlpha)
        canvas.drawText(text, 0, text.length, x + DIGIT_SHADOW_OFFSET, y + DIGIT_SHADOW_OFFSET, textPaint)
    }

    /** Outline colour of arrows, padlocks and portal marks at decal [alpha]. */
    private fun decalOutline(alpha: Float): Int =
        MathUtils.withAlpha(palette.decalOutline, alpha * palette.decalOutlineAlpha)

    companion object {
        /** Decal units from the cell centre to its nearest edge (the inscribed radius). */
        const val HALF_SIZE = 100f

        /** Largest number a cell can show: a far hexagon sees 6 + 12 cells. */
        const val MAX_NUMBER = 19

        /** Scale of the closed padlock on a line cell, where it has to clear the arrow. */
        const val LINE_LOCK_SCALE = 0.75f

        /** Largest padlock count prepared when the caller does not name one. */
        const val DEFAULT_MAX_LOCK_COUNT = 99

        /** Digit ink height in decal units: 54 % of the inscribed diameter (2 * HALF_SIZE). */
        private const val DIGIT_INK_HEIGHT = 0.54f * 2f * HALF_SIZE
        private const val DIGIT_SHADOW_OFFSET = 4f

        // Far cells: ring radius and stroke in decal units, number scaled to fit inside the ring.
        private const val FAR_RING_RADIUS = 86f
        private const val FAR_RING_STROKE = 8f
        private const val FAR_RING_ALPHA = 0.85f
        private const val FAR_TEXT_RADIUS = 66f
        private const val FAR_MAX_SCALE = 0.9f

        // Line cells: number scaled to fit within LINE_TEXT_RADIUS, arrowhead between the number and
        // the edge (decal units, the edge itself lies at HALF_SIZE or a little farther).
        private const val LINE_TEXT_RADIUS = 56f
        private const val LINE_MAX_SCALE = 0.8f
        private const val LINE_ARROW_TIP = 92f
        private const val LINE_ARROW_NOTCH = 68f
        private const val LINE_ARROW_BASE = 60f
        private const val LINE_ARROW_HALF_WIDTH = 30f
        private const val LINE_ARROW_OUTLINE = 9f

        // Dash of a line cell whose row holds no mine (decal units), round caps, about a digit's width.
        private const val EMPTY_LINE_HALF_LENGTH = 26f
        private const val EMPTY_LINE_STROKE = 12f
        private const val EMPTY_LINE_OUTLINE = 9f
        private const val EMPTY_LINE_ALPHA = 0.75f

        // Padlock of a closed lock in decal units: body below, shackle above, the count inside the body.
        // It stays within the ring of a far cell; on line cells it shrinks to clear the arrow.
        private const val LOCK_BODY_HALF_WIDTH = 50f
        private const val LOCK_BODY_TOP = -6f
        private const val LOCK_BODY_BOTTOM = 52f
        private const val LOCK_BODY_CORNER = 12f
        private const val LOCK_SHACKLE_HALF_WIDTH = 28f
        private const val LOCK_SHACKLE_ARC_Y = -28f
        private const val LOCK_SHACKLE_STROKE = 12f
        private const val LOCK_OUTLINE = 9f
        private const val LOCK_TEXT_WIDTH = 82f
        private const val LOCK_TEXT_HEIGHT = 38f
        private const val LOCK_TEXT_MAX_SCALE = 0.5f

        // Open padlock below the number of an opened lock (decal units), clear of the digit ink.
        private const val OPEN_BODY_HALF_WIDTH = 13f
        private const val OPEN_BODY_TOP = 74f
        private const val OPEN_BODY_BOTTOM = 90f
        private const val OPEN_BODY_CORNER = 4f
        private const val OPEN_SHACKLE_HALF_WIDTH = 7f
        private const val OPEN_SHACKLE_ARC_Y = 66f
        private const val OPEN_SHACKLE_RIGHT_END = 69f
        private const val OPEN_SHACKLE_STROKE = 4.5f
        private const val OPEN_LOCK_ALPHA = 0.6f

        // Portal marks in decal units: ring and dot on covered cells, a thin edge ring otherwise.
        private const val PORTAL_RING_RADIUS = 56f
        private const val PORTAL_RING_STROKE = 13f
        private const val PORTAL_DOT_RADIUS = 18f
        private const val PORTAL_OUTLINE = 8f
        private const val PORTAL_FILL_ALPHA = 0.22f
        private const val PORTAL_EDGE_RADIUS = 90f
        private const val PORTAL_EDGE_STROKE = 8f
        private const val PORTAL_EDGE_ALPHA = 0.9f

        // Half the gap, in degrees, at the bottom of the edge ring on a lock cell; the open padlock body
        // (OPEN_BODY_HALF_WIDTH wide, down to OPEN_BODY_BOTTOM) spans about 8 degrees each side.
        private const val PORTAL_LOCK_GAP_HALF_ANGLE = 20f

        // Flag geometry in decal units (about 55 % of the cell's height).
        private const val FLAG_STROKE = 9f
        private const val FLAG_POLE_X = -18f
        private const val FLAG_TOP = -56f
        private const val FLAG_TIP_X = 48f
        private const val FLAG_TIP_Y = -30f
        private const val FLAG_PENNANT_BOTTOM = -4f
        private const val FLAG_BASE_Y = 52f
        private const val FLAG_BASE_LEFT = -44f
        private const val FLAG_BASE_RIGHT = 8f

        // Mine geometry in decal units: body about 42 % and spikes about 55 % of the cell diameter.
        private const val MINE_BODY_RADIUS = 42f
        private const val MINE_SPIKE_LENGTH = 51f
        private const val MINE_SPIKE_STROKE = 9f

        // Dot on a plain covered cell: 0.14 of a hexagon's circumradius, which is HALF_SIZE / cos 30 degrees.
        private const val HEX_CIRCUM_RADIUS = 115.47f
        private const val COVERED_DOT_RADIUS = 0.14f * HEX_CIRCUM_RADIUS

        // Ocean waves, in 22nds of a hexagon's circumradius: each 14 long with an amplitude of 2.2,
        // centred at (-6, -5) and (3, 5), drawn 1.6 wide with round caps.
        private const val WAVE_HALF_LENGTH = 7f / 22f * HEX_CIRCUM_RADIUS
        private const val WAVE_AMPLITUDE = 2.2f / 22f * HEX_CIRCUM_RADIUS
        private const val WAVE_A_X = -6f / 22f * HEX_CIRCUM_RADIUS
        private const val WAVE_A_Y = -5f / 22f * HEX_CIRCUM_RADIUS
        private const val WAVE_B_X = 3f / 22f * HEX_CIRCUM_RADIUS
        private const val WAVE_B_Y = 5f / 22f * HEX_CIRCUM_RADIUS
        private const val WAVE_STROKE = 1.6f / 22f * HEX_CIRCUM_RADIUS
        private const val WAVE_SEGMENTS = 16

        private val DIGITS = Array(MAX_NUMBER + 1) { it.toString() }

        /** Relative luminance (WCAG) of an ARGB colour; alpha is ignored. */
        private fun luminance(color: Int): Float =
            0.2126f * linear((color shr 16) and 0xFF) +
                0.7152f * linear((color shr 8) and 0xFF) +
                0.0722f * linear(color and 0xFF)

        /** sRGB channel value 0..255 to linear light 0..1. */
        private fun linear(channel: Int): Float {
            val c = channel / 255f
            return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }

        private const val SPIKES = 8
        private val SPIKE_COS = FloatArray(SPIKES) { cos(it * PI / 4.0).toFloat() }
        private val SPIKE_SIN = FloatArray(SPIKES) { sin(it * PI / 4.0).toFloat() }
    }
}
