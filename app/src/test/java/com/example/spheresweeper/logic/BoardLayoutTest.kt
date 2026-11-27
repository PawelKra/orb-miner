package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BoardLayoutTest {

    private val geometries: Map<BoardSize, SphereGeometry> =
        BoardSize.values().associateWith { SphereGeometry.build(it.frequency) }

    @Test
    fun classicLayoutUsesPlainNeighbours() {
        val geometry = geometries.getValue(BoardSize.MEDIUM)
        val layout = BoardLayout.classic(geometry)
        assertEquals(0, layout.walls.size)
        assertEquals(0, layout.farCount)
        for (cell in 0 until geometry.cellCount) {
            assertTrue(geometry.neighbors[cell].contentEquals(layout.scopes[cell]))
        }
    }

    @Test
    fun wallsRespectLimitsAndSeparateOnlyTheirOwnEdges() {
        for (size in BoardSize.values()) {
            val geometry = geometries.getValue(size)
            for (seed in 0 until 40) {
                val layout = BoardLayout.generate(geometry, size, Random(seed))
                assertTrue("$size seed $seed wall count", layout.walls.size <= size.walls)
                if (size.walls > 0 && size.cellCount > 100) {
                    assertTrue("$size seed $seed has walls", layout.walls.isNotEmpty())
                }

                // Pairs cut by the walls, recovered from the drawn corners.
                val cut = HashSet<Pair<Int, Int>>()
                val usedPoints = HashSet<Int>()
                for (wall in layout.walls) {
                    val edges = wall.size - 1
                    assertTrue("$size seed $seed length $edges", edges in size.wallMinLength..size.wallMaxLength)
                    for (corner in wall) {
                        assertTrue("$size seed $seed walls share a point", usedPoints.add(geometry.cornerPoint[corner]))
                    }
                    for (k in 0 until edges) {
                        val pair = cellsAcross(geometry, wall[k], wall[k + 1])
                        assertTrue("$size seed $seed segment $k is a cell edge", pair != null)
                        cut.add(pair!!)
                    }
                }

                val wallEdges = IntArray(geometry.cellCount)
                for ((a, b) in cut) {
                    wallEdges[a]++
                    wallEdges[b]++
                }
                for (cell in 0 until geometry.cellCount) {
                    assertTrue("$size seed $seed cell $cell wraps", wallEdges[cell] <= 2)
                    val links = layout.links[cell]
                    assertTrue("$size seed $seed cell $cell isolated", links.isNotEmpty())
                    for (nb in geometry.neighbors[cell]) {
                        val separated = cut.contains(Pair(minOf(cell, nb), maxOf(cell, nb)))
                        assertEquals("$size seed $seed $cell-$nb", !separated, links.contains(nb))
                        if (!separated) assertTrue(layout.links[nb].contains(cell))
                    }
                }
            }
        }
    }

    @Test
    fun farCellsSeeTwoStepsThroughLinksAndOthersSeeLinks() {
        val size = BoardSize.GIANT
        val geometry = geometries.getValue(size)
        val layout = BoardLayout.generate(geometry, size, Random(7))
        assertEquals(size.farCells, layout.farCount)
        for (cell in 0 until geometry.cellCount) {
            val expected = if (layout.isFar(cell)) {
                val set = HashSet<Int>()
                for (a in layout.links[cell]) {
                    set.add(a)
                    for (b in layout.links[a]) if (b != cell) set.add(b)
                }
                set.sorted()
            } else {
                layout.links[cell].toList()
            }
            assertEquals("cell $cell", expected, layout.scopes[cell].toList())
        }
        // Without walls a far hexagon sees 6 + 12 cells.
        val open = BoardLayout.generate(geometry, 0, 0, 0, geometry.cellCount, Random(1))
        val hexagon = (0 until geometry.cellCount).first { !geometry.isPentagon(it) &&
            geometry.neighbors[it].none { nb -> geometry.isPentagon(nb) } &&
            geometry.neighbors[it].all { nb -> geometry.neighbors[nb].none { geometry.isPentagon(it) } } }
        assertEquals(18, open.scopes[hexagon].size)
    }

    @Test
    fun sameSeedGivesSameLayout() {
        val geometry = geometries.getValue(BoardSize.HUGE)
        val a = BoardLayout.generate(geometry, BoardSize.HUGE, Random(42))
        val b = BoardLayout.generate(geometry, BoardSize.HUGE, Random(42))
        assertEquals(a.walls.map { it.toList() }, b.walls.map { it.toList() })
        for (cell in 0 until geometry.cellCount) assertEquals(a.isFar(cell), b.isFar(cell))
    }

    @Test
    fun farCellNumberCountsSecondRingAndWallHidesMine() {
        val geometry = geometries.getValue(BoardSize.LARGE)
        val open = BoardLayout.generate(geometry, 0, 0, 0, geometry.cellCount, Random(3))
        val cell = 100
        val ringOne = geometry.neighbors[cell].toSet()
        val ringTwo = open.scopes[cell].first { it !in ringOne }

        val board = MinesweeperBoard(open.scopes, 1)
        board.placeMinesAt(intArrayOf(ringTwo))
        assertEquals(MoveResult.REVEALED, board.reveal(cell))
        assertEquals(1, board.adjacentMines(cell))

        // A plain board never counts that mine from this cell.
        val plain = MinesweeperBoard(geometry.neighbors, 1)
        plain.placeMinesAt(intArrayOf(ringTwo))
        assertEquals(0, plain.adjacentMines(cell))
    }

    @Test
    fun wallsNeverCutRegionOff() {
        val size = BoardSize.GIANT
        val geometry = geometries.getValue(size)
        val layout = BoardLayout.generate(geometry, size.walls, size.wallMinLength, size.wallMaxLength, 0, Random(11))
        assertTrue(layout.walls.isNotEmpty())
        val board = MinesweeperBoard(layout.scopes, 0)
        board.placeMinesAt(IntArray(0))
        // No mines: one reveal spreads over the links to the whole sphere.
        assertEquals(MoveResult.WON, board.reveal(0))
        for (cell in 0 until geometry.cellCount) assertTrue(board.isRevealed(cell))
    }

    /** The two cells separated by the edge between two corner indices, or null if it is not a cell edge. */
    private fun cellsAcross(geometry: SphereGeometry, cornerA: Int, cornerB: Int): Pair<Int, Int>? {
        val pa = geometry.cornerPoint[cornerA]
        val pb = geometry.cornerPoint[cornerB]
        for (cell in 0 until geometry.cellCount) {
            val start = geometry.cornerStart[cell]
            val end = geometry.cornerStart[cell + 1]
            for (k in start until end) {
                val next = if (k + 1 < end) k + 1 else start
                if (geometry.cornerPoint[k] == pa && geometry.cornerPoint[next] == pb) {
                    val other = geometry.edgeNeighbor[k]
                    return Pair(minOf(cell, other), maxOf(cell, other))
                }
            }
        }
        return null
    }
}
