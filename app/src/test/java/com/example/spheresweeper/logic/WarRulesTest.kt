package com.example.spheresweeper.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Five-move war rules of [MatchEngine] with [WarRules]: points, turn order, tie-breaks and the end. */
class WarRulesTest {

    /**
     * Cells 0..11 in a row, each the neighbour of the next, with mines on 4 and 8, opened at 0. The start
     * reveals 0..3 (3 touches the mine on 4); 5, 7 and 9 show 1, and 6, 10 and 11 are zeros.
     */
    private fun rowBoard(lockThresholds: IntArray? = null): MinesweeperBoard {
        val neighbors = Array(ROW) { cell -> listOf(cell - 1, cell + 1).filter { it in 0 until ROW }.toIntArray() }
        val board = MinesweeperBoard(neighbors, 2, lockThresholds = lockThresholds)
        board.startWith(intArrayOf(4, 8), 0)
        return board
    }

    /**
     * The start cell 0 on its own, then [pairs] pairs of a mine and a safe cell that touch only each
     * other: mines on the odd cells 1, 3, 5, ..., and a 1 on the even cell after each mine. A claim of a
     * mine gives 3, a reveal of a safe cell 1, and no move reveals more than one cell.
     */
    private fun pairBoard(pairs: Int): MinesweeperBoard {
        val size = 1 + 2 * pairs
        val neighbors = Array(size) { cell ->
            when {
                cell == 0 -> IntArray(0)
                cell % 2 == 1 -> intArrayOf(cell + 1)
                else -> intArrayOf(cell - 1)
            }
        }
        val board = MinesweeperBoard(neighbors, pairs)
        board.startWith(IntArray(pairs) { 2 * it + 1 }, 0)
        return board
    }

    private fun war(board: MinesweeperBoard) = MatchEngine(board, WarRules)

    @Test
    fun theStartAreaIsNeutralAndPlayerOneHasFiveMoves() {
        val engine = war(rowBoard())
        for (cell in 0..3) assertEquals("cell $cell", MatchEngine.NO_OWNER, engine.owner(cell))
        assertEquals(0, engine.score(0))
        assertEquals(0, engine.score(1))
        assertEquals(0, engine.currentPlayer)
        assertEquals(WarRules.MOVES_PER_TURN, engine.turnMoves)
        assertEquals(WarRules.MOVES_PER_TURN, engine.movesLeft)
        assertEquals(WarRules.MOVES_PER_TURN, engine.movesPerTurn)
        assertFalse(engine.isTieBreak)
        assertFalse(engine.isOver)
    }

    @Test
    fun aRevealedNumberGivesItsValue() {
        val engine = war(rowBoard())
        val move = engine.reveal(5)
        assertEquals(MatchMoveKind.REVEAL, move.kind)
        assertEquals(0, move.mover)
        assertEquals(0, move.gainer)
        assertEquals(1, move.points)
        assertArrayEquals(intArrayOf(5), move.cells)
        assertEquals(1, engine.score(0))
        assertEquals("the mover's colour marks the cell", 0, engine.owner(5))
    }

    @Test
    fun aRevealedZeroGivesOnePointPerOtherFloodedCell() {
        val engine = war(rowBoard())
        // 6 is a zero: 0 for itself, 1 each for 5 and 7.
        val move = engine.reveal(6)
        assertArrayEquals(intArrayOf(5, 6, 7), move.cells)
        assertEquals(2, move.points)
        assertEquals(2, engine.score(0))
        for (cell in 5..7) assertEquals("cell $cell", 0, engine.owner(cell))
        // 11 is a zero that floods 10, which floods 9: 0 + 2.
        val end = engine.reveal(11)
        assertArrayEquals(intArrayOf(9, 10, 11), end.cells)
        assertEquals(2, end.points)
        assertEquals(4, engine.score(0))
    }

    @Test
    fun aZeroWithNothingLeftToFloodGivesNothing() {
        val engine = war(rowBoard())
        engine.reveal(5)
        engine.reveal(7)
        val move = engine.reveal(6)
        assertArrayEquals(intArrayOf(6), move.cells)
        assertEquals(0, move.points)
        assertEquals(2, engine.score(0))
        assertEquals("a move that scores nothing still counts", 2, engine.movesLeft)
    }

    @Test
    fun cellsALockReleasesCountAsFloodedCells() {
        // Cell 10 shows its number once 6 cells are revealed; the start reveals 4.
        val locks = IntArray(ROW).also { it[10] = 6 }
        val engine = war(rowBoard(locks))
        val first = engine.reveal(10)
        assertArrayEquals("a locked zero does not spread", intArrayOf(10), first.cells)
        assertEquals(0, first.points)
        // 6 floods 5 and 7, which opens the lock, and 10 then floods 9 and 11: 0 + 4.
        val second = engine.reveal(6)
        assertArrayEquals(intArrayOf(5, 6, 7, 9, 11), second.cells)
        assertEquals(4, second.points)
        assertEquals(4, engine.score(0))
    }

