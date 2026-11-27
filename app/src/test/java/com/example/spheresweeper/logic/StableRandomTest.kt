package com.example.spheresweeper.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference values were computed with a separate Python implementation of SplitMix64 and of the
 * bounded draws described on [StableRandom]. The seed 0 outputs also match the published SplitMix64
 * test vector (0xE220A8397B1DCDAF, 0x6E789E6AA1B965F4, ...). If any of these change, every campaign
 * level changes with them.
 */
class StableRandomTest {

    private fun longs(seed: Long, count: Int): LongArray {
        val random = StableRandom(seed)
        return LongArray(count) { random.nextLong() }
    }

    /** Parses unsigned 64-bit hex values, as SplitMix64 outputs are usually written. */
    private fun ref(vararg hex: String): LongArray = LongArray(hex.size) { hex[it].toULong(16).toLong() }

    @Test
    fun nextLongMatchesSplitMix64() {
        assertArrayEquals(
            ref("E220A8397B1DCDAF", "6E789E6AA1B965F4", "06C45D188009454F", "F88BB8A8724C81EC"),
            longs(0L, 4),
        )
        assertArrayEquals(
            ref("910A2DEC89025CC1", "BEEB8DA1658EEC67", "F893A2EEFB32555E", "71C18690EE42C90B"),
            longs(1L, 4),
        )
        assertArrayEquals(
            ref("E4D971771B652C20", "E99FF867DBF682C9", "382FF84CB27281E9", "6D1DB36CCBA982D2"),
            longs(-1L, 4),
        )
        assertArrayEquals(
            ref("157A3807A48FAA9D", "D573529B34A1D093", "2F90B72E996DCCBE", "A2D419334C4667EC"),
            longs(0x0123456789ABCDEFL, 4),
        )
    }

    @Test
    fun nextIntWithBoundMatchesReference() {
        fun draws(seed: Long, bound: Int): IntArray {
            val random = StableRandom(seed)
            return IntArray(8) { random.nextInt(bound) }
        }
        assertArrayEquals(intArrayOf(5, 2, 0, 5, 0, 1, 1, 4), draws(0L, 6))
        assertArrayEquals(intArrayOf(566, 745, 971, 444, 444, 762, 877, 523), draws(1L, 1000))
        assertArrayEquals(intArrayOf(6, 6, 1, 2, 4, 5, 6, 1), draws(-1L, 7))
        assertArrayEquals(
            intArrayOf(
                1592498450, 343404953, 598291371, 739143935, 81669164, 1864505596, 469021581, 1719343862,
            ),
            draws(42L, Int.MAX_VALUE),
        )
    }

    @Test
    fun nextIntRejectsTheUnevenPartOfTheRange() {
        // With bound 1.5e9 the outputs 6 and 7 of seed 7 fall in the rejected zone, so six values take
        // eight outputs and the next nextLong is output 9.
        val random = StableRandom(7L)
        val values = IntArray(6) { random.nextInt(1_500_000_000) }
        assertArrayEquals(intArrayOf(584744622, 25182441, 1351141020, 874395439, 678662842, 492115108), values)
        assertEquals(0x225EC07A99506761uL.toLong(), random.nextLong())
    }

    @Test
    fun otherDrawsMatchReference() {
        val ints = StableRandom(5L)
        assertArrayEquals(intArrayOf(-3, 5, -6, -9, -7, -3), IntArray(6) { ints.nextInt(-10, 10) })

        val longs = StableRandom(11L)
        assertArrayEquals(
            longArrayOf(69071857923403933L, 228096790202356641L, 240588745334264429L, 85113852893490672L),
            LongArray(4) { longs.nextLong(1_000_000_000_000_000_000L) },
        )
        val small = StableRandom(11L)
        assertArrayEquals(longArrayOf(-4, -5, -1, -1, 1, -5, -2, -4), LongArray(8) { small.nextLong(-5L, 5L) })

        val doubles = StableRandom(3L)
        assertEquals(0.11345034205715454, doubles.nextDouble(), 0.0)
        assertEquals(0.7002935135929024, doubles.nextDouble(), 0.0)
        assertEquals(0.6129746825466243, doubles.nextDouble(), 0.0)

        val floats = StableRandom(3L)
        assertEquals(0.11345028877258301, floats.nextFloat().toDouble(), 0.0)
        assertEquals(0.7002934813499451, floats.nextFloat().toDouble(), 0.0)
        assertEquals(0.6129746437072754, floats.nextFloat().toDouble(), 0.0)

        val booleans = StableRandom(9L)
        assertArrayEquals(
            booleanArrayOf(true, true, false, true, false, false, true, true),
            BooleanArray(8) { booleans.nextBoolean() },
        )

        assertArrayEquals(
            byteArrayOf(-1, -118, -83, -8, -73, 55, -54, -60, 113, -96),
            StableRandom(13L).nextBytes(10),
        )

        val bits = StableRandom(17L)
        assertEquals(16, bits.nextBits(5))
        assertEquals(1681194850, bits.nextBits(32))
        assertEquals(0, bits.nextBits(1))
    }

    @Test
    fun drawsStayInRange() {
        val random = StableRandom(99L)
        repeat(20_000) {
            assertTrue(random.nextInt(1) == 0)
            assertTrue(random.nextInt(3) in 0 until 3)
            assertTrue(random.nextInt(Int.MAX_VALUE) in 0 until Int.MAX_VALUE)
            val wide = random.nextInt(Int.MIN_VALUE, Int.MAX_VALUE)
            assertTrue(wide != Int.MAX_VALUE)
            assertTrue(random.nextInt(-7, -3) in -7 until -3)
            assertTrue(random.nextLong(1L) == 0L)
            val anyLong = random.nextLong(Long.MIN_VALUE, Long.MAX_VALUE)
            assertTrue(anyLong != Long.MAX_VALUE)
            assertTrue(random.nextLong(Long.MIN_VALUE, Long.MIN_VALUE + 3) - Long.MIN_VALUE in 0L until 3L)
            assertTrue(random.nextLong(1L shl 40) in 0L until (1L shl 40))
            val d = random.nextDouble()
            assertTrue(d >= 0.0 && d < 1.0)
            val huge = random.nextDouble(-Double.MAX_VALUE, Double.MAX_VALUE)
            assertTrue(huge.isFinite() && huge < Double.MAX_VALUE)
            assertTrue(random.nextDouble(2.0, 3.0).let { it >= 2.0 && it < 3.0 })
            val f = random.nextFloat()
            assertTrue(f >= 0f && f < 1f)
            assertTrue(random.nextBits(7) in 0 until 128)
            assertEquals(0, random.nextBits(0))
        }
    }

    @Test
    fun nextIntIsRoughlyUniform() {
        val random = StableRandom(2024L)
        val counts = IntArray(6)
        val n = 60_000
        repeat(n) { counts[random.nextInt(6)]++ }
        for (c in counts) assertTrue("$c", c in 9_600..10_400)
    }

    @Test
    fun nextBytesFillsOnlyTheGivenRange() {
        val array = ByteArray(12) { 7 }
        StableRandom(13L).nextBytes(array, 2, 12)
        assertEquals(7.toByte(), array[0])
        assertEquals(7.toByte(), array[1])
        assertArrayEquals(
            byteArrayOf(-1, -118, -83, -8, -73, 55, -54, -60, 113, -96),
            array.copyOfRange(2, 12),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nextIntRejectsEmptyRange() {
        StableRandom(0L).nextInt(0)
    }
}
