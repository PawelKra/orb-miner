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
import com.example.spheresweeper.logic.GameStatus
import com.example.spheresweeper.logic.MinesweeperBoard
import com.example.spheresweeper.logic.SphereGeometry
import com.example.spheresweeper.math.MathUtils
import com.example.spheresweeper.math.Quaternion
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
 * the cell centre) and outlined by a thin dark border. Digits, flags and mines are decals: they are drawn
 * in a local glyph space that a perspective matrix maps onto a square in the cell's tangent plane, so
 * they lie on the surface and rotate with the ball.
 *
 * Everything that depends only on the geometry (cell sizes, tangent frames, colours, glyph metrics,
 * confetti parameters, shaders, paints) is computed once in the constructor. [draw] works on
 * preallocated objects only and does not allocate.
 */
class SphereRenderer(private val geometry: SphereGeometry) {

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

    // Covered-cell base colour per cell (teal -> blue -> violet by model latitude).
    private val baseR = FloatArray(cellCount)
    private val baseG = FloatArray(cellCount)
    private val baseB = FloatArray(cellCount)

    // Per-frame buffers.
    private val matrix = FloatArray(9)
    private val rotated = FloatArray(cellCount * 3)
    private val facing = FloatArray(cellCount)
    private val order = IntArray(cellCount)
    private val depth = FloatArray(cellCount)
    private val decalDst = FloatArray(8)
    private val decalMatrix = Matrix()

    // Frame parameters shared by the helpers.
    private var frameCx = 0f
    private var frameCy = 0f
    private var frameFocal = 1f

    private val path = Path()

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
        color = BORDER_COLOR
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val confettiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Per-digit offsets that put the centre of the glyph's ink bounds on the decal origin. */
    private val digitX = FloatArray(9)
    private val digitY = FloatArray(9)

