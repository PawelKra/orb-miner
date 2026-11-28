package com.example.spheresweeper.ui

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.example.spheresweeper.logic.BoardLayout
import com.example.spheresweeper.logic.GameStatus
import com.example.spheresweeper.logic.LineCells
import com.example.spheresweeper.logic.MatchEngine
import com.example.spheresweeper.logic.MinesweeperBoard
import com.example.spheresweeper.logic.SphereGeometry
import com.example.spheresweeper.math.MathUtils
import com.example.spheresweeper.math.Quaternion
import com.example.spheresweeper.ui.theme.SpherePalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Draws the Goldberg sphere with its own perspective projection on a native canvas.
 *
 * Every cell is one flat-coloured polygon, lit as a whole (Lambert, specular and rim terms evaluated at
 * the cell centre) and outlined by a thin border. Digits, flags and mines are decals: they are drawn
 * in a local glyph space that a perspective matrix maps onto a square in the cell's tangent plane, so
 * they lie on the surface and rotate with the ball. Flags and mines keep a fixed frame on the ball.
 * Digits turn with the view: each frame their baseline snaps to the cell edge that is most horizontal
 * on screen, so they stay as upright as the cell shape allows. Far cells show their number in gold
 * inside a gold ring. Line cells show their number in turquoise with an arrow fixed on the ball that
 * points at the edge their row starts through; once revealed, a faint line runs through the row. A
 * revealed line cell whose row holds no mine shows only a turquoise dash: no arrow, no row line.
 * Walls are glowing lines along cell edges, drawn on top of the cells and the row lines.
 *
 * Ocean cells are water with a soft sheen, two short waves and no border; a heavy coastline runs along
 * every land edge that borders them. A covered cell without any other mark shows a small dot, and a
 * revealed cell that shows nothing gets a soft outline close to its own fill, so that covered cells,
 * cleared areas and the ocean read apart. Both cells of a portal pair
 * carry the same mark in the pair's colour: a ring with a dot on a covered cell, a thin ring along the
 * edge on a revealed or flagged one so the number stays readable. On a lock cell the mark is always the
 * thin ring, open below the padlock, so the padlock and its count stay clear. A cell whose lock is still closed
 * shows a padlock with the number of cells left to reveal instead of its own number; once the lock
 * opens, the number shows with a small open padlock below it.
 *
 * The renderer only places decal space on each cell; [CellDecals] paints the digits and icons in it.
 *
 * A highlighted far cell ([draw]: highlightCell) shows its scope: every cell its number counts gets a
 * translucent tint and an inner edge band in the far colour, and the far cell itself a wider band. A thin
 * line in the palette's farOutline runs along the inner side of each band, so the band stands out on cells
 * close to the far colour. Both lie over the cell fill and under the decals, line rows and walls, so they
 * never cover a number or a mark.
 *
 * All colours, the lighting and the backdrop shaders come from [palette]; digits use [typeface]. A
 * renderer belongs to one geometry and one theme: a theme change creates a new renderer.
 *
 * Everything that depends only on the geometry and the theme (cell sizes, tangent frames, colours, glyph
 * metrics, confetti parameters, shaders, paints) is computed once in the constructor. [draw] works on
 * preallocated objects only and does not allocate.
 */
