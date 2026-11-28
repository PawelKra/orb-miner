package com.example.spheresweeper.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Free games: a [FreeGameSpec] rebuilds the same board, and the walls switch takes walls and rooms away. */
class FreeGameTest {

    private val geometries = HashMap<Int, SphereGeometry>()

    private fun geometry(size: BoardSize): SphereGeometry =
        geometries.getOrPut(size.frequency) { SphereGeometry.build(size.frequency) }

    private fun assertSameGame(label: String, a: PreparedFreeGame, b: PreparedFreeGame) {
        assertEquals("$label spec", a.spec, b.spec)
        assertEquals("$label start", a.start, b.start)
        assertArrayEquals("$label mines", a.mines, b.mines)
        assertEquals("$label walls", a.layout.walls.map { it.toList() }, b.layout.walls.map { it.toList() })
        assertEquals("$label rooms", a.layout.regionCount, b.layout.regionCount)
        assertEquals("$label present", a.layout.presentMask().toList(), b.layout.presentMask().toList())
        assertEquals("$label scope count", a.layout.scopes.size, b.layout.scopes.size)
        for (cell in a.layout.scopes.indices) {
            assertEquals("$label far $cell", a.layout.isFar(cell), b.layout.isFar(cell))
            assertArrayEquals("$label scope of $cell", a.layout.scopes[cell], b.layout.scopes[cell])
            assertArrayEquals("$label links of $cell", a.layout.links[cell], b.layout.links[cell])
        }
        assertEquals("$label lock count", a.board.lockCount, b.board.lockCount)
        assertEquals("$label mine count", a.board.mineCount, b.board.mineCount)
    }

    @Test
    fun aDrawnGameIsRebuiltFromItsSpec() {
        for (size in BoardSize.values()) {
            for (walls in listOf(true, false)) {
                val label = "$size walls=$walls"
                val drawn = FreeGame.draw(geometry(size), size, walls, Random(size.ordinal * 2L + if (walls) 1 else 0))
                assertEquals("$label size", size, drawn.spec.size)
                assertEquals("$label walls flag", walls, drawn.spec.walls)
                val rebuilt = FreeGame.build(drawn.spec, geometry(size))
                assertSameGame(label, drawn, rebuilt)
            }
        }
    }

    @Test
    fun theSameSpecGivesTheSameBoardEveryTime() {
        val size = BoardSize.LARGE
        val spec = FreeGameSpec(size, seed = 424242L, rooms = size.rooms, walls = true)
        val first = FreeGame.build(spec, geometry(size))
        val second = FreeGame.build(spec, geometry(size))
        assertSameGame("$spec", first, second)
    }

    @Test
    fun anotherSeedGivesAnotherBoard() {
        val size = BoardSize.MEDIUM
        val a = FreeGame.build(FreeGameSpec(size, 1L, size.rooms, true), geometry(size))
        val b = FreeGame.build(FreeGameSpec(size, 2L, size.rooms, true), geometry(size))
        assertFalse(a.mines.contentEquals(b.mines) && a.start == b.start)
    }

    @Test
    fun withoutWallsTheBoardHasNoWallsAndNoRooms() {
        for (size in BoardSize.values()) {
            for (seed in 0L until 5L) {
                val drawn = FreeGame.draw(geometry(size), size, walls = false, seeds = Random(seed))
                assertEquals("$size rooms", 0, drawn.spec.rooms)
                assertTrue("$size walls ${drawn.layout.walls.size}", drawn.layout.walls.isEmpty())
                assertFalse("$size has rooms", drawn.layout.hasRooms)
                assertTrue("$size line cells", drawn.layout.lineCount <= size.lineCells)
                assertTrue("$size locks", drawn.board.lockCount <= size.locks)
            }
        }
    }

    @Test
    fun withWallsTheFirstSuccessfulDrawKeepsTheSizeRooms() {
        val size = BoardSize.MEDIUM
        val drawn = FreeGame.draw(geometry(size), size, walls = true, seeds = Random(3))
        // A failed draw falls back to 0 rooms only after ATTEMPTS failures, which MEDIUM never reaches.
        assertEquals(size.rooms, drawn.spec.rooms)
        assertTrue(drawn.layout.hasRooms)
    }

