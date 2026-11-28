package com.example.spheresweeper.logic

/** What one move of a match did; see [MatchEngine]. */
enum class MatchMoveKind {
    /** The move did not apply: the match is over, or the cell is not hidden or not in play. */
    IGNORED,

    /** A safe cell revealed, with the cells its zero flood revealed. */
    REVEAL,

    /** A correct claim of a mine; the mine is marked as found. */
    MINE_FOUND,

    /** A claim of a safe cell; the cell is revealed with its flood. */
    WRONG_CLAIM,

    /** A revealed mine; the mine is marked as found and the match goes on. */
    EXPLOSION,
}

/** Result of one move of a match. */
class MatchMove(
    val kind: MatchMoveKind,
    /** Player who made the move, -1 when it was [MatchMoveKind.IGNORED]. */
    val mover: Int,
    /** Player whose score the move changed by [points], -1 when the move was ignored. */
    val gainer: Int,
    /** Score change of [gainer]: cells taken in a territory match, points (maybe negative) in a war. */
    val points: Int,
    /**
     * Cells the move revealed or marked as found, ascending; empty when the move was ignored. In a
     * territory match they all go to [gainer].
     */
    val cells: IntArray,
    /** True when the move used the last move of the turn and the other player moves next. */
    val turnEnded: Boolean,
    /** True when the move ended the match. */
    val matchOver: Boolean,
) {
    companion object {
        val IGNORED = MatchMove(MatchMoveKind.IGNORED, -1, -1, 0, IntArray(0), turnEnded = false, matchOver = false)
    }
}

/** The two-player modes; each plays by its own [MatchRules]. */
enum class MatchMode {
    /** Territory match ([TerritoryRules]). */
    TERRITORY,

    /** Five-move war ([WarRules]). */
    WAR,
}

/** Score change of one move: [player] gets [points], and the cells of the move go to [owner]. */
class MatchAward(val player: Int, val points: Int, val owner: Int)

/**
 * How a match is scored, how long its turns are and when it ends; [MatchEngine] plays by one of them.
 * A round is one turn of player 0 followed by one turn of player 1; rounds are counted from 0.
 */
sealed class MatchRules {
    /** Moves in each turn of the first round. */
    abstract val movesPerTurn: Int

    /** Moves in each turn of round [round]. */
    abstract fun movesInRound(round: Int): Int

    /** True when the turns of round [round] are tie-break turns. */
    open fun isTieBreak(round: Int): Boolean = false

    /**
     * Who gets the points of a move of [kind] by [mover] on [cell], how many, and who owns [cells], the
     * cells the move revealed or marked as found. [board] is the state after the move.
     */
    abstract fun award(kind: MatchMoveKind, mover: Int, cell: Int, cells: IntArray, board: MinesweeperBoard): MatchAward

    /** True when the match ends right after a move, whatever is left of the turn. */
    abstract fun endsAfterMove(engine: MatchEngine): Boolean

    /** True when the match ends after the last move of a round. */
    abstract fun endsAfterRound(engine: MatchEngine): Boolean
}

/**
 * Rules of the two-player territory match ("Gra o teren"): two players share one board and take turns on
 * the same phone. Whoever owns more cells at the end wins.
 *
 * 1. The board is a free-game board whose start area is already revealed. Those cells are neutral: they
 *    belong to no player.
 * 2. A turn is [movesPerTurn] moves, the same number for both players. A move is revealing a hidden cell
 *    ([MatchEngine.reveal]) or claiming a hidden cell as a mine ([MatchEngine.claim]). Player 0 moves
 *    first.
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
 * @throws IllegalArgumentException when [movesPerTurn] is outside
 *   [MatchEngine.MIN_MOVES_PER_TURN]..[MatchEngine.MAX_MOVES_PER_TURN].
 */
class TerritoryRules(override val movesPerTurn: Int = MatchEngine.DEFAULT_MOVES_PER_TURN) : MatchRules() {

