package com.example.spheresweeper.math

/** Small allocation-free scalar helpers used by rendering and animation code. */
object MathUtils {

    fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Hermite smoothstep between [edge0] and [edge1]. */
    fun smoothstep(edge0: Float, edge1: Float, v: Float): Float {
        val t = clamp01((v - edge0) / (edge1 - edge0))
        return t * t * (3f - 2f * t)
    }

    /** Cubic ease-out on [0, 1]. */
    fun easeOutCubic(t: Float): Float {
        val u = 1f - clamp01(t)
        return 1f - u * u * u
    }

    /** Packs float channels in [0, 1] (clamped) into an ARGB colour int. */
    fun argb(a: Float, r: Float, g: Float, b: Float): Int {
        val ai = (clamp01(a) * 255f + 0.5f).toInt()
        val ri = (clamp01(r) * 255f + 0.5f).toInt()
        val gi = (clamp01(g) * 255f + 0.5f).toInt()
        val bi = (clamp01(b) * 255f + 0.5f).toInt()
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /** Replaces the alpha channel of an ARGB colour int. */
    fun withAlpha(color: Int, alpha: Float): Int {
        val ai = (clamp01(alpha) * 255f + 0.5f).toInt()
        return (ai shl 24) or (color and 0x00FFFFFF)
    }
}