    @Test
    fun aNumberBehindAClosedLockGivesNothingForItself() {
        // Cell 5 shows 1 once 6 cells are revealed; the start reveals 4.
        val locks = IntArray(ROW).also { it[5] = 6 }
        val board = rowBoard(locks)
        val engine = war(board)
        val locked = engine.reveal(5)
        assertEquals(MatchMoveKind.REVEAL, locked.kind)
        assertArrayEquals(intArrayOf(5), locked.cells)
        assertFalse("the lock is still closed", board.isLockOpen(5))
        assertEquals("the hidden number is not scored", 0, locked.points)
        assertEquals(0, engine.score(0))
        // The sixth revealed cell opens the lock; that move scores only its own cell.
        val next = engine.reveal(7)
        assertTrue(board.isLockOpen(5))
        assertEquals(1, next.points)
        assertEquals(1, engine.score(0))
    }

    @Test
    fun aLockTheMoveItselfOpensShowsTheNumber() {
        // Cell 5 shows 1 once 5 cells are revealed: revealing it is the fifth.
        val locks = IntArray(ROW).also { it[5] = 5 }
        val board = rowBoard(locks)
        val engine = war(board)
        val move = engine.reveal(5)
        assertTrue(board.isLockOpen(5))
        assertEquals(1, move.points)
    }

    @Test
    fun aCorrectClaimGivesThreePoints() {
        val board = rowBoard()
        val engine = war(board)
        val move = engine.claim(4)
        assertEquals(MatchMoveKind.MINE_FOUND, move.kind)
        assertEquals(WarRules.MINE_FOUND_POINTS, move.points)
        assertArrayEquals(intArrayOf(4), move.cells)
        assertEquals(3, engine.score(0))
        assertTrue(engine.isFound(4))
        assertTrue("the board shows the found mine", board.isFlagged(4))
        assertEquals(0, engine.owner(4))
        assertFalse(engine.isHidden(4))
    }

    @Test
    fun aWrongClaimCostsThreeAndItsFloodGivesNothing() {
        val board = rowBoard()
        val engine = war(board)
        val move = engine.claim(6)
        assertEquals(MatchMoveKind.WRONG_CLAIM, move.kind)
        assertEquals(0, move.gainer)
        assertEquals(WarRules.WRONG_CLAIM_POINTS, move.points)
        assertArrayEquals(intArrayOf(5, 6, 7), move.cells)
        assertEquals("scores can go below zero", -3, engine.score(0))
        assertEquals(0, engine.score(1))
        for (cell in 5..7) {
            assertTrue("cell $cell revealed", board.isRevealed(cell))
            assertFalse("cell $cell flagged", board.isFlagged(cell))
            assertEquals("cell $cell belongs to nobody", MatchEngine.NO_OWNER, engine.owner(cell))
        }
        assertEquals(1, engine.leader)
    }

    @Test
    fun anExplosionCostsFiveAndTheWarGoesOn() {
        val board = rowBoard()
        val livesBefore = board.livesLeft
        val engine = war(board)
        val move = engine.reveal(4)
        assertEquals(MatchMoveKind.EXPLOSION, move.kind)
        assertEquals(WarRules.EXPLOSION_POINTS, move.points)
        assertEquals(-5, engine.score(0))
        assertTrue(engine.isFound(4))
        assertTrue("the mine is marked", board.isFlagged(4))
        assertFalse("the mine is not revealed", board.isRevealed(4))
        assertEquals(MatchEngine.NO_OWNER, engine.owner(4))
        assertEquals(GameStatus.PLAYING, board.status)
        assertEquals("no life is lost", livesBefore, board.livesLeft)
        assertFalse(engine.isOver)
        assertFalse(move.matchOver)
        assertEquals("the turn goes on", 0, engine.currentPlayer)
        assertEquals(4, engine.movesLeft)
        assertEquals(1, engine.reveal(5).points)
    }

