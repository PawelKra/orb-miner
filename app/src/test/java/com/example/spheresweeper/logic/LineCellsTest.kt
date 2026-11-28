package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Line cells whose row holds no mine: free games without rooms never get one ([NoGuessGenerator.generate]
 * with `forbidEmptyLines`), free games with rooms keep them at passages, and [LineCells.showsEmptyRow]
 * marks them for the renderer.
 */
class LineCellsTest {

    private val geometries = HashMap<Int, SphereGeometry>()

    private fun geometry(size: BoardSize): SphereGeometry =
        geometries.getOrPut(size.frequency) { SphereGeometry.build(size.frequency) }

    /** Free game specs of every size: with the size's rooms, with walls but no rooms, and without walls. */
    private fun specs(seeds: Int): List<FreeGameSpec> = BoardSize.values().flatMap { size ->
        (0 until seeds).flatMap { seed ->
            listOf(
                FreeGameSpec(size, seed * 31L + 1, size.rooms, walls = true),
                FreeGameSpec(size, seed * 31L + 2, 0, walls = true),
                FreeGameSpec(size, seed * 31L + 3, 0, walls = false),
            )
        }
    }

    private fun mineMask(cellCount: Int, mines: IntArray): BooleanArray =
        BooleanArray(cellCount).also { mask -> for (m in mines) mask[m] = true }

    /** Line cells of [layout] whose row holds none of [mines]. */
    private fun emptyLines(layout: BoardLayout, mines: BooleanArray): List<Int> =
        layout.scopes.indices.filter { layout.isLine(it) && LineCells.rowMines(layout, it) { c -> mines[c] } == 0 }

    @Test
    fun freeGamesWithoutRoomsHaveNoLineWithAnEmptyRowAndStaySolvable() {
        var built = 0
        var lines = 0
        val specs = specs(20).filter { it.rooms == 0 }
        for (spec in specs) {
            val game = try {
                FreeGame.build(spec, geometry(spec.size))
            } catch (e: IllegalStateException) {
                continue
            } catch (e: IllegalArgumentException) {
                continue
            }
            built++
            val layout = game.layout
            assertFalse("$spec has rooms", layout.hasRooms)
            val mines = mineMask(layout.scopes.size, game.mines)
            assertEquals("$spec empty lines", emptyList<Int>(), emptyLines(layout, mines))
            assertFalse("$spec start on a line cell", layout.isLine(game.start))
            for (cell in layout.scopes.indices) {
                if (!layout.isLine(cell)) continue
                assertEquals(
                    "$spec line $cell number", LineCells.rowMines(layout, cell) { mines[it] },
                    game.board.adjacentMines(cell),
                )
            }
            // The same draws again, for the locks the solver needs.
            val random = StableRandom(spec.seed)
            val drawn = drawnLayout(spec, random)
            val generated = NoGuessGenerator.generate(
                drawn, game.mines.size, random, spec.size.locks, forbidEmptyLines = true,
            )
            assertTrue("$spec redrawn mines", generated.mines.contentEquals(game.mines))
            assertEquals("$spec all lines kept", drawn.lineCount, layout.lineCount)
            lines += layout.lineCount
            assertTrue(
                "$spec not solvable",
                LogicSolver.solves(
                    layout.scopes, mines, game.start, layout.presentMask(), generated.lockThresholds,
                    generated.lockRegions,
                ),
            )
        }
        assertTrue("only $built of ${specs.size} specs built", built * 10 >= specs.size * 9)
        assertTrue("no line cells on boards without rooms", lines > 0)
    }

