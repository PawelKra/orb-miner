package com.example.spheresweeper.logic

import kotlin.random.Random

enum class GameStatus { READY, PLAYING, WON, LOST }

/** MINE_HIT: a mine went off but a spare life absorbed it and the game goes on. */
enum class MoveResult { NOTHING, REVEALED, MINE_HIT, WON, LOST }

/**
 * Minesweeper rules on an arbitrary cell graph given by [neighbors]: neighbors[i] lists the cells
 * whose mines the number on i counts. Reveals of zero cells spread over the same lists. With walls
 * and far cells this is [BoardLayout.scopes] rather than plain adjacency, and need not be symmetric.
 *
 * Mines are placed lazily on the first reveal so that the first revealed cell and all of its
 * neighbours are always safe. The board is mutable; callers observe changes through the return
 * values of [reveal], [toggleFlag] and [chord].
 *
 * The player has [lives] lives. Hitting a mine costs one; while lives remain, the mine is marked as
 * detonated ([isDetonated]) and counts as a known mine, like a flag. Losing the last life ends the game.
 * The mine that ends the game is not marked as revealed; it is reported through [explodedCell].
 *
 * Cells outside [present] (the ocean of [BoardLayout]) take no part: they hold no mine, cannot be
 * revealed or flagged, appear in no list and do not count towards the win, which comes once every
 * present safe cell is revealed. By default every cell is present.
 *
 * A cell with a lock ([lockThreshold] above 0) hides its number while fewer than that many cells are
 * revealed ([isLockOpen]). It can be revealed like any cell, but while it is locked a zero on it does
 * not spread and it cannot be chorded, since either would give the number away. When the count reaches
 * the threshold the lock opens; if the cell is revealed and shows 0, its zero spreads at once as on a
 * normal reveal, and locks that this spread opens in turn act the same way. Lock cells never receive
 * mines when the board places them itself.
 *
 * Without [lockRegions] a lock counts the revealed cells of the whole board ([revealedCount]). With
 * them it counts only the revealed cells of its own region: lockRegions[i] is the region of cell i when
 * that region is counted by a lock, -1 otherwise, and every lock cell lies in a counted region. Regions
 * are plain numbers here; [NoGuessGenerator] uses those of [BoardLayout.regionOf] and sets each threshold
 * to the safe cells of the region, so a lock opens once its region is solved. [lockRemaining] gives the
 * cells still missing in either mode.
 */