    init {
        require(movesPerTurn in MatchEngine.MIN_MOVES_PER_TURN..MatchEngine.MAX_MOVES_PER_TURN) {
            "moves per turn $movesPerTurn"
        }
    }

    override fun movesInRound(round: Int): Int = movesPerTurn

    override fun award(kind: MatchMoveKind, mover: Int, cell: Int, cells: IntArray, board: MinesweeperBoard): MatchAward =
        when (kind) {
            MatchMoveKind.REVEAL, MatchMoveKind.MINE_FOUND -> MatchAward(mover, cells.size, mover)
            MatchMoveKind.WRONG_CLAIM, MatchMoveKind.EXPLOSION ->
                MatchAward(1 - mover, cells.size, 1 - mover)
            MatchMoveKind.IGNORED -> throw IllegalArgumentException("an ignored move has no award")
        }

    /** Rule 6: the last safe cell is revealed, so the board is solved. */
    override fun endsAfterMove(engine: MatchEngine): Boolean = engine.board.status == GameStatus.WON

    override fun endsAfterRound(engine: MatchEngine): Boolean = false
}

/**
 * Rules of the five-move war ("Wojna pięciu ruchów"): two players share one board on the same phone and
 * score points for what their moves uncover. The higher score wins.
 *
 * 1. The board is a free-game board whose start area is already revealed and neutral.
 * 2. A move is revealing a hidden cell ([MatchEngine.reveal]) or claiming a hidden cell as a mine
 *    ([MatchEngine.claim]). Player 0 makes all of their [MOVES_PER_TURN] moves, then player 1 makes all
 *    of theirs.
 * 3. Revealing a safe cell gives the mover the number of the cell (its [MinesweeperBoard.adjacentMines],
 *    0 for a zero) plus 1 for every other cell the move revealed with it: the zero flood, and zeros that
 *    a lock opened by the move lets spread. A revealed zero that floods n other cells gives n points, a
 *    number k that floods nothing gives k. A cell whose lock is still closed after the move hides its
 *    number, so it counts 0 for itself.
 * 4. A claim is checked at once. On a mine it is correct: [MINE_FOUND_POINTS] points, and the mine is
 *    marked as found. On a safe cell it is wrong: [WRONG_CLAIM_POINTS] points, and the cell is revealed
 *    with its flood, which gives no points.
 * 5. Revealing a mine is an explosion: [EXPLOSION_POINTS] points. The mine is marked as found, and the
 *    war goes on.
 * 6. After both players' moves the higher score wins. On equal scores each player makes one more move,
 *    player 0 then player 1, and this repeats until the scores differ after player 1's move.
 * 7. The war ends at once when the last safe cell is revealed, by a reveal or by a wrong claim, as in a
 *    territory match; mines nobody found by then stay hidden and score for nobody. The higher score wins
 *    then, and equal scores are a draw.
 * 8. Cells a reveal took and a correctly claimed mine belong to the mover, so the board shows them in
 *    the mover's colour; cells of a wrong claim and an exploded mine belong to nobody.
 * 9. Locks, portals, far cells, line cells and rooms work as in a single-player game.
 */
object WarRules : MatchRules() {
    const val MOVES_PER_TURN = 5
    const val TIE_BREAK_MOVES = 1
    const val MINE_FOUND_POINTS = 3
    const val WRONG_CLAIM_POINTS = -3
    const val EXPLOSION_POINTS = -5

    override val movesPerTurn: Int get() = MOVES_PER_TURN

    override fun movesInRound(round: Int): Int = if (round == 0) MOVES_PER_TURN else TIE_BREAK_MOVES

    override fun isTieBreak(round: Int): Boolean = round > 0

