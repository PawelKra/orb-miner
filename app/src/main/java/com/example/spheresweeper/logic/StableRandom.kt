package com.example.spheresweeper.logic

import kotlin.math.nextDown
import kotlin.random.Random

/**
 * Seeded random number generator whose output is fixed by this file alone, so that the same seed
 * gives the same sequence on every Kotlin version and platform.
 *
 * [kotlin.random.Random] promises a repeatable sequence for a seed only within one runtime version.
 * Campaign levels are built from a seed and must stay the same for players across app updates, so
 * they draw from this class instead. Every `next*` method of [Random] is overridden here with the
 * algorithm described on it; none falls back to a default implementation of [Random].
 *
 * The source is SplitMix64 (Steele, Lea, Flood, "Fast Splittable Pseudorandom Number Generators",
 * OOPSLA 2014), the variant used by `java.util.SplittableRandom` and as the seeding generator of the
 * xoshiro family: the 64-bit state grows by the golden gamma 0x9E3779B97F4A7C15 on each step, and the
 * new state is mixed into the output. Its period is 2^64 and its output passes BigCrush, which is far
 * more than board generation needs.
 *
 * Each method documents how many 64-bit outputs it draws, since that decides what later calls return.
 */
class StableRandom(seed: Long) : Random() {

    private var state: Long = seed

    /**
     * One SplitMix64 step: state += 0x9E3779B97F4A7C15, then
     * z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9, z = (z xor (z ushr 27)) * 0x94D049BB133111EB,
     * result z xor (z ushr 31), all in wrapping 64-bit arithmetic. Draws one output.
     */
    override fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /**
     * The top [bitCount] bits (0..32) of one output, in the low bits of the result; the rest are zero.
     * Draws one output, also for [bitCount] 0.
     */
    override fun nextBits(bitCount: Int): Int {
        require(bitCount in 0..32) { "bitCount $bitCount outside 0..32" }
        val top = upper32()
        return if (bitCount == 0) 0 else (top ushr (32 - bitCount)).toInt()
    }

    /** The top 32 bits of one output as a signed Int. Draws one output. */
    override fun nextInt(): Int = upper32().toInt()

    /** Uniform in 0 until [until], drawn as [nextInt] with from = 0. */
    override fun nextInt(until: Int): Int {
        require(until > 0) { "until $until must be positive" }
        return bounded32(until.toLong()).toInt()
    }

    /** Uniform in [from] until [until], as [from] plus an unbiased draw below the range size. */
    override fun nextInt(from: Int, until: Int): Int {
        require(from < until) { "empty range $from until $until" }
        return (from + bounded32(until.toLong() - from)).toInt()
    }

    /** Uniform in 0 until [until], drawn as [nextLong] with from = 0. */
    override fun nextLong(until: Long): Long {
        require(until > 0) { "until $until must be positive" }
        return bounded64(until.toULong()).toLong()
    }

    /**
     * Uniform in [from] until [until], as [from] plus an unbiased draw below the range size. The size is
     * taken as an unsigned 64-bit number, so ranges wider than Long.MAX_VALUE work too.
     */
    override fun nextLong(from: Long, until: Long): Long {
        require(from < until) { "empty range $from until $until" }
        return from + bounded64((until - from).toULong()).toLong()
    }

    /** True when the top bit of one output is set. Draws one output. */
    override fun nextBoolean(): Boolean = nextLong() < 0

    /** The top 53 bits of one output times 2^-53: a multiple of 2^-53 in [0, 1). Draws one output. */
    override fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * DOUBLE_UNIT

    /** Uniform in [0, [until]), drawn as [nextDouble] with from = 0. */
    override fun nextDouble(until: Double): Double = nextDouble(0.0, until)

