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
