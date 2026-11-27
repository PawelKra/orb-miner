package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            } else if (layout.isLine(cell)) {
                layout.linePath(cell).sorted()
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

    @Test
    fun lineCellsMatchBoardSizeAndCountTheirRow() {
        for (size in BoardSize.values()) {
            val geometry = geometries.getValue(size)
            val maxLength = 2 * geometry.frequency
            for (seed in 0 until 20) {
                val label = "$size seed $seed"
                val layout = BoardLayout.generate(geometry, size, Random(seed))
                assertEquals("$label line count", size.lineCells, layout.lineCount)
                for (cell in 0 until geometry.cellCount) {
                    if (!layout.isLine(cell)) {
                        assertEquals("$label cell $cell side", -1, layout.lineSide(cell))
                        assertEquals("$label cell $cell path", 0, layout.linePath(cell).size)
                        if (!layout.isFar(cell)) {
                            assertEquals("$label cell $cell scope", layout.links[cell].toList(), layout.scopes[cell].toList())
                        }
                        continue
                    }
                    val row = layout.linePath(cell)
                    assertFalse("$label line $cell is a pentagon", geometry.isPentagon(cell))
                    assertFalse("$label line $cell is far", layout.isFar(cell))
                    assertTrue("$label line $cell length ${row.size}", row.size in BoardLayout.MIN_LINE_LENGTH..maxLength)
                    assertEquals("$label line $cell scope", row.sorted(), layout.scopes[cell].toList())
                }
            }
        }
    }

    @Test
    fun lineRowsGoStraightAndStopAtWallPentagonOrLimit() {
        val reasons = HashMap<String, Int>()
        for (size in BoardSize.values()) {
            val geometry = geometries.getValue(size)
            val maxLength = 2 * geometry.frequency
            for (seed in 0 until 10) {
                // Every eligible cell becomes a line cell, so all kinds of rows show up.
                val layout = BoardLayout.generate(
                    geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, Random(seed),
                    lineCount = geometry.cellCount,
                )
                for (cell in 0 until geometry.cellCount) {
                    if (!layout.isLine(cell)) continue
                    val label = "$size seed $seed line $cell"
                    val row = layout.linePath(cell)
                    assertEquals(label, row.toList(), BoardLayout.straightRow(geometry, layout.links, cell, layout.lineSide(cell), maxLength).toList())
                    val reason = checkStraightRow(label, geometry, layout.links, cell, layout.lineSide(cell), row, maxLength)
                    reasons[reason] = (reasons[reason] ?: 0) + 1
                }
            }
        }
        assertTrue("rows ended by a wall: $reasons", (reasons["wall"] ?: 0) > 0)
        assertTrue("rows ended on a pentagon: $reasons", (reasons["pentagon"] ?: 0) > 0)
        assertTrue("rows ended by the limit: $reasons", (reasons["limit"] ?: 0) > 0)
    }

    @Test
    fun straightRowStopsBeforeRepeatingACell() {
        // Without a length limit rows only end on pentagons or by coming back around the sphere.
        val geometry = geometries.getValue(BoardSize.LARGE)
        val reasons = HashMap<String, Int>()
        for (cell in 0 until geometry.cellCount) {
            if (geometry.isPentagon(cell)) continue
            for (side in 0 until 6) {
                val label = "cell $cell side $side"
                val row = BoardLayout.straightRow(geometry, geometry.neighbors, cell, side, geometry.cellCount)
                val reason = checkStraightRow(label, geometry, geometry.neighbors, cell, side, row, geometry.cellCount)
                assertTrue("$label ended by $reason", reason == "pentagon" || reason == "repeat")
                reasons[reason] = (reasons[reason] ?: 0) + 1
            }
        }
        assertTrue("rows ended by a repeat: $reasons", (reasons["repeat"] ?: 0) > 0)
    }

    @Test
    fun lineCellNumberCountsOnlyItsRowAndZeroOpensTheRow() {
        val size = BoardSize.GIANT
        val geometry = geometries.getValue(size)
        val layout = BoardLayout.generate(geometry, size, Random(5))
        val cell = (0 until geometry.cellCount).first { layout.isLine(it) }
        val row = layout.linePath(cell)
        val offRow = geometry.neighbors[cell].filter { it !in row }
        assertTrue(offRow.isNotEmpty())

        // Mines on every neighbour outside the row and on the third cell of the row: the number is 1.
        val board = MinesweeperBoard(layout.scopes, 0)
        board.placeMinesAt((offRow + row[2]).toIntArray())
        assertEquals(MoveResult.REVEALED, board.reveal(cell))
        assertEquals(1, board.adjacentMines(cell))
        assertFalse(board.isRevealed(row[0]))

        // Without the mine in the row the cell shows 0 and the reveal opens the whole row.
        val open = MinesweeperBoard(layout.scopes, 0)
        open.placeMinesAt(offRow.toIntArray())
        open.reveal(cell)
        assertEquals(0, open.adjacentMines(cell))
        for (c in row) assertTrue("row cell $c revealed", open.isRevealed(c))
    }

    @Test
    fun linesDoNotChangeWallsOrFarCellsOfTheSameSeed() {
        val size = BoardSize.HUGE
        val geometry = geometries.getValue(size)
        val plain = BoardLayout.generate(geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, Random(9))
        val lined = BoardLayout.generate(geometry, size, Random(9))
        val again = BoardLayout.generate(geometry, size, Random(9))
        assertEquals(0, plain.lineCount)
        assertEquals(plain.walls.map { it.toList() }, lined.walls.map { it.toList() })
        for (cell in 0 until geometry.cellCount) {
            assertEquals(plain.isFar(cell), lined.isFar(cell))
            assertEquals(lined.lineSide(cell), again.lineSide(cell))
            assertEquals(lined.linePath(cell).toList(), again.linePath(cell).toList())
        }
    }

    /**
     * Checks that [row] leaves [cell] through edge [side], passes every hexagon through opposite edges
     * over [links] without repeating a cell, and returns why it ended: "limit", "pentagon", "wall" or
     * "repeat".
     */
    private fun checkStraightRow(
        label: String, geometry: SphereGeometry, links: Array<IntArray>, cell: Int, side: Int, row: IntArray, maxLength: Int,
    ): String {
        assertEquals("$label first cell", geometry.edgeNeighbor[geometry.cornerStart[cell] + side], row[0])
        assertTrue("$label leaves $cell across a wall", links[cell].contains(row[0]))
        assertEquals("$label repeats a cell", row.size, row.toSet().size)
        assertFalse("$label contains its own cell", row.contains(cell))
        assertTrue("$label too long", row.size <= maxLength)
        for (i in 1 until row.size) {
            val prev = if (i >= 2) row[i - 2] else cell
            val here = row[i - 1]
            assertFalse("$label goes on after pentagon $here", geometry.isPentagon(here))
            assertTrue("$label crosses a wall at $here", links[here].contains(row[i]))
            val entry = localEdge(geometry, here, prev)
            val exit = localEdge(geometry, here, row[i])
            assertEquals("$label bends at $here", 3, (exit - entry + 6) % 6)
        }
        val last = row[row.size - 1]
        if (row.size == maxLength) return "limit"
        if (geometry.isPentagon(last)) return "pentagon"
        val before = if (row.size >= 2) row[row.size - 2] else cell
        val next = geometry.edgeNeighbor[geometry.cornerStart[last] + (localEdge(geometry, last, before) + 3) % 6]
        if (!links[last].contains(next)) return "wall"
        assertTrue("$label stopped without a reason", next == cell || row.contains(next))
        return "repeat"
    }

    /** Index 0 until cornerCount(cell) of the edge [cell] shares with [other]. */
    private fun localEdge(geometry: SphereGeometry, cell: Int, other: Int): Int {
        val start = geometry.cornerStart[cell]
        for (k in start until geometry.cornerStart[cell + 1]) {
            if (geometry.edgeNeighbor[k] == other) return k - start
        }
        throw AssertionError("cells $cell and $other are not neighbours")
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
