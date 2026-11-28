package com.example.spheresweeper.logic

/** What a line cell ([BoardLayout.isLine]) tells about its row. */
object LineCells {

    /** Mines among the cells of the row of [cell] ([BoardLayout.linePath]); 0 for a cell that is no line cell. */
    fun rowMines(layout: BoardLayout, cell: Int, isMine: (Int) -> Boolean): Int =
        layout.linePath(cell).count(isMine)

    /**
     * True when [cell] is a line cell whose empty row the player can already see: it is revealed, its
     * lock (if any) is open and its number, the mines of its row, is 0. Such a line points at nothing
     * worth reading, so it is drawn without its arrow and row line. While the lock is closed the number
     * is hidden, so this stays false and the line looks like any other.
     */
    fun showsEmptyRow(layout: BoardLayout, board: MinesweeperBoard, cell: Int): Boolean =
        layout.isLine(cell) && board.isRevealed(cell) && board.isLockOpen(cell) && board.adjacentMines(cell) == 0
}