    override fun award(kind: MatchMoveKind, mover: Int, cell: Int, cells: IntArray, board: MinesweeperBoard): MatchAward =
        when (kind) {
            MatchMoveKind.REVEAL -> MatchAward(mover, revealPoints(board, cell, cells), mover)
            MatchMoveKind.MINE_FOUND -> MatchAward(mover, MINE_FOUND_POINTS, mover)
            MatchMoveKind.WRONG_CLAIM -> MatchAward(mover, WRONG_CLAIM_POINTS, MatchEngine.NO_OWNER)
            MatchMoveKind.EXPLOSION -> MatchAward(mover, EXPLOSION_POINTS, MatchEngine.NO_OWNER)
            MatchMoveKind.IGNORED -> throw IllegalArgumentException("an ignored move has no award")
        }

    /**
     * Rule 3: the number of the revealed [cell] plus 1 for each other cell in [cells], which include it.
     * The number counts only when the lock of [cell] is open on [board], the state after the move, so the
     * points never tell a number the board still hides.
     */
    fun revealPoints(board: MinesweeperBoard, cell: Int, cells: IntArray): Int =
        (if (board.isLockOpen(cell)) board.adjacentMines(cell) else 0) + cells.size - 1

    /** Rule 7: the last safe cell is revealed, or nothing is left to reveal or to find. */
    override fun endsAfterMove(engine: MatchEngine): Boolean =
        engine.hiddenCount == 0 || engine.board.status == GameStatus.WON

    /** Rule 6: the scores differ after player 1's turn. */
    override fun endsAfterRound(engine: MatchEngine): Boolean = engine.leader >= 0
}

/**
 * A two-player match on one board, played by [rules]: [TerritoryRules] or [WarRules], which describe the
 * modes. The engine checks moves, keeps the turns, the scores and the owner of every cell.
 *
 * A turn is [MatchRules.movesInRound] moves; player 0 starts every round and player 1 ends it. A hidden
 * cell is a present cell that is neither revealed nor a found mine; only hidden cells take moves.
 *
 * The engine never reveals a mine on [board]: a found mine, claimed or exploded, gets a flag there while
 * the board still takes flags, so numbers, chords and the renderer see it as a known mine and the board
 * never loses a life. Once every safe cell is revealed the board is [GameStatus.WON] and takes no more
 * flags; a mine found after that is known through [isFound] only.
 *
 * All moves of the match must go through the engine; [board] must not be changed directly meanwhile.
 *
 * @throws IllegalArgumentException when [board] is not a game in progress without flags and lost lives.
 */
class MatchEngine(val board: MinesweeperBoard, val rules: MatchRules = TerritoryRules()) {

    /** A territory match with [movesPerTurn] moves per turn. */
    constructor(board: MinesweeperBoard, movesPerTurn: Int) : this(board, TerritoryRules(movesPerTurn))

    init {
        require(board.minesPlaced && board.status == GameStatus.PLAYING) { "the board must be opened and in play" }
        require(board.flagCount == 0 && board.detonatedCount == 0) { "the board must have no flags and no lost lives" }
    }

    /** Moves in each turn of the first round. */
    val movesPerTurn: Int get() = rules.movesPerTurn

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

    /** Rounds completed so far; the current turn belongs to round [round]. */
    var round: Int = 0
        private set

    /** Moves in the current turn: [MatchRules.movesInRound] of [round]. */
    var turnMoves: Int = rules.movesInRound(0)
        private set

    /** Moves the current player has left in this turn, 1..[turnMoves] while the match goes on. */
    var movesLeft: Int = turnMoves
        private set

    /** True once the match has ended by its [rules]; no more moves apply then. */
    var isOver: Boolean = false
        private set

    /** True while the current turn is a tie-break turn of [rules]. */
    val isTieBreak: Boolean get() = rules.isTieBreak(round)

    /** Player with the higher score, or -1 while both have the same score; the winner once [isOver]. */
    val leader: Int
        get() = when {
            scores[0] > scores[1] -> 0
            scores[1] > scores[0] -> 1
            else -> -1
        }

