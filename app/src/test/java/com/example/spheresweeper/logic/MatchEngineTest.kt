package com.example.spheresweeper.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Territory match rules of [MatchEngine]: ownership, claims, explosions, turns and the end of a match. */
class MatchEngineTest {

    /**
     * Cells 0..11 in a row, each the neighbour of the next, with mines on 4 and 8, opened at 0. The start
     * reveals 0..3 (3 touches the mine on 4); 5..7 and 9..11 are safe and 6 and 10 are zeros.
     */
    private fun rowBoard(lockThresholds: IntArray? = null): MinesweeperBoard {
        val neighbors = Array(ROW) { cell -> listOf(cell - 1, cell + 1).filter { it in 0 until ROW }.toIntArray() }
        val board = MinesweeperBoard(neighbors, 2, lockThresholds = lockThresholds)
        board.startWith(intArrayOf(4, 8), 0)
        return board
    }

    @Test
    fun theStartAreaIsNeutral() {
        val engine = MatchEngine(rowBoard())
        for (cell in 0..3) assertEquals("cell $cell", MatchEngine.NO_OWNER, engine.owner(cell))
        assertEquals(0, engine.score(0))
        assertEquals(0, engine.score(1))
        assertEquals(8, engine.hiddenCount)
        assertEquals(0, engine.currentPlayer)
        assertFalse(engine.isOver)
    }

    @Test
    fun aRevealGivesTheMoverTheWholeFlood() {
        val engine = MatchEngine(rowBoard())
        val move = engine.reveal(6)
        assertEquals(MatchMoveKind.REVEAL, move.kind)
        assertEquals(0, move.mover)
        assertEquals(0, move.gainer)
        assertArrayEquals(intArrayOf(5, 6, 7), move.cells)
        for (cell in 5..7) assertEquals("cell $cell", 0, engine.owner(cell))
        assertEquals(3, engine.score(0))
        assertEquals(0, engine.score(1))
        assertEquals(5, engine.hiddenCount)
        for (cell in 0..3) assertEquals("start cell $cell", MatchEngine.NO_OWNER, engine.owner(cell))
    }

    @Test
    fun aCorrectClaimGivesTheMoverTheMine() {
        val board = rowBoard()
        val engine = MatchEngine(board)
        val move = engine.claim(4)
        assertEquals(MatchMoveKind.MINE_FOUND, move.kind)
        assertEquals(0, move.gainer)
        assertArrayEquals(intArrayOf(4), move.cells)
        assertEquals(0, engine.owner(4))
        assertTrue(engine.isFound(4))
        assertTrue("the board shows the found mine", board.isFlagged(4))
        assertEquals(1, engine.score(0))
        assertFalse(engine.isHidden(4))
    }

    @Test
    fun aWrongClaimRevealsTheCellForTheOpponent() {
        val board = rowBoard()
        val engine = MatchEngine(board)
        val move = engine.claim(6)
        assertEquals(MatchMoveKind.WRONG_CLAIM, move.kind)
        assertEquals(0, move.mover)
        assertEquals(1, move.gainer)
        assertArrayEquals(intArrayOf(5, 6, 7), move.cells)
        for (cell in 5..7) {
            assertEquals("cell $cell", 1, engine.owner(cell))
            assertTrue("cell $cell revealed", board.isRevealed(cell))
            assertFalse("cell $cell flagged", board.isFlagged(cell))
        }
        assertEquals(0, engine.score(0))
        assertEquals(3, engine.score(1))
    }

    @Test
    fun anExplosionGivesTheMineToTheOpponentAndTheGameGoesOn() {
        val board = rowBoard()
        val livesBefore = board.livesLeft
        val engine = MatchEngine(board)
        val move = engine.reveal(4)
        assertEquals(MatchMoveKind.EXPLOSION, move.kind)
        assertEquals(0, move.mover)
        assertEquals(1, move.gainer)
        assertEquals(1, engine.owner(4))
        assertTrue(engine.isFound(4))
        assertEquals(GameStatus.PLAYING, board.status)
        assertEquals("no life is lost", livesBefore, board.livesLeft)
        assertFalse("the mine is not revealed", board.isRevealed(4))
        assertFalse(engine.isOver)
        // The match goes on with the other player.
        assertEquals(1, engine.currentPlayer)
        assertEquals(MatchMoveKind.REVEAL, engine.reveal(6).kind)
    }

    @Test
    fun explosionsNeverEndAMatchOnAOneLifeBoard() {
        val board = rowBoard()
        assertEquals(1, board.lives)
        val engine = MatchEngine(board)
        engine.reveal(4)
        engine.reveal(8)
        assertEquals(GameStatus.PLAYING, board.status)
        assertEquals(1, engine.score(0))
        assertEquals(1, engine.score(1))
    }

