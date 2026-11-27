package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LockTest {

    /** Undirected graph from edge pairs. */
    private fun graph(cellCount: Int, vararg edges: Int): Array<IntArray> {
        val lists = Array(cellCount) { ArrayList<Int>() }
        for (k in edges.indices step 2) {
            lists[edges[k]].add(edges[k + 1])
            lists[edges[k + 1]].add(edges[k])
        }
        return Array(cellCount) { lists[it].sorted().toIntArray() }
    }

    private fun locks(cellCount: Int, vararg cellThreshold: Int): IntArray {
        val result = IntArray(cellCount)
        for (k in cellThreshold.indices step 2) result[cellThreshold[k]] = cellThreshold[k + 1]
        return result
    }

    @Test
    fun lockedZeroWaitsAndOpeningSpreadsInACascade() {
        // Chains 0-1-2-3-4, 5-6-7-8 and 9-10-11 with the only mine on 11. Cells 1 and 6 carry locks.
        val neighbors = graph(12, 0, 1, 1, 2, 2, 3, 3, 4, 5, 6, 6, 7, 7, 8, 9, 10, 10, 11)
        val thresholds = locks(12, 1, 6, 6, 8)
        val board = MinesweeperBoard(neighbors, 1, present = null, lockThresholds = thresholds)
        board.placeMinesAt(intArrayOf(11))
        assertEquals(2, board.lockCount)
        assertEquals(6, board.lockThreshold(1))
        assertEquals(0, board.lockThreshold(0))
        assertTrue(board.isLockOpen(0))

        assertEquals(MoveResult.REVEALED, board.reveal(0))
        // 1 shows 0 but is locked: the reveal stops there.
        assertTrue(board.isRevealed(1))
        assertFalse(board.isLockOpen(1))
        assertFalse(board.isRevealed(2))
        assertEquals(2, board.revealedCount)

        assertEquals(MoveResult.REVEALED, board.reveal(5))
        assertTrue(board.isRevealed(6))
        assertFalse(board.isRevealed(7))
        assertEquals(4, board.revealedCount)

        // 9 and 10 bring the count to 6: lock 1 opens and spreads 2, 3, 4; at 8 lock 6 opens and
        // spreads 7, 8. One move, two openings.
        assertEquals(MoveResult.WON, board.reveal(9))
        for (cell in 0..10) assertTrue("cell $cell", board.isRevealed(cell))
        assertTrue(board.isLockOpen(1))
        assertTrue(board.isLockOpen(6))
        assertEquals(11, board.revealedCount)
    }

    @Test
    fun lockedNumberCannotBeChorded() {
        // 1 sees the mine 2 and the safe 3; 4-5 is a separate pair used to raise the count.
        val neighbors = graph(6, 0, 1, 1, 2, 1, 3, 4, 5)
        val board = MinesweeperBoard(neighbors, 1, lockThresholds = locks(6, 1, 4))
        board.placeMinesAt(intArrayOf(2))
        assertEquals(MoveResult.REVEALED, board.reveal(1))
        assertTrue(board.toggleFlag(2))
        assertFalse(board.canChord(1))
        assertEquals(MoveResult.NOTHING, board.chord(1))
        assertFalse(board.isRevealed(3))

        board.reveal(0)
        assertEquals(MoveResult.REVEALED, board.reveal(4))
        assertTrue(board.isLockOpen(1))
        // The number is 1, so opening the lock reveals nothing by itself.
        assertFalse(board.isRevealed(3))
        assertTrue(board.canChord(1))
        assertEquals(MoveResult.WON, board.chord(1))
    }

    @Test
    fun solverIgnoresLockedNumber() {
        // Start 0 reveals 1 (the lock), which alone sees the mine 2; cell 3 is seen by nobody.
        val neighbors = graph(4, 0, 1, 1, 2)
        val mines = booleanArrayOf(false, false, true, false)

        val open = LogicSolver(neighbors)
        open.load(mines, locks(4, 1, 2))
        open.reveal(0)
        assertTrue("lock opens on the start reveal", open.isLockOpen(1))
        assertTrue(open.run())

        val closed = LogicSolver(neighbors)
        closed.load(mines, locks(4, 1, 3))
        closed.reveal(0)
        assertFalse(closed.isLockOpen(1))
        assertFalse("mine 2 or cell 3 deduced from a locked number", closed.run())
        assertTrue(closed.isUnknown(2))
        assertTrue(closed.isUnknown(3))
        assertFalse(LogicSolver.solves(neighbors, mines, 0, lockThresholds = locks(4, 1, 3)))
        assertTrue(LogicSolver.solves(neighbors, mines, 0, lockThresholds = locks(4, 1, 2)))
    }

    @Test
    fun solverSpreadsLikeTheBoardOnOpening() {
        val neighbors = graph(12, 0, 1, 1, 2, 2, 3, 3, 4, 5, 6, 6, 7, 7, 8, 9, 10, 10, 11)
        val thresholds = locks(12, 1, 6, 6, 8)
        val mines = BooleanArray(12).also { it[11] = true }
        val board = MinesweeperBoard(neighbors, 1, lockThresholds = thresholds)
        board.placeMinesAt(intArrayOf(11))
        val solver = LogicSolver(neighbors)
        solver.load(mines, thresholds)
        for (move in intArrayOf(0, 5, 9)) {
            board.reveal(move)
            solver.reveal(move)
            for (cell in 0 until 12) {
                assertEquals("after $move cell $cell", board.isRevealed(cell), solver.isRevealed(cell))
                assertEquals("after $move lock $cell", board.isLockOpen(cell), solver.isLockOpen(cell))
            }
            assertEquals(board.revealedCount, solver.revealedCount)
        }
    }

    @Test
    fun boardAndSolverAgreeOnGeneratedBoardsWithLocks() {
        for (size in listOf(BoardSize.MEDIUM, BoardSize.LARGE, BoardSize.GIANT)) {
            val geometry = SphereGeometry.build(size.frequency)
            for (seed in 0 until 10) {
                val label = "$size seed $seed"
                val layout = BoardLayout.generate(
                    geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, Random(seed),
                    size.lineCells, 2, 2,
                )
                val mineCount = layout.presentCount * 17 / 100
                val generated = NoGuessGenerator.generate(layout, mineCount, Random(seed), lockCount = 8)
                val board = MinesweeperBoard(
                    layout.scopes, mineCount, present = layout.presentMask(), lockThresholds = generated.lockThresholds,
                )
                board.startWith(generated.mines, generated.start)
                val mines = BooleanArray(geometry.cellCount)
                for (cell in generated.mines) mines[cell] = true
                val solver = LogicSolver(layout.scopes, layout.presentMask())
                solver.load(mines, generated.lockThresholds)
                solver.reveal(generated.start)
                assertEquals(label, board.revealedCount, solver.revealedCount)
                for (cell in 0 until geometry.cellCount) {
                    assertEquals("$label number $cell", board.adjacentMines(cell), solver.number(cell))
                    assertEquals("$label revealed $cell", board.isRevealed(cell), solver.isRevealed(cell))
                    assertEquals("$label lock $cell", board.isLockOpen(cell), solver.isLockOpen(cell))
                    assertEquals("$label threshold $cell", generated.lockThresholds[cell], board.lockThreshold(cell))
                }
            }
        }
    }

    /**
     * Every step must follow from numbers the player could see before it. A locked number becomes
     * visible only after the step that brought the revealed count to its threshold. Among all layouts
     * with the same mine count that agree with every number visible earlier, the resolved cell must
     * have the same state. Checked by enumerating layouts on a small king-move grid with locks.
     */
    @Test
    fun everyStepIsForcedByNumbersVisibleBeforeIt() {
        val width = 5
        val height = 4
        val cellCount = width * height
        val mineCount = 4
        val grid = Array(cellCount) { cell ->
            val x = cell % width
            val y = cell / width
            val list = ArrayList<Int>()
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until width && ny in 0 until height) list.add(ny * width + nx)
            }
            list.toIntArray()
        }
        val layouts = ArrayList<Int>()
        fun build(from: Int, left: Int, mask: Int) {
            if (left == 0) {
                layouts.add(mask)
                return
            }
            for (cell in from..cellCount - left) build(cell + 1, left - 1, mask or (1 shl cell))
        }
        build(0, mineCount, 0)

        val random = Random(77)
        var checkedSteps = 0
        var lockedUses = 0
        repeat(150) { round ->
            val start = random.nextInt(cellCount)
            val area = grid[start].toSet() + start
            val free = (0 until cellCount).filter { it !in area }.shuffled(random)
            val mines = BooleanArray(cellCount)
            for (i in 0 until mineCount) mines[free[i]] = true
            val thresholds = IntArray(cellCount)
            for (cell in free.drop(mineCount).take(3)) thresholds[cell] = 2 + random.nextInt(cellCount - mineCount - 2)

            val solver = LogicSolver(grid)
            solver.load(mines, thresholds)
            solver.reveal(start)
            solver.run()

            val order = IntArray(cellCount) { solver.resolveOrder(it) }
            val numbers = IntArray(cellCount) { solver.number(it) }
            // Step after which a lock with threshold t is open: the step of the t-th revealed cell.
            val revealOrder = (0 until cellCount).filter { solver.isRevealed(it) }.map { order[it] }.sorted()
            fun visibleFrom(cell: Int): Int {
                val t = thresholds[cell]
                if (t == 0) return order[cell]
                if (t > revealOrder.size) return Int.MAX_VALUE
                return maxOf(order[cell], revealOrder[t - 1])
            }
            val firstConflict = IntArray(layouts.size) { index ->
                val mask = layouts[index]
                var first = Int.MAX_VALUE
                for (cell in 0 until cellCount) {
                    if (!solver.isRevealed(cell)) continue
                    if (mask and (1 shl cell) != 0) first = minOf(first, order[cell])
                    if (grid[cell].count { mask and (1 shl it) != 0 } != numbers[cell]) {
                        first = minOf(first, visibleFrom(cell))
                    }
                }
                first
            }
            for (cell in 0 until cellCount) {
                if (thresholds[cell] > 0 && solver.isRevealed(cell) && visibleFrom(cell) > order[cell]) lockedUses++
                val step = order[cell]
                if (step <= 0) continue
                checkedSteps++
                for (index in layouts.indices) {
                    if (firstConflict[index] < step) continue
                    val mined = layouts[index] and (1 shl cell) != 0
                    assertEquals("round $round cell $cell step $step", mines[cell], mined)
                }
            }
        }
        assertTrue("too few steps checked: $checkedSteps", checkedSteps > 500)
        assertTrue("locks never stayed closed after a reveal: $lockedUses", lockedUses > 50)
    }

    @Test
    fun boardWithoutLocksBehavesAsBefore() {
        val neighbors = graph(4, 0, 1, 1, 2, 2, 3)
        val board = MinesweeperBoard(neighbors, 1)
        assertEquals(0, board.lockCount)
        for (cell in 0 until 4) {
            assertEquals(0, board.lockThreshold(cell))
            assertTrue(board.isLockOpen(cell))
            assertTrue(board.isPresent(cell))
        }
        assertEquals(4, board.presentCount)
    }
}