    @Test
    fun freeGamesWithRoomsAreBuiltAsWithoutTheFlag() {
        var built = 0
        var emptyPassageLines = 0
        val specs = specs(20).filter { it.rooms > 0 }
        for (spec in specs) {
            val game = try {
                FreeGame.build(spec, geometry(spec.size))
            } catch (e: IllegalStateException) {
                continue
            } catch (e: IllegalArgumentException) {
                continue
            }
            built++
            val random = StableRandom(spec.seed)
            val drawn = drawnLayout(spec, random)
            val generated = NoGuessGenerator.generate(drawn, spec.size.minesFor(drawn), random, spec.size.locks)
            val layout = game.layout
            assertEquals("$spec start", generated.start, game.start)
            assertTrue("$spec mines", generated.mines.contentEquals(game.mines))
            assertEquals("$spec line count", drawn.lineCount, layout.lineCount)
            assertEquals("$spec regions", drawn.regionCount, layout.regionCount)
            assertEquals("$spec present", drawn.presentMask().toList(), layout.presentMask().toList())
            for (cell in drawn.scopes.indices) {
                assertEquals("$spec line $cell", drawn.isLine(cell), layout.isLine(cell))
                assertTrue("$spec scope of $cell", drawn.scopes[cell].contentEquals(layout.scopes[cell]))
                assertEquals(
                    "$spec lock of $cell", generated.lockThresholds[cell], game.board.lockThreshold(cell),
                )
            }
            if (layout.hasRooms) emptyPassageLines += emptyLines(layout, mineMask(layout.scopes.size, game.mines)).size
        }
        assertTrue("only $built of ${specs.size} specs built", built * 10 >= specs.size * 9)
        assertTrue("no passage line with an empty row", emptyPassageLines > 0)
    }

    /** The layout [FreeGame.build] draws for [spec] from [random], before the mines. */
    private fun drawnLayout(spec: FreeGameSpec, random: Random): BoardLayout {
        val size = spec.size
        return BoardLayout.generate(
            geometry(size), if (spec.walls) size.walls else 0, size.wallMinLength, size.wallMaxLength, size.farCells,
            random, size.lineCells, size.islands, size.portals, spec.rooms, BoardSize.OCEAN_SHARE,
        )
    }

    @Test
    fun manyLinesOnBoardsWithOceanNeverCountAnEmptyRow() {
        val size = BoardSize.GIANT
        var lines = 0
        var boards = 0
        for (seed in 0 until 12) {
            val random = StableRandom(seed + 900L)
            val layout = try {
                BoardLayout.generate(
                    geometry(size), size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, random,
                    lineCount = 30, islandCount = seed % 3, portalCount = 1 + seed % 2,
                )
            } catch (e: IllegalStateException) {
                continue
            }
            boards++
            lines += layout.lineCount
            val mineCount = size.minesFor(layout)
            val generated = NoGuessGenerator.generate(layout, mineCount, random, size.locks, forbidEmptyLines = true)
            val mines = mineMask(layout.scopes.size, generated.mines)
            assertEquals("seed $seed mine count", mineCount, generated.mines.size)
            assertEquals("seed $seed empty lines", emptyList<Int>(), emptyLines(layout, mines))
            assertTrue(
                "seed $seed not solvable",
                LogicSolver.solves(
                    layout.scopes, mines, generated.start, layout.presentMask(), generated.lockThresholds,
                    generated.lockRegions,
                ),
            )
        }
        assertTrue("only $boards boards", boards >= 8)
        assertTrue("only $lines line cells", lines >= boards * 20)
    }

