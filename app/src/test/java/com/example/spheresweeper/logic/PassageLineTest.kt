package com.example.spheresweeper.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Line cells on boards with rooms stand at the passages and look into the deeper room. */
class PassageLineTest {

    private val geometries: Map<BoardSize, SphereGeometry> =
        BoardSize.values().associateWith { SphereGeometry.build(it.frequency) }

    /** Board setups with rooms: size, rooms, islands, extra portals, line cells asked for. */
    private val setups = listOf(
        Setup(BoardSize.SMALL, 3, 0, 0, 2),
        Setup(BoardSize.MEDIUM, 4, 0, 1, 4),
        Setup(BoardSize.LARGE, 5, 0, 1, 6),
        Setup(BoardSize.HUGE, 6, 2, 1, 8),
        Setup(BoardSize.GIANT, 7, 0, 2, 12),
        Setup(BoardSize.GIANT, 9, 3, 2, 30),
    )

    private data class Setup(val size: BoardSize, val rooms: Int, val islands: Int, val portals: Int, val lines: Int) {
        override fun toString() = "$size rooms $rooms islands $islands portals $portals lines $lines"
    }

    private fun layout(setup: Setup, seed: Int): BoardLayout {
        val size = setup.size
        return BoardLayout.generate(
            geometries.getValue(size), size.walls, size.wallMinLength, size.wallMaxLength, size.farCells,
            Random(seed), setup.lines, setup.islands, setup.portals, setup.rooms,
        )
    }

    private fun forEachLayout(seeds: Int, body: (String, Setup, SphereGeometry, BoardLayout) -> Unit) {
        for (setup in setups) {
            val geometry = geometries.getValue(setup.size)
            for (seed in 0 until seeds) body("$setup seed $seed", setup, geometry, layout(setup, seed))
        }
    }

    private fun passages(layout: BoardLayout): List<Pair<Int, Int>> =
        layout.gates.filter { layout.portalPartner(it.first) != it.second }

    @Test
    fun lineCellsStandOnTheNearCellOfAPassage() {
        var lines = 0
        forEachLayout(20) { label, _, geometry, layout ->
            assertTrue("$label has rooms", layout.hasRooms)
            val passages = passages(layout)
            for (cell in 0 until geometry.cellCount) {
                if (!layout.isLine(cell)) continue
                lines++
                val row = layout.linePath(cell)
                val passage = passages.firstOrNull { it.first == cell && it.second == row[0] }
                assertTrue("$label line $cell is not at a passage", passage != null)
                assertTrue("$label line $cell on a pentagon", !geometry.isPentagon(cell))
                assertTrue("$label line $cell is a far cell", !layout.isFar(cell))
                assertTrue("$label line $cell is a portal", !layout.isPortal(cell))
                val side = layout.lineSide(cell)
                assertEquals("$label line $cell side", row[0], geometry.edgeNeighbor[geometry.cornerStart[cell] + side])
                val straight = BoardLayout.straightRow(geometry, layout.links, cell, side, BoardLayout.maxLineLength(geometry))
                assertArrayEquals("$label line $cell row", straight, row)
            }
        }
        assertTrue("line cells on boards with rooms: $lines", lines > 0)
    }

    @Test
    fun rowsRunOnlyIntoDeeperRegions() {
        forEachLayout(20) { label, _, geometry, layout ->
            for (cell in 0 until geometry.cellCount) {
                if (!layout.isLine(cell)) continue
                val row = layout.linePath(cell)
                val depth = layout.regionDepth(layout.regionOf(cell))
                for (c in row) {
                    assertTrue("$label line $cell reaches $c", layout.regionDepth(layout.regionOf(c)) > depth)
                }
                val farRegion = layout.regionOf(row[0])
                val inFar = row.count { layout.regionOf(it) == farRegion }
                assertTrue("$label line $cell has $inFar cells in the far room", inFar >= BoardLayout.MIN_LINE_LENGTH)
                assertTrue("$label line $cell row length", row.size >= BoardLayout.MIN_LINE_LENGTH)
            }
        }
    }

    @Test
    fun lineCountStaysWithinPassagesAndRequest() {
        forEachLayout(20) { label, setup, _, layout ->
            val passageCount = passages(layout).size
            assertTrue(
                "$label ${layout.lineCount} lines, $passageCount passages",
                layout.lineCount <= minOf(setup.lines, passageCount),
            )
        }
    }

    @Test
    fun everyLandCellStaysInAnotherCellsScope() {
        forEachLayout(20) { label, _, geometry, layout ->
            val seen = BooleanArray(geometry.cellCount)
            for (cell in 0 until geometry.cellCount) {
                for (other in layout.scopes[cell]) if (other != cell) seen[other] = true
            }
            for (cell in 0 until geometry.cellCount) {
                if (layout.isPresent(cell)) assertTrue("$label cell $cell seen by no cell", seen[cell])
            }
        }
    }

    @Test
    fun noRoomsKeepsTheLinesAndTheDraws() {
        for (size in BoardSize.values()) {
            if (size.lineCells == 0) continue
            val geometry = geometries.getValue(size)
            val islandOptions = listOf(0, minOf(size.islands, BoardLayout.maxIslandCount(geometry))).distinct()
            for (islands in islandOptions) {
                for (seed in 0 until 6) {
                    val label = "$size islands $islands seed $seed"
                    val plainRandom = Random(seed)
                    val plain = BoardLayout.generate(
                        geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells,
                        plainRandom, size.lineCells, islands, size.portals,
                    )
                    val roomsRandom = Random(seed)
                    val zero = BoardLayout.generate(
                        geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells,
                        roomsRandom, size.lineCells, islands, size.portals, 0,
                    )
                    assertEquals("$label next draw", plainRandom.nextLong(), roomsRandom.nextLong())
                    assertTrue("$label has lines", plain.lineCount > 0)
                    assertEquals("$label line count", plain.lineCount, zero.lineCount)
                    for (cell in 0 until geometry.cellCount) {
                        assertEquals("$label line side $cell", plain.lineSide(cell), zero.lineSide(cell))
                        assertArrayEquals("$label line path $cell", plain.linePath(cell), zero.linePath(cell))
                        assertArrayEquals("$label scope $cell", plain.scopes[cell], zero.scopes[cell])
                        assertEquals("$label far $cell", plain.isFar(cell), zero.isFar(cell))
                    }
                }
            }
        }
    }

    @Test
    fun manyRequestedLinesGiveAtMostOnePerPassage() {
        val setup = Setup(BoardSize.GIANT, 7, 0, 0, 200)
        for (seed in 0 until 10) {
            val layout = layout(setup, seed)
            val passageCount = passages(layout).size
            assertTrue("seed $seed ${layout.lineCount} lines, $passageCount passages", layout.lineCount <= passageCount)
        }
    }
}
