package com.example.spheresweeper.logic

/** What one move of a match did; see [MatchEngine]. */
enum class MatchMoveKind {
    /** The move did not apply: the match is over, or the cell is not hidden or not in play. */
    IGNORED,

    /** A safe cell revealed; the mover gets it and the cells its zero flood revealed. */
    REVEAL,

    /** A correct claim of a mine; the mover gets the mine. */
    MINE_FOUND,

    /** A claim of a safe cell; the cell is revealed with its flood and those cells go to the opponent. */
    WRONG_CLAIM,

    /** A revealed mine; the mine goes to the opponent and the match goes on. */
    EXPLOSION,
}

/** Result of one move of a match. */
class MatchMove(
    val kind: MatchMoveKind,
    /** Player who made the move, -1 when it was [MatchMoveKind.IGNORED]. */
    val mover: Int,
    /** Player who got [cells], -1 when the move was ignored. */
    val gainer: Int,
    /** Cells the move gave to [gainer], ascending; empty when the move was ignored. */
    val cells: IntArray,
    /** True when the move used the last move of the turn and the other player moves next. */
    val turnEnded: Boolean,
    /** True when the move revealed the last hidden safe cell and the match is over. */
    val matchOver: Boolean,
) {
    companion object {
        val IGNORED = MatchMove(MatchMoveKind.IGNORED, -1, -1, IntArray(0), turnEnded = false, matchOver = false)
    }
}

/**
 * Rules of the two-player territory match ("Gra o teren"): two players share one board and take turns on
 * the same phone. Whoever owns more cells at the end wins.
 *
 * 1. The board is a free-game board whose start area is already revealed. Those cells are neutral: they
 *    belong to no player.
 * 2. A turn is [movesPerTurn] moves, the same number for both players. A move is revealing a hidden cell
 *    ([reveal]) or claiming a hidden cell as a mine ([claim]). Player 0 moves first.
 * 3. Revealing a safe cell gives the mover that cell and every cell the move revealed with it (the zero
 *    flood, and zeros that a lock opened by the move lets spread). These are found as the cells revealed
 *    after the move that were not revealed before it.
 * 4. A claim is checked at once. On a mine it is correct: the mover gets the mine, which is marked as
 *    found. On a safe cell it is wrong: the cell is revealed with its flood, and those cells go to the
 *    opponent.
 * 5. Revealing a mine is an explosion. The match does not end: the mine goes to the opponent and is marked
 *    as found as well.
 * 6. A player's score is the number of cells they own; a mine counts 1 like any cell. The match ends as
 *    soon as the last safe cell is revealed, by a reveal or by a wrong claim. Mines nobody found by then
 *    stay unowned and count for nobody. The higher score wins, equal scores are a draw.
 * 7. Locks, portals, far cells, line cells and rooms work as in a single-player game.
 *
 * A hidden cell is a present cell that is neither revealed nor a found mine. The engine never reveals a
 * mine on [board]: a found mine, claimed or exploded, gets a flag there, so numbers, chords and the
 * renderer see it as a known mine and the board never loses a life. The match is over exactly when the
 * board turns [GameStatus.WON], that is when every safe cell is revealed; the board takes no more moves
 * then, and every found mine carries its flag.
 *
 * All moves of the match must go through the engine; [board] must not be changed directly meanwhile.
 *
 * @throws IllegalArgumentException when [movesPerTurn] is outside [MIN_MOVES_PER_TURN]..[MAX_MOVES_PER_TURN],
 *   or [board] is not a game in progress without flags and lost lives.
 */
class MatchEngine(val board: MinesweeperBoard, val movesPerTurn: Int = DEFAULT_MOVES_PER_TURN) {

    init {
        require(movesPerTurn in MIN_MOVES_PER_TURN..MAX_MOVES_PER_TURN) { "moves per turn $movesPerTurn" }
        require(board.minesPlaced && board.status == GameStatus.PLAYING) { "the board must be opened and in play" }
        require(board.flagCount == 0 && board.detonatedCount == 0) { "the board must have no flags and no lost lives" }
    }

    private val cellCount = board.cellCount

    /** Owner per cell: a player index, or [NO_OWNER]. */
    private val owners = IntArray(cellCount) { NO_OWNER }

    /** Revealed cells as of the end of the last move; the next move's cells are the ones it adds. */
    private val known = BooleanArray(cellCount) { board.isRevealed(it) }

    /** Mines claimed correctly or exploded. */
    private val found = BooleanArray(cellCount)

    private val scores = IntArray(PLAYERS)

    /** Present cells neither revealed nor found. */
    var hiddenCount: Int = (0 until cellCount).count { board.isPresent(it) && !board.isRevealed(it) }
        private set