    @Test
    fun theFlagIsRejectedOnBoardsWithRooms() {
        val size = BoardSize.HUGE
        val random = StableRandom(77L)
        val layout = BoardLayout.generate(
            geometry(size), size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, random,
            size.lineCells, size.islands, size.portals, size.rooms,
        )
        assertTrue(layout.hasRooms)
        try {
            NoGuessGenerator.generate(layout, size.minesFor(layout), random, size.locks, forbidEmptyLines = true)
            throw AssertionError("the flag was accepted on a board with rooms")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("rooms"))
        }
    }

    @Test
    fun withoutTheFlagLinesWithAnEmptyRowStillOccur() {
        // Shows that the check above can fail: the same boards drawn the way campaign levels are.
        var empty = 0
        for (spec in specs(10)) {
            val size = spec.size
            val random = StableRandom(spec.seed)
            val layout = try {
                BoardLayout.generate(
                    geometry(size), if (spec.walls) size.walls else 0, size.wallMinLength, size.wallMaxLength,
                    size.farCells, random, size.lineCells, size.islands, size.portals, spec.rooms,
                )
            } catch (e: IllegalStateException) {
                continue
            } catch (e: IllegalArgumentException) {
                continue
            }
            val generated = try {
                NoGuessGenerator.generate(layout, size.minesFor(layout), random, size.locks)
            } catch (e: IllegalStateException) {
                continue
            }
            empty += emptyLines(layout, mineMask(layout.scopes.size, generated.mines)).size
        }
        assertTrue("no empty line without the flag", empty > 0)
    }

    @Test
    fun theFlagOffDrawsExactlyAsTheDefault() {
        for (size in BoardSize.values()) {
            for (seed in 0 until 4) {
                fun draw(explicit: Boolean): NoGuessLayout {
                    val random = StableRandom(seed * 13L + size.ordinal)
                    val layout = BoardLayout.generate(
                        geometry(size), size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, random,
                        size.lineCells, size.islands, size.portals, size.rooms,
                    )
                    val mines = size.minesFor(layout)
                    return if (explicit) {
                        NoGuessGenerator.generate(layout, mines, random, size.locks, forbidEmptyLines = false)
                    } else {
                        NoGuessGenerator.generate(layout, mines, random, size.locks)
                    }
                }
                val a = try { draw(false) } catch (e: RuntimeException) { continue }
                val b = draw(true)
                assertEquals("$size seed $seed start", a.start, b.start)
                assertTrue("$size seed $seed mines", a.mines.contentEquals(b.mines))
                assertTrue("$size seed $seed locks", a.lockThresholds.contentEquals(b.lockThresholds))
                assertEquals("$size seed $seed repairs", a.repairs, b.repairs)
            }
        }
    }

    @Test
    fun showsEmptyRowOnlyForARevealedOpenLineThatCountsNoMine() {
        val size = BoardSize.LARGE
        var checkedEmpty = 0
        var checkedLocked = 0
        var checkedCounting = 0
        for (seed in 0 until 40) {
            val random = StableRandom(seed + 500L)
            val layout = BoardLayout.generate(
                geometry(size), size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, random,
                size.lineCells,
            )
            val mineCount = size.minesFor(layout)
            val generated = NoGuessGenerator.generate(layout, mineCount, random)
            val mines = mineMask(layout.scopes.size, generated.mines)
            val safeCount = layout.presentCount - mineCount
            for (line in layout.scopes.indices) {
                if (!layout.isLine(line) || mines[line] || line == generated.start) continue
                val rowMines = LineCells.rowMines(layout, line) { mines[it] }

                val board = MinesweeperBoard(layout.scopes, mineCount, present = layout.presentMask())
                board.startWith(generated.mines, generated.start)
                if (!board.isRevealed(line)) {
                    assertFalse("seed $seed covered line $line", LineCells.showsEmptyRow(layout, board, line))
                    board.reveal(line)
                }
                assertEquals("seed $seed line $line", rowMines == 0, LineCells.showsEmptyRow(layout, board, line))
                if (rowMines == 0) checkedEmpty++ else checkedCounting++

                if (rowMines != 0) continue
                // A closed lock hides the number, so the line keeps its arrow and row.
                val locks = IntArray(layout.scopes.size).also { it[line] = safeCount }
                val locked = MinesweeperBoard(
                    layout.scopes, mineCount, present = layout.presentMask(), lockThresholds = locks,
                )
                locked.startWith(generated.mines, generated.start)
                locked.reveal(line)
                if (!locked.isRevealed(line) || locked.isLockOpen(line)) continue
                assertFalse("seed $seed locked line $line", LineCells.showsEmptyRow(layout, locked, line))
                checkedLocked++
            }
            val plain = layout.scopes.indices.first { layout.isPresent(it) && !layout.isLine(it) }
            val board = MinesweeperBoard(layout.scopes, mineCount, present = layout.presentMask())
            board.startWith(generated.mines, generated.start)
            assertFalse("seed $seed plain cell", LineCells.showsEmptyRow(layout, board, plain))
        }
        assertTrue("no empty line checked", checkedEmpty > 0)
        assertTrue("no counting line checked", checkedCounting > 0)
        assertTrue("no locked line checked", checkedLocked > 0)
    }
}
