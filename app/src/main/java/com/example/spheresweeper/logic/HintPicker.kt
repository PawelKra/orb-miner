package com.example.spheresweeper.logic

/**
 * Chooses the cell a hint opens in a single-player game.
 *
 * A flag on a safe cell (a wrong flag) comes first: while one exists, the hint picks a wrong flag, so a
 * mistake that blocks the end of the game is always pointed out. Otherwise it picks a covered safe cell
 * without a flag. Among the candidates of the chosen kind the highest score wins: how much the cell faces
 * the viewer, in -1..1, plus [TOUCH_BONUS] when one of its neighbours is revealed. Equal scores keep the
 * lowest cell index.
 */
object HintPicker {

    /** Score bonus of a cell next to the revealed area; larger than any difference in facing. */
    const val TOUCH_BONUS = 2f

    /**
     * Cell the hint should open on [board], or -1 when no safe cell is left covered. [adjacent] lists the
     * cells touching each cell on the ball; [facing] gives how much a cell faces the viewer, in -1..1.
     * The caller removes the flag of a picked flagged cell before it reveals the cell.
     */
    fun pick(board: MinesweeperBoard, adjacent: Array<IntArray>, facing: (Int) -> Float): Int {
        val wrongFlag = best(board, adjacent, facing, flagged = true)
        return if (wrongFlag >= 0) wrongFlag else best(board, adjacent, facing, flagged = false)
    }

    /** Best covered safe cell whose flag state equals [flagged], or -1 when there is none. */
    private fun best(board: MinesweeperBoard, adjacent: Array<IntArray>, facing: (Int) -> Float, flagged: Boolean): Int {
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (cell in 0 until board.cellCount) {
            if (!board.isPresent(cell) || board.isMine(cell) || board.isRevealed(cell)) continue
            if (board.isFlagged(cell) != flagged) continue
            var score = facing(cell)
            if (adjacent[cell].any { board.isRevealed(it) }) score += TOUCH_BONUS
            if (score > bestScore) {
                bestScore = score
                best = cell
            }
        }
        return best
    }
}
