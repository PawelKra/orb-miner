package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class MinesweeperBoardTest {

    private val geometries: Map<BoardSize, SphereGeometry> =
        BoardSize.values().associateWith { SphereGeometry.build(it.frequency) }

    private fun geometry(size: BoardSize): SphereGeometry = geometries.getValue(size)

    // ---------------------------------------------------------------- first click

    @Test
    fun firstRevealIsAlwaysSafe() {
        for (size in BoardSize.values()) {
            val neighbors = geometry(size).neighbors
            val picker = Random(size.ordinal * 7919L)
            for (seed in 0 until 200) {
                val board = MinesweeperBoard(neighbors, size.mines, Random(seed.toLong()))
                assertEquals(GameStatus.READY, board.status)
                assertFalse(board.minesPlaced)
                val first = picker.nextInt(board.cellCount)

                val result = board.reveal(first)

                assertNotEquals("$size seed $seed", MoveResult.LOST, result)
                assertNotEquals(MoveResult.NOTHING, result)
                assertTrue(
                    "$size seed $seed status ${board.status}",
                    board.status == GameStatus.PLAYING || board.status == GameStatus.WON,
                )
                assertTrue(board.minesPlaced)
                assertFalse("$size seed $seed first cell mined", board.isMine(first))
                for (nb in neighbors[first]) {
                    assertFalse("$size seed $seed neighbour $nb mined", board.isMine(nb))
                }
                assertEquals(0, board.adjacentMines(first))
                assertTrue(board.isRevealed(first))
                assertEquals(size.mines, board.mineCount)
                assertEquals(size.mines, countMines(board))
                assertEquals(-1, board.explodedCell)
            }
        }
    }

    @Test
    fun minePlacementDependsOnRandom() {
        val neighbors = geometry(BoardSize.LARGE).neighbors
        val a = MinesweeperBoard(neighbors, BoardSize.LARGE.mines, Random(1))
        val b = MinesweeperBoard(neighbors, BoardSize.LARGE.mines, Random(2))
        a.reveal(0)
        b.reveal(0)
        var differs = false
        for (cell in 0 until a.cellCount) {
            if (a.isMine(cell) != b.isMine(cell)) differs = true
        }
        assertTrue(differs)
    }

    @Test
    fun mineCountIsClampedToFitSafeZone() {
        val neighbors = geometry(BoardSize.TINY).neighbors
        val board = MinesweeperBoard(neighbors, 10_000)
        assertEquals(neighbors.size - 7, board.mineCount)
        assertNotEquals(MoveResult.LOST, board.reveal(3))
        assertEquals(board.mineCount, countMines(board))

        val negative = MinesweeperBoard(neighbors, -5)
        assertEquals(0, negative.mineCount)
    }

    @Test
    fun adjacentMinesIsZeroBeforePlacement() {
        val board = MinesweeperBoard(geometry(BoardSize.SMALL).neighbors, BoardSize.SMALL.mines)
        for (cell in 0 until board.cellCount) {
            assertEquals(0, board.adjacentMines(cell))
        }
    }

    // ---------------------------------------------------------------- flood fill

    @Test
    fun floodFillRevealsZeroRegionAndNumberedBorderOnly() {
        val geometry = geometry(BoardSize.MEDIUM)
        val neighbors = geometry.neighbors
        val z = FloatArray(geometry.cellCount) { geometry.centers[3 * it + 2] }

        // A belt of mines around the equator separates the southern and northern caps.
        val mines = (0 until geometry.cellCount).filter { abs(z[it]) < 0.25f }.toIntArray()
        val isMine = BooleanArray(geometry.cellCount)
        for (m in mines) isMine[m] = true
        val adjacency = IntArray(geometry.cellCount) { cell -> neighbors[cell].count { isMine[it] } }

        val start = (0 until geometry.cellCount).minByOrNull { z[it] }!!
        assertEquals(0, adjacency[start])

        // Flag one safe zero neighbour of the start cell; the fill must not uncover it.
        val flaggedCell = neighbors[start].first { !isMine[it] && adjacency[it] == 0 }

        val board = MinesweeperBoard(neighbors, mines.size)
        board.placeMinesAt(mines)
        assertEquals(GameStatus.PLAYING, board.status)
        assertEquals(mines.size, board.mineCount)
        assertTrue(board.toggleFlag(flaggedCell))

        val result = board.reveal(start)
        assertEquals(MoveResult.REVEALED, result)

        val expected = referenceFlood(neighbors, isMine, adjacency, start, setOf(flaggedCell))
        var revealed = 0
        for (cell in 0 until geometry.cellCount) {
            assertEquals("cell $cell", expected.contains(cell), board.isRevealed(cell))
            if (board.isRevealed(cell)) revealed++
            if (isMine[cell]) assertFalse("mine $cell revealed", board.isRevealed(cell))
            assertEquals(adjacency[cell], board.adjacentMines(cell))
        }
        assertEquals(revealed, board.revealedCount)
        assertFalse("flagged cell revealed", board.isRevealed(flaggedCell))
        assertTrue(board.isFlagged(flaggedCell))

        // The region is a connected zero area plus its numbered border.
        var numberedBorder = 0
        for (cell in expected) {
            if (adjacency[cell] == 0) {
                for (nb in neighbors[cell]) {
                    if (nb != flaggedCell) assertTrue("neighbour $nb of zero $cell", board.isRevealed(nb))
                }
            } else {
                numberedBorder++
                assertTrue(
                    "numbered $cell must touch a revealed zero",
                    neighbors[cell].any { board.isRevealed(it) && adjacency[it] == 0 },
                )
            }
        }
        assertTrue(numberedBorder > 0)
        assertTrue(expected.size > 7)

        // The northern cap stays covered.
        val northSafe = (0 until geometry.cellCount).filter { z[it] > 0.5f && !isMine[it] }
        assertTrue(northSafe.isNotEmpty())
        for (cell in northSafe) assertFalse("north cell $cell", board.isRevealed(cell))
        assertEquals(GameStatus.PLAYING, board.status)
    }

    @Test
    fun revealingNumberedCellRevealsOnlyThatCell() {
        val neighbors = geometry(BoardSize.SMALL).neighbors
        val cell = 20
        val mine = neighbors[cell][0]
        val board = MinesweeperBoard(neighbors, 1)
        board.placeMinesAt(intArrayOf(mine))
        assertEquals(MoveResult.REVEALED, board.reveal(cell))
        assertEquals(1, board.revealedCount)
        assertEquals(1, board.adjacentMines(cell))
    }

    @Test
    fun revealingFlaggedOrRevealedCellDoesNothing() {
        val neighbors = geometry(BoardSize.SMALL).neighbors
        val board = MinesweeperBoard(neighbors, 1)
        board.placeMinesAt(intArrayOf(neighbors[20][0]))
        assertTrue(board.toggleFlag(50))
        assertEquals(MoveResult.NOTHING, board.reveal(50))
        assertFalse(board.isRevealed(50))
        assertEquals(MoveResult.REVEALED, board.reveal(20))
        assertEquals(MoveResult.NOTHING, board.reveal(20))
        assertFalse(board.toggleFlag(20))
    }

    // ---------------------------------------------------------------- chording

    @Test
    fun chordWithCorrectFlagsRevealsNeighbours() {
        val neighbors = geometry(BoardSize.SMALL).neighbors
        val cell = 20
        val mine = neighbors[cell][0]
        // Mines around a distant cell keep that cell unreachable, so the chord cannot win the game.
        val far = farCell(neighbors, cell)
        val mines = intArrayOf(mine) + neighbors[far]
        val board = MinesweeperBoard(neighbors, mines.size)
        board.placeMinesAt(mines)
        assertEquals(MoveResult.REVEALED, board.reveal(cell))
        assertEquals(1, board.adjacentMines(cell))
        assertTrue(board.toggleFlag(mine))
        assertTrue(board.canChord(cell))

        assertEquals(MoveResult.REVEALED, board.chord(cell))
        assertEquals(GameStatus.PLAYING, board.status)
        for (nb in neighbors[cell]) {
            if (nb == mine) {
                assertFalse(board.isRevealed(nb))
                assertTrue(board.isFlagged(nb))
            } else {
                assertTrue("neighbour $nb", board.isRevealed(nb))
            }
        }
        assertFalse(board.isRevealed(far))
        assertTrue(neighbors[far].none { board.isRevealed(it) })
        assertEquals(-1, board.explodedCell)
        assertFalse(board.canChord(cell))
        assertEquals(MoveResult.NOTHING, board.chord(cell))
    }

    @Test
    fun chordWithWrongFlagLoses() {
        val neighbors = geometry(BoardSize.SMALL).neighbors
        val cell = 20
        val mine = neighbors[cell][0]
        val wrong = neighbors[cell][1]
        val board = MinesweeperBoard(neighbors, 1)
        board.placeMinesAt(intArrayOf(mine))
        assertEquals(MoveResult.REVEALED, board.reveal(cell))
        assertTrue(board.toggleFlag(wrong))
        assertTrue(board.canChord(cell))

        assertEquals(MoveResult.LOST, board.chord(cell))
        assertEquals(GameStatus.LOST, board.status)
        assertEquals(mine, board.explodedCell)
        assertTrue(board.isWrongFlag(wrong))
        assertFalse(board.isWrongFlag(mine))
        assertFalse(board.isWrongFlag(cell))
    }

    @Test
    fun chordWithFlagCountMismatchDoesNothing() {
        val neighbors = geometry(BoardSize.SMALL).neighbors
        val cell = 20
        val mine = neighbors[cell][0]
        val board = MinesweeperBoard(neighbors, 1)
        board.placeMinesAt(intArrayOf(mine))
        assertEquals(MoveResult.REVEALED, board.reveal(cell))

        // No flags at all.
        assertFalse(board.canChord(cell))
        assertEquals(MoveResult.NOTHING, board.chord(cell))

        // Too many flags.
        assertTrue(board.toggleFlag(neighbors[cell][0]))
        assertTrue(board.toggleFlag(neighbors[cell][1]))
        assertEquals(2, board.flaggedNeighbors(cell))
        assertFalse(board.canChord(cell))
        assertEquals(MoveResult.NOTHING, board.chord(cell))
        assertEquals(1, board.revealedCount)
        assertEquals(GameStatus.PLAYING, board.status)
    }

    @Test
    fun chordOnZeroOrCoveredCellDoesNothing() {
        val neighbors = geometry(BoardSize.MEDIUM).neighbors
        val cell = 30
        // Mines around a distant cell keep that cell unreachable, so the reveal cannot win the game.
        val far = farCell(neighbors, cell)
        val mines = neighbors[far].copyOf()
        val board = MinesweeperBoard(neighbors, mines.size)
        board.placeMinesAt(mines)

        // Covered cell.
        assertEquals(MoveResult.NOTHING, board.chord(cell))
        assertFalse(board.canChord(cell))

        assertEquals(MoveResult.REVEALED, board.reveal(cell))
        assertEquals(0, board.adjacentMines(cell))
        assertEquals(GameStatus.PLAYING, board.status)
        assertFalse(board.isRevealed(far))
        val before = board.revealedCount
        assertFalse(board.canChord(cell))
        assertEquals(MoveResult.NOTHING, board.chord(cell))
        assertEquals(before, board.revealedCount)
    }

    @Test
    fun chordBeforeFirstRevealDoesNothing() {
        val board = MinesweeperBoard(geometry(BoardSize.TINY).neighbors, BoardSize.TINY.mines)
        assertEquals(MoveResult.NOTHING, board.chord(0))
        assertEquals(GameStatus.READY, board.status)
    }

    // ---------------------------------------------------------------- win / loss

    @Test
    fun revealingAllSafeCellsWins() {
        val neighbors = geometry(BoardSize.TINY).neighbors
        val mines = intArrayOf(0, 5, 17, 30)
        val board = MinesweeperBoard(neighbors, mines.size)
        board.placeMinesAt(mines)
        val isMine = BooleanArray(board.cellCount)
        for (m in mines) isMine[m] = true

        var last = MoveResult.NOTHING
        for (cell in 0 until board.cellCount) {
            if (isMine[cell] || board.isRevealed(cell)) continue
            assertEquals(GameStatus.PLAYING, board.status)
            last = board.reveal(cell)
            assertNotEquals(MoveResult.LOST, last)
        }
        assertEquals(MoveResult.WON, last)
        assertEquals(GameStatus.WON, board.status)
        assertEquals(board.cellCount - mines.size, board.revealedCount)
        assertEquals(-1, board.explodedCell)

        // Game over: nothing changes any more.
        assertEquals(MoveResult.NOTHING, board.reveal(0))
        assertFalse(board.toggleFlag(0))
        assertFalse(board.isWrongFlag(0))
    }

    @Test
    fun revealingMineLosesAndFreezesBoard() {
        val neighbors = geometry(BoardSize.SMALL).neighbors
        val board = MinesweeperBoard(neighbors, 2)
        board.placeMinesAt(intArrayOf(10, 40))
        assertTrue(board.toggleFlag(60))
        assertFalse(board.isWrongFlag(60))

        assertEquals(MoveResult.LOST, board.reveal(40))
        assertEquals(GameStatus.LOST, board.status)
        assertEquals(40, board.explodedCell)
        assertTrue(board.isWrongFlag(60))

        assertEquals(MoveResult.NOTHING, board.reveal(70))
        assertFalse(board.isRevealed(70))
        assertEquals(MoveResult.NOTHING, board.reveal(10))
        assertFalse(board.toggleFlag(80))
        assertEquals(MoveResult.NOTHING, board.chord(70))
        assertEquals(40, board.explodedCell)
    }

    // ---------------------------------------------------------------- flags

    @Test
    fun minesRemainingTracksFlags() {
        val size = BoardSize.TINY
        val board = MinesweeperBoard(geometry(size).neighbors, size.mines)
        assertEquals(size.mines, board.minesRemaining)

        assertTrue(board.toggleFlag(1))
        assertTrue(board.isFlagged(1))
        assertEquals(1, board.flagCount)
        assertEquals(size.mines - 1, board.minesRemaining)

        assertTrue(board.toggleFlag(1))
        assertFalse(board.isFlagged(1))
        assertEquals(0, board.flagCount)
        assertEquals(size.mines, board.minesRemaining)

        for (cell in 0 until size.mines + 2) assertTrue(board.toggleFlag(cell))
        assertEquals(size.mines + 2, board.flagCount)
        assertEquals(-2, board.minesRemaining)
        assertEquals(GameStatus.READY, board.status)
    }

    @Test
    fun placeMinesAtIgnoresDuplicates() {
        val board = MinesweeperBoard(geometry(BoardSize.TINY).neighbors, 6)
        board.placeMinesAt(intArrayOf(3, 3, 9))
        assertEquals(2, board.mineCount)
        assertEquals(2, countMines(board))
        assertTrue(board.minesPlaced)
        assertEquals(GameStatus.PLAYING, board.status)
    }

    // ---------------------------------------------------------------- helpers

    private fun countMines(board: MinesweeperBoard): Int =
        (0 until board.cellCount).count { board.isMine(it) }

    /** A cell whose neighbourhood does not touch the neighbourhood of [cell] (graph distance >= 3). */
    private fun farCell(neighbors: Array<IntArray>, cell: Int): Int {
        val distance = IntArray(neighbors.size) { -1 }
        val queue = ArrayDeque<Int>()
        distance[cell] = 0
        queue.addLast(cell)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (nb in neighbors[current]) {
                if (distance[nb] < 0) {
                    distance[nb] = distance[current] + 1
                    queue.addLast(nb)
                }
            }
        }
        return distance.indices.maxByOrNull { distance[it] }!!
    }

    /** Straightforward breadth-first reference implementation of the reveal flood fill. */
    private fun referenceFlood(
        neighbors: Array<IntArray>,
        isMine: BooleanArray,
        adjacency: IntArray,
        start: Int,
        flagged: Set<Int>,
    ): Set<Int> {
        val result = mutableSetOf(start)
        val queue = ArrayDeque<Int>()
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (adjacency[current] != 0) continue
            for (nb in neighbors[current]) {
                if (!isMine[nb] && nb !in flagged && result.add(nb)) {
                    queue.addLast(nb)
                }
            }
        }
        return result
    }

}