class MinesweeperBoard(
    val neighbors: Array<IntArray>,
    mineCount: Int,
    private val random: Random = Random.Default,
    lives: Int = 1,
    present: BooleanArray? = null,
    lockThresholds: IntArray? = null,
    lockRegions: IntArray? = null,
) {
    val cellCount: Int = neighbors.size

    private val present: BooleanArray = present?.copyOf() ?: BooleanArray(cellCount) { true }

    /** Cells that take part in the game. */
    val presentCount: Int = this.present.count { it }

    /** Per cell: revealed cells needed before its number shows, 0 for cells without a lock. */
    private val lockThreshold: IntArray = lockThresholds?.copyOf() ?: IntArray(cellCount)

    /** Per cell: region whose revealed cells the locks in it count, -1 outside such regions; null: whole board. */
    private val lockRegion: IntArray? = lockRegions?.copyOf()

    /** Revealed cells per counted region; empty without [lockRegions]. */
    private val regionRevealed: IntArray

    /** Lock cells by rising threshold; without [lockRegions], locks before [nextLock] have been opened. */
    private val lockOrder: IntArray

    private var nextLock = 0

    init {
        require(this.present.size == cellCount) { "present has ${this.present.size} cells, expected $cellCount" }
        require(lockThreshold.size == cellCount) { "lockThresholds has ${lockThreshold.size} cells, expected $cellCount" }
        require(lockRegion == null || lockRegion.size == cellCount) {
            "lockRegions has ${lockRegion?.size} cells, expected $cellCount"
        }
        var regionCount = 0
        for (cell in 0 until cellCount) {
            require(lockThreshold[cell] >= 0) { "cell $cell has negative lock threshold" }
            require(this.present[cell] || lockThreshold[cell] == 0) { "cell $cell is not present but has a lock" }
            if (!this.present[cell]) require(neighbors[cell].isEmpty()) { "cell $cell is not present but has neighbours" }
            for (nb in neighbors[cell]) require(this.present[nb]) { "cell $cell lists cell $nb, which is not present" }
            if (lockRegion != null) {
                val region = lockRegion[cell]
                require(region >= -1) { "cell $cell has lock region $region" }
                require(region < 0 || this.present[cell]) { "cell $cell is not present but has a lock region" }
                require(lockThreshold[cell] == 0 || region >= 0) { "lock cell $cell has no lock region" }
                regionCount = maxOf(regionCount, region + 1)
            }
        }
        regionRevealed = IntArray(regionCount)
        lockOrder = (0 until cellCount).filter { lockThreshold[it] > 0 }
            .sortedWith(compareBy({ lockThreshold[it] }, { it }))
            .toIntArray()
    }

    /** Number of cells with a lock. */
    val lockCount: Int get() = lockOrder.size

    /** Lives at the start of the game, at least 1. */
    val lives: Int = maxOf(1, lives)

    /** Lives not yet lost; 0 once the game is LOST. */
    var livesLeft: Int = this.lives
        private set

    /** Mines that went off without ending the game. */
    var detonatedCount: Int = 0
        private set

    /** -1 if none; otherwise the most recent mine that cost a life without ending the game. */
    var lastDetonatedCell: Int = -1
        private set

    /** Requested count clamped so that the first-click safe zone always fits. */
    var mineCount: Int = mineCount.coerceIn(0, maxOf(0, presentCount - lockOrder.size - 1 - maxDegree(neighbors)))
        private set

    /** READY until the first reveal places the mines. */
    var status: GameStatus = GameStatus.READY
        private set

    var minesPlaced: Boolean = false
        private set

    /** -1 if none; otherwise the mine that ended the game. */
    var explodedCell: Int = -1
        private set

    var flagCount: Int = 0
        private set

    /** mineCount - flagCount - detonatedCount (may be negative). */
    val minesRemaining: Int get() = mineCount - flagCount - detonatedCount

    var revealedCount: Int = 0
        private set

    private val mine = BooleanArray(cellCount)
    private val revealed = BooleanArray(cellCount)
    private val flagged = BooleanArray(cellCount)
    private val detonated = BooleanArray(cellCount)
    private val adjacent = IntArray(cellCount)

    /**
     * Work stack for the iterative flood fill. Every cell is pushed at most once when revealed and a
     * lock cell once more when its lock opens.
     */
    private val stack = IntArray(cellCount + lockOrder.size)
    private var stackTop = 0

    private val isGameOver: Boolean
        get() = status == GameStatus.WON || status == GameStatus.LOST

    fun isMine(cell: Int): Boolean = mine[cell]

    /** False for cells that take no part in the game (ocean). */
    fun isPresent(cell: Int): Boolean = present[cell]

    /** Revealed cells needed before the number of [cell] shows; 0 when it has no lock. */
    fun lockThreshold(cell: Int): Int = lockThreshold[cell]

    /**
     * True when the number of [cell] may be shown: it has no lock or enough cells are revealed, on the
     * whole board or in its region (see [lockRegions]).
     */
    fun isLockOpen(cell: Int): Boolean = lockThreshold[cell] <= lockCounted(cell)

    /** Cells still to reveal before the lock of [cell] opens: 0 when it is open or there is no lock. */
    fun lockRemaining(cell: Int): Int = maxOf(0, lockThreshold[cell] - lockCounted(cell))

    /** Revealed cells the lock of [cell] counts: the whole board, or its region with [lockRegions]. */
    private fun lockCounted(cell: Int): Int {
        if (lockRegion == null) return revealedCount
        val region = lockRegion[cell]
        return if (region >= 0) regionRevealed[region] else revealedCount
    }

    fun isRevealed(cell: Int): Boolean = revealed[cell]

    fun isFlagged(cell: Int): Boolean = flagged[cell]

    /** True for a mine that went off and cost a life while the game continued. */
    fun isDetonated(cell: Int): Boolean = detonated[cell]

    /** Number of mined neighbours. Valid once mines are placed; 0 before. Shown only if [isLockOpen]. */
    fun adjacentMines(cell: Int): Int = adjacent[cell]

    fun flaggedNeighbors(cell: Int): Int {
        var count = 0
        for (nb in neighbors[cell]) {
            if (flagged[nb]) count++
        }
        return count
    }

    /** Neighbours known to be mines: flagged or already detonated. Chording compares this with the number. */
    fun knownMineNeighbors(cell: Int): Int {
        var count = 0
        for (nb in neighbors[cell]) {
            if (flagged[nb] || detonated[nb]) count++
        }
        return count
    }

    /** True when the game is LOST and this cell carries a flag but no mine. */
    fun isWrongFlag(cell: Int): Boolean = status == GameStatus.LOST && flagged[cell] && !mine[cell]

    /** Reveal a covered, unflagged cell. First call places mines (cell + neighbours safe) and sets PLAYING.
     *  Zero cells flood-fill iteratively (ArrayDeque/IntArray stack). Ignored (NOTHING) when game over,
     *  cell revealed, flagged or detonated. A mine costs a life: MINE_HIT while lives remain, LOST with
     *  the last one (all mines then count as shown by the UI). WON if that completes the board, else REVEALED. */
    fun reveal(cell: Int): MoveResult {
        if (isGameOver || !present[cell] || revealed[cell] || flagged[cell] || detonated[cell]) return MoveResult.NOTHING
        if (!minesPlaced) placeMinesAvoiding(cell)
        if (mine[cell]) {
            if (livesLeft > 1) {
                detonate(cell)
                return MoveResult.MINE_HIT
            }
            explode(cell)
            return MoveResult.LOST
        }
        floodReveal(cell)
        return if (checkWin()) MoveResult.WON else MoveResult.REVEALED
    }

    /** Toggle flag on a covered cell. Allowed in READY and PLAYING. Returns true if the flag state changed. */
    fun toggleFlag(cell: Int): Boolean {
        if (isGameOver || !present[cell] || revealed[cell] || detonated[cell]) return false
        if (flagged[cell]) {
            flagged[cell] = false
            flagCount--
        } else {
            flagged[cell] = true
            flagCount++
        }
        return true
    }

    /** Chording on a revealed numbered cell: if knownMineNeighbors == adjacentMines (> 0), reveal every covered,
     *  unflagged, undetonated neighbour (with flood fill). Each mine among them costs a life. Returns NOTHING
     *  if not applicable or nothing changed, LOST when the hits use up the last life (explodedCell = first
     *  such mine), WON if the board is complete, MINE_HIT if a mine cost a life, else REVEALED. */
    fun chord(cell: Int): MoveResult {
        if (!canChord(cell)) return MoveResult.NOTHING
        var firstHit = -1
        var hits = 0
        for (nb in neighbors[cell]) {
            if (revealed[nb] || flagged[nb] || detonated[nb]) continue
            if (mine[nb]) {
                if (firstHit < 0) firstHit = nb
                hits++
            } else {
                floodReveal(nb)
            }
        }
        if (hits >= livesLeft) {
            explode(firstHit)
            return MoveResult.LOST
        }
        for (nb in neighbors[cell]) {
            if (mine[nb] && !flagged[nb] && !detonated[nb]) detonate(nb)
        }
        if (checkWin()) return MoveResult.WON
        return if (hits > 0) MoveResult.MINE_HIT else MoveResult.REVEALED
    }

    /** True if chord(cell) would reveal at least one cell (for haptics/UI). Never on a locked number. */
    fun canChord(cell: Int): Boolean {
        if (status != GameStatus.PLAYING || !revealed[cell] || mine[cell] || !isLockOpen(cell)) return false
        val number = adjacent[cell]
        if (number <= 0 || knownMineNeighbors(cell) != number) return false
        for (nb in neighbors[cell]) {
            if (!revealed[nb] && !flagged[nb] && !detonated[nb]) return true
        }
        return false
    }

    /**
     * Cells that must hold a mine by the number on [cell] alone, for flagging them at once. Does not
     * change the board.
     *
     * The number on [cell] counts the cells of neighbors[cell] (its scope: walls, far range or row
     * included). When the cells of the scope that are not revealed, flagged ones and detonated mines
     * included, are exactly as many as the number, every one of them is a mine. The result then lists
     * those that still need a flag: not flagged and not detonated, in scope order. It is empty when all
     * of them already carry a flag or have gone off, and for a number 0 with nothing covered around it.
     *
     * Returns null when the number gives no such certainty: more cells are covered than the number,
     * or the move does not apply because the game is not PLAYING, [cell] is not revealed, or its lock is
     * still closed (the number is hidden).
     */
    fun autoFlagCells(cell: Int): IntArray? {
        if (status != GameStatus.PLAYING || !revealed[cell] || mine[cell] || !isLockOpen(cell)) return null
        var covered = 0
        var toFlag = 0
        for (nb in neighbors[cell]) {
            if (revealed[nb]) continue
            covered++
            if (!flagged[nb] && !detonated[nb]) toFlag++
        }
        if (covered != adjacent[cell]) return null
        val result = IntArray(toFlag)
        var k = 0
        for (nb in neighbors[cell]) {
            if (!revealed[nb] && !flagged[nb] && !detonated[nb]) result[k++] = nb
        }
        return result
    }

    /**
     * Starts the game on a prepared layout instead of a random one: places mines at exactly [mines]
     * (duplicates count once, [mineCount] becomes their number) and reveals [start] like [reveal].
     * Meant for layouts from [NoGuessGenerator]. A flag on [start] is removed first, since the cell
     * is known to be safe. Must be called before mines are placed.
     *
     * @throws IllegalStateException when mines are already placed.
     * @throws IllegalArgumentException when a cell is out of range or not present, or [start] is
     *   among [mines].
     */
    fun startWith(mines: IntArray, start: Int): MoveResult {
        check(!minesPlaced) { "mines are already placed" }
        require(start in 0 until cellCount && present[start]) { "start $start out of range or not present" }
        for (cell in mines) {
            require(cell in 0 until cellCount && present[cell]) { "mine $cell out of range or not present" }
            require(cell != start) { "start $start is a mine" }
        }
        if (flagged[start]) {
            flagged[start] = false
            flagCount--
        }
        placeMinesAt(mines)
        return reveal(start)
    }

    /** Test hook: place mines at exactly these cells and switch to PLAYING. */
    internal fun placeMinesAt(mineCells: IntArray) {
        mine.fill(false)
        var count = 0
        for (cell in mineCells) {
            if (!mine[cell]) {
                mine[cell] = true
                count++
            }
        }
        mineCount = count
        finishPlacement()
    }

    /**
     * Places [mineCount] mines uniformly among present cells without a lock outside
     * {first} ∪ neighbours(first).
     */
    private fun placeMinesAvoiding(first: Int) {
        val excluded = BooleanArray(cellCount)
        excluded[first] = true
        for (nb in neighbors[first]) excluded[nb] = true

        val candidates = IntArray(cellCount)
        var candidateCount = 0
        for (cell in 0 until cellCount) {
            if (!excluded[cell] && present[cell] && lockThreshold[cell] == 0) {
                candidates[candidateCount] = cell
                candidateCount++
            }
        }

        // Partial Fisher-Yates shuffle: the first `toPlace` entries become a uniform random subset.
        val toPlace = minOf(mineCount, candidateCount)
        for (i in 0 until toPlace) {
            val j = i + random.nextInt(candidateCount - i)
            val tmp = candidates[i]
            candidates[i] = candidates[j]
            candidates[j] = tmp
            mine[candidates[i]] = true
        }
        mineCount = toPlace
        finishPlacement()
    }

    private fun finishPlacement() {
        for (cell in 0 until cellCount) {
            var count = 0
            for (nb in neighbors[cell]) {
                if (mine[nb]) count++
            }
            adjacent[cell] = count
        }
        minesPlaced = true
        status = GameStatus.PLAYING
    }

    /**
     * Reveals [start] (which must be covered, unflagged and mine-free) and, if it has no mined
     * neighbours, iteratively spreads through connected zero cells, revealing their numbered border.
     * Flagged cells are never revealed. A zero on a locked cell does not spread until its lock opens;
     * locks opened by this reveal spread their zeros within the same call.
     */
    private fun floodReveal(start: Int) {
        stackTop = 0
        revealCell(start)
        while (stackTop > 0) {
            val cell = stack[--stackTop]
            if (adjacent[cell] != 0 || !isLockOpen(cell)) continue
            for (nb in neighbors[cell]) {
                if (!revealed[nb] && !flagged[nb] && !mine[nb]) revealCell(nb)
            }
        }
    }

    /** Marks [cell] revealed, queues it for spreading and opens the locks the new count reaches. */
    private fun revealCell(cell: Int) {
        revealed[cell] = true
        revealedCount++
        stack[stackTop++] = cell
        if (lockRegion != null) {
            val region = lockRegion[cell]
            if (region < 0) return
            val count = ++regionRevealed[region]
            // Counts grow by one, so a lock opens exactly when its region's count equals its threshold.
            for (opened in lockOrder) {
                if (lockRegion[opened] == region && lockThreshold[opened] == count) spreadOpened(opened)
            }
            return
        }
        while (nextLock < lockOrder.size && lockThreshold[lockOrder[nextLock]] <= revealedCount) {
            spreadOpened(lockOrder[nextLock++])
        }
    }

    /** A revealed zero held back by the lock of [opened], which has just opened, spreads now. */
    private fun spreadOpened(opened: Int) {
        if (revealed[opened] && adjacent[opened] == 0) stack[stackTop++] = opened
    }

    /** A mine went off but a spare life absorbs it. */
    private fun detonate(cell: Int) {
        detonated[cell] = true
        detonatedCount++
        livesLeft--
        lastDetonatedCell = cell
    }

    private fun explode(cell: Int) {
        explodedCell = cell
        livesLeft = 0
        status = GameStatus.LOST
    }

    private fun checkWin(): Boolean {
        if (revealedCount == presentCount - mineCount) {
            status = GameStatus.WON
            return true
        }
        return false
    }

    private companion object {
        fun maxDegree(neighbors: Array<IntArray>): Int {
            var max = 0
            for (list in neighbors) {
                if (list.size > max) max = list.size
            }
            return max
        }
    }
}
