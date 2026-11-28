package com.example.spheresweeper.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AutoFlagTest {

    private val geometry = SphereGeometry.build(BoardSize.MEDIUM.frequency)

    /** Walls, far cells and line cells on the whole sphere, no ocean. */
    private val layout = BoardLayout.generate(geometry, 8, 3, 6, 8, Random(5), lineCount = 8)

    /** Undirected graph from edge pairs. */
    private fun graph(cellCount: Int, vararg edges: Int): Array<IntArray> {
        val lists = Array(cellCount) { ArrayList<Int>() }
        for (k in edges.indices step 2) {
            lists[edges[k]].add(edges[k + 1])
            lists[edges[k + 1]].add(edges[k])
        }
        return Array(cellCount) { lists[it].sorted().toIntArray() }
    }

    /**
     * Board on the scopes of [layout] with a mine on every cell of the scope of [cell] except the first
     * [safe] ones, plus [extraMines]. Reveals [cell] and the safe scope cells.
     */
    private fun boardAround(cell: Int, safe: Int, extraMines: IntArray = IntArray(0)): MinesweeperBoard {
        val scope = layout.scopes[cell]
        assertFalse("cell $cell in its own scope", cell in scope)
        val mines = scope.copyOfRange(safe, scope.size) + extraMines
        val board = MinesweeperBoard(layout.scopes, 0, present = layout.presentMask())
        board.placeMinesAt(mines)
        assertEquals(MoveResult.REVEALED, board.reveal(cell))
        for (k in 0 until safe) board.reveal(scope[k])
        return board
    }

    /** The cells [autoFlagCells] returns, sorted, so that the scope order does not matter. */
    private fun sortedFlags(board: MinesweeperBoard, cell: Int): IntArray? = board.autoFlagCells(cell)?.sortedArray()

    private fun covered(board: MinesweeperBoard, cells: IntArray): IntArray =
        cells.filter { !board.isRevealed(it) }.sorted().toIntArray()

    @Test
    fun plainNumberFlagsAllCoveredNeighboursWhenTheyMatchTheNumber() {
        val cell = (0 until geometry.cellCount).first {
            !layout.isFar(it) && !layout.isLine(it) && layout.scopes[it].contentEquals(geometry.neighbors[it])
        }
        val board = boardAround(cell, safe = 2)
        val expected = covered(board, layout.scopes[cell])
        assertEquals(board.adjacentMines(cell), expected.size)
        assertArrayEquals(expected, sortedFlags(board, cell))
    }

    @Test
    fun cellBehindAWallIsNeitherCountedNorFlagged() {
        val cell = (0 until geometry.cellCount).first { c ->
            !layout.isFar(c) && !layout.isLine(c) && geometry.neighbors[c].any { it !in layout.scopes[c] }
        }
        val hidden = geometry.neighbors[cell].first { it !in layout.scopes[cell] }
        // A mine behind the wall too: it neither breaks the count nor gets a flag.
        val board = boardAround(cell, safe = 1, extraMines = intArrayOf(hidden))
        val flags = sortedFlags(board, cell)
        assertNotNull(flags)
        assertArrayEquals(covered(board, layout.scopes[cell]), flags)
        assertFalse(hidden in flags!!)
    }

    @Test
    fun farCellFlagsItsWholeRange() {
        val cell = (0 until geometry.cellCount).first { layout.isFar(it) }
        val scope = layout.scopes[cell]
        assertTrue("far scope reaches two steps", scope.size > geometry.neighbors[cell].size)
        val board = boardAround(cell, safe = 3)
        val flags = sortedFlags(board, cell)
        assertArrayEquals(covered(board, scope), flags)
        assertTrue(flags!!.any { it !in geometry.neighbors[cell] })
    }

    @Test
    fun lineCellFlagsItsRowOnly() {
        val cell = (0 until geometry.cellCount).first { layout.isLine(it) }
        val board = boardAround(cell, safe = 0)
        val flags = sortedFlags(board, cell)
        assertArrayEquals(layout.scopes[cell].sortedArray(), flags)
        assertArrayEquals(layout.linePath(cell).sortedArray(), flags)
    }

    @Test
    fun uncertainNumberGivesNull() {
        val cell = (0 until geometry.cellCount).first { !layout.isFar(it) && !layout.isLine(it) }
        // One scope cell is safe but stays covered: one more covered cell than the number.
        val scope = layout.scopes[cell]
        val board = MinesweeperBoard(layout.scopes, 0, present = layout.presentMask())
        board.placeMinesAt(scope.copyOfRange(1, scope.size))
        assertEquals(MoveResult.REVEALED, board.reveal(cell))
        assertFalse(board.isRevealed(scope[0]))
        assertNull(board.autoFlagCells(cell))
        // Flags alone do not make it certain.
        assertTrue(board.toggleFlag(scope[1]))
        assertNull(board.autoFlagCells(cell))
    }

    @Test
    fun allFlaggedGivesEmptyAndFlagsAreLeftAlone() {
        val cell = (0 until geometry.cellCount).first { !layout.isFar(it) && !layout.isLine(it) }
        val board = boardAround(cell, safe = 1)
        val flags = board.autoFlagCells(cell)!!
        assertTrue(flags.isNotEmpty())
        assertTrue(board.toggleFlag(flags[0]))
        // Flagged cells still count as covered and are not listed again.
        assertArrayEquals(flags.copyOfRange(1, flags.size).sortedArray(), sortedFlags(board, cell))
        for (k in 1 until flags.size) assertTrue(board.toggleFlag(flags[k]))
        val none = board.autoFlagCells(cell)
        assertNotNull(none)
        assertEquals(0, none!!.size)
        // The query never changes the board.
        assertEquals(flags.size, board.flagCount)
    }

    @Test
    fun detonatedMineCountsAsKnownAndIsNotFlagged() {
        // Cell 5 stays covered so that the board is not won.
        val neighbors = graph(6, 0, 1, 0, 2, 0, 3, 3, 4)
        val board = MinesweeperBoard(neighbors, 0, lives = 2)
        board.placeMinesAt(intArrayOf(1, 2))
        assertEquals(MoveResult.REVEALED, board.reveal(0))
        assertNull(board.autoFlagCells(0))
        assertEquals(MoveResult.MINE_HIT, board.reveal(1))
        assertNull(board.autoFlagCells(0))
        assertEquals(MoveResult.REVEALED, board.reveal(3))
        assertArrayEquals(intArrayOf(2), board.autoFlagCells(0))
    }

    @Test
    fun zeroGivesEmpty() {
        // Cell 4 stays covered so that the board is not won.
        val neighbors = graph(5, 0, 1, 1, 2, 2, 3)
        val board = MinesweeperBoard(neighbors, 0)
        board.placeMinesAt(intArrayOf(3))
        assertEquals(MoveResult.REVEALED, board.reveal(0))
        assertEquals(0, board.adjacentMines(0))
        assertArrayEquals(IntArray(0), board.autoFlagCells(0))
    }

    @Test
    fun closedLockGivesNullAndOpenLockWorks() {
        // Chain 0-1-2-3-4-5 with the only mine on 2; cell 1 carries a lock that opens at 3 revealed cells.
        // Cell 6 stays covered so that the board is not won.
        val neighbors = graph(7, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5)
        val thresholds = IntArray(7).also { it[1] = 3 }
        val board = MinesweeperBoard(neighbors, 0, lockThresholds = thresholds)
        board.placeMinesAt(intArrayOf(2))
        assertEquals(MoveResult.REVEALED, board.reveal(1))
        assertEquals(1, board.adjacentMines(1))
        assertFalse(board.isLockOpen(1))
        assertNull(board.autoFlagCells(1))
        // Revealing 0 makes the scope of 1 certain, but the lock is still closed.
        assertEquals(MoveResult.REVEALED, board.reveal(0))
        assertFalse(board.isLockOpen(1))
        assertNull(board.autoFlagCells(1))
        assertEquals(MoveResult.REVEALED, board.reveal(5))
        assertTrue(board.isLockOpen(1))
        assertArrayEquals(intArrayOf(2), board.autoFlagCells(1))
    }

    @Test
    fun coveredCellOrUnplacedMinesGiveNull() {
        val neighbors = graph(3, 0, 1, 1, 2)
        val board = MinesweeperBoard(neighbors, 1)
        assertNull(board.autoFlagCells(0))
        board.placeMinesAt(intArrayOf(2))
        assertNull(board.autoFlagCells(1))
    }
}
