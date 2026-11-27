package com.example.spheresweeper.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NoGuessGeneratorTest {

    private val geometries: Map<BoardSize, SphereGeometry> =
        BoardSize.values().associateWith { SphereGeometry.build(it.frequency) }

    @Test
    fun layoutsAreValidAndSolvableForEverySize() {
        for (size in BoardSize.values()) {
            for (seed in 0 until 15) {
                val scopes = BoardLayout.generate(geometries.getValue(size), size, Random(seed)).scopes
                val layout = NoGuessGenerator.generate(scopes, size.mines, Random(seed))
                assertValidAndSolvable("$size seed $seed", scopes, size.mines, layout)
            }
        }
    }

    @Test
    fun layoutsOnPlainSpheresAreSolvable() {
        for (size in BoardSize.values()) {
            val neighbors = geometries.getValue(size).neighbors
            for (seed in 0 until 5) {
                val layout = NoGuessGenerator.generate(neighbors, size.mines, Random(seed + 100))
                assertValidAndSolvable("$size seed $seed", neighbors, size.mines, layout)
            }
        }
    }

    @Test
    fun sameSeedGivesSameLayout() {
        for (size in BoardSize.values()) {
            val scopes = BoardLayout.generate(geometries.getValue(size), size, Random(7)).scopes
            for (seed in 0 until 5) {
                val first = NoGuessGenerator.generate(scopes, size.mines, Random(seed))
                val second = NoGuessGenerator.generate(scopes, size.mines, Random(seed))
                assertEquals("$size seed $seed", first.start, second.start)
                assertArrayEquals("$size seed $seed", first.mines, second.mines)
            }
        }
    }

    @Test
    fun differentSeedsGiveDifferentLayouts() {
        val size = BoardSize.LARGE
        val scopes = BoardLayout.generate(geometries.getValue(size), size, Random(3)).scopes
        val a = NoGuessGenerator.generate(scopes, size.mines, Random(1))
        val b = NoGuessGenerator.generate(scopes, size.mines, Random(2))
        assertFalse(a.start == b.start && a.mines.contentEquals(b.mines))
    }

    @Test
    fun boardStartedFromLayoutIsWonByRevealingCellsInSolverOrder() {
        for (size in BoardSize.values()) {
            for (seed in 0 until 8) {
                val label = "$size seed $seed"
                val scopes = BoardLayout.generate(geometries.getValue(size), size, Random(seed + 200)).scopes
                val layout = NoGuessGenerator.generate(scopes, size.mines, Random(seed + 200))
                val board = MinesweeperBoard(scopes, size.mines, lives = size.lives)
                assertEquals("$label start", MoveResult.REVEALED, board.startWith(layout.mines, layout.start))
                assertEquals("$label status after start", GameStatus.PLAYING, board.status)
                assertEquals("$label mine count", size.mines, board.mineCount)

                val mines = BooleanArray(scopes.size)
                for (cell in layout.mines) mines[cell] = true
                val solver = LogicSolver(scopes)
                solver.load(mines)
                solver.reveal(layout.start)
                assertTrue("$label not solvable", solver.run())

                // Safe cells in the order the solver resolved them; the start area comes first.
                val safeCells = scopes.indices.filter { !mines[it] }.sortedBy { solver.resolveOrder(it) }
                var last = MoveResult.REVEALED
                for (cell in safeCells) {
                    if (board.isRevealed(cell)) continue
                    assertTrue("$label safe $cell not resolved", solver.resolveOrder(cell) >= 0)
                    last = board.reveal(cell)
                    assertTrue("$label reveal $cell gave $last", last == MoveResult.REVEALED || last == MoveResult.WON)
                    if (last == MoveResult.WON) break
                }
                assertEquals("$label last move", MoveResult.WON, last)
                assertEquals("$label status", GameStatus.WON, board.status)
                assertEquals("$label lives", size.lives, board.livesLeft)
                for (cell in scopes.indices) {
                    assertEquals("$label cell $cell revealed", !mines[cell], board.isRevealed(cell))
                }
            }
        }
    }

    @Test
    fun zeroMinesGivesEmptyLayout() {
        val neighbors = geometries.getValue(BoardSize.TINY).neighbors
        val layout = NoGuessGenerator.generate(neighbors, 0, Random(1))
        assertEquals(0, layout.mines.size)
        assertValidAndSolvable("no mines", neighbors, 0, layout)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooManyMinesAreRejected() {
        val neighbors = geometries.getValue(BoardSize.TINY).neighbors
        NoGuessGenerator.generate(neighbors, neighbors.size - 5, Random(1))
    }

    /**
     * Islands, portals and locks together: the layout respects the rules for each, and a board started
     * from it is won by revealing safe cells in the order the solver resolved them, with the locks
     * opening on the board exactly as the solver assumed.
     */
    @Test
    fun boardWithIslandsPortalsAndLocksIsWonInSolverOrder() {
        var lockedAfterStart = 0
        var redraws = 0
        var boards = 0
        for (size in listOf(BoardSize.LARGE, BoardSize.HUGE, BoardSize.GIANT)) {
            val geometry = geometries.getValue(size)
            for (seed in 0 until 12) {
                val label = "$size seed $seed"
                val random = StableRandom(seed.toLong() + 300)
                val islands = 2 + seed % 3
                val layout = BoardLayout.generate(
                    geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, random,
                    size.lineCells, islands, 1 + seed % 3,
                )
                val mineCount = (layout.presentCount * 19 + 50) / 100
                val lockCount = 6
                val generated = NoGuessGenerator.generate(layout, mineCount, random, lockCount = lockCount)
                val locks = generated.lockThresholds
                redraws += generated.redraws
                boards++
                val mines = BooleanArray(geometry.cellCount)
                for (cell in generated.mines) mines[cell] = true

                assertEquals("$label mine count", mineCount, generated.mines.size)
                assertTrue("$label start on land", layout.isPresent(generated.start))
                assertEquals("$label start area lock", 0, locks[generated.start])
                for (nb in layout.scopes[generated.start]) assertEquals("$label start area lock $nb", 0, locks[nb])
                val safeCells = layout.presentCount - mineCount
                assertEquals("$label lock count", lockCount, locks.count { it > 0 })
                for (cell in 0 until geometry.cellCount) {
                    if (!layout.isPresent(cell)) assertFalse("$label mine in ocean $cell", mines[cell])
                    if (layout.isPortal(cell)) assertFalse("$label mine on portal $cell", mines[cell])
                    if (locks[cell] == 0) continue
                    assertTrue("$label lock $cell on land", layout.isPresent(cell))
                    assertFalse("$label lock $cell on portal", layout.isPortal(cell))
                    assertFalse("$label lock $cell on mine", mines[cell])
                    assertTrue(
                        "$label lock $cell threshold ${locks[cell]} of $safeCells",
                        locks[cell] * 4 >= safeCells && locks[cell] * 4 <= safeCells * 3,
                    )
                }

                val solver = LogicSolver(layout.scopes, layout.presentMask())
                solver.load(mines, locks)
                solver.reveal(generated.start)
                lockedAfterStart += (0 until geometry.cellCount).count { locks[it] > 0 && !solver.isLockOpen(it) }
                assertTrue("$label not solvable", solver.run())

                val board = MinesweeperBoard(
                    layout.scopes, mineCount, lives = 1, present = layout.presentMask(), lockThresholds = locks,
                )
                assertEquals("$label start", MoveResult.REVEALED, board.startWith(generated.mines, generated.start))
                val safeInOrder = (0 until geometry.cellCount)
                    .filter { layout.isPresent(it) && !mines[it] }
                    .sortedBy { solver.resolveOrder(it) }
                var last = MoveResult.REVEALED
                for (cell in safeInOrder) {
                    if (board.isRevealed(cell)) continue
                    assertTrue("$label safe $cell not resolved", solver.resolveOrder(cell) >= 0)
                    last = board.reveal(cell)
                    assertTrue("$label reveal $cell gave $last", last == MoveResult.REVEALED || last == MoveResult.WON)
                    if (last == MoveResult.WON) break
                }
                assertEquals("$label last move", MoveResult.WON, last)
                assertEquals("$label lives", 1, board.livesLeft)
                for (cell in 0 until geometry.cellCount) {
                    assertEquals("$label cell $cell revealed", layout.isPresent(cell) && !mines[cell], board.isRevealed(cell))
                    if (locks[cell] > 0) assertTrue("$label lock $cell still closed", board.isLockOpen(cell))
                }
            }
        }
        assertTrue("no lock was closed after the start reveal", lockedAfterStart > 0)
        // Mines that cut land off behind a coastal neck are moved instead of drawing the layout again.
        assertTrue("$redraws redraws for $boards boards", redraws <= boards / 2)
    }

    @Test
    fun sameSeedGivesSameLayoutWithIslandsPortalsAndLocks() {
        val size = BoardSize.HUGE
        val geometry = geometries.getValue(size)
        fun build(): Pair<BoardLayout, NoGuessLayout> {
            val random = StableRandom(11)
            val layout = BoardLayout.generate(
                geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, random, size.lineCells, 3, 2,
            )
            return layout to NoGuessGenerator.generate(layout, layout.presentCount / 6, random, lockCount = 4)
        }
        val (layoutA, a) = build()
        val (layoutB, b) = build()
        assertEquals(a.start, b.start)
        assertArrayEquals(a.mines, b.mines)
        assertArrayEquals(a.lockThresholds, b.lockThresholds)
        for (cell in 0 until geometry.cellCount) assertEquals(layoutA.scopes[cell].toList(), layoutB.scopes[cell].toList())
    }

    @Test
    fun noLocksRequestedGivesNoLocks() {
        val size = BoardSize.MEDIUM
        val scopes = BoardLayout.generate(geometries.getValue(size), size, Random(4)).scopes
        val layout = NoGuessGenerator.generate(scopes, size.mines, Random(4))
        assertEquals(scopes.size, layout.lockThresholds.size)
        assertTrue(layout.lockThresholds.all { it == 0 })
    }

    private fun assertValidAndSolvable(label: String, scopes: Array<IntArray>, mineCount: Int, layout: NoGuessLayout) {
        assertEquals("$label mine count", mineCount, layout.mines.size)
        for (i in 1 until layout.mines.size) {
            assertTrue("$label mines sorted and distinct", layout.mines[i - 1] < layout.mines[i])
        }
        val mines = BooleanArray(scopes.size)
        for (cell in layout.mines) mines[cell] = true
        assertFalse("$label start mined", mines[layout.start])
        for (nb in scopes[layout.start]) assertFalse("$label start area cell $nb mined", mines[nb])

        val solver = LogicSolver(scopes)
        solver.load(mines)
        solver.reveal(layout.start)
        assertTrue("$label not solvable", solver.run())
        for (cell in scopes.indices) {
            if (mines[cell]) assertFalse("$label mine $cell revealed", solver.isRevealed(cell))
            else assertTrue("$label safe $cell left covered", solver.isRevealed(cell))
        }
    }
}