    /** Owner of [cell]: a player index, or [NO_OWNER] for hidden cells, neutral cells and cells nobody scored. */
    fun owner(cell: Int): Int = owners[cell]

    /** Score of [player]: cells owned in a territory match, points in a war (maybe negative). */
    fun score(player: Int): Int = scores[player]

    /** True for a mine that was claimed correctly or exploded. */
    fun isFound(cell: Int): Boolean = found[cell]

    /** True for a present cell that is neither revealed nor a found mine; only these take moves. */
    fun isHidden(cell: Int): Boolean =
        cell in 0 until cellCount && board.isPresent(cell) && !board.isRevealed(cell) && !found[cell]

    /**
     * The current player reveals [cell]: a safe cell is revealed with its flood, a mine explodes and is
     * marked as found. Ignored when the match is over or [cell] is not hidden.
     */
    fun reveal(cell: Int): MatchMove {
        if (isOver || !isHidden(cell)) return MatchMove.IGNORED
        if (board.isMine(cell)) return finish(MatchMoveKind.EXPLOSION, cell, takeMine(cell))
        board.reveal(cell)
        return finish(MatchMoveKind.REVEAL, cell, takeRevealed())
    }

    /**
     * The current player claims [cell] as a mine: a mine is marked as found, a safe cell is revealed with
     * its flood. Ignored when the match is over or [cell] is not hidden.
     */
    fun claim(cell: Int): MatchMove {
        if (isOver || !isHidden(cell)) return MatchMove.IGNORED
        if (board.isMine(cell)) return finish(MatchMoveKind.MINE_FOUND, cell, takeMine(cell))
        board.reveal(cell)
        return finish(MatchMoveKind.WRONG_CLAIM, cell, takeRevealed())
    }

    /** Marks the mine [cell] as found and flags it while the board takes flags; returns the cells taken. */
    private fun takeMine(cell: Int): IntArray {
        found[cell] = true
        // A solved board takes no flags; isFound still knows the mine.
        if (board.status == GameStatus.PLAYING) board.toggleFlag(cell)
        hiddenCount--
        return intArrayOf(cell)
    }

    /** Every cell revealed since the last move, in ascending order. */
    private fun takeRevealed(): IntArray {
        var count = 0
        for (cell in 0 until cellCount) {
            if (!known[cell] && board.isRevealed(cell)) count++
        }
        val cells = IntArray(count)
        var k = 0
        for (cell in 0 until cellCount) {
            if (known[cell] || !board.isRevealed(cell)) continue
            known[cell] = true
            cells[k++] = cell
        }
        hiddenCount -= count
        return cells
    }

    /** Scores the move, counts it against the turn and passes the turn on after its last move. */
    private fun finish(kind: MatchMoveKind, cell: Int, cells: IntArray): MatchMove {
        val mover = currentPlayer
        val award = rules.award(kind, mover, cell, cells, board)
        scores[award.player] += award.points
        if (award.owner != NO_OWNER) for (taken in cells) owners[taken] = award.owner
        fun result(turnEnded: Boolean) =
            MatchMove(kind, mover, award.player, award.points, cells, turnEnded, matchOver = isOver)
        if (rules.endsAfterMove(this)) {
            isOver = true
            return result(turnEnded = false)
        }
        movesLeft--
        if (movesLeft > 0) return result(turnEnded = false)
        if (mover == PLAYERS - 1) {
            if (rules.endsAfterRound(this)) {
                isOver = true
                return result(turnEnded = false)
            }
            round++
        }
        currentPlayer = 1 - mover
        turnMoves = rules.movesInRound(round)
        movesLeft = turnMoves
        return result(turnEnded = true)
    }

    companion object {
        const val PLAYERS = 2
        const val NO_OWNER = -1
        const val MIN_MOVES_PER_TURN = 1
        const val MAX_MOVES_PER_TURN = 5
        const val DEFAULT_MOVES_PER_TURN = 1
    }
}