    @Test
    fun oneMovePerTurnAlternatesThePlayers() {
        val engine = MatchEngine(rowBoard(), movesPerTurn = 1)
        val first = engine.reveal(6)
        assertTrue(first.turnEnded)
        assertEquals(1, engine.currentPlayer)
        assertEquals(1, engine.movesLeft)
        // Revealing 10 would solve the board and end the match, so the second move claims a mine.
        val second = engine.claim(4)
        assertEquals(1, second.mover)
        assertTrue(second.turnEnded)
        assertEquals(0, engine.currentPlayer)
        assertEquals(3, engine.score(0))
        assertEquals(1, engine.score(1))
    }

    @Test
    fun aTurnHasTheChosenNumberOfMoves() {
        val engine = MatchEngine(rowBoard(), movesPerTurn = 3)
        assertEquals(3, engine.movesLeft)
        assertFalse(engine.reveal(6).turnEnded)
        assertEquals(0, engine.currentPlayer)
        assertEquals(2, engine.movesLeft)
        // An ignored move costs nothing.
        assertSame(MatchMove.IGNORED, engine.reveal(6))
        assertSame(MatchMove.IGNORED, engine.claim(2))
        assertSame(MatchMove.IGNORED, engine.reveal(-1))
        assertSame(MatchMove.IGNORED, engine.reveal(ROW))
        assertEquals(2, engine.movesLeft)
        assertFalse(engine.claim(4).turnEnded)
        assertEquals(1, engine.movesLeft)
        val third = engine.reveal(8)
        assertEquals(MatchMoveKind.EXPLOSION, third.kind)
        assertTrue(third.turnEnded)
        assertEquals(1, engine.currentPlayer)
        assertEquals(3, engine.movesLeft)
        // A found mine takes no more moves.
        assertSame(MatchMove.IGNORED, engine.claim(8))
        assertSame(MatchMove.IGNORED, engine.reveal(4))
    }

    @Test
    fun theMatchEndsWithTheLastSafeCell() {
        val board = rowBoard()
        val engine = MatchEngine(board)
        engine.claim(4) // player 0: 4
        engine.reveal(6) // player 1: 5, 6, 7
        assertFalse(engine.isOver)
        val last = engine.reveal(10) // player 0: 9, 10, 11
        assertTrue(last.matchOver)
        assertFalse(last.turnEnded)
        assertTrue(engine.isOver)
        assertEquals(GameStatus.WON, board.status)
        assertEquals(4, engine.score(0))
        assertEquals(3, engine.score(1))
        assertEquals(0, engine.leader)
        // The mine on 8 was never found: it stays hidden and belongs to nobody.
        assertEquals(1, engine.hiddenCount)
        assertEquals(MatchEngine.NO_OWNER, engine.owner(8))
        assertFalse(engine.isFound(8))
        assertFalse(board.isFlagged(8))
        assertSame(MatchMove.IGNORED, engine.claim(8))
        assertSame(MatchMove.IGNORED, engine.reveal(8))
        assertSame(MatchMove.IGNORED, engine.reveal(1))
        assertEquals(4, engine.score(0))
        assertEquals(3, engine.score(1))
    }

    @Test
    fun aWrongClaimOnTheLastSafeCellsEndsTheMatch() {
        val board = rowBoard()
        val engine = MatchEngine(board)
        engine.reveal(6) // player 0: 5, 6, 7
        val last = engine.claim(10) // player 1 is wrong: 9, 10, 11 go to player 0
        assertEquals(MatchMoveKind.WRONG_CLAIM, last.kind)
        assertEquals(0, last.gainer)
        assertTrue(last.matchOver)
        assertTrue(engine.isOver)
        assertEquals(6, engine.score(0))
        assertEquals(0, engine.score(1))
        for (mine in intArrayOf(4, 8)) assertEquals("mine $mine", MatchEngine.NO_OWNER, engine.owner(mine))
        assertEquals(0, engine.leader)
    }

    @Test
    fun equalScoresAreADraw() {
        val engine = MatchEngine(rowBoard())
        engine.reveal(6) // player 0: 5, 6, 7
        engine.claim(4) // player 1: 4
        engine.claim(8) // player 0: 8
        assertFalse(engine.isOver)
        assertEquals(0, engine.leader)
        engine.reveal(10) // player 1: 9, 10, 11
        assertTrue(engine.isOver)
        assertEquals(4, engine.score(0))
        assertEquals(4, engine.score(1))
        assertEquals(-1, engine.leader)
    }

