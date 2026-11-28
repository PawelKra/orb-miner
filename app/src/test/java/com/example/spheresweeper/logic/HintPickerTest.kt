package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which cell a hint opens: wrong flags first, then covered safe cells, by contact and facing. */
class HintPickerTest {

    /** Cells 0..11 in a row, each the neighbour of the next. */
    private val row = Array(ROW) { cell -> listOf(cell - 1, cell + 1).filter { it in 0 until ROW }.toIntArray() }

    /** Facing that grows with the cell index, so the last cells face the viewer most. */
    private val towardsTheEnd: (Int) -> Float = { cell -> cell / ROW.toFloat() }

    /** Facing that falls with the cell index, so the first cells face the viewer most. */
    private val towardsTheStart: (Int) -> Float = { cell -> -cell / ROW.toFloat() }

    /** Mines on 4 and 8; revealing 0 opens 0..3, and 5 shows a number next to the mine on 4. */
    private fun rowBoard(): MinesweeperBoard {
        val board = MinesweeperBoard(row, 0)
        board.placeMinesAt(intArrayOf(4, 8))
        assertEquals(MoveResult.REVEALED, board.reveal(0))
        return board
    }

    @Test
    fun withoutFlagsTheCellFacingTheViewerWins() {
        val board = rowBoard()
        assertEquals(11, HintPicker.pick(board, row, towardsTheEnd))
        assertEquals(5, HintPicker.pick(board, row, towardsTheStart))
    }

    @Test
    fun touchingTheRevealedAreaOutweighsFacing() {
        val board = rowBoard()
        assertEquals(MoveResult.REVEALED, board.reveal(5))
        // 6 touches the revealed 5; 11 faces the viewer most but touches nothing revealed.
        assertEquals(6, HintPicker.pick(board, row, towardsTheEnd))
    }

    @Test
    fun aWrongFlagComesBeforeAnyUnflaggedCell() {
        val board = rowBoard()
        assertEquals(MoveResult.REVEALED, board.reveal(5))
        board.toggleFlag(10)
        // 6 touches the revealed area and 5 faces the viewer more, yet the wrong flag on 10 is picked.
        assertEquals(10, HintPicker.pick(board, row, towardsTheStart))
    }

    @Test
    fun theBestPlacedWrongFlagIsPicked() {
        val board = rowBoard()
        board.toggleFlag(7)
        board.toggleFlag(11)
        assertEquals(11, HintPicker.pick(board, row, towardsTheEnd))
        assertEquals(7, HintPicker.pick(board, row, towardsTheStart))
        // Once 6 is revealed, 7 touches the revealed area and beats the better facing 11.
        assertEquals(MoveResult.REVEALED, board.reveal(6))
        assertEquals(7, HintPicker.pick(board, row, towardsTheEnd))
    }

    @Test
    fun aCorrectFlagIsNeverPicked() {
        val board = rowBoard()
        board.toggleFlag(4)
        board.toggleFlag(8)
        val cell = HintPicker.pick(board, row, towardsTheStart)
        assertEquals(5, cell)
        assertTrue(!board.isMine(cell))
    }

    @Test
    fun aWrongFlagIsFoundWhenOnlyFlaggedSafeCellsAreLeft() {
        val board = rowBoard()
        board.toggleFlag(4)
        board.toggleFlag(8)
        board.toggleFlag(10)
        for (cell in intArrayOf(5, 6, 7, 9, 11)) board.reveal(cell)
        assertTrue("the flood stops at the flag", !board.isRevealed(10))
        assertEquals(GameStatus.PLAYING, board.status)
        val cell = HintPicker.pick(board, row, towardsTheStart)
        assertEquals(10, cell)
        // What the game does with the pick: the flag goes, the cell opens and the board is solved.
        board.toggleFlag(cell)
        assertEquals(MoveResult.WON, board.reveal(cell))
        assertEquals(-1, HintPicker.pick(board, row, towardsTheStart))
    }

    @Test
    fun noCellIsPickedOnASolvedBoard() {
        val board = rowBoard()
        for (cell in intArrayOf(5, 6, 7, 9, 10, 11)) board.reveal(cell)
        assertEquals(GameStatus.WON, board.status)
        assertEquals(-1, HintPicker.pick(board, row, towardsTheEnd))
    }

    private companion object {
        const val ROW = 12
    }
}