    /**
     * from + [nextDouble] * (until - from), for finite bounds with [from] < [until]. When the width
     * overflows to infinity, the half width is drawn and added twice. A result that rounds up to
     * [until] becomes the largest Double below it. Draws one output.
     */
    override fun nextDouble(from: Double, until: Double): Double {
        require(from.isFinite() && until.isFinite() && from < until) { "bad range $from until $until" }
        val size = until - from
        val r = if (size.isInfinite()) {
            val half = nextDouble() * (until / 2 - from / 2)
            from + half + half
        } else {
            from + nextDouble() * size
        }
        return if (r >= until) until.nextDown() else r
    }

    /** The top 24 bits of one output times 2^-24: a multiple of 2^-24 in [0, 1). Draws one output. */
    override fun nextFloat(): Float = (nextLong() ushr 40).toInt().toFloat() * FLOAT_UNIT

    /**
     * Fills [array] from [fromIndex] until [toIndex] with bytes of successive outputs, eight per output,
     * lowest byte first. A final partial group still draws a whole output.
     */
    override fun nextBytes(array: ByteArray, fromIndex: Int, toIndex: Int): ByteArray {
        require(fromIndex in 0..array.size && toIndex in fromIndex..array.size) {
            "range $fromIndex until $toIndex outside 0..${array.size}"
        }
        var i = fromIndex
        while (i < toIndex) {
            var bits = nextLong()
            val end = minOf(i + 8, toIndex)
            while (i < end) {
                array[i++] = bits.toByte()
                bits = bits ushr 8
            }
        }
        return array
    }

    /** Fills the whole [array], as nextBytes(array, 0, array.size). */
    override fun nextBytes(array: ByteArray): ByteArray = nextBytes(array, 0, array.size)

    /** A new array of [size] bytes, filled as nextBytes(array, 0, size). */
    override fun nextBytes(size: Int): ByteArray = nextBytes(ByteArray(size), 0, size)

    /** The top 32 bits of one output, as a non-negative Long below 2^32. */
    private fun upper32(): Long = nextLong() ushr 32

    /**
     * Unbiased integer in 0 until [n], for [n] in 1..2^32, by Lemire's multiply-and-reject method
     * (D. Lemire, "Fast Random Integer Generation in an Interval", ACM TOMACS 2019):
     *
     * 1. Take x = [upper32] and m = x * n (below 2^64, kept as unsigned bits).
     * 2. The candidate is m ushr 32. Its low half l = m and 0xFFFFFFFF tells whether x fell in the
     *    uneven part of the range: when l < (2^32 mod n), reject and draw a new x.
     * 3. The remainder 2^32 mod n is computed only when l < n, since it never exceeds n.
     *
     * Each draw takes one output. A draw is rejected with probability below n / 2^32.
     */
    private fun bounded32(n: Long): Long {
        var m = upper32() * n
        var low = m and LOW_32
        if (low < n) {
            val threshold = (1L shl 32) % n
            while (low < threshold) {
                m = upper32() * n
                low = m and LOW_32
            }
        }
        return m ushr 32
    }

    /**
     * Unbiased integer in 0 until [n], for [n] >= 1, by masking and rejection: keep the lowest bits of
     * one output that can hold n - 1 and draw again while the value is n or more. For a power of two
     * the mask fits exactly and the first output is always taken. Each draw takes one output and is
     * rejected with probability below 1/2.
     */
    private fun bounded64(n: ULong): ULong {
        if (n and (n - 1uL) == 0uL) return nextLong().toULong() and (n - 1uL)
        val mask = ULong.MAX_VALUE shr java.lang.Long.numberOfLeadingZeros((n - 1uL).toLong())
        while (true) {
            val r = nextLong().toULong() and mask
            if (r < n) return r
        }
    }

    private companion object {
        val GOLDEN_GAMMA: Long = 0x9E3779B97F4A7C15uL.toLong()
        val MIX_1: Long = 0xBF58476D1CE4E5B9uL.toLong()
        val MIX_2: Long = 0x94D049BB133111EBuL.toLong()
        const val LOW_32: Long = 0xFFFF_FFFFL
        const val DOUBLE_UNIT: Double = 1.0 / (1L shl 53)
        const val FLOAT_UNIT: Float = 1.0f / (1 shl 24)
    }
}