class SphereRenderer(
    private val geometry: SphereGeometry,
    private val palette: SpherePalette,
    typeface: Typeface,
) {

    private val cellCount = geometry.cellCount
    private val cornerStart = geometry.cornerStart
    private val centers = geometry.centers
    private val corners = geometry.corners

    /** Mean centre-to-corner distance per cell in model units. */
    private val cellRadius = FloatArray(cellCount)

    /** Distance from the cell centre to its nearest edge in model units; one decal unit square half-size. */
    private val inRadius = FloatArray(cellCount)

    /**
     * Model-space tangent frame per cell (x,y,z interleaved). "Up" points towards the model +Y pole
     * (+X for cells at the poles), "right" = up x centre, so decals read correctly from outside.
     */
    private val decalRight = FloatArray(cellCount * 3)
    private val decalUp = FloatArray(cellCount * 3)

    // Covered-cell base colour per cell (palette colours by model latitude).
    private val baseR = FloatArray(cellCount)
    private val baseG = FloatArray(cellCount)
    private val baseB = FloatArray(cellCount)

    // Revealed-cell colour per cell before shading.
    private val revealedR = FloatArray(cellCount)
    private val revealedG = FloatArray(cellCount)
    private val revealedB = FloatArray(cellCount)

    // Ocean colour per cell before shading (south to north colour by model latitude).
    private val oceanR = FloatArray(cellCount)
    private val oceanG = FloatArray(cellCount)
    private val oceanB = FloatArray(cellCount)

    private val lighting = palette.lighting
    private val confettiColors = palette.confetti

    /** Digits and icons on the cells; padlock counts never exceed the cell count. */
    private val decals = CellDecals(palette, typeface, cellCount)

    // Per-frame buffers.
    private val matrix = FloatArray(9)
    private val rotated = FloatArray(cellCount * 3)
    private val facing = FloatArray(cellCount)
    private val order = IntArray(cellCount)
    private val depth = FloatArray(cellCount)
    private val decalDst = FloatArray(8)
    private val decalMatrix = Matrix()

    // Corners of the cell last passed to buildCellPath: rotated model space (x,y,z) and screen (x,y).
    private val cellWorld: FloatArray
    private val cellScreen: FloatArray

    /** Edge index the digit of each cell is aligned to, -1 before the first draw; gives the choice hysteresis. */
    private val digitEdge = IntArray(cellCount) { -1 }

    // Frame parameters shared by the helpers.
    private var frameCx = 0f
    private var frameCy = 0f
    private var frameFocal = 1f

    /** Territory match of the current frame, or null; see [fillOwned]. */
    private var frameMatch: MatchEngine? = null

    /** Fill colour of each visible land cell in the current frame; the dot and the soft outline use it. */
    private val fillColor = IntArray(cellCount)

    // Screen ends and mean perspective scale of the edge last passed to projectEdge.
    private var edgeX0 = 0f
    private var edgeY0 = 0f
    private var edgeX1 = 0f
    private var edgeY1 = 0f
    private var edgeScale = 0f

    private val path = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = palette.border
    }

    /** Soft outline of an empty revealed cell; its colour depends on the cell's fill. */
    private val emptyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    /** Normal border along one edge between an empty revealed cell and another land cell. */
    private val sharedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = palette.border
    }
    private val coastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val wallGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val wallCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val scopePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    /** Per cell: true for the highlighted far cell and the cells of its scope. */
    private val scopeMarked = BooleanArray(cellCount)

    /** Far cell whose scope [scopeMarked] holds, -1 for none, and that scope (identity tells boards apart). */
    private var markedCell = -1
    private var markedScope: IntArray? = null

    /**
     * Outline in the ocean colour over an ocean cell; covers seams between them. Its width is set per
     * cell to the land border width, see [fillOcean].
     */
    private val oceanEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    /** Wall stroke width in model units, derived from the mean cell size. */
    private val wallWidth: Float

    /** Stroke width of the line through a line cell's row, in model units. */
    private val rowWidth: Float

    /** Coastline stroke width in model units, derived from the mean cell size. */
    private val coastWidth: Float
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val confettiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Unit-radius shaders, positioned and scaled through the canvas matrix.
    private val haloShader = RadialGradient(
        0f, 0f, 1f,
        palette.halo,
        floatArrayOf(0f, 0.6f, 0.7f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val poolShader = RadialGradient(
        0f, 0f, 1f,
        palette.pool,
        floatArrayOf(0f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val coreShader = RadialGradient(
        -0.35f, -0.4f, 1.5f,
        palette.core,
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val glossShader = RadialGradient(
        -0.42f, -0.48f, 0.95f,
        palette.gloss,
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val winShader = RadialGradient(
        0f, 0f, 1f,
        palette.win,
        floatArrayOf(0f, 0.62f, 0.72f, 0.86f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val explosionShader = RadialGradient(
        0f, 0f, 1f,
        palette.explosion,
        floatArrayOf(0f, 0.18f, 0.45f, 0.75f, 1f),
        Shader.TileMode.CLAMP,
    )

    // Confetti particles (deterministic).
    private val cfDirX = FloatArray(CONFETTI_COUNT)
    private val cfDirY = FloatArray(CONFETTI_COUNT)
    private val cfSpeed = FloatArray(CONFETTI_COUNT)
    private val cfSpin = FloatArray(CONFETTI_COUNT)
    private val cfFlutter = FloatArray(CONFETTI_COUNT)
    private val cfSize = FloatArray(CONFETTI_COUNT)
    private val cfDelay = FloatArray(CONFETTI_COUNT)
    private val cfColor = IntArray(CONFETTI_COUNT)

    init {
        var most = 0
        for (i in 0 until cellCount) most = max(most, cornerStart[i + 1] - cornerStart[i])
        cellWorld = FloatArray(most * 3)
        cellScreen = FloatArray(most * 2)

        for (i in 0 until cellCount) {
            val o = i * 3
            val cx = centers[o]
            val cy = centers[o + 1]
            val cz = centers[o + 2]
            val start = cornerStart[i]
            val end = cornerStart[i + 1]
            var sum = 0f
            var minEdge = Float.MAX_VALUE
            for (k in start until end) {
                val p = k * 3
                val dx = corners[p] - cx
                val dy = corners[p + 1] - cy
                val dz = corners[p + 2] - cz
                sum += sqrt(dx * dx + dy * dy + dz * dz)
                // Distance from the centre to the line through this corner and the next one.
                val q = (if (k + 1 < end) k + 1 else start) * 3
                val ex = corners[q] - corners[p]
                val ey = corners[q + 1] - corners[p + 1]
                val ez = corners[q + 2] - corners[p + 2]
                val el = sqrt(ex * ex + ey * ey + ez * ez)
                if (el > 1e-6f) {
                    val crx = dy * ez - dz * ey
                    val cry = dz * ex - dx * ez
                    val crz = dx * ey - dy * ex
                    val dist = sqrt(crx * crx + cry * cry + crz * crz) / el
                    if (dist < minEdge) minEdge = dist
                }
            }
            cellRadius[i] = if (end > start) sum / (end - start) else 0.1f
            inRadius[i] = if (minEdge < Float.MAX_VALUE) minEdge else cellRadius[i] * 0.86f
            buildTangentFrame(i, cx, cy, cz)
            assignBaseColors(i, cy)
        }

        var radiusSum = 0f
        for (i in 0 until cellCount) radiusSum += cellRadius[i]
        wallWidth = radiusSum / max(1, cellCount) * WALL_WIDTH_PER_RADIUS
        rowWidth = radiusSum / max(1, cellCount) * ROW_WIDTH_PER_RADIUS
        coastWidth = radiusSum / max(1, cellCount) * COAST_WIDTH_PER_RADIUS

        val random = Random(CONFETTI_SEED)
        for (p in 0 until CONFETTI_COUNT) {
            // Mostly upward burst; gravity pulls the pieces down afterwards.
            val angle = (-PI / 2.0 + (random.nextDouble() - 0.5) * PI * 1.6).toFloat()
            cfDirX[p] = cos(angle)
            cfDirY[p] = sin(angle)
            cfSpeed[p] = 0.7f + random.nextFloat() * 1.3f
            cfSpin[p] = (random.nextFloat() - 0.5f) * 3f
            cfFlutter[p] = 4f + random.nextFloat() * 8f
            cfSize[p] = 0.6f + random.nextFloat() * 0.8f
            cfDelay[p] = random.nextFloat() * 0.25f
            cfColor[p] = random.nextInt(confettiColors.size)
        }
    }

    /** Model-space decal frame at unit centre c: up = reference axis projected onto the tangent plane. */
    private fun buildTangentFrame(cell: Int, cx: Float, cy: Float, cz: Float) {
        // Reference axis: model +Y, or +X close to the poles where +Y is almost parallel to c.
        var nx = 0f
        var ny = 1f
        if (abs(cy) > 0.99f) {
            nx = 1f
            ny = 0f
        }
        val dot = nx * cx + ny * cy
        var ux = nx - dot * cx
        var uy = ny - dot * cy
        var uz = -dot * cz
        val ul = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= ul
        uy /= ul
        uz /= ul
        // right = up x c; with c pointing at the viewer this is screen right when up is screen up.
        val rx = uy * cz - uz * cy
        val ry = uz * cx - ux * cz
        val rz = ux * cy - uy * cx
        val o = cell * 3
        decalUp[o] = ux
        decalUp[o + 1] = uy
        decalUp[o + 2] = uz
        decalRight[o] = rx
        decalRight[o + 1] = ry
        decalRight[o + 2] = rz
    }

    /**
     * Covered, revealed and ocean colours of [cell] before shading. The covered colour runs through the
     * palette's colours from south to north, with a small deterministic per-cell variation that gives
     * the surface some texture.
     */
    private fun assignBaseColors(cell: Int, latitudeY: Float) {
        val hash = (cell * -1640531535) ushr 24
        val jitter = (hash / 255f - 0.5f) * 0.08f
        val lat = (latitudeY + 1f) * 0.5f
        val t = MathUtils.clamp01(lat + jitter)
        val stops = palette.covered
        val scaled = t * (stops.size - 1)
        val k = min(stops.size - 2, scaled.toInt())
        val u = scaled - k
        val a = stops[k]
        val b = stops[k + 1]
        val r = MathUtils.lerp(a.r, b.r, u)
        val g = MathUtils.lerp(a.g, b.g, u)
        val bl = MathUtils.lerp(a.b, b.b, u)
        baseR[cell] = r
        baseG[cell] = g
        baseB[cell] = bl

        val revealed = palette.revealed
        if (revealed == null) {
            val gray = (r + g + bl) / 3f
            val d = palette.revealedDesaturation
            revealedR[cell] = MathUtils.lerp(r, gray, d)
            revealedG[cell] = MathUtils.lerp(g, gray, d)
            revealedB[cell] = MathUtils.lerp(bl, gray, d)
        } else {
            revealedR[cell] = revealed.r
            revealedG[cell] = revealed.g
            revealedB[cell] = revealed.b
        }

        val south = palette.oceanSouth
        val north = palette.oceanNorth
        val w = MathUtils.clamp01(lat)
        oceanR[cell] = MathUtils.lerp(south.r, north.r, w)
        oceanG[cell] = MathUtils.lerp(south.g, north.g, w)
        oceanB[cell] = MathUtils.lerp(south.b, north.b, w)
    }

    /**
     * Draws the ball. [board] may be null (decorative ball: every cell covered).
     * [nowNanos] is compared with [effectStartNanos] (both System.nanoTime()) to animate effects.
     * [highlightCell] is a far cell whose scope (board.neighbors) is highlighted, -1 for none.
     * [match] is the territory match played on [board], or null; the cells a player owns are filled in
     * the player's colour ([SpherePalette.playerCells]). A solved board of a match is the end of the match,
     * not a single-player win: it gets no win halo, and its mines nobody found show as unowned mines.
     */
    fun draw(
        scope: DrawScope,
        orientation: Quaternion,
        zoom: Float,
        insets: ViewInsets,
        board: MinesweeperBoard?,
        effect: SphereEffect,
        effectStartNanos: Long,
        nowNanos: Long,
        effectCell: Int = -1,
        layout: BoardLayout? = null,
        highlightCell: Int = -1,
        match: MatchEngine? = null,
    ) {
        frameMatch = match
        val width = scope.size.width
        val height = scope.size.height
        if (width <= 0f || height <= 0f) return
        val canvas = scope.drawContext.canvas.nativeCanvas
        val radius = SphereCamera.silhouetteRadius(width, height, zoom, insets)
        if (radius <= 0f) return
        val status = board?.status ?: GameStatus.READY
        val won = status == GameStatus.WON

        val effectTime =
            if (effect == SphereEffect.NONE) -1f else max(0f, (nowNanos - effectStartNanos) / 1e9f)

        var cx = SphereCamera.centerX(width, insets)
        var cy = SphereCamera.centerY(height, insets)
        val blast = effect == SphereEffect.EXPLOSION || effect == SphereEffect.MINE_HIT
        if (blast && effectTime < SHAKE_SECONDS) {
            val decay = 1f - effectTime / SHAKE_SECONDS
            val strength = if (effect == SphereEffect.EXPLOSION) 0.08f else 0.045f
            val amp = radius * strength * decay * decay
            cx += amp * sin(effectTime * 71f)
            cy += amp * 0.7f * cos(effectTime * 53f)
        }
        frameCx = cx
        frameCy = cy
        frameFocal = SphereCamera.focalLength(width, height, zoom, insets)

        // The win halo belongs to a single-player win; a match ends with its board solved too.
        drawBackdrop(canvas, cx, cy, radius, won && match == null, effect, effectTime)

        orientation.toRotationMatrix(matrix)
        val visible = transformAndCull()
        sortBackToFront(visible)
        updateScopeMarks(board, highlightCell)

        // The surface: cell fills with their borders and the scope highlight, the outlines of empty
        // revealed cells (after every fill, so no fill drawn later covers them) and the ocean waves.
        for (k in 0 until visible) {
            val cell = order[k]
            drawCell(canvas, cell, board, status, layout, fill = true, drawDecals = false)
            // The highlight of an empty revealed cell follows its outline, in drawEmptyCellOutlines.
            if (scopeMarked[cell] && !isEmptyRevealed(cell, board, layout)) drawScopeMark(canvas, cell)
        }
        if (board != null) drawEmptyCellOutlines(canvas, visible, board, layout)
        if (layout != null) drawOceanWaves(canvas, visible, layout)

        if (palette.decalsOverGloss) {
            // The gloss over the surface, then the coast and everything drawn on the cells.
            drawUnitShader(canvas, glossShader, cx, cy, radius, radius, 1f)
            drawCellMarks(canvas, visible, board, status, layout)
        } else {
            drawCellMarks(canvas, visible, board, status, layout)
            drawUnitShader(canvas, glossShader, cx, cy, radius, radius, 1f)
        }

        if (blast && board != null && effectTime < EXPLOSION_SECONDS) {
            val cell = if (effect == SphereEffect.EXPLOSION) board.explodedCell else effectCell
            drawExplosion(canvas, cell, radius, effectTime)
        }
        if (effect == SphereEffect.WIN && effectTime < WIN_SECONDS) {
            drawConfetti(canvas, cx, cy, radius, effectTime)
        }
        if (effect == SphereEffect.HINT && effectTime < HINT_SECONDS) {
            drawHintPulse(canvas, effectCell, effectTime)
        }
    }

    // ------------------------------------------------------------------ projection

    private fun transformAndCull(): Int {
        val m = matrix
        val m0 = m[0]
        val m1 = m[1]
        val m2 = m[2]
        val m3 = m[3]
        val m4 = m[4]
        val m5 = m[5]
        val m6 = m[6]
        val m7 = m[7]
        val m8 = m[8]
        val d = SphereCamera.DISTANCE
        var visible = 0
        for (i in 0 until cellCount) {
            val o = i * 3
            val x = centers[o]
            val y = centers[o + 1]
            val z = centers[o + 2]
            val rx = m0 * x + m1 * y + m2 * z
            val ry = m3 * x + m4 * y + m5 * z
            val rz = m6 * x + m7 * y + m8 * z
            rotated[o] = rx
            rotated[o + 1] = ry
            rotated[o + 2] = rz
            // Cosine between the outward normal (the unit centre) and the direction to the camera.
            val vz = d - rz
            val len = sqrt(rx * rx + ry * ry + vz * vz)
            val fc = (d * rz - 1f) / len
            facing[i] = fc
            if (fc > CULL_EPSILON) {
                order[visible] = i
                depth[visible] = rz
                visible++
            }
        }
        return visible
    }

    /** Insertion sort by depth (ascending z = farthest first). Nearly sorted between frames anyway. */
    private fun sortBackToFront(count: Int) {
        for (a in 1 until count) {
            val key = depth[a]
            val value = order[a]
            var b = a - 1
            while (b >= 0 && depth[b] > key) {
                depth[b + 1] = depth[b]
                order[b + 1] = order[b]
                b--
            }
            depth[b + 1] = key
            order[b + 1] = value
        }
    }

    /** Projects the corners of [cell] and rebuilds [path] from them. */
    private fun buildCellPath(cell: Int) {
        val m = matrix
        val d = SphereCamera.DISTANCE
        val start = cornerStart[cell]
        val count = cornerStart[cell + 1] - start
        path.reset()
        var p = start * 3
        var j = 0
        while (j < count) {
            val x = corners[p]
            val y = corners[p + 1]
            val z = corners[p + 2]
            val wx = m[0] * x + m[1] * y + m[2] * z
            val wy = m[3] * x + m[4] * y + m[5] * z
            val wz = m[6] * x + m[7] * y + m[8] * z
            val s = frameFocal / (d - wz)
            val sx = frameCx + wx * s
            val sy = frameCy - wy * s
            if (j == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            cellWorld[j * 3] = wx
            cellWorld[j * 3 + 1] = wy
            cellWorld[j * 3 + 2] = wz
            cellScreen[j * 2] = sx
            cellScreen[j * 2 + 1] = sy
            p += 3
            j++
        }
        path.close()
    }

    /** Decal in the cell's fixed model-space frame (flags, mines): it rotates together with the ball. */
    private fun setupDecal(cell: Int): Boolean {
        val m = matrix
        val o = cell * 3
        val ax = decalRight[o]
        val ay = decalRight[o + 1]
        val az = decalRight[o + 2]
        val bx = decalUp[o]
        val by = decalUp[o + 1]
        val bz = decalUp[o + 2]
        return setupDecalFrame(
            cell,
            m[0] * ax + m[1] * ay + m[2] * az,
            m[3] * ax + m[4] * ay + m[5] * az,
            m[6] * ax + m[7] * ay + m[8] * az,
            m[0] * bx + m[1] * by + m[2] * bz,
            m[3] * bx + m[4] * by + m[5] * bz,
            m[6] * bx + m[7] * by + m[8] * bz,
        )
    }

    /**
     * Decal aligned to the cell edge that is most horizontal on screen (digits). Must follow
     * buildCellPath(cell), which fills the corner buffers. The previous choice is kept until another
     * edge is clearly more horizontal, so the digit does not flicker between two equally good edges.
     */
    private fun setupAlignedDecal(cell: Int): Boolean {
        val count = cornerStart[cell + 1] - cornerStart[cell]
        if (count < 3) return false
        var best = -1
        var bestScore = Float.MAX_VALUE
        var previousScore = Float.MAX_VALUE
        val previous = digitEdge[cell]
        for (j in 0 until count) {
            val score = edgeSlope(j, if (j + 1 < count) j + 1 else 0)
            if (score < bestScore) {
                bestScore = score
                best = j
            }
            if (j == previous) previousScore = score
        }
        if (best < 0) return false
        if (previous in 0 until count && previousScore <= bestScore + EDGE_HYSTERESIS) best = previous
        digitEdge[cell] = best
        val a = best
        val b = if (best + 1 < count) best + 1 else 0

        // Edge direction in rotated model space, oriented to point right on screen.
        var ex = cellWorld[b * 3] - cellWorld[a * 3]
        var ey = cellWorld[b * 3 + 1] - cellWorld[a * 3 + 1]
        var ez = cellWorld[b * 3 + 2] - cellWorld[a * 3 + 2]
        if (cellScreen[b * 2] < cellScreen[a * 2]) {
            ex = -ex
            ey = -ey
            ez = -ez
        }
        // Project onto the tangent plane at the unit centre c, then up = c x right.
        val o = cell * 3
        val cx = rotated[o]
        val cy = rotated[o + 1]
        val cz = rotated[o + 2]
        val dot = ex * cx + ey * cy + ez * cz
        ex -= dot * cx
        ey -= dot * cy
        ez -= dot * cz
        val len = sqrt(ex * ex + ey * ey + ez * ez)
        if (len < 1e-6f) return false
        ex /= len
        ey /= len
        ez /= len
        return setupDecalFrame(
            cell, ex, ey, ez,
            cy * ez - cz * ey,
            cz * ex - cx * ez,
            cx * ey - cy * ex,
        )
    }

    /**
     * Decal fixed on the ball whose up axis points from the centre of [cell] towards the middle of its
     * edge [side] (the edge from corner cornerStart[cell] + side to the next corner), in the tangent
     * plane. Returns false for an invalid side or a degenerate frame.
     */
    private fun setupEdgeDecal(cell: Int, side: Int): Boolean {
        val start = cornerStart[cell]
        val count = cornerStart[cell + 1] - start
        if (side < 0 || side >= count) return false
        val a = (start + side) * 3
        val b = (start + (side + 1) % count) * 3
        val o = cell * 3
        val cx = centers[o]
        val cy = centers[o + 1]
        val cz = centers[o + 2]
        // Edge midpoint relative to the centre, projected onto the tangent plane at the unit centre c.
        var ux = (corners[a] + corners[b]) * 0.5f - cx
        var uy = (corners[a + 1] + corners[b + 1]) * 0.5f - cy
        var uz = (corners[a + 2] + corners[b + 2]) * 0.5f - cz
        val dot = ux * cx + uy * cy + uz * cz
        ux -= dot * cx
        uy -= dot * cy
        uz -= dot * cz
        val len = sqrt(ux * ux + uy * uy + uz * uz)
        if (len < 1e-6f) return false
        ux /= len
        uy /= len
        uz /= len
        // right = up x c, as in buildTangentFrame, so the decal is not mirrored.
        val rx = uy * cz - uz * cy
        val ry = uz * cx - ux * cz
        val rz = ux * cy - uy * cx
        val m = matrix
        return setupDecalFrame(
            cell,
            m[0] * rx + m[1] * ry + m[2] * rz,
            m[3] * rx + m[4] * ry + m[5] * rz,
            m[6] * rx + m[7] * ry + m[8] * rz,
            m[0] * ux + m[1] * uy + m[2] * uz,
            m[3] * ux + m[4] * uy + m[5] * uz,
            m[6] * ux + m[7] * uy + m[8] * uz,
        )
    }

    /** |sin| of the screen angle between edge (a, b) and the horizontal; MAX_VALUE for a degenerate edge. */
    private fun edgeSlope(a: Int, b: Int): Float {
        val dx = cellScreen[b * 2] - cellScreen[a * 2]
        val dy = cellScreen[b * 2 + 1] - cellScreen[a * 2 + 1]
        val len = sqrt(dx * dx + dy * dy)
        return if (len < 1e-3f) Float.MAX_VALUE else abs(dy) / len
    }

    /**
     * Sets [decalMatrix] so that decal space of [CellDecals] (x right, y down, [-DECAL_HALF, DECAL_HALF]
     * on both axes) maps with true perspective onto the square centre +- inRadius * (right, up) in the cell's tangent
     * plane. Right and up are unit vectors in rotated model space. Returns false when the projected
     * square is degenerate.
     */
    private fun setupDecalFrame(
        cell: Int, rightX: Float, rightY: Float, rightZ: Float, upX: Float, upY: Float, upZ: Float,
    ): Boolean {
        val o = cell * 3
        val s = inRadius[cell]
        // Right and up vectors pre-scaled by the half-size of the square.
        val rx = rightX * s
        val ry = rightY * s
        val rz = rightZ * s
        val ux = upX * s
        val uy = upY * s
        val uz = upZ * s
        val cx = rotated[o]
        val cy = rotated[o + 1]
        val cz = rotated[o + 2]
        // Order matches DECAL_SRC: top-left, top-right, bottom-right, bottom-left.
        projectDecalCorner(0, cx - rx + ux, cy - ry + uy, cz - rz + uz)
        projectDecalCorner(1, cx + rx + ux, cy + ry + uy, cz + rz + uz)
        projectDecalCorner(2, cx + rx - ux, cy + ry - uy, cz + rz - uz)
        projectDecalCorner(3, cx - rx - ux, cy - ry - uy, cz - rz - uz)
        return decalMatrix.setPolyToPoly(DECAL_SRC, 0, decalDst, 0, 4)
    }

    private fun projectDecalCorner(index: Int, x: Float, y: Float, z: Float) {
        val s = frameFocal / (SphereCamera.DISTANCE - z)
        decalDst[index * 2] = frameCx + x * s
        decalDst[index * 2 + 1] = frameCy - y * s
    }

    // ------------------------------------------------------------------ cells

    /**
     * Draws [cell]: its fill when [fill] is true and the digits and icons on it when [drawDecals] is true.
     * The two parts can be drawn in separate passes so that the gloss lies between them.
     */
    private fun drawCell(
        canvas: Canvas, cell: Int, board: MinesweeperBoard?, status: GameStatus, layout: BoardLayout?,
        fill: Boolean, drawDecals: Boolean,
    ) {
        val o = cell * 3
        val rx = rotated[o]
        val ry = rotated[o + 1]
        val rz = rotated[o + 2]
        val fc = facing[cell]
        val projR = cellRadius[cell] * frameFocal / (SphereCamera.DISTANCE - rz)
        buildCellPath(cell)

        // Lambert + Blinn specular + rim term, evaluated once per cell.
        val lambert = max(0f, rx * LIGHT_X + ry * LIGHT_Y + rz * LIGHT_Z)
        var spec = max(0f, rx * HALF_X + ry * HALF_Y + rz * HALF_Z)
        spec *= spec
        spec *= spec
        spec *= spec
        spec *= spec
        spec *= spec
        val rimT = 1f - MathUtils.clamp01(fc)
        val rim = rimT * rimT * rimT
        val light = lighting.ambient + lighting.diffuse * lambert
        val decalAlpha = MathUtils.smoothstep(DECAL_FADE_START, DECAL_FADE_END, fc)

        if (layout != null && !layout.isPresent(cell)) {
            if (fill) fillOcean(canvas, cell, projR, rx, ry, rz, light, spec, rim)
            return
        }
        if (board == null) {
            if (fill) fillCovered(canvas, cell, projR, light, spec, rim)
            return
        }

        val revealed = board.isRevealed(cell)
        val flagged = board.isFlagged(cell)
        val mine = board.minesPlaced && board.isMine(cell)
        val lost = status == GameStatus.LOST
        val won = status == GameStatus.WON

        if (lost && cell == board.explodedCell) {
            val c = palette.explodedCell
            if (fill) fillLit(canvas, cell, projR, c.r, c.g, c.b, light, spec, 0.4f)
            if (drawDecals) drawMineDecal(canvas, cell, decalAlpha, palette.mineOnRed)
        } else if (board.isDetonated(cell)) {
            // A mine that cost a life earlier: darker red than the one that ended the game.
            val c = palette.detonatedCell
            if (fill) fillLit(canvas, cell, projR, c.r, c.g, c.b, light, spec, 0.3f)
            if (drawDecals) drawMineDecal(canvas, cell, decalAlpha, palette.mineOnRed)
        } else if (lost && mine && !flagged) {
            val c = palette.lostMineCell
            if (fill) fillLit(canvas, cell, projR, c.r, c.g, c.b, light, spec, 0.25f)
            if (drawDecals) drawMineDecal(canvas, cell, decalAlpha, palette.mine)
        } else if (revealed) {
            val owner = frameMatch?.owner(cell) ?: MatchEngine.NO_OWNER
            if (fill && owner >= 0) {
                // An empty revealed cell gets its outline in drawEmptyCellOutlines.
                fillOwned(canvas, cell, projR, owner, light, spec, border = !isEmptyRevealed(cell, board, layout))
            } else if (fill) {
                val shade = lighting.revealedFlat + lighting.revealedLit * light
                val sheen = spec * lighting.revealedSheen
                val lift = lighting.revealedLift
                fillCell(
                    canvas, cell, projR,
                    revealedR[cell] * shade + lift.r + sheen,
                    revealedG[cell] * shade + lift.g + sheen,
                    revealedB[cell] * shade + lift.b + sheen,
                    // An empty revealed cell gets its outline in drawEmptyCellOutlines.
                    border = !isEmptyRevealed(cell, board, layout),
                )
            }
            if (drawDecals && !mine) {
                val far = layout != null && layout.isFar(cell)
                val line = layout != null && layout.isLine(cell)
                // A closed lock hides the number; the far ring and the line arrow still show.
                val locked = !board.isLockOpen(cell)
                val n = if (locked) 0 else board.adjacentMines(cell)
                if (far) {
                    drawFarDecal(canvas, cell, n, decalAlpha)
                } else if (line && layout != null) {
                    if (LineCells.showsEmptyRow(layout, board, cell)) {
                        drawEmptyLineDecal(canvas, cell, decalAlpha)
                    } else {
                        drawLineDecal(canvas, cell, n, layout.lineSide(cell), decalAlpha)
                    }
                } else if (n in 1..CellDecals.MAX_NUMBER) {
                    drawDigitDecal(canvas, cell, n, decalAlpha)
                }
                if (locked) {
                    drawLockDecal(canvas, cell, board, if (line) CellDecals.LINE_LOCK_SCALE else 1f, decalAlpha)
                } else if (board.lockThreshold(cell) > 0 && !far && !line) {
                    drawOpenLockDecal(canvas, cell, decalAlpha)
                }
            }
        } else if (lost && flagged && board.isWrongFlag(cell)) {
            // A flag on a safe cell: muted red cell and a greyed-out flag.
            val c = palette.wrongFlagCell
            if (fill) fillLit(canvas, cell, projR, c.r, c.g, c.b, light, spec, 0.25f)
            if (drawDecals) drawFlagDecal(canvas, cell, decalAlpha, palette.wrongPole, palette.wrongFlag)
        } else if (frameMatch != null && won && mine && !flagged) {
            // The end of a territory match: a mine nobody found belongs to nobody and shows as a plain mine.
            val c = palette.lostMineCell
            if (fill) fillLit(canvas, cell, projR, c.r, c.g, c.b, light, spec, 0.25f)
            if (drawDecals) drawMineDecal(canvas, cell, decalAlpha, palette.unownedMine)
        } else {
            // In a territory match a covered cell has an owner only when it is a found mine.
            val owner = frameMatch?.owner(cell) ?: MatchEngine.NO_OWNER
            if (fill && owner >= 0) {
                fillOwned(canvas, cell, projR, owner, light, spec, border = true)
            } else if (fill) {
                fillCovered(canvas, cell, projR, light, spec, rim)
            }
            if (drawDecals) {
                if (owner >= 0 && flagged) {
                    // A found mine of a match, on its owner's cell: a flag that stands out on the player colours.
                    drawFlagDecal(canvas, cell, decalAlpha, palette.ownedPole, palette.ownedFlag)
                } else if (flagged || (won && mine)) {
                    drawFlagDecal(canvas, cell, decalAlpha, palette.pole, palette.flag)
                } else if (!board.isLockOpen(cell)) {
                    drawLockDecal(canvas, cell, board, 1f, decalAlpha)
                } else if (layout == null || !layout.isPortal(cell)) {
                    // A covered cell with no other mark shows a dot: nothing is known about it yet.
                    drawCoveredDot(canvas, cell, decalAlpha)
                }
            }
        }
        if (drawDecals && layout != null && layout.isPortal(cell)) {
            // On a lock cell the full ring and dot would cover the padlock and its count.
            if (board.lockThreshold(cell) > 0) {
                drawPortalAroundLockDecal(canvas, cell, layout.portalPair(cell), decalAlpha)
            } else {
                drawPortalDecal(canvas, cell, layout.portalPair(cell), !revealed && !flagged, decalAlpha)
            }
        }
    }

    /**
     * Ocean cell: water lit like the ball, with a broad soft sheen and a small glint instead of
     * the gloss of covered cells. It has no border: the outline is stroked in the fill colour, as wide
     * as the border of land cells ([borderWidth]), so that neighbouring ocean cells merge without seams
     * and the coast leaves no gap next to the land border.
     */
    private fun fillOcean(
        canvas: Canvas, cell: Int, projR: Float, rx: Float, ry: Float, rz: Float, light: Float, spec: Float,
        rim: Float,
    ) {
        var sheen = max(0f, rx * HALF_X + ry * HALF_Y + rz * HALF_Z)
        sheen *= sheen
        sheen *= sheen
        sheen *= sheen
        val l = lighting
        val gloss = sheen * l.oceanSheen + spec * l.oceanGlint
        // Slight per-cell change in depth, like the jitter of the land colours.
        val hash = (cell * -1640531535) ushr 24
        val shade = (l.oceanFlat + l.oceanLit * light) * (1f + (hash / 255f - 0.5f) * l.oceanJitter)
        val tint = l.oceanGlossTint
        val rimTint = l.oceanRim
        val color = MathUtils.argb(
            1f,
            oceanR[cell] * shade + gloss * tint.r + rim * rimTint.r,
            oceanG[cell] * shade + gloss * tint.g + rim * rimTint.g,
            oceanB[cell] * shade + gloss * tint.b + rim * rimTint.b,
        )
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
        oceanEdgePaint.color = color
        oceanEdgePaint.strokeWidth = borderWidth(projR)
        canvas.drawPath(path, oceanEdgePaint)
    }

    private fun fillCovered(canvas: Canvas, cell: Int, projR: Float, light: Float, spec: Float, rim: Float) {
        val l = lighting
        val shade = l.coveredFlat + l.coveredLit * light
        val gloss = spec * l.coveredGloss
        fillCell(
            canvas, cell, projR,
            baseR[cell] * shade + gloss + rim * l.rim.r,
            baseG[cell] * shade + gloss + rim * l.rim.g,
            baseB[cell] * shade + gloss + rim * l.rim.b,
            border = true,
        )
    }

    /**
     * Fills [path] with colour (r, g, b) shaded like a covered cell under [light], plus a specular sheen
     * of strength [gloss].
     */
    private fun fillLit(
        canvas: Canvas, cell: Int, projR: Float, r: Float, g: Float, b: Float, light: Float, spec: Float,
        gloss: Float,
    ) {
        val shade = lighting.coveredFlat + lighting.coveredLit * light
        val sheen = spec * gloss
        fillCell(canvas, cell, projR, r * shade + sheen, g * shade + sheen, b * shade + sheen, border = true)
    }

    /**
     * Cell of player [owner] in a territory match: the player's colour shaded like a revealed cell under
     * [light], with the shade at least [SpherePalette.playerCellMinShade], plus the revealed sheen.
     */
    private fun fillOwned(
        canvas: Canvas, cell: Int, projR: Float, owner: Int, light: Float, spec: Float, border: Boolean,
    ) {
        val l = lighting
        val shade = max(palette.playerCellMinShade, l.revealedFlat + l.revealedLit * light)
        val sheen = spec * l.revealedSheen
        val c = palette.playerCells[owner]
        fillCell(
            canvas, cell, projR,
            c.r * shade + l.revealedLift.r + sheen,
            c.g * shade + l.revealedLift.g + sheen,
            c.b * shade + l.revealedLift.b + sheen,
            border,
        )
    }

    /**
     * One flat colour per cell, kept in [fillColor] for the marks drawn later, outlined with a thin
     * border that separates touching cells when [border] is true.
     */
    private fun fillCell(canvas: Canvas, cell: Int, projR: Float, r: Float, g: Float, b: Float, border: Boolean) {
        val color = MathUtils.argb(1f, r, g, b)
        fillColor[cell] = color
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
        if (!border) return
        borderPaint.strokeWidth = borderWidth(projR)
        canvas.drawPath(path, borderPaint)
    }

    /**
     * True for a revealed land cell that shows nothing: no number, no padlock and no mine, and not a far,
     * line or portal cell. It gets the soft outline of [drawEmptyCellOutlines] instead of the border.
     */
    private fun isEmptyRevealed(cell: Int, board: MinesweeperBoard?, layout: BoardLayout?): Boolean {
        if (board == null || !board.isRevealed(cell)) return false
        if (layout != null &&
            (!layout.isPresent(cell) || layout.isFar(cell) || layout.isLine(cell) || layout.isPortal(cell))
        ) {
            return false
        }
        if ((board.minesPlaced && board.isMine(cell)) || board.isDetonated(cell)) return false
        return board.lockThreshold(cell) <= 0 && board.adjacentMines(cell) == 0
    }

    /**
     * Outline of every visible empty revealed cell ([isEmptyRevealed]): the border mixed most of the way
     * towards the cell's fill ([SpherePalette.emptyCellBorder]), so that a cleared area nearly merges into
     * one surface. Along each edge shared with another land cell that is not empty, the normal border
     * goes over it, so that cell keeps its full border there. Both are clipped to the empty cell; the
     * other half of the edge keeps the border its own cell drew with its fill. The scope highlight of the
     * cell follows, over the outline, as on the other cells it lies over their border.
     */
    private fun drawEmptyCellOutlines(canvas: Canvas, visible: Int, board: MinesweeperBoard, layout: BoardLayout?) {
        val edgeNeighbor = geometry.edgeNeighbor
        for (k in 0 until visible) {
            val cell = order[k]
            if (!isEmptyRevealed(cell, board, layout)) continue
            buildCellPath(cell)
            val projR = cellRadius[cell] * frameFocal / (SphereCamera.DISTANCE - rotated[cell * 3 + 2])
            val width = borderWidth(projR)
            canvas.save()
            canvas.clipPath(path)
            emptyBorderPaint.strokeWidth = width
            emptyBorderPaint.color = palette.emptyCellBorder(fillColor[cell])
            canvas.drawPath(path, emptyBorderPaint)
            sharedBorderPaint.strokeWidth = width
            val start = cornerStart[cell]
            val count = cornerStart[cell + 1] - start
            for (j in 0 until count) {
                val other = edgeNeighbor[start + j]
                if (layout != null && !layout.isPresent(other)) continue
                if (isEmptyRevealed(other, board, layout)) continue
                val next = if (j + 1 < count) j + 1 else 0
                canvas.drawLine(
                    cellScreen[j * 2], cellScreen[j * 2 + 1], cellScreen[next * 2], cellScreen[next * 2 + 1],
                    sharedBorderPaint,
                )
            }
            canvas.restore()
            if (scopeMarked[cell]) drawScopeMark(canvas, cell)
        }
    }

    /** Waves on every visible ocean cell, fixed on the ball and fading towards the horizon like the decals. */
    private fun drawOceanWaves(canvas: Canvas, visible: Int, layout: BoardLayout) {
        for (k in 0 until visible) {
            val cell = order[k]
            if (layout.isPresent(cell)) continue
            val alpha = MathUtils.smoothstep(DECAL_FADE_START, DECAL_FADE_END, facing[cell])
            if (alpha < MIN_DECAL_ALPHA || !setupDecal(cell)) continue
            onDecal(canvas) { decals.drawWaves(canvas, alpha) }
        }
    }

    /**
     * Everything drawn over the surface, in this order: the coast, the digits and icons of every visible
     * cell, the line rows and the walls.
     */
    private fun drawCellMarks(
        canvas: Canvas, visible: Int, board: MinesweeperBoard?, status: GameStatus, layout: BoardLayout?,
    ) {
        if (layout != null) drawCoast(canvas, visible, layout)
        for (k in 0 until visible) {
            drawCell(canvas, order[k], board, status, layout, fill = false, drawDecals = true)
        }
        if (layout != null) {
            if (board != null) drawLineRows(canvas, layout, board)
            drawWalls(canvas, layout)
        }
    }

    /** Cell outline width in pixels for a cell of projected radius [projR]: proportional, kept thin. */
    private fun borderWidth(projR: Float): Float = min(BORDER_MAX, max(BORDER_MIN, projR * BORDER_PER_RADIUS))

    // ------------------------------------------------------------------ scope highlight

    /**
     * Points [scopeMarked] at the scope of [cell] on [board], or at no cells when [cell] is -1, out of
     * range or there is no board. Only the cells of the previous and the new scope are touched.
     */
    private fun updateScopeMarks(board: MinesweeperBoard?, cell: Int) {
        val scope = if (board != null && cell in 0 until min(cellCount, board.cellCount)) board.neighbors[cell] else null
        val target = if (scope == null) -1 else cell
        if (target == markedCell && scope === markedScope) return
        setScopeMarks(markedCell, markedScope, false)
        setScopeMarks(target, scope, true)
        markedCell = target
        markedScope = scope
    }

    private fun setScopeMarks(cell: Int, scope: IntArray?, value: Boolean) {
        if (cell < 0 || scope == null) return
        scopeMarked[cell] = value
        for (c in scope) {
            if (c in 0 until cellCount) scopeMarked[c] = value
        }
    }

    /**
     * Scope highlight on [cell], whose outline [path] holds: a translucent tint in the far colour on a
     * scope cell, and a band along the inside of the edge, wider on the highlighted far cell. The band is
     * the far colour next to the edge and a thin line in the palette's farOutline on its inner side,
     * where it meets the fill. The far colour is light in the dark themes and the line is dark, so
     * whatever the fill and its lighting, at least one of the two stands out against it.
     * Both are stroked on the outline and clipped to the cell, so cells drawn later cannot cover them.
     * The highlight fades towards the horizon like the decals.
     */
    private fun drawScopeMark(canvas: Canvas, cell: Int) {
        val alpha = MathUtils.smoothstep(DECAL_FADE_START, DECAL_FADE_END, facing[cell])
        if (alpha < MIN_DECAL_ALPHA) return
        val source = cell == markedCell
        val projR = cellRadius[cell] * frameFocal / (SphereCamera.DISTANCE - rotated[cell * 3 + 2])
        if (!source) {
            fillPaint.color = MathUtils.withAlpha(palette.far, alpha * SCOPE_TINT_ALPHA)
            canvas.drawPath(path, fillPaint)
        }
        val band = max(SCOPE_BAND_MIN, projR * (if (source) SOURCE_BAND_PER_RADIUS else SCOPE_BAND_PER_RADIUS))
        val edge = max(SCOPE_EDGE_MIN, projR * SCOPE_EDGE_PER_RADIUS)
        canvas.save()
        canvas.clipPath(path)
        // The outline colour is stroked wider and the far colour over it, which leaves a line of the
        // outline colour, [edge] wide, on the inner side of the band.
        scopePaint.strokeWidth = (band + edge) * 2f
        scopePaint.color = MathUtils.withAlpha(palette.farOutline, alpha)
        canvas.drawPath(path, scopePaint)
        scopePaint.strokeWidth = band * 2f
        scopePaint.color = MathUtils.withAlpha(palette.far, alpha)
        canvas.drawPath(path, scopePaint)
        canvas.restore()
    }

    // ------------------------------------------------------------------ decals

    /**
     * Saves the canvas, maps decal space onto the cell through [decalMatrix], runs [paint] and restores
     * the canvas. The frame must have been set up by one of the setup functions.
     */
    private inline fun onDecal(canvas: Canvas, paint: () -> Unit) {
        canvas.save()
        canvas.concat(decalMatrix)
        paint()
        canvas.restore()
    }

    /** Number of a plain revealed cell, upright on its most horizontal edge. */
    private fun drawDigitDecal(canvas: Canvas, cell: Int, n: Int, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA || !setupAlignedDecal(cell)) return
        onDecal(canvas) { decals.drawDigit(canvas, n, alpha) }
    }

    /** Far cell: ring in the far colour (gold), and the number inside it in the same colour when it is not zero. */
    private fun drawFarDecal(canvas: Canvas, cell: Int, n: Int, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA || !setupAlignedDecal(cell)) return
        onDecal(canvas) { decals.drawFar(canvas, n, alpha) }
    }

    /**
     * Line cell: number upright like a digit, and an arrow fixed on the ball at the edge [side] the
     * counted row starts through. A hidden number (closed lock) shows the arrow alone.
     */
    private fun drawLineDecal(canvas: Canvas, cell: Int, n: Int, side: Int, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA) return
        if (n in 1..CellDecals.MAX_NUMBER && setupAlignedDecal(cell)) {
            onDecal(canvas) { decals.drawLineNumber(canvas, n, alpha) }
        }
        if (!setupEdgeDecal(cell, side)) return
        onDecal(canvas) { decals.drawLineArrow(canvas, alpha) }
    }

    /**
     * Line cell whose row holds no mine ([LineCells.showsEmptyRow]): a short dash in the line colour, upright
     * like a digit, without the arrow.
     */
    private fun drawEmptyLineDecal(canvas: Canvas, cell: Int, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA || !setupAlignedDecal(cell)) return
        onDecal(canvas) { decals.drawEmptyLine(canvas, alpha) }
    }

    /**
     * Padlock of a closed lock with the number of cells still to reveal before it opens, upright like a
     * digit. [scale] shrinks it on line cells so that it stays clear of the arrow.
     */
    private fun drawLockDecal(canvas: Canvas, cell: Int, board: MinesweeperBoard, scale: Float, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA || !setupAlignedDecal(cell)) return
        val left = board.lockRemaining(cell)
        onDecal(canvas) { decals.drawLock(canvas, left, scale, alpha) }
    }

    /** Small faint open padlock below the number of a revealed cell whose lock has opened. */
    private fun drawOpenLockDecal(canvas: Canvas, cell: Int, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA || !setupAlignedDecal(cell)) return
        onDecal(canvas) { decals.drawOpenLock(canvas, alpha) }
    }

    /** Portal mark of [pair], fixed on the ball; [full] for covered cells without a flag. */
    private fun drawPortalDecal(canvas: Canvas, cell: Int, pair: Int, full: Boolean, alpha: Float) {
        if (pair < 0 || alpha < MIN_DECAL_ALPHA || !setupDecal(cell)) return
        onDecal(canvas) { decals.drawPortal(canvas, pair, full, alpha) }
    }

    /** Portal mark of [pair] on a lock cell: thin edge ring, upright like the padlock, open below it. */
    private fun drawPortalAroundLockDecal(canvas: Canvas, cell: Int, pair: Int, alpha: Float) {
        if (pair < 0 || alpha < MIN_DECAL_ALPHA || !setupAlignedDecal(cell)) return
        onDecal(canvas) { decals.drawPortalAroundLock(canvas, pair, alpha) }
    }

    /** Dot of a covered cell without other marks, in a shade of the fill drawn for it this frame. */
    private fun drawCoveredDot(canvas: Canvas, cell: Int, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA || !setupDecal(cell)) return
        val fill = fillColor[cell]
        onDecal(canvas) { decals.drawCoveredDot(canvas, fill, alpha) }
    }

    /** Flag fixed on the ball, in the given pole and pennant colours. */
    private fun drawFlagDecal(canvas: Canvas, cell: Int, alpha: Float, poleColor: Int, pennantColor: Int) {
        if (alpha < MIN_DECAL_ALPHA || !setupDecal(cell)) return
        onDecal(canvas) { decals.drawFlag(canvas, poleColor, pennantColor, alpha) }
    }

    /** Mine fixed on the ball, in [color]. */
    private fun drawMineDecal(canvas: Canvas, cell: Int, alpha: Float, color: Int) {
        if (alpha < MIN_DECAL_ALPHA || !setupDecal(cell)) return
        onDecal(canvas) { decals.drawMine(canvas, color, alpha) }
    }

    // ------------------------------------------------------------------ line rows

    /**
     * For every revealed line cell, a thin translucent line from its arrow edge through the centres of
     * the cells its number counts. Segments fade towards the horizon like the walls. A line whose row
     * shows no mine ([LineCells.showsEmptyRow]) gets no row line.
     */
    private fun drawLineRows(canvas: Canvas, layout: BoardLayout, board: MinesweeperBoard) {
        for (cell in 0 until cellCount) {
            if (!layout.isLine(cell) || !board.isRevealed(cell)) continue
            if (LineCells.showsEmptyRow(layout, board, cell)) continue
            val row = layout.linePath(cell)
            if (row.isEmpty()) continue
            val o = cell * 3
            val first = row[0]
            val f = first * 3
            // Start halfway to the first row cell, at the edge the arrow points at.
            var ax = (rotated[o] + rotated[f]) * 0.5f
            var ay = (rotated[o + 1] + rotated[f + 1]) * 0.5f
            var az = (rotated[o + 2] + rotated[f + 2]) * 0.5f
            var fa = min(facing[cell], facing[first])
            for (k in 0 until row.size) {
                val next = row[k]
                val q = next * 3
                val bx = rotated[q]
                val by = rotated[q + 1]
                val bz = rotated[q + 2]
                val fb = facing[next]
                drawRowSegment(canvas, ax, ay, az, fa, bx, by, bz, fb)
                ax = bx
                ay = by
                az = bz
                fa = fb
            }
        }
    }

    /** One row segment between rotated points a and b with facing values [fa] and [fb]. */
    private fun drawRowSegment(
        canvas: Canvas, ax: Float, ay: Float, az: Float, fa: Float, bx: Float, by: Float, bz: Float, fb: Float,
    ) {
        val alpha = MathUtils.smoothstep(DECAL_FADE_START, DECAL_FADE_END, min(fa, fb)) * ROW_ALPHA
        if (alpha < MIN_DECAL_ALPHA) return
        val d = SphereCamera.DISTANCE
        val sa = frameFocal / (d - az)
        val sb = frameFocal / (d - bz)
        rowPaint.strokeWidth = rowWidth * (sa + sb) * 0.5f
        rowPaint.color = MathUtils.withAlpha(palette.line, alpha)
        canvas.drawLine(frameCx + ax * sa, frameCy - ay * sa, frameCx + bx * sb, frameCy - by * sb, rowPaint)
    }

    // ------------------------------------------------------------------ walls

    /** Every wall segment whose ends face the viewer: a wide soft glow under a thin bright core. */
    private fun drawWalls(canvas: Canvas, layout: BoardLayout) {
        for (wall in layout.walls) {
            for (k in 0 until wall.size - 1) {
                drawWallSegment(canvas, wall[k], wall[k + 1])
            }
        }
    }

    private fun drawWallSegment(canvas: Canvas, cornerA: Int, cornerB: Int) {
        val alpha = projectEdge(cornerA, cornerB)
        if (alpha < MIN_DECAL_ALPHA) return
        val width = wallWidth * edgeScale
        wallGlowPaint.strokeWidth = width * WALL_GLOW_FACTOR
        wallGlowPaint.color = MathUtils.withAlpha(palette.wallGlow, alpha * WALL_GLOW_ALPHA)
        canvas.drawLine(edgeX0, edgeY0, edgeX1, edgeY1, wallGlowPaint)
        wallCorePaint.strokeWidth = width
        wallCorePaint.color = MathUtils.withAlpha(palette.wallCore, alpha)
        canvas.drawLine(edgeX0, edgeY0, edgeX1, edgeY1, wallCorePaint)
    }

    /**
     * Projects the cell edge between corner indices [cornerA] and [cornerB] into [edgeX0], [edgeY0],
     * [edgeX1], [edgeY1] and its mean perspective scale into [edgeScale]. Returns its fade towards the
     * horizon (0..1); below [MIN_DECAL_ALPHA] the screen values are not set.
     */
    private fun projectEdge(cornerA: Int, cornerB: Int): Float {
        val m = matrix
        val d = SphereCamera.DISTANCE
        var p = cornerA * 3
        val ax = m[0] * corners[p] + m[1] * corners[p + 1] + m[2] * corners[p + 2]
        val ay = m[3] * corners[p] + m[4] * corners[p + 1] + m[5] * corners[p + 2]
        val az = m[6] * corners[p] + m[7] * corners[p + 1] + m[8] * corners[p + 2]
        p = cornerB * 3
        val bx = m[0] * corners[p] + m[1] * corners[p + 1] + m[2] * corners[p + 2]
        val by = m[3] * corners[p] + m[4] * corners[p + 1] + m[5] * corners[p + 2]
        val bz = m[6] * corners[p] + m[7] * corners[p + 1] + m[8] * corners[p + 2]
        // Same facing measure as the cells: cosine between the surface normal and the view direction.
        val fa = (d * az - 1f) / sqrt(ax * ax + ay * ay + (d - az) * (d - az))
        val fb = (d * bz - 1f) / sqrt(bx * bx + by * by + (d - bz) * (d - bz))
        val alpha = MathUtils.smoothstep(DECAL_FADE_START, DECAL_FADE_END, min(fa, fb))
        if (alpha < MIN_DECAL_ALPHA) return alpha
        val sa = frameFocal / (d - az)
        val sb = frameFocal / (d - bz)
        edgeX0 = frameCx + ax * sa
        edgeY0 = frameCy - ay * sa
        edgeX1 = frameCx + bx * sb
        edgeY1 = frameCy - by * sb
        edgeScale = (sa + sb) * 0.5f
        return alpha
    }

    // ------------------------------------------------------------------ coast

    /**
     * Coastline: a heavy line along every edge between a visible land cell and the ocean, over the casing
     * of the palette when it has one. Each pass draws all edges, so the casing never covers the line
     * where two edges meet. It fades towards the horizon like the walls.
     */
    private fun drawCoast(canvas: Canvas, visible: Int, layout: BoardLayout) {
        if ((palette.coastCasing ushr 24) != 0) {
            drawCoastPass(canvas, visible, layout, palette.coastCasing, coastWidth * COAST_CASING_FACTOR)
        }
        if ((palette.coast ushr 24) != 0) drawCoastPass(canvas, visible, layout, palette.coast, coastWidth)
    }

    /** Every coast edge of the visible land cells in [color], [width] model units wide. */
    private fun drawCoastPass(canvas: Canvas, visible: Int, layout: BoardLayout, color: Int, width: Float) {
        val edgeNeighbor = geometry.edgeNeighbor
        val colorAlpha = (color ushr 24) / 255f
        for (k in 0 until visible) {
            val cell = order[k]
            if (!layout.isPresent(cell)) continue
            val start = cornerStart[cell]
            val end = cornerStart[cell + 1]
            for (c in start until end) {
                if (layout.isPresent(edgeNeighbor[c])) continue
                val alpha = projectEdge(c, if (c + 1 < end) c + 1 else start)
                if (alpha < MIN_DECAL_ALPHA) continue
                coastPaint.strokeWidth = width * edgeScale
                coastPaint.color = MathUtils.withAlpha(color, alpha * colorAlpha)
                canvas.drawLine(edgeX0, edgeY0, edgeX1, edgeY1, coastPaint)
            }
        }
    }

    // ------------------------------------------------------------------ backdrop and effects

    private fun drawBackdrop(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        won: Boolean, effect: SphereEffect, effectTime: Float,
    ) {
        drawUnitShader(canvas, haloShader, cx, cy, radius * 1.55f, radius * 1.55f, 1f)
        // Soft light pool under the ball.
        drawUnitShader(canvas, poolShader, cx, cy + radius * 1.12f, radius * 0.95f, radius * 0.2f, 0.9f)
        if (won) {
            val alpha = if (effect == SphereEffect.WIN && effectTime < WIN_SECONDS) {
                0.65f + 0.35f * sin(effectTime * 7f)
            } else {
                0.7f
            }
            drawUnitShader(canvas, winShader, cx, cy, radius * 1.5f, radius * 1.5f, alpha)
        }
        drawUnitShader(canvas, coreShader, cx, cy, radius * 0.998f, radius * 0.998f, 1f)
    }

    private fun drawUnitShader(
        canvas: Canvas, shader: Shader, x: Float, y: Float, scaleX: Float, scaleY: Float, alpha: Float,
    ) {
        if (scaleX <= 0f || scaleY <= 0f) return
        shaderPaint.shader = shader
        shaderPaint.alpha = (MathUtils.clamp01(alpha) * 255f).toInt()
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scaleX, scaleY)
        canvas.drawCircle(0f, 0f, 1f, shaderPaint)
        canvas.restore()
    }

    private fun drawExplosion(canvas: Canvas, cell: Int, radius: Float, t: Float) {
        // Red screen tint right after the hit.
        if (t < FLASH_TINT_SECONDS) {
            canvas.drawColor(MathUtils.withAlpha(palette.tint, 0.3f * (1f - t / FLASH_TINT_SECONDS)))
        }
        if (cell < 0 || cell >= cellCount) return
        val o = cell * 3
        val s = frameFocal / (SphereCamera.DISTANCE - rotated[o + 2])
        val x = frameCx + rotated[o] * s
        val y = frameCy - rotated[o + 1] * s
        val p = t / EXPLOSION_SECONDS
        val r = radius * (0.12f + 1.3f * MathUtils.easeOutCubic(p))
        val fade = 1f - p
        drawUnitShader(canvas, explosionShader, x, y, r, r, fade * fade)
    }

    /** Two expanding rings around the cell a hint revealed; they follow the cell while the ball turns. */
    private fun drawHintPulse(canvas: Canvas, cell: Int, t: Float) {
        if (cell < 0 || cell >= cellCount || facing[cell] <= 0f) return
        val o = cell * 3
        val s = frameFocal / (SphereCamera.DISTANCE - rotated[o + 2])
        val x = frameCx + rotated[o] * s
        val y = frameCy - rotated[o + 1] * s
        val base = cellRadius[cell] * s
        ringPaint.strokeWidth = max(2f, base * 0.12f)
        for (ring in 0 until 2) {
            val p = (t / HINT_SECONDS - ring * 0.25f) / 0.75f
            if (p <= 0f || p >= 1f) continue
            val fade = 1f - p
            ringPaint.color = MathUtils.withAlpha(palette.hint, fade * fade)
            canvas.drawCircle(x, y, base * (0.8f + 1.6f * MathUtils.easeOutCubic(p)), ringPaint)
        }
    }

    private fun drawConfetti(canvas: Canvas, cx: Float, cy: Float, radius: Float, t: Float) {
        val fadeOut = 1f - MathUtils.smoothstep(WIN_SECONDS - 1f, WIN_SECONDS, t)
        if (fadeOut <= 0f) return
        for (p in 0 until CONFETTI_COUNT) {
            val tt = t - cfDelay[p]
            if (tt <= 0f) continue
            val travel = radius * 0.3f + radius * cfSpeed[p] * tt / (0.35f + tt)
            val x = cx + cfDirX[p] * travel
            val y = cy + cfDirY[p] * travel + radius * 0.35f * tt * tt
            val size = radius * 0.03f * cfSize[p]
            confettiPaint.color = MathUtils.withAlpha(confettiColors[cfColor[p]], fadeOut)
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(cfSpin[p] * tt * 360f)
            canvas.scale(1f, cos(tt * cfFlutter[p]))
            canvas.drawRect(-size, -size * 0.5f, size, size * 0.5f, confettiPaint)
            canvas.restore()
        }
    }

    companion object {
        private const val CULL_EPSILON = 0.01f
        // Cell border width in pixels: proportional to the projected cell size, kept thin.
        private const val BORDER_MIN = 1f
        private const val BORDER_MAX = 2.6f
        private const val BORDER_PER_RADIUS = 0.035f

        // Decals fade out towards the horizon, where they are seen almost edge-on.
        private const val DECAL_FADE_START = 0.06f
        private const val DECAL_FADE_END = 0.3f
        private const val MIN_DECAL_ALPHA = 0.02f

        /** Half-size of the decal square in decal units; it maps to the cell's inscribed radius. */
        private const val DECAL_HALF = CellDecals.HALF_SIZE
        private val DECAL_SRC = floatArrayOf(
            -DECAL_HALF, -DECAL_HALF,
            DECAL_HALF, -DECAL_HALF,
            DECAL_HALF, DECAL_HALF,
            -DECAL_HALF, DECAL_HALF,
        )

        // Scope highlight: tint alpha, band widths inside the cell relative to its projected radius, the
        // narrowest band in pixels, and the width of the outline line on the band's inner side (relative
        // to the projected radius, and the narrowest in pixels).
        private const val SCOPE_TINT_ALPHA = 0.22f
        private const val SCOPE_BAND_PER_RADIUS = 0.09f
        private const val SOURCE_BAND_PER_RADIUS = 0.17f
        private const val SCOPE_BAND_MIN = 2f
        private const val SCOPE_EDGE_PER_RADIUS = 0.04f
        private const val SCOPE_EDGE_MIN = 1.5f

        // Row lines: thin and translucent, width relative to the mean cell radius.
        private const val ROW_WIDTH_PER_RADIUS = 0.045f
        private const val ROW_ALPHA = 0.4f

        // Walls: core width relative to the mean cell radius, glow width relative to the core.
        private const val WALL_WIDTH_PER_RADIUS = 0.07f
        private const val WALL_GLOW_FACTOR = 3.2f
        private const val WALL_GLOW_ALPHA = 0.4f

        // Coastline: width relative to the mean cell radius (circumradius), and the width of the casing
        // under it relative to the line.
        private const val COAST_WIDTH_PER_RADIUS = 3.9f / 22f
        private const val COAST_CASING_FACTOR = 1.6f

        /** A digit switches to another edge only when that edge is this much flatter (|sin|, about 5 degrees). */
        private const val EDGE_HYSTERESIS = 0.09f

        private const val SHAKE_SECONDS = 0.5f
        private const val FLASH_TINT_SECONDS = 0.35f
        private const val EXPLOSION_SECONDS = 1.0f
        private const val WIN_SECONDS = 3.2f
        private const val HINT_SECONDS = 1.4f

        private const val CONFETTI_COUNT = 120
        private const val CONFETTI_SEED = 20240611

        // Light from the upper left front, and the Blinn half vector with the view direction (0, 0, 1).
        private val LIGHT_X: Float
        private val LIGHT_Y: Float
        private val LIGHT_Z: Float
        private val HALF_X: Float
        private val HALF_Y: Float
        private val HALF_Z: Float

        init {
            val lx = -0.45f
            val ly = 0.6f
            val lz = 0.66f
            val ll = sqrt(lx * lx + ly * ly + lz * lz)
            LIGHT_X = lx / ll
            LIGHT_Y = ly / ll
            LIGHT_Z = lz / ll
            val hx = LIGHT_X
            val hy = LIGHT_Y
            val hz = LIGHT_Z + 1f
            val hl = sqrt(hx * hx + hy * hy + hz * hz)
            HALF_X = hx / hl
            HALF_Y = hy / hl
            HALF_Z = hz / hl
        }

        private val EXPLOSION_NANOS = (EXPLOSION_SECONDS * 1e9f).toLong()
        private val WIN_NANOS = (WIN_SECONDS * 1e9f).toLong()
        private val HINT_NANOS = (HINT_SECONDS * 1e9f).toLong()

        /** How long [effect] needs frame ticks, in nanoseconds. */
        fun effectDurationNanos(effect: SphereEffect): Long = when (effect) {
            SphereEffect.NONE -> 0L
            SphereEffect.HINT -> HINT_NANOS
            SphereEffect.MINE_HIT, SphereEffect.EXPLOSION -> EXPLOSION_NANOS
            SphereEffect.WIN -> WIN_NANOS
        }
    }
}
