package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LogicSolverTest {

    // ---------------------------------------------------------------- hand-made boards

    @Test
    fun singleNumberMarksMinesAndClearsTheRest() {
        // 0 sees {2}, 1 sees {2, 3}; 4 and 5 are seen by nobody and share one mine.
        val scopes = arrayOf(intArrayOf(2), intArrayOf(2, 3), intArrayOf(), intArrayOf(), intArrayOf(), intArrayOf())
        val solver = solverFor(scopes, mines = intArrayOf(2, 4), revealed = intArrayOf(0, 1))

        assertFalse(solver.run())

        assertTrue(solver.isMarked(2))
        assertTrue(solver.isRevealed(3))
        assertTrue(solver.isUnknown(4))
        assertTrue(solver.isUnknown(5))
        assertEquals(1, solver.markedCount)
        assertEquals(3, solver.revealedCount)
    }

    @Test
    fun subsetPairClearsTheDifference() {
        // A = 0 sees {x, y} and shows 1; B = 1 sees {x, y, z} and shows 1. Neither alone decides
        // anything, together they make z safe. x and y stay a 50/50.
        val x = 2
        val y = 3
        val z = 4
        val scopes = arrayOf(intArrayOf(x, y), intArrayOf(x, y, z), intArrayOf(), intArrayOf(), intArrayOf())
        val solver = solverFor(scopes, mines = intArrayOf(x), revealed = intArrayOf(0, 1))

        assertFalse(solver.run())

        assertTrue(solver.isRevealed(z))
        assertTrue(solver.isUnknown(x))
        assertTrue(solver.isUnknown(y))
        assertEquals(0, solver.markedCount)
    }

    @Test
    fun overlappingPairResolvesBothOuterParts() {
        // A sees {a1, s1, s2} and shows 1; B sees {s1, s2, b1} and shows 2. B needs a mine outside the
        // shared part, so the shared part holds exactly one mine: a1 is safe and b1 is a mine.
        val a1 = 2
        val s1 = 3
        val s2 = 4
        val b1 = 5
        val scopes = arrayOf(
            intArrayOf(a1, s1, s2),
            intArrayOf(s1, s2, b1),
            intArrayOf(), intArrayOf(), intArrayOf(), intArrayOf(),
        )
        val solver = solverFor(scopes, mines = intArrayOf(s1, b1), revealed = intArrayOf(0, 1))

        assertFalse(solver.run())

        assertTrue(solver.isRevealed(a1))
        assertTrue(solver.isMarked(b1))
        assertTrue(solver.isUnknown(s1))
        assertTrue(solver.isUnknown(s2))
    }

    @Test
    fun counterClearsCellsNobodySeesWhenNoMinesAreLeft() {
        val scopes = arrayOf(intArrayOf(1), intArrayOf(), intArrayOf(), intArrayOf())
        val solver = solverFor(scopes, mines = intArrayOf(1), revealed = intArrayOf(0))

        assertTrue(solver.run())

        assertTrue(solver.isMarked(1))
        assertTrue(solver.isRevealed(2))
        assertTrue(solver.isRevealed(3))
    }

    @Test
    fun counterMarksCellsNobodySeesWhenAllAreMines() {
        val scopes = arrayOf(intArrayOf(), intArrayOf(), intArrayOf())
        val solver = solverFor(scopes, mines = intArrayOf(1, 2), revealed = intArrayOf(0))

        assertTrue(solver.run())

        assertTrue(solver.isMarked(1))
        assertTrue(solver.isMarked(2))
    }

    @Test
    fun fiftyFiftyStaysUnresolved() {
        // Two numbers see the same two cells and both show 1; one mine in total.
        val scopes = arrayOf(intArrayOf(2, 3), intArrayOf(2, 3), intArrayOf(), intArrayOf())
        val solver = solverFor(scopes, mines = intArrayOf(2), revealed = intArrayOf(0, 1))

        assertFalse(solver.run())

        assertTrue(solver.isUnknown(2))
        assertTrue(solver.isUnknown(3))
        assertEquals(2, solver.revealedCount)
        assertEquals(0, solver.markedCount)
    }

    @Test
    fun sphereGuessBetweenWalledInCellsStaysUnresolved() {
        // Cells a and b are each surrounded by mines, so no number sees them, and one mine is left for
        // the two of them. Everything else is revealed. Only a guess decides which one holds it.
        val neighbors = SphereGeometry.build(2).neighbors
        val a = 0
        val b = farthestFrom(neighbors, a)
        val mines = BooleanArray(neighbors.size)
        mines[a] = true
        for (nb in neighbors[a]) mines[nb] = true
        for (nb in neighbors[b]) mines[nb] = true
        val solver = LogicSolver(neighbors)
        solver.load(mines)
        for (cell in neighbors.indices) {
            if (cell != b && !mines[cell] && solver.isUnknown(cell)) solver.reveal(cell)
        }

        assertFalse(solver.run())

        assertTrue(solver.isUnknown(a))
        assertTrue(solver.isUnknown(b))
        assertEquals(neighbors.size - solver.mineCount - 1, solver.revealedCount)
    }

    @Test
    fun runCanBeContinuedAfterMoveMine() {
        // Same 50/50 as above; moving the mine to the cell nobody sees makes both cells safe.
        val scopes = arrayOf(intArrayOf(2, 3), intArrayOf(2, 3), intArrayOf(), intArrayOf(), intArrayOf())
        val solver = solverFor(scopes, mines = intArrayOf(2), revealed = intArrayOf(0, 1))
        assertFalse(solver.run())

        solver.moveMine(2, 4)

        assertTrue(solver.run())
        assertTrue(solver.isRevealed(2))
        assertTrue(solver.isRevealed(3))
        assertTrue(solver.isMarked(4))
    }

    // ---------------------------------------------------------------- spheres

    @Test
    fun revealSpreadsLikeTheBoard() {
        for (size in listOf(BoardSize.SMALL, BoardSize.LARGE)) {
            val geometry = SphereGeometry.build(size.frequency)
            for (seed in 0 until 20) {
                val scopes = BoardLayout.generate(geometry, size, Random(seed)).scopes
                val board = MinesweeperBoard(scopes, size.mines, Random(seed))
                val start = Random(seed + 500).nextInt(scopes.size)
                board.reveal(start)

                val solver = LogicSolver(scopes)
                solver.load(BooleanArray(scopes.size) { board.isMine(it) })
                solver.reveal(start)
                for (cell in scopes.indices) {
                    assertEquals("$size seed $seed cell $cell", board.isRevealed(cell), solver.isRevealed(cell))
                }
            }
        }
    }

    @Test
    fun deductionsOnRandomBoardsMatchTheLayout() {
        for (size in BoardSize.values()) {
            val geometry = SphereGeometry.build(size.frequency)
            for (seed in 0 until 40) {
                val scopes = BoardLayout.generate(geometry, size, Random(seed)).scopes
                val board = MinesweeperBoard(scopes, size.mines, Random(seed))
                val start = Random(seed + 1000).nextInt(scopes.size)
                board.reveal(start)
                val mines = BooleanArray(scopes.size) { board.isMine(it) }

                val solver = LogicSolver(scopes)
                solver.load(mines)
                solver.reveal(start)
                val solved = solver.run()

                var revealed = 0
                for (cell in scopes.indices) {
                    if (solver.isMarked(cell)) assertTrue("$size seed $seed marked $cell", mines[cell])
                    if (solver.isRevealed(cell)) {
                        assertFalse("$size seed $seed revealed $cell", mines[cell])
                        revealed++
                    }
                }
                assertEquals(solved, revealed == scopes.size - size.mines)
            }
        }
    }

    /**
     * Every step must follow from what the player saw before it: among all layouts with the same mine
     * count that agree with every number revealed earlier, the resolved cell must have the same state.
     * Checked by enumerating layouts on a small grid, with some cells counting two steps away.
     */
    @Test
    fun everyStepIsForcedByEarlierNumbers() {
        val width = 5
        val height = 4
        val cellCount = width * height
        val mineCount = 4
        val grid = kingGrid(width, height)
        val layouts = allLayouts(cellCount, mineCount)
        val random = Random(2024)
        var checkedSteps = 0
        repeat(150) { round ->
            val scopes = Array(cellCount) { grid[it] }
            repeat(2) {
                val cell = random.nextInt(cellCount)
                scopes[cell] = twoStep(grid, cell)
            }
            var start: Int
            var free: List<Int>
            do {
                start = random.nextInt(cellCount)
                free = (0 until cellCount).filter { it != start && it !in scopes[start] }.shuffled(random)
            } while (free.size < mineCount)
            val mines = BooleanArray(cellCount)
            for (i in 0 until mineCount) mines[free[i]] = true

            val solver = LogicSolver(scopes)
            solver.load(mines)
            solver.reveal(start)
            solver.run()

            val order = IntArray(cellCount) { solver.resolveOrder(it) }
            val numbers = IntArray(cellCount) { solver.number(it) }
            // For each candidate layout: order of the first revealed cell it contradicts.
            val firstConflict = IntArray(layouts.size) { index ->
                val mask = layouts[index]
                var first = Int.MAX_VALUE
                for (cell in 0 until cellCount) {
                    if (!solver.isRevealed(cell) || order[cell] >= first) continue
                    val conflict = mask and (1 shl cell) != 0 ||
                        scopes[cell].count { mask and (1 shl it) != 0 } != numbers[cell]
                    if (conflict) first = order[cell]
                }
                first
            }
            for (cell in 0 until cellCount) {
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
    }

    // ---------------------------------------------------------------- helpers

    private fun solverFor(scopes: Array<IntArray>, mines: IntArray, revealed: IntArray): LogicSolver {
        val solver = LogicSolver(scopes)
        solver.load(BooleanArray(scopes.size) { it in mines })
        for (cell in revealed) solver.reveal(cell)
        return solver
    }

    /** A cell at the largest graph distance from [cell]. */
    private fun farthestFrom(neighbors: Array<IntArray>, cell: Int): Int {
        val distance = IntArray(neighbors.size) { -1 }
        val queue = ArrayDeque<Int>()
        distance[cell] = 0
        queue.addLast(cell)
        var last = cell
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            last = current
            for (nb in neighbors[current]) {
                if (distance[nb] < 0) {
                    distance[nb] = distance[current] + 1
                    queue.addLast(nb)
                }
            }
        }
        return last
    }

    /** 8-neighbour grid adjacency, row by row. */
    private fun kingGrid(width: Int, height: Int): Array<IntArray> = Array(width * height) { cell ->
        val x = cell % width
        val y = cell / width
        val list = ArrayList<Int>()
        for (dy in -1..1) {
            for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if ((dx != 0 || dy != 0) && nx in 0 until width && ny in 0 until height) list.add(ny * width + nx)
            }
        }
        list.toIntArray()
    }

    private fun twoStep(links: Array<IntArray>, cell: Int): IntArray {
        val result = sortedSetOf<Int>()
        for (a in links[cell]) {
            result.add(a)
            for (b in links[a]) if (b != cell) result.add(b)
        }
        return result.toIntArray()
    }

    /** Every [mineCount]-subset of [cellCount] cells as a bit mask. */
    private fun allLayouts(cellCount: Int, mineCount: Int): IntArray {
        val result = ArrayList<Int>()
        fun build(from: Int, left: Int, mask: Int) {
            if (left == 0) {
                result.add(mask)
                return
            }
            for (cell in from..cellCount - left) build(cell + 1, left - 1, mask or (1 shl cell))
        }
        build(0, mineCount, 0)
        return result.toIntArray()
    }
}