    @Test
    fun anExplosionDoesNotEndTheMatchWhileSafeCellsAreHidden() {
        val engine = MatchEngine(rowBoard())
        engine.reveal(6) // player 0: 5, 6, 7
        engine.reveal(4) // player 1 explodes: 4 goes to player 0
        engine.reveal(8) // player 0 explodes: 8 goes to player 1
        assertFalse(engine.isOver)
        assertEquals(3, engine.hiddenCount)
        val last = engine.reveal(10) // player 1: 9, 10, 11
        assertTrue(last.matchOver)
        assertEquals(4, engine.score(0))
        assertEquals(4, engine.score(1))
        assertEquals(-1, engine.leader)
    }

    @Test
    fun cellsALockReleasesGoToTheMoverWhoOpenedIt() {
        // Cell 10 shows its number once 6 cells are revealed; the start reveals 4.
        val locks = IntArray(ROW).also { it[10] = 6 }
        val board = rowBoard(locks)
        val engine = MatchEngine(board)
        val first = engine.reveal(10)
        assertArrayEquals("a locked zero does not spread", intArrayOf(10), first.cells)
        assertFalse(board.isRevealed(9))
        val second = engine.reveal(6)
        assertEquals(1, second.mover)
        assertArrayEquals(intArrayOf(5, 6, 7, 9, 11), second.cells)
        for (cell in intArrayOf(9, 11)) assertEquals("cell $cell", 1, engine.owner(cell))
        assertEquals(0, engine.owner(10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooManyMovesPerTurnAreRejected() {
        MatchEngine(rowBoard(), movesPerTurn = MatchEngine.MAX_MOVES_PER_TURN + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun noMovesPerTurnAreRejected() {
        MatchEngine(rowBoard(), movesPerTurn = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBoardWithFlagsIsRejected() {
        val board = rowBoard()
        board.toggleFlag(4)
        MatchEngine(board)
    }

    @Test
    fun randomMatchesOnFreeGameBoardsShareOutEverySafeCell() {
        val sizes = listOf(BoardSize.TINY, BoardSize.SMALL, BoardSize.MEDIUM)
        for (size in sizes) {
            val geometry = SphereGeometry.build(size.frequency)
            for (walls in listOf(true, false)) {
                val label = "$size walls=$walls"
                val game = FreeGame.draw(geometry, size, walls, Random(size.ordinal * 31L + if (walls) 1 else 0))
                val board = game.board
                val neutral = (0 until board.cellCount).count { board.isRevealed(it) }
                val engine = MatchEngine(board, movesPerTurn = 1 + size.ordinal)
                val picker = Random(size.ordinal * 97L)
                var moves = 0
                while (!engine.isOver) {
                    val hidden = (0 until board.cellCount).filter { engine.isHidden(it) }
                    val cell = hidden[picker.nextInt(hidden.size)]
                    val player = engine.currentPlayer
                    val before = engine.score(0) + engine.score(1)
                    val move = if (picker.nextBoolean()) engine.claim(cell) else engine.reveal(cell)
                    assertTrue("$label move on $cell applies", move.kind != MatchMoveKind.IGNORED)
                    assertEquals("$label mover", player, move.mover)
                    assertEquals("$label score grows by the cells", before + move.cells.size, engine.score(0) + engine.score(1))
                    for (taken in move.cells) assertEquals("$label owner of $taken", move.gainer, engine.owner(taken))
                    assertTrue("$label board never lost", board.status != GameStatus.LOST)
                    moves++
                }
                assertEquals("$label the board is solved", GameStatus.WON, board.status)
                val unfound = (0 until board.cellCount).count { board.isPresent(it) && board.isMine(it) && !engine.isFound(it) }
                assertEquals("$label unfound mines stay hidden", unfound, engine.hiddenCount)
                assertEquals(
                    "$label every cell is neutral, owned or an unfound mine",
                    board.presentCount, neutral + engine.score(0) + engine.score(1) + unfound,
                )
                for (cell in 0 until board.cellCount) {
                    if (!board.isPresent(cell)) continue
                    val owned = engine.owner(cell) != MatchEngine.NO_OWNER
                    if (board.isMine(cell)) {
                        assertEquals("$label mine $cell owned exactly when found", engine.isFound(cell), owned)
                        assertEquals("$label mine $cell flagged exactly when found", engine.isFound(cell), board.isFlagged(cell))
                    } else {
                        assertTrue("$label safe cell $cell revealed", board.isRevealed(cell))
                        assertFalse("$label safe cell $cell never found", engine.isFound(cell))
                    }
                }
                assertTrue("$label took moves", moves > 0)
            }
        }
    }

    private companion object {
        const val ROW = 12
    }
}
