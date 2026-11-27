package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SphereGeometryTest {

    private val geometries: Map<BoardSize, SphereGeometry> =
        BoardSize.values().associateWith { SphereGeometry.build(it.frequency) }

    @Test
    fun cellCountMatchesFormula() {
        for ((size, geometry) in geometries) {
            val n = size.frequency
            assertEquals("$size formula", 10 * n * n + 2, size.cellCount)
            assertEquals("$size geometry", size.cellCount, geometry.cellCount)
            assertEquals("$size frequency", n, geometry.frequency)
            assertEquals("$size centers", 3 * size.cellCount, geometry.centers.size)
            assertEquals("$size cornerStart", size.cellCount + 1, geometry.cornerStart.size)
            assertEquals("$size neighbors", size.cellCount, geometry.neighbors.size)
        }
    }

    @Test
    fun exactlyTwelvePentagonsAndTheRestHexagons() {
        for ((size, geometry) in geometries) {
            var pentagons = 0
            for (cell in 0 until geometry.cellCount) {
                val degree = geometry.neighbors[cell].size
                if (geometry.isPentagon(cell)) {
                    assertEquals("$size cell $cell", 5, degree)
                    pentagons++
                } else {
                    assertEquals("$size cell $cell", 6, degree)
                }
            }
            assertEquals("$size pentagons", 12, pentagons)
        }
    }

    @Test
    fun neighbourRelationIsSymmetricSortedAndSimple() {
        for ((size, geometry) in geometries) {
            for (cell in 0 until geometry.cellCount) {
                val list = geometry.neighbors[cell]
                for (k in list.indices) {
                    val nb = list[k]
                    assertTrue("$size cell $cell neighbour in range", nb in 0 until geometry.cellCount)
                    assertTrue("$size cell $cell self neighbour", nb != cell)
                    if (k > 0) {
                        assertTrue("$size cell $cell sorted without duplicates", list[k - 1] < nb)
                    }
                    assertTrue(
                        "$size neighbour relation not symmetric for $cell -> $nb",
                        geometry.neighbors[nb].contains(cell),
                    )
                }
            }
        }
    }

    @Test
    fun cornerCountEqualsNeighbourCount() {
        for ((size, geometry) in geometries) {
            for (cell in 0 until geometry.cellCount) {
                assertEquals("$size cell $cell", geometry.neighbors[cell].size, geometry.cornerCount(cell))
            }
        }
    }

    @Test
    fun totalCornerReferencesIsThreePerTriangle() {
        for ((size, geometry) in geometries) {
            val n = size.frequency
            // 20 * n^2 triangles, each one is a corner of its 3 vertices' cells.
            val expected = 60 * n * n
            assertEquals("$size", expected, geometry.cornerStart[geometry.cellCount])
            assertEquals("$size", 3 * expected, geometry.corners.size)
            assertEquals("$size", 0, geometry.cornerStart[0])
        }
    }

    @Test
    fun centersAndCornersAreUnitVectors() {
        for ((size, geometry) in geometries) {
            for (cell in 0 until geometry.cellCount) {
                assertUnit("$size center $cell", geometry.centers, 3 * cell)
            }
            for (corner in 0 until geometry.cornerStart[geometry.cellCount]) {
                assertUnit("$size corner $corner", geometry.corners, 3 * corner)
            }
        }
    }

    @Test
    fun cornersAreCounterClockwiseFromOutside() {
        for ((size, geometry) in geometries) {
            val c = geometry.centers
            val p = geometry.corners
            for (cell in 0 until geometry.cellCount) {
                val cx = c[3 * cell].toDouble()
                val cy = c[3 * cell + 1].toDouble()
                val cz = c[3 * cell + 2].toDouble()
                val start = geometry.cornerStart[cell]
                val count = geometry.cornerCount(cell)
                for (k in 0 until count) {
                    val a = start + k
                    val b = start + (k + 1) % count
                    val ax = p[3 * a] - cx
                    val ay = p[3 * a + 1] - cy
                    val az = p[3 * a + 2] - cz
                    val bx = p[3 * b] - cx
                    val by = p[3 * b + 1] - cy
                    val bz = p[3 * b + 2] - cz
                    val nx = ay * bz - az * by
                    val ny = az * bx - ax * bz
                    val nz = ax * by - ay * bx
                    val orientation = nx * cx + ny * cy + nz * cz
                    assertTrue("$size cell $cell corner $k not CCW", orientation > 0.0)
                }
            }
        }
    }

    @Test
    fun cellCentersAreDistinct() {
        for ((size, geometry) in geometries) {
            val c = geometry.centers
            for (i in 0 until geometry.cellCount) {
                for (j in i + 1 until geometry.cellCount) {
                    val dx = c[3 * i] - c[3 * j]
                    val dy = c[3 * i + 1] - c[3 * j + 1]
                    val dz = c[3 * i + 2] - c[3 * j + 2]
                    assertFalse("$size cells $i and $j coincide", dx * dx + dy * dy + dz * dz < 1e-6f)
                }
            }
        }
    }

    @Test
    fun frequencyOneIsDodecahedron() {
        val geometry = SphereGeometry.build(1)
        assertEquals(12, geometry.cellCount)
        for (cell in 0 until geometry.cellCount) {
            assertTrue(geometry.isPentagon(cell))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun frequencyZeroIsRejected() {
        SphereGeometry.build(0)
    }

    private fun assertUnit(message: String, array: FloatArray, offset: Int) {
        val x = array[offset].toDouble()
        val y = array[offset + 1].toDouble()
        val z = array[offset + 2].toDouble()
        val length = sqrt(x * x + y * y + z * z)
        assertTrue("$message has length $length", abs(length - 1.0) < 1e-5)
    }
}