    @Test
    fun drawFallsBackToNoRoomsAfterFailedAttemptsWithRooms() {
        val size = BoardSize.MEDIUM
        assertTrue(size.rooms > 0)
        val tried = ArrayList<FreeGameSpec>()
        val drawn = FreeGame.draw(geometry(size), size, walls = true, seeds = Random(5)) { spec, g ->
            tried.add(spec)
            check(spec.rooms == 0) { "every attempt with rooms fails here" }
            FreeGame.build(spec, g)
        }
        assertEquals("attempts with the size's rooms first", List(FreeGame.ATTEMPTS) { size.rooms }, tried.take(FreeGame.ATTEMPTS).map { it.rooms })
        assertTrue("then attempts without rooms", tried.size > FreeGame.ATTEMPTS && tried.drop(FreeGame.ATTEMPTS).all { it.rooms == 0 })
        assertEquals("spec records the fallback", 0, drawn.spec.rooms)
        assertTrue("walls stay on", drawn.spec.walls)
        assertEquals(tried.last(), drawn.spec)
        assertFalse(drawn.layout.hasRooms)
        assertSameGame("fallback", drawn, FreeGame.build(drawn.spec, geometry(size)))
    }

    @Test
    fun drawWithTheRealBuilderMatchesThePublicDraw() {
        val size = BoardSize.SMALL
        val a = FreeGame.draw(geometry(size), size, walls = true, seeds = Random(11))
        val b = FreeGame.draw(geometry(size), size, walls = true, seeds = Random(11), builder = FreeGame::build)
        assertSameGame("seam", a, b)
    }

    @Test
    fun drawPassesTheLastErrorOnWhenEveryAttemptFails() {
        val size = BoardSize.SMALL
        var calls = 0
        try {
            FreeGame.draw(geometry(size), size, walls = true, seeds = Random(1)) { _, _ ->
                calls++
                throw IllegalStateException("attempt $calls")
            }
            throw AssertionError("draw did not fail")
        } catch (e: IllegalStateException) {
            assertEquals("attempt ${2 * FreeGame.ATTEMPTS}", e.message)
        }
        assertEquals(2 * FreeGame.ATTEMPTS, calls)
    }

    /** [real] with a two-cell board in place of its board; the start reveals both cells, so it is WON. */
    private fun alreadyWon(real: PreparedFreeGame): PreparedFreeGame {
        val board = MinesweeperBoard(arrayOf(intArrayOf(1), intArrayOf(0)), 0)
        board.startWith(IntArray(0), 0)
        assertEquals(GameStatus.WON, board.status)
        return PreparedFreeGame(real.spec, real.layout, 0, IntArray(0), board)
    }

    @Test
    fun drawRetriesWhenTheStartAlreadyWinsTheBoard() {
        val size = BoardSize.TINY
        val tried = ArrayList<FreeGameSpec>()
        val wonAttempts = 3
        val drawn = FreeGame.draw(geometry(size), size, walls = true, seeds = Random(7)) { spec, g ->
            tried.add(spec)
            val real = FreeGame.build(spec, g)
            if (tried.size <= wonAttempts) alreadyWon(real) else real
        }
        assertEquals("won boards are retried", wonAttempts + 1, tried.size)
        assertEquals(GameStatus.PLAYING, drawn.board.status)
        assertEquals(tried.last(), drawn.spec)
        assertEquals("rooms stay within the first ATTEMPTS", size.rooms, drawn.spec.rooms)
        assertSameGame("after won boards", drawn, FreeGame.build(drawn.spec, geometry(size)))
        MatchEngine(drawn.board)
    }

    @Test
    fun drawFailsWhenEveryStartAlreadyWinsTheBoard() {
        val size = BoardSize.TINY
        var calls = 0
        try {
            FreeGame.draw(geometry(size), size, walls = true, seeds = Random(2)) { spec, g ->
                calls++
                alreadyWon(FreeGame.build(spec, g))
            }
            throw AssertionError("draw did not fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message ?: "", e.message?.contains("WON") == true)
        }
        assertEquals(2 * FreeGame.ATTEMPTS, calls)
    }

    @Test
    fun drawnGamesAreInPlay() {
        for (size in listOf(BoardSize.TINY, BoardSize.SMALL)) {
            for (walls in listOf(true, false)) {
                for (seed in 0L until 40L) {
                    val drawn = FreeGame.draw(geometry(size), size, walls, Random(seed))
                    assertEquals("$size walls=$walls seed=$seed", GameStatus.PLAYING, drawn.board.status)
                }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun roomsWithoutWallsAreRejected() {
        FreeGameSpec(BoardSize.SMALL, 1L, rooms = 3, walls = false)
    }
}