    @Test
    fun playerOneMakesFiveMovesThenPlayerTwoMakesFive() {
        val engine = war(pairBoard(8))
        // Player 1: two mines and three safe cells, 3 + 3 + 1 + 1 + 1.
        val first = intArrayOf(1, 3, 2, 4, 6)
        for ((k, cell) in first.withIndex()) {
            assertEquals(0, engine.currentPlayer)
            assertEquals(WarRules.MOVES_PER_TURN - k, engine.movesLeft)
            val move = if (cell % 2 == 1) engine.claim(cell) else engine.reveal(cell)
            assertEquals(0, move.mover)
            assertEquals("move $k ends the turn", k == first.lastIndex, move.turnEnded)
            assertFalse(move.matchOver)
        }
        assertEquals(9, engine.score(0))
        assertEquals(1, engine.currentPlayer)
        assertEquals(WarRules.MOVES_PER_TURN, engine.movesLeft)
        assertEquals(WarRules.MOVES_PER_TURN, engine.turnMoves)
        assertFalse(engine.isTieBreak)
        // Player 2: one mine and four safe cells, 3 + 4.
        val second = intArrayOf(5, 8, 10, 12, 14)
        for ((k, cell) in second.withIndex()) {
            assertFalse(engine.isOver)
            assertEquals(1, engine.currentPlayer)
            val move = if (cell % 2 == 1) engine.claim(cell) else engine.reveal(cell)
            assertEquals(1, move.mover)
            assertFalse(move.turnEnded)
            assertEquals("move $k ends the war", k == second.lastIndex, move.matchOver)
        }
        assertEquals(7, engine.score(1))
        assertTrue(engine.isOver)
        assertEquals(0, engine.leader)
        assertTrue("hidden cells are left", engine.hiddenCount > 0)
        assertSame(MatchMove.IGNORED, engine.claim(7))
        assertSame(MatchMove.IGNORED, engine.reveal(16))
        assertEquals(9, engine.score(0))
    }

    @Test
    fun ignoredMovesCostNothing() {
        val engine = war(rowBoard())
        assertSame(MatchMove.IGNORED, engine.reveal(0))
        assertSame(MatchMove.IGNORED, engine.claim(2))
        assertSame(MatchMove.IGNORED, engine.reveal(-1))
        assertSame(MatchMove.IGNORED, engine.reveal(ROW))
        engine.claim(4)
        assertSame("a found mine takes no more moves", MatchMove.IGNORED, engine.reveal(4))
        assertEquals(4, engine.movesLeft)
        assertEquals(3, engine.score(0))
    }

    /** Plays the regular turns on [pairBoard] to 9 : 9 and returns the engine at the first tie-break. */
    private fun tiedWar(pairs: Int): MatchEngine {
        val engine = war(pairBoard(pairs))
        for (cell in intArrayOf(1, 3, 2, 4, 6)) if (cell % 2 == 1) engine.claim(cell) else engine.reveal(cell)
        for (cell in intArrayOf(5, 7, 8, 10, 12)) if (cell % 2 == 1) engine.claim(cell) else engine.reveal(cell)
        return engine
    }

    @Test
    fun aTieAfterBothTurnsGivesEachPlayerOneMoreMove() {
        val engine = tiedWar(8)
        assertEquals(9, engine.score(0))
        assertEquals(9, engine.score(1))
        assertFalse(engine.isOver)
        assertEquals(0, engine.currentPlayer)
        assertTrue(engine.isTieBreak)
        assertEquals(1, engine.round)
        assertEquals(WarRules.TIE_BREAK_MOVES, engine.turnMoves)
        assertEquals(WarRules.TIE_BREAK_MOVES, engine.movesLeft)
        val extra = engine.claim(9)
        assertTrue("one move ends a tie-break turn", extra.turnEnded)
        assertFalse(extra.matchOver)
        assertEquals(1, engine.currentPlayer)
        assertEquals(1, engine.movesLeft)
        val answer = engine.reveal(14)
        assertEquals(1, answer.mover)
        assertTrue(answer.matchOver)
        assertFalse(answer.turnEnded)
        assertTrue(engine.isOver)
        assertEquals(12, engine.score(0))
        assertEquals(10, engine.score(1))
        assertEquals(0, engine.leader)
    }

    @Test
    fun aTieBreakRepeatsWhileTheScoresStayEqual() {
        val engine = tiedWar(9)
        engine.claim(9) // 12 : 9
        val even = engine.claim(11) // 12 : 12
        assertTrue(even.turnEnded)
        assertFalse(even.matchOver)
        assertEquals(2, engine.round)
        assertEquals(0, engine.currentPlayer)
        assertTrue(engine.isTieBreak)
        // Player 2 still answers when player 1 falls behind in a tie-break.
        val behind = engine.claim(14) // wrong claim: 9 : 12
        assertEquals(-3, behind.points)
        assertTrue(behind.turnEnded)
        assertFalse(engine.isOver)
        val last = engine.reveal(16) // 9 : 13
        assertTrue(last.matchOver)
        assertEquals(1, engine.leader)
        assertEquals(9, engine.score(0))
        assertEquals(13, engine.score(1))
    }

