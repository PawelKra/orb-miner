package com.example.spheresweeper.ui

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.example.spheresweeper.logic.BoardLayout
import com.example.spheresweeper.logic.GameStatus
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
 * points at the edge their row starts through; once revealed, a faint line runs through the row.
 * Walls are glowing lines along cell edges, drawn on top of the cells and the row lines.
 *
 * Ocean cells are water with a soft sheen and no border or decal. Both cells of a portal pair
 * carry the same mark in the pair's colour: a ring with a dot on a covered cell, a thin ring along the
 * edge on a revealed or flagged one so the number stays readable. A cell whose lock is still closed
 * shows a padlock with the number of cells left to reveal instead of its own number; once the lock
 * opens, the number shows with a small open padlock below it.
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
    private val numberColors = palette.numbers
    private val portalColors = palette.portals
    private val confettiColors = palette.confetti

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

    private val path = Path()

    /** Closed padlock shackle in decal space, open at the bottom where it meets the body. */
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

    /** Line-cell arrowhead in decal space pointing up (towards -y), built once. */
    private val arrowPath = Path().apply {
        moveTo(0f, -LINE_ARROW_TIP)
        lineTo(LINE_ARROW_HALF_WIDTH, -LINE_ARROW_BASE)
        lineTo(0f, -LINE_ARROW_NOTCH)
        lineTo(-LINE_ARROW_HALF_WIDTH, -LINE_ARROW_BASE)
        close()
    }

    /** Flag pennant in decal space, built once. */
    private val pennantPath = Path().apply {
        moveTo(FLAG_POLE_X, FLAG_TOP)
        lineTo(FLAG_TIP_X, FLAG_TIP_Y)
        lineTo(FLAG_POLE_X, FLAG_PENNANT_BOTTOM)
        close()
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = palette.border
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        this.typeface = typeface
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
    private val arrowOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

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
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val confettiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Per-number offsets that put the centre of the glyph's ink bounds on the decal origin. */
    private val digitX = FloatArray(MAX_NUMBER + 1)
    private val digitY = FloatArray(MAX_NUMBER + 1)

    /** Per-number scale that fits the number inside the ring of a far cell. */
    private val farScale = FloatArray(MAX_NUMBER + 1)

    /** Per-number scale that keeps the number of a line cell clear of its arrow. */
    private val lineScale = FloatArray(MAX_NUMBER + 1)

    /**
     * Texts, centring offsets and scales of the counts a padlock can show (cells left to reveal before
     * a lock opens), indexed by the count; no threshold exceeds the cell count.
     */
    private val lockText = Array(cellCount + 1) { it.toString() }
    private val lockTextX = FloatArray(cellCount + 1)
    private val lockTextY = FloatArray(cellCount + 1)
    private val lockTextScale = FloatArray(cellCount + 1)

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
        for (n in 0..cellCount) {
            val text = lockText[n]
            textPaint.getTextBounds(text, 0, text.length, bounds)
            lockTextX[n] = -(bounds.left + bounds.right) * 0.5f
            lockTextY[n] = -(bounds.top + bounds.bottom) * 0.5f
            lockTextScale[n] = min(
                LOCK_TEXT_MAX_SCALE,
                min(LOCK_TEXT_WIDTH / max(1, bounds.width()), LOCK_TEXT_HEIGHT / max(1, bounds.height())),
            )
        }

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
    ) {
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

        drawBackdrop(canvas, cx, cy, radius, won, effect, effectTime)

        orientation.toRotationMatrix(matrix)
        val visible = transformAndCull()
        sortBackToFront(visible)
        if (palette.decalsOverGloss) {
            // Cell fills, then the gloss, then everything drawn on the cells.
            for (k in 0 until visible) {
                drawCell(canvas, order[k], board, status, layout, fill = true, decals = false)
            }
            drawUnitShader(canvas, glossShader, cx, cy, radius, radius, 1f)
            for (k in 0 until visible) {
                drawCell(canvas, order[k], board, status, layout, fill = false, decals = true)
            }
            if (layout != null) {
                if (board != null) drawLineRows(canvas, layout, board)
                drawWalls(canvas, layout)
            }
        } else {
            for (k in 0 until visible) {
                drawCell(canvas, order[k], board, status, layout, fill = true, decals = true)
            }
            if (layout != null) {
                if (board != null) drawLineRows(canvas, layout, board)
                drawWalls(canvas, layout)
            }
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
     * Sets [decalMatrix] so that decal space (x right, y down, [-DECAL_HALF, DECAL_HALF] on both axes)
     * maps with true perspective onto the square centre +- inRadius * (right, up) in the cell's tangent
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
     * Draws [cell]: its fill when [fill] is true and the digits and icons on it when [decals] is true.
     * The two parts can be drawn in separate passes so that the gloss lies between them.
     */
    private fun drawCell(
        canvas: Canvas, cell: Int, board: MinesweeperBoard?, status: GameStatus, layout: BoardLayout?,
        fill: Boolean, decals: Boolean,
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
            if (fill) fillLit(canvas, projR, c.r, c.g, c.b, light, spec, 0.4f)
            if (decals) drawMineDecal(canvas, cell, decalAlpha, palette.mineOnRed)
        } else if (board.isDetonated(cell)) {
            // A mine that cost a life earlier: darker red than the one that ended the game.
            val c = palette.detonatedCell
            if (fill) fillLit(canvas, projR, c.r, c.g, c.b, light, spec, 0.3f)
            if (decals) drawMineDecal(canvas, cell, decalAlpha, palette.mineOnRed)
        } else if (lost && mine && !flagged) {
            val c = palette.lostMineCell
            if (fill) fillLit(canvas, projR, c.r, c.g, c.b, light, spec, 0.25f)
            if (decals) drawMineDecal(canvas, cell, decalAlpha, palette.mine)
        } else if (revealed) {
            if (fill) {
                val shade = lighting.revealedFlat + lighting.revealedLit * light
                val sheen = spec * lighting.revealedSheen
                val lift = lighting.revealedLift
                fillCell(
                    canvas, projR,
                    revealedR[cell] * shade + lift.r + sheen,
                    revealedG[cell] * shade + lift.g + sheen,
                    revealedB[cell] * shade + lift.b + sheen,
                )
            }
            if (decals && !mine) {
                val far = layout != null && layout.isFar(cell)
                val line = layout != null && layout.isLine(cell)
                // A closed lock hides the number; the far ring and the line arrow still show.
                val locked = !board.isLockOpen(cell)
                val n = if (locked) 0 else board.adjacentMines(cell)
                if (far) {
                    drawFarDecal(canvas, cell, n, decalAlpha)
                } else if (line && layout != null) {
                    drawLineDecal(canvas, cell, n, layout.lineSide(cell), decalAlpha)
                } else if (n in 1..MAX_NUMBER) {
                    drawDigitDecal(canvas, cell, n, decalAlpha)
                }
                if (locked) {
                    drawLockDecal(canvas, cell, board, if (line) LOCK_LINE_SCALE else 1f, decalAlpha)
                } else if (board.lockThreshold(cell) > 0 && !far && !line) {
                    drawOpenLockDecal(canvas, cell, decalAlpha)
                }
            }
        } else if (lost && flagged && board.isWrongFlag(cell)) {
            // A flag on a safe cell: muted red cell and a greyed-out flag.
            val c = palette.wrongFlagCell
            if (fill) fillLit(canvas, projR, c.r, c.g, c.b, light, spec, 0.25f)
            if (decals) drawFlagDecal(canvas, cell, decalAlpha, palette.wrongPole, palette.wrongFlag)
        } else {
            if (fill) fillCovered(canvas, cell, projR, light, spec, rim)
            if (decals) {
                if (flagged || (won && mine)) {
                    drawFlagDecal(canvas, cell, decalAlpha, palette.pole, palette.flag)
                } else if (!board.isLockOpen(cell)) {
                    drawLockDecal(canvas, cell, board, 1f, decalAlpha)
                }
            }
        }
        if (decals && layout != null && layout.isPortal(cell)) {
            drawPortalDecal(canvas, cell, layout.portalPair(cell), !revealed && !flagged, decalAlpha)
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
            canvas, projR,
            baseR[cell] * shade + gloss + rim * l.rim.r,
            baseG[cell] * shade + gloss + rim * l.rim.g,
            baseB[cell] * shade + gloss + rim * l.rim.b,
        )
    }

    /**
     * Fills [path] with colour (r, g, b) shaded like a covered cell under [light], plus a specular sheen
     * of strength [gloss].
     */
    private fun fillLit(
        canvas: Canvas, projR: Float, r: Float, g: Float, b: Float, light: Float, spec: Float, gloss: Float,
    ) {
        val shade = lighting.coveredFlat + lighting.coveredLit * light
        val sheen = spec * gloss
        fillCell(canvas, projR, r * shade + sheen, g * shade + sheen, b * shade + sheen)
    }

    /** One flat colour per cell, outlined with a thin border that separates touching cells. */
    private fun fillCell(canvas: Canvas, projR: Float, r: Float, g: Float, b: Float) {
        fillPaint.color = MathUtils.argb(1f, r, g, b)
        canvas.drawPath(path, fillPaint)
        borderPaint.strokeWidth = borderWidth(projR)
        canvas.drawPath(path, borderPaint)
    }

    /** Cell outline width in pixels for a cell of projected radius [projR]: proportional, kept thin. */
    private fun borderWidth(projR: Float): Float = min(BORDER_MAX, max(BORDER_MIN, projR * BORDER_PER_RADIUS))

    // ------------------------------------------------------------------ decals

    private fun drawDigitDecal(canvas: Canvas, cell: Int, n: Int, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA || !setupAlignedDecal(cell)) return
        canvas.save()
        canvas.concat(decalMatrix)
        val x = digitX[n]
        val y = digitY[n]
        val text = DIGITS[n]
        drawDigitShadow(canvas, text, x, y, alpha)
        textPaint.color = MathUtils.withAlpha(numberColors[min(n, numberColors.size - 1)], alpha)
        canvas.drawText(text, 0, text.length, x, y, textPaint)
        canvas.restore()
    }

    /** Soft shadow under a number at decal position (x, y); themes without a shadow skip it. */
    private fun drawDigitShadow(canvas: Canvas, text: String, x: Float, y: Float, alpha: Float) {
        if (palette.digitShadowAlpha <= 0f) return
        textPaint.color = MathUtils.withAlpha(palette.digitShadow, alpha * palette.digitShadowAlpha)
        canvas.drawText(text, 0, text.length, x + DIGIT_SHADOW_OFFSET, y + DIGIT_SHADOW_OFFSET, textPaint)
    }

    /** Far cell: ring in the far colour (gold), and the number inside it in the same colour when it is not zero. */
    private fun drawFarDecal(canvas: Canvas, cell: Int, n: Int, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA || !setupAlignedDecal(cell)) return
        canvas.save()
        canvas.concat(decalMatrix)
        ringPaint.strokeWidth = FAR_RING_STROKE
        ringPaint.color = MathUtils.withAlpha(palette.far, alpha * FAR_RING_ALPHA)
        canvas.drawCircle(0f, 0f, FAR_RING_RADIUS, ringPaint)
        if (n in 1..MAX_NUMBER) {
            val s = farScale[n]
            canvas.scale(s, s)
            val text = DIGITS[n]
            val x = digitX[n]
            val y = digitY[n]
            drawDigitShadow(canvas, text, x, y, alpha)
            textPaint.color = MathUtils.withAlpha(palette.far, alpha)
            canvas.drawText(text, 0, text.length, x, y, textPaint)
        }
        canvas.restore()
    }

    /**
     * Line cell: number in the line colour (turquoise), scaled to stay clear of the arrow, and an arrow at the edge [side]
     * the counted row starts through. A zero shows the arrow alone.
     */
    private fun drawLineDecal(canvas: Canvas, cell: Int, n: Int, side: Int, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA) return
        if (n in 1..MAX_NUMBER && setupAlignedDecal(cell)) {
            canvas.save()
            canvas.concat(decalMatrix)
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
        if (!setupEdgeDecal(cell, side)) return
        canvas.save()
        canvas.concat(decalMatrix)
        // An outline keeps the arrow readable on light and dark cells alike.
        arrowOutlinePaint.strokeWidth = LINE_ARROW_OUTLINE
        arrowOutlinePaint.color = decalOutline(alpha)
        canvas.drawPath(arrowPath, arrowOutlinePaint)
        iconPaint.color = MathUtils.withAlpha(palette.line, alpha)
        canvas.drawPath(arrowPath, iconPaint)
        canvas.restore()
    }

    /**
     * Padlock of a closed lock: shackle, body and the number of cells still to reveal before it opens,
     * upright like a digit. [scale] shrinks it on line cells so that it stays clear of the arrow.
     */
    private fun drawLockDecal(canvas: Canvas, cell: Int, board: MinesweeperBoard, scale: Float, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA || !setupAlignedDecal(cell)) return
        val left = (board.lockThreshold(cell) - board.revealedCount).coerceIn(0, cellCount)
        canvas.save()
        canvas.concat(decalMatrix)
        if (scale != 1f) canvas.scale(scale, scale)
        // An outline keeps the padlock readable on covered and revealed cells alike.
        val outline = decalOutline(alpha)
        iconStroke.color = outline
        iconStroke.strokeWidth = LOCK_SHACKLE_STROKE + LOCK_OUTLINE
        canvas.drawPath(shacklePath, iconStroke)
        arrowOutlinePaint.color = outline
        arrowOutlinePaint.strokeWidth = LOCK_OUTLINE
        canvas.drawRoundRect(
            -LOCK_BODY_HALF_WIDTH, LOCK_BODY_TOP, LOCK_BODY_HALF_WIDTH, LOCK_BODY_BOTTOM,
            LOCK_BODY_CORNER, LOCK_BODY_CORNER, arrowOutlinePaint,
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

    /** Small faint open padlock below the number of a revealed cell whose lock has opened. */
    private fun drawOpenLockDecal(canvas: Canvas, cell: Int, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA || !setupAlignedDecal(cell)) return
        canvas.save()
        canvas.concat(decalMatrix)
        val color = MathUtils.withAlpha(palette.lock, alpha * OPEN_LOCK_ALPHA)
        iconStroke.color = color
        iconStroke.strokeWidth = OPEN_SHACKLE_STROKE
        canvas.drawPath(openShacklePath, iconStroke)
        iconPaint.color = color
        canvas.drawRoundRect(
            -OPEN_BODY_HALF_WIDTH, OPEN_BODY_TOP, OPEN_BODY_HALF_WIDTH, OPEN_BODY_BOTTOM,
            OPEN_BODY_CORNER, OPEN_BODY_CORNER, iconPaint,
        )
        canvas.restore()
    }

    /**
     * Portal mark in the colour of [pair]. [full]: a ring with a dot in the middle, for covered cells
     * without a flag. Otherwise a thin ring along the cell edge that leaves room for a number or flag.
     */
    private fun drawPortalDecal(canvas: Canvas, cell: Int, pair: Int, full: Boolean, alpha: Float) {
        if (pair < 0 || alpha < MIN_DECAL_ALPHA || !setupDecal(cell)) return
        canvas.save()
        canvas.concat(decalMatrix)
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
            // Pairs beyond the palette repeat its colours with a hollow dot.
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
        canvas.restore()
    }

    /** Outline colour of arrows, padlocks and portal marks at decal [alpha]. */
    private fun decalOutline(alpha: Float): Int =
        MathUtils.withAlpha(palette.decalOutline, alpha * palette.decalOutlineAlpha)

    // ------------------------------------------------------------------ line rows

    /**
     * For every revealed line cell, a thin translucent line from its arrow edge through the centres of
     * the cells its number counts. Segments fade towards the horizon like the walls.
     */
    private fun drawLineRows(canvas: Canvas, layout: BoardLayout, board: MinesweeperBoard) {
        for (cell in 0 until cellCount) {
            if (!layout.isLine(cell) || !board.isRevealed(cell)) continue
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
        if (alpha < MIN_DECAL_ALPHA) return
        val sa = frameFocal / (d - az)
        val sb = frameFocal / (d - bz)
        val x0 = frameCx + ax * sa
        val y0 = frameCy - ay * sa
        val x1 = frameCx + bx * sb
        val y1 = frameCy - by * sb
        val width = wallWidth * (sa + sb) * 0.5f
        wallGlowPaint.strokeWidth = width * WALL_GLOW_FACTOR
        wallGlowPaint.color = MathUtils.withAlpha(palette.wallGlow, alpha * WALL_GLOW_ALPHA)
        canvas.drawLine(x0, y0, x1, y1, wallGlowPaint)
        wallCorePaint.strokeWidth = width
        wallCorePaint.color = MathUtils.withAlpha(palette.wallCore, alpha)
        canvas.drawLine(x0, y0, x1, y1, wallCorePaint)
    }

    /** Flat flag icon: thin pole, triangular pennant and a short base line. */
    private fun drawFlagDecal(canvas: Canvas, cell: Int, alpha: Float, poleColor: Int, pennantColor: Int) {
        if (alpha < MIN_DECAL_ALPHA || !setupDecal(cell)) return
        canvas.save()
        canvas.concat(decalMatrix)
        iconStroke.strokeWidth = FLAG_STROKE
        iconStroke.color = MathUtils.withAlpha(poleColor, alpha)
        canvas.drawLine(FLAG_POLE_X, FLAG_TOP, FLAG_POLE_X, FLAG_BASE_Y, iconStroke)
        canvas.drawLine(FLAG_BASE_LEFT, FLAG_BASE_Y, FLAG_BASE_RIGHT, FLAG_BASE_Y, iconStroke)
        iconPaint.color = MathUtils.withAlpha(pennantColor, alpha)
        canvas.drawPath(pennantPath, iconPaint)
        canvas.restore()
    }

    /** Flat spiked ball with a small highlight dot. */
    private fun drawMineDecal(canvas: Canvas, cell: Int, alpha: Float, color: Int) {
        if (alpha < MIN_DECAL_ALPHA || !setupDecal(cell)) return
        canvas.save()
        canvas.concat(decalMatrix)
        iconStroke.strokeWidth = MINE_SPIKE_STROKE
        iconStroke.color = MathUtils.withAlpha(color, alpha)
        for (k in 0 until SPIKES) {
            canvas.drawLine(0f, 0f, SPIKE_COS[k] * MINE_SPIKE_LENGTH, SPIKE_SIN[k] * MINE_SPIKE_LENGTH, iconStroke)
        }
        iconPaint.color = MathUtils.withAlpha(color, alpha)
        canvas.drawCircle(0f, 0f, MINE_BODY_RADIUS, iconPaint)
        iconPaint.color = MathUtils.withAlpha(palette.mineHighlight, alpha)
        canvas.drawCircle(-MINE_BODY_RADIUS * 0.36f, -MINE_BODY_RADIUS * 0.36f, MINE_BODY_RADIUS * 0.22f, iconPaint)
        canvas.restore()
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
        private const val DECAL_HALF = 100f
        private val DECAL_SRC = floatArrayOf(
            -DECAL_HALF, -DECAL_HALF,
            DECAL_HALF, -DECAL_HALF,
            DECAL_HALF, DECAL_HALF,
            -DECAL_HALF, DECAL_HALF,
        )

        /** Digit ink height in decal units: 54 % of the inscribed diameter (2 * DECAL_HALF). */
        private const val DIGIT_INK_HEIGHT = 0.54f * 2f * DECAL_HALF

        /** Largest number a cell can show: a far hexagon sees 6 + 12 cells. */
        private const val MAX_NUMBER = 19

        // Far cells: ring radius and stroke in decal units, number scaled to fit inside the ring.
        private const val FAR_RING_RADIUS = 86f
        private const val FAR_RING_STROKE = 8f
        private const val FAR_RING_ALPHA = 0.85f
        private const val FAR_TEXT_RADIUS = 66f
        private const val FAR_MAX_SCALE = 0.9f

        // Line cells: number scaled to fit within LINE_TEXT_RADIUS, arrowhead between the number and
        // the edge (decal units, the edge itself lies at DECAL_HALF or a little farther).
        private const val LINE_TEXT_RADIUS = 56f
        private const val LINE_MAX_SCALE = 0.8f
        private const val LINE_ARROW_TIP = 92f
        private const val LINE_ARROW_NOTCH = 68f
        private const val LINE_ARROW_BASE = 60f
        private const val LINE_ARROW_HALF_WIDTH = 30f
        private const val LINE_ARROW_OUTLINE = 9f

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
        private const val LOCK_LINE_SCALE = 0.75f

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

        // Row lines: thin and translucent, width relative to the mean cell radius.
        private const val ROW_WIDTH_PER_RADIUS = 0.045f
        private const val ROW_ALPHA = 0.4f

        // Walls: core width relative to the mean cell radius, glow width relative to the core.
        private const val WALL_WIDTH_PER_RADIUS = 0.07f
        private const val WALL_GLOW_FACTOR = 3.2f
        private const val WALL_GLOW_ALPHA = 0.4f

        /** A digit switches to another edge only when that edge is this much flatter (|sin|, about 5 degrees). */
        private const val EDGE_HYSTERESIS = 0.09f
        private const val DIGIT_SHADOW_OFFSET = 4f

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

        private val DIGITS = Array(MAX_NUMBER + 1) { it.toString() }

        private const val SPIKES = 8
        private val SPIKE_COS = FloatArray(SPIKES) { cos(it * PI / 4.0).toFloat() }
        private val SPIKE_SIN = FloatArray(SPIKES) { sin(it * PI / 4.0).toFloat() }

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