    // Unit-radius shaders, positioned and scaled through the canvas matrix.
    private val haloShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(argbLong(0x5545A0FF), argbLong(0x5545A0FF), argbLong(0x3045A0FF), argbLong(0x00203060)),
        floatArrayOf(0f, 0.6f, 0.7f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val poolShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(argbLong(0x663A6BFF), argbLong(0x00203080)),
        floatArrayOf(0f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val coreShader = RadialGradient(
        -0.35f, -0.4f, 1.5f,
        intArrayOf(argbLong(0xFF1E2C66), argbLong(0xFF0B1030), argbLong(0xFF020308)),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val glossShader = RadialGradient(
        -0.42f, -0.48f, 0.95f,
        intArrayOf(argbLong(0x3CFFFFFF), argbLong(0x12FFFFFF), argbLong(0x00FFFFFF)),
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val winShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            argbLong(0x00FFD166), argbLong(0x00FFD166), argbLong(0xB0FFD166),
            argbLong(0x505CF2A8), argbLong(0x005CF2A8),
        ),
        floatArrayOf(0f, 0.62f, 0.72f, 0.86f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val explosionShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            argbLong(0xFFFFFFFF), argbLong(0xFFFFE08A), argbLong(0xCCFF7A2E),
            argbLong(0x66E0203A), argbLong(0x00E0203A),
        ),
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
            assignBaseColor(i, cy)
        }

        // Digit size: ink height of the digits is a fixed share of the inscribed diameter.
        val bounds = Rect()
        textPaint.textSize = 100f
        textPaint.getTextBounds(DIGITS[8], 0, 1, bounds)
        val inkHeight = max(1, bounds.height()).toFloat()
        textPaint.textSize = 100f * DIGIT_INK_HEIGHT / inkHeight
        for (n in 1..8) {
            textPaint.getTextBounds(DIGITS[n], 0, 1, bounds)
            digitX[n] = -(bounds.left + bounds.right) * 0.5f
            digitY[n] = -(bounds.top + bounds.bottom) * 0.5f
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
            cfColor[p] = random.nextInt(CONFETTI_COLORS.size)
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

    private fun assignBaseColor(cell: Int, latitudeY: Float) {
        // Small deterministic per-cell variation gives the surface some texture.
        val hash = (cell * -1640531535) ushr 24
        val jitter = (hash / 255f - 0.5f) * 0.08f
        val t = MathUtils.clamp01((latitudeY + 1f) * 0.5f + jitter)
        val r: Float
        val g: Float
        val b: Float
        if (t < 0.5f) {
            val u = t * 2f
            r = MathUtils.lerp(TEAL_R, BLUE_R, u)
            g = MathUtils.lerp(TEAL_G, BLUE_G, u)
            b = MathUtils.lerp(TEAL_B, BLUE_B, u)
        } else {
            val u = (t - 0.5f) * 2f
            r = MathUtils.lerp(BLUE_R, VIOLET_R, u)
            g = MathUtils.lerp(BLUE_G, VIOLET_G, u)
            b = MathUtils.lerp(BLUE_B, VIOLET_B, u)
        }
        baseR[cell] = r
        baseG[cell] = g
        baseB[cell] = b
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
        if (effect == SphereEffect.EXPLOSION && effectTime < SHAKE_SECONDS) {
            val decay = 1f - effectTime / SHAKE_SECONDS
            val amp = radius * 0.08f * decay * decay
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
        for (k in 0 until visible) {
            drawCell(canvas, order[k], board, status)
        }

        drawUnitShader(canvas, glossShader, cx, cy, radius, radius, 1f)

        if (effect == SphereEffect.EXPLOSION && board != null && effectTime < EXPLOSION_SECONDS) {
            drawExplosion(canvas, board.explodedCell, radius, effectTime)
        }
        if (effect == SphereEffect.WIN && effectTime < WIN_SECONDS) {
            drawConfetti(canvas, cx, cy, radius, effectTime)
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
            p += 3
            j++
        }
        path.close()
    }

    /**
     * Sets [decalMatrix] so that decal space (x right, y down, [-DECAL_HALF, DECAL_HALF] on both axes)
     * maps with true perspective onto the square centre +- inRadius * (right, up) in the cell's tangent
     * plane. Returns false when the projected square is degenerate.
     */
    private fun setupDecal(cell: Int): Boolean {
        val m = matrix
        val o = cell * 3
        val s = inRadius[cell]
        val ax = decalRight[o]
        val ay = decalRight[o + 1]
        val az = decalRight[o + 2]
        val bx = decalUp[o]
        val by = decalUp[o + 1]
        val bz = decalUp[o + 2]
        // Rotated right and up vectors, pre-scaled by the half-size of the square.
        val rx = (m[0] * ax + m[1] * ay + m[2] * az) * s
        val ry = (m[3] * ax + m[4] * ay + m[5] * az) * s
        val rz = (m[6] * ax + m[7] * ay + m[8] * az) * s
        val ux = (m[0] * bx + m[1] * by + m[2] * bz) * s
        val uy = (m[3] * bx + m[4] * by + m[5] * bz) * s
        val uz = (m[6] * bx + m[7] * by + m[8] * bz) * s
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

    private fun drawCell(canvas: Canvas, cell: Int, board: MinesweeperBoard?, status: GameStatus) {
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
        val light = AMBIENT + DIFFUSE * lambert
        val decalAlpha = MathUtils.smoothstep(DECAL_FADE_START, DECAL_FADE_END, fc)

        if (board == null) {
            fillCovered(canvas, cell, projR, light, spec, rim)
            return
        }

        val revealed = board.isRevealed(cell)
        val flagged = board.isFlagged(cell)
        val mine = board.minesPlaced && board.isMine(cell)
        val lost = status == GameStatus.LOST
        val won = status == GameStatus.WON

        if (lost && cell == board.explodedCell) {
            fillLit(canvas, projR, 0.95f, 0.13f, 0.16f, light, spec, 0.4f)
            drawMineDecal(canvas, cell, decalAlpha, MINE_ON_RED_COLOR)
        } else if (lost && mine && !flagged) {
            fillLit(canvas, projR, 0.31f, 0.32f, 0.36f, light, spec, 0.25f)
            drawMineDecal(canvas, cell, decalAlpha, MINE_COLOR)
        } else if (revealed) {
            val br = baseR[cell]
            val bg = baseG[cell]
            val bb = baseB[cell]
            val gray = (br + bg + bb) / 3f
            val shade = REVEALED_BASE + REVEALED_LIGHT * light
            val sheen = spec * 0.15f
            fillCell(
                canvas, projR,
                MathUtils.lerp(br, gray, DESATURATION) * shade + 0.03f + sheen,
                MathUtils.lerp(bg, gray, DESATURATION) * shade + 0.035f + sheen,
                MathUtils.lerp(bb, gray, DESATURATION) * shade + 0.05f + sheen,
            )
            if (!mine) {
                val n = board.adjacentMines(cell)
                if (n in 1..8) drawDigitDecal(canvas, cell, n, decalAlpha)
            }
        } else if (lost && flagged && board.isWrongFlag(cell)) {
            // A flag on a safe cell: muted red cell and a greyed-out flag.
            fillLit(canvas, projR, 0.50f, 0.16f, 0.19f, light, spec, 0.25f)
            drawFlagDecal(canvas, cell, decalAlpha, WRONG_POLE_COLOR, WRONG_FLAG_COLOR)
        } else {
            fillCovered(canvas, cell, projR, light, spec, rim)
            if (flagged || (won && mine)) drawFlagDecal(canvas, cell, decalAlpha, POLE_COLOR, FLAG_COLOR)
        }
    }

    private fun fillCovered(canvas: Canvas, cell: Int, projR: Float, light: Float, spec: Float, rim: Float) {
        fillCell(
            canvas, projR,
            baseR[cell] * light + spec * COVERED_GLOSS + rim * 0.18f,
            baseG[cell] * light + spec * COVERED_GLOSS + rim * 0.25f,
            baseB[cell] * light + spec * COVERED_GLOSS + rim * 0.55f,
        )
    }

    /** Fills [path] with colour (r, g, b) shaded by [light] plus a specular sheen of strength [gloss]. */
    private fun fillLit(
        canvas: Canvas, projR: Float, r: Float, g: Float, b: Float, light: Float, spec: Float, gloss: Float,
    ) {
        val sheen = spec * gloss
        fillCell(canvas, projR, r * light + sheen, g * light + sheen, b * light + sheen)
    }

    /** One flat colour per cell, outlined with a thin dark border that separates touching cells. */
    private fun fillCell(canvas: Canvas, projR: Float, r: Float, g: Float, b: Float) {
        fillPaint.color = MathUtils.argb(1f, r, g, b)
        canvas.drawPath(path, fillPaint)
        borderPaint.strokeWidth = min(BORDER_MAX, max(BORDER_MIN, projR * BORDER_PER_RADIUS))
        canvas.drawPath(path, borderPaint)
    }

    // ------------------------------------------------------------------ decals

    private fun drawDigitDecal(canvas: Canvas, cell: Int, n: Int, alpha: Float) {
        if (alpha < MIN_DECAL_ALPHA || !setupDecal(cell)) return
        canvas.save()
        canvas.concat(decalMatrix)
        val x = digitX[n]
        val y = digitY[n]
        textPaint.color = MathUtils.withAlpha(DIGIT_SHADOW_COLOR, alpha * DIGIT_SHADOW_ALPHA)
        canvas.drawText(DIGITS[n], 0, 1, x + DIGIT_SHADOW_OFFSET, y + DIGIT_SHADOW_OFFSET, textPaint)
        textPaint.color = MathUtils.withAlpha(NUMBER_COLORS[n], alpha)
        canvas.drawText(DIGITS[n], 0, 1, x, y, textPaint)
        canvas.restore()
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
        iconPaint.color = MathUtils.withAlpha(MINE_HIGHLIGHT_COLOR, alpha)
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
            canvas.drawColor(MathUtils.withAlpha(TINT_COLOR, 0.3f * (1f - t / FLASH_TINT_SECONDS)))
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
            confettiPaint.color = MathUtils.withAlpha(CONFETTI_COLORS[cfColor[p]], fadeOut)
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
        private const val DESATURATION = 0.62f

        /** Strength of the specular highlight on covered cells; higher values wash the cells out to white. */
        private const val COVERED_GLOSS = 0.42f
        private const val AMBIENT = 0.30f
        private const val DIFFUSE = 0.75f

        /** Revealed cells: shade = REVEALED_BASE + REVEALED_LIGHT * light, much darker than covered ones. */
        private const val REVEALED_BASE = 0.08f
        private const val REVEALED_LIGHT = 0.25f

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

        /** Digit ink height in decal units: 63 % of the inscribed diameter (2 * DECAL_HALF). */
        private const val DIGIT_INK_HEIGHT = 0.63f * 2f * DECAL_HALF
        private const val DIGIT_SHADOW_OFFSET = 4f
        private const val DIGIT_SHADOW_ALPHA = 0.45f

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

        private const val CONFETTI_COUNT = 120
        private const val CONFETTI_SEED = 20240611

        // Palette endpoints for covered cells.
        private const val TEAL_R = 0.05f
        private const val TEAL_G = 0.82f
        private const val TEAL_B = 0.78f
        private const val BLUE_R = 0.16f
        private const val BLUE_G = 0.46f
        private const val BLUE_B = 1.00f
        private const val VIOLET_R = 0.64f
        private const val VIOLET_G = 0.30f
        private const val VIOLET_B = 1.00f

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

        private val DIGITS = arrayOf("0", "1", "2", "3", "4", "5", "6", "7", "8")

        /** Index = number of adjacent mines (index 0 unused). */
        private val NUMBER_COLORS = intArrayOf(
            argbLong(0xFFFFFFFF),
            argbLong(0xFF5AA8FF), // 1 blue
            argbLong(0xFF5ED66E), // 2 green
            argbLong(0xFFFF5A5A), // 3 red
            argbLong(0xFFB98CFF), // 4 violet
            argbLong(0xFFFF9F45), // 5 orange
            argbLong(0xFF3ED8D8), // 6 cyan
            argbLong(0xFFE6E8EE), // 7 light grey
            argbLong(0xFFFF80C8), // 8 pink
        )

        private val FLAG_COLOR = argbLong(0xFFFF4A57)
        private val POLE_COLOR = argbLong(0xFFF2F4F8)
        private val WRONG_FLAG_COLOR = argbLong(0xFF9A9CA3)
        private val WRONG_POLE_COLOR = argbLong(0xFF7D8089)
        private val MINE_COLOR = argbLong(0xFF0D0F16)
        private val MINE_ON_RED_COLOR = argbLong(0xFF1A0508)
        private val MINE_HIGHLIGHT_COLOR = argbLong(0x8CC8CCD6)
        private val BORDER_COLOR = argbLong(0xF00A0C12)
        private val DIGIT_SHADOW_COLOR = argbLong(0xFF000000)
        private val TINT_COLOR = argbLong(0xFFFF2040)

        private val CONFETTI_COLORS = intArrayOf(
            argbLong(0xFFFFD166),
            argbLong(0xFF5CF2A8),
            argbLong(0xFF4FE3FF),
            argbLong(0xFFA77BFF),
            argbLong(0xFFFF7AD9),
            argbLong(0xFFFF6B6B),
        )

        private const val SPIKES = 8
        private val SPIKE_COS = FloatArray(SPIKES) { cos(it * PI / 4.0).toFloat() }
        private val SPIKE_SIN = FloatArray(SPIKES) { sin(it * PI / 4.0).toFloat() }

        private val EXPLOSION_NANOS = (EXPLOSION_SECONDS * 1e9f).toLong()
        private val WIN_NANOS = (WIN_SECONDS * 1e9f).toLong()

        private fun argbLong(value: Long): Int = value.toInt()

        /** How long [effect] needs frame ticks, in nanoseconds. */
        fun effectDurationNanos(effect: SphereEffect): Long = when (effect) {
            SphereEffect.NONE -> 0L
            SphereEffect.EXPLOSION -> EXPLOSION_NANOS
            SphereEffect.WIN -> WIN_NANOS
        }
    }
}