    @Test
    fun theWarEndsAtOnceWhenTheLastSafeCellIsRevealed() {
        val board = rowBoard()
        val engine = war(board)
        engine.claim(4) // 3
        engine.reveal(6) // 5, 6, 7: 2
        val solved = engine.reveal(10) // 9, 10, 11: 2
        assertEquals("every safe cell is revealed", GameStatus.WON, board.status)
        assertTrue(solved.matchOver)
        assertFalse(solved.turnEnded)
        assertTrue(engine.isOver)
        assertEquals("the mine nobody found stays hidden", 1, engine.hiddenCount)
        assertTrue(engine.isHidden(8))
        assertFalse(engine.isFound(8))
        assertEquals(MatchEngine.NO_OWNER, engine.owner(8))
        assertEquals("player 1 ends the war in their own turn", 0, engine.currentPlayer)
        assertEquals(7, engine.score(0))
        assertEquals(0, engine.score(1))
        assertEquals(0, engine.leader)
        assertSame("a hidden mine takes no more moves", MatchMove.IGNORED, engine.claim(8))
        assertEquals(7, engine.score(0))
    }

    @Test
    fun aWrongClaimOfTheLastSafeCellEndsTheWar() {
        val board = rowBoard()
        val engine = war(board)
        engine.reveal(6) // 5, 6, 7: 2
        val last = engine.claim(10) // 9, 10, 11 revealed: -3
        assertEquals(MatchMoveKind.WRONG_CLAIM, last.kind)
        assertEquals(GameStatus.WON, board.status)
        assertTrue(last.matchOver)
        assertTrue(engine.isOver)
        assertEquals(2, engine.hiddenCount)
        assertEquals(-1, engine.score(0))
        assertEquals(1, engine.leader)
    }

    @Test
    fun equalScoresWhenTheLastSafeCellIsRevealedAreADraw() {
        // On 6 pairs the regular turns reveal every safe cell; player 2's last reveal ends the war at 9 : 9.
        val engine = tiedWar(6)
        assertTrue(engine.isOver)
        assertEquals(GameStatus.WON, engine.board.status)
        assertEquals("mines 9 and 11 stay hidden", 2, engine.hiddenCount)
        assertEquals(9, engine.score(0))
        assertEquals(9, engine.score(1))
        assertEquals(-1, engine.leader)
        assertFalse("a draw on a solved board needs no tie-break", engine.isTieBreak)
        assertSame(MatchMove.IGNORED, engine.claim(9))
    }

    @Test
    fun randomWarsOnFreeGameBoardsKeepTheScoresAndEnd() {
        val sizes = listOf(BoardSize.TINY, BoardSize.SMALL)
        for (size in sizes) {
            val geometry = SphereGeometry.build(size.frequency)
            for (walls in listOf(true, false)) {
                val label = "$size walls=$walls"
                val game = FreeGame.draw(geometry, size, walls, Random(size.ordinal * 37L + if (walls) 1 else 0))
                val board = game.board
                val engine = war(board)
                val picker = Random(size.ordinal * 101L)
                val totals = IntArray(MatchEngine.PLAYERS)
                var moves = 0
                while (!engine.isOver) {
                    val hidden = (0 until board.cellCount).filter { engine.isHidden(it) }
                    assertTrue("$label a running war has hidden cells", hidden.isNotEmpty())
                    val cell = hidden[picker.nextInt(hidden.size)]
                    val player = engine.currentPlayer
                    val move = if (picker.nextBoolean()) engine.claim(cell) else engine.reveal(cell)
                    assertTrue("$label move on $cell applies", move.kind != MatchMoveKind.IGNORED)
                    assertEquals("$label mover", player, move.mover)
                    assertEquals("$label the mover scores", player, move.gainer)
                    val expected = when (move.kind) {
                        MatchMoveKind.REVEAL ->
                            (if (board.isLockOpen(cell)) board.adjacentMines(cell) else 0) + move.cells.size - 1
                        MatchMoveKind.MINE_FOUND -> 3
                        MatchMoveKind.WRONG_CLAIM -> -3
                        MatchMoveKind.EXPLOSION -> -5
                        MatchMoveKind.IGNORED -> 0
                    }
                    assertEquals("$label points of $cell", expected, move.points)
                    totals[player] += move.points
                    assertTrue("$label board never lost", board.status != GameStatus.LOST)
                    moves++
                }
                assertEquals("$label score of player 1", totals[0], engine.score(0))
                assertEquals("$label score of player 2", totals[1], engine.score(1))
                val solved = board.status == GameStatus.WON
                assertTrue("$label ten moves unless the board was solved", moves >= 10 || solved)
                assertTrue("$label a draw only on a solved board", engine.leader >= 0 || solved)
            }
        }
    }

    private companion object {
        const val ROW = 12
    }
}