    /** Player whose turn it is, 0 or 1. Stays on the last mover once the match is over. */
    var currentPlayer: Int = 0
        private set

    /** Moves the current player has left in this turn, 1..[movesPerTurn] while the match goes on. */
    var movesLeft: Int = movesPerTurn
        private set

    /** True once every safe cell is revealed (rule 6); hidden mines may be left, and they stay unowned. */
    val isOver: Boolean get() = board.status == GameStatus.WON

    /** Player with more cells, or -1 while both have the same number; the winner once [isOver]. */
    val leader: Int
        get() = when {
            scores[0] > scores[1] -> 0
            scores[1] > scores[0] -> 1
            else -> -1
        }

    /** Owner of [cell]: a player index, or [NO_OWNER] for hidden cells, neutral cells and unfound mines. */
    fun owner(cell: Int): Int = owners[cell]

    /** Cells [player] owns. */
    fun score(player: Int): Int = scores[player]

    /** True for a mine that was claimed correctly or exploded. */
    fun isFound(cell: Int): Boolean = found[cell]

    /** True for a present cell that is neither revealed nor a found mine; only these take moves. */
    fun isHidden(cell: Int): Boolean =
        cell in 0 until cellCount && board.isPresent(cell) && !board.isRevealed(cell) && !found[cell]

    /**
     * The current player reveals [cell] (rules 3 and 5): a safe cell with its flood goes to the mover, a
     * mine to the opponent. Ignored when the match is over or [cell] is not hidden.
     */
    fun reveal(cell: Int): MatchMove {
        if (isOver || !isHidden(cell)) return MatchMove.IGNORED
        val mover = currentPlayer
        if (board.isMine(cell)) return finish(MatchMoveKind.EXPLOSION, mover, takeMine(cell, other(mover)))
        board.reveal(cell)
        return finish(MatchMoveKind.REVEAL, mover, takeRevealed(mover))
    }

    /**
     * The current player claims [cell] as a mine (rule 4): a mine goes to the mover, a safe cell is
     * revealed with its flood for the opponent. Ignored when the match is over or [cell] is not hidden.
     */
    fun claim(cell: Int): MatchMove {
        if (isOver || !isHidden(cell)) return MatchMove.IGNORED
        val mover = currentPlayer
        if (board.isMine(cell)) return finish(MatchMoveKind.MINE_FOUND, mover, takeMine(cell, mover))
        board.reveal(cell)
        return finish(MatchMoveKind.WRONG_CLAIM, mover, takeRevealed(other(mover)))
    }

    /** Marks the mine [cell] as found and gives it to [player]; returns the cells taken. */
    private fun takeMine(cell: Int, player: Int): IntArray {
        found[cell] = true
        // Moves apply only before the board is won, so the board still takes the flag.
        board.toggleFlag(cell)
        give(cell, player)
        return intArrayOf(cell)
    }

    /** Gives [player] every cell revealed since the last move; returns them in ascending order. */
    private fun takeRevealed(player: Int): IntArray {
        var count = 0
        for (cell in 0 until cellCount) {
            if (!known[cell] && board.isRevealed(cell)) count++
        }
        val cells = IntArray(count)
        var k = 0
        for (cell in 0 until cellCount) {
            if (known[cell] || !board.isRevealed(cell)) continue
            known[cell] = true
            give(cell, player)
            cells[k++] = cell
        }
        return cells
    }

    private fun give(cell: Int, player: Int) {
        owners[cell] = player
        scores[player]++
        hiddenCount--
    }

    /** Counts the move against the turn and passes the turn on after its last move. */
    private fun finish(kind: MatchMoveKind, mover: Int, cells: IntArray): MatchMove {
        val gainer = when (kind) {
            MatchMoveKind.REVEAL, MatchMoveKind.MINE_FOUND -> mover
            else -> other(mover)
        }
        if (isOver) return MatchMove(kind, mover, gainer, cells, turnEnded = false, matchOver = true)
        movesLeft--
        val turnEnded = movesLeft == 0
        if (turnEnded) {
            currentPlayer = other(currentPlayer)
            movesLeft = movesPerTurn
        }
        return MatchMove(kind, mover, gainer, cells, turnEnded, matchOver = false)
    }

    private fun other(player: Int): Int = 1 - player

    companion object {
        const val PLAYERS = 2
        const val NO_OWNER = -1
        const val MIN_MOVES_PER_TURN = 1
        const val MAX_MOVES_PER_TURN = 5
        const val DEFAULT_MOVES_PER_TURN = 1
    }
}
