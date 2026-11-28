package com.example.spheresweeper.ui

import com.example.spheresweeper.logic.SphereGeometry
import com.example.spheresweeper.math.Quaternion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Screen margins (pixels) that the ball must stay clear of: HUD, hints and system bars.
 * Immutable so the draw pass can read it without copying.
 */
data class ViewInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    companion object {
        val ZERO = ViewInsets()
    }
}

/**
 * Perspective camera shared by the renderer and the hit-test so both use exactly the same projection.
 *
 * World space: x right, y up, z towards the viewer. The camera sits at (0, 0, [DISTANCE]) looking at the
 * origin. Zoom scales the focal length (a zoom lens), so the perspective of the ball never changes and
 * the camera can never end up inside the sphere.
 *
 * The ball is centred in the view area left after removing [ViewInsets] and is sized from the shorter
 * side of that area.
 */
object SphereCamera {
    const val DISTANCE = 3.2f

    /**
     * Fraction of the shorter side of the available area covered by the silhouette diameter at zoom 1,
     * which leaves a margin of about 6 % on each side.
     */
    const val FILL = 0.88f

    const val MIN_ZOOM = 0.6f
    const val MAX_ZOOM = 2.5f

    /**
     * Under perspective the unit sphere's outline subtends half-angle asin(1 / D), so its screen radius
     * is focal / sqrt(D^2 - 1). The focal length is derived from the desired outline radius with this
     * factor, which makes the projected silhouette exactly [silhouetteRadius] (not the smaller f / D).
     */
    private val TAN_FACTOR = sqrt(DISTANCE * DISTANCE - 1f)

    /** Horizontal screen position of the ball centre. */
    fun centerX(width: Float, insets: ViewInsets): Float =
        insets.left + max(0f, width - insets.left - insets.right) * 0.5f

    /** Vertical screen position of the ball centre. */
    fun centerY(height: Float, insets: ViewInsets): Float =
        insets.top + max(0f, height - insets.top - insets.bottom) * 0.5f

    /** Screen radius of the projected sphere silhouette in pixels. */
    fun silhouetteRadius(width: Float, height: Float, zoom: Float, insets: ViewInsets): Float {
        val w = max(0f, width - insets.left - insets.right)
        val h = max(0f, height - insets.top - insets.bottom)
        return 0.5f * FILL * min(w, h) * zoom
    }

    /** Focal length in pixels: screen = center + world.xy * focal / (DISTANCE - world.z). */
    fun focalLength(width: Float, height: Float, zoom: Float, insets: ViewInsets): Float =
        silhouetteRadius(width, height, zoom, insets) * TAN_FACTOR

    /**
     * Returns the cell under screen point ([sx], [sy]) or -1 when the ray misses the ball.
     * [scratch] must hold at least 3 floats; it receives the model-space hit point.
     */
    fun pickCell(
        geometry: SphereGeometry,
        orientation: Quaternion,
        zoom: Float,
        width: Float,
        height: Float,
        sx: Float,
        sy: Float,
        insets: ViewInsets,
        scratch: FloatArray,
    ): Int {
        if (width <= 0f || height <= 0f) return -1
        val f = focalLength(width, height, zoom, insets)
        if (f <= 0f) return -1
        // Ray from the camera through the pixel: origin (0, 0, D), direction (dx, dy, -1).
        val dx = (sx - centerX(width, insets)) / f
        val dy = -(sy - centerY(height, insets)) / f
        val a = dx * dx + dy * dy + 1f
        val b = -2f * DISTANCE
        val c = DISTANCE * DISTANCE - 1f
        val disc = b * b - 4f * a * c
        if (disc < 0f) return -1
        // Nearest intersection is the front side of the ball.
        val t = (-b - sqrt(disc)) / (2f * a)
        val hx = dx * t
        val hy = dy * t
        val hz = DISTANCE - t
        orientation.rotateInverse(hx, hy, hz, scratch, 0)
        val mx = scratch[0]
        val my = scratch[1]
        val mz = scratch[2]
        val centers = geometry.centers
        var best = -1
        var bestDot = -2f
        for (i in 0 until geometry.cellCount) {
            val o = i * 3
            val d = centers[o] * mx + centers[o + 1] * my + centers[o + 2] * mz
            if (d > bestDot) {
                bestDot = d
                best = i
            }
        }
        return best
    }
}

/** One-shot board animations started by the view model. */
enum class SphereEffect { NONE, HINT, MINE_HIT, EXPLOSION, WIN }

/** Haptic event produced by a move. REJECT: a gesture that could not act, such as an uncertain auto flag. */
enum class Feedback { NONE, FLAG, CHORD, EXPLOSION, REJECT }
