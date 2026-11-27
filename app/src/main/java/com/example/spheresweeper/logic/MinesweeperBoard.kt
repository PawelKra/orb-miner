package com.example.spheresweeper.logic

import kotlin.random.Random

enum class GameStatus { READY, PLAYING, WON, LOST }

enum class MoveResult { NOTHING, REVEALED, WON, LOST }

/**
 * Minesweeper rules on an arbitrary cell graph given by [neighbors].
 *
 * Mines are placed lazily on the first reveal so that the first revealed cell and all of its
 * neighbours are always safe. The board is mutable; callers observe changes through the return
 * values of [reveal], [toggleFlag] and [chord].
 *
 * The mine that ends the game is not marked as revealed; it is reported through [explodedCell].
 */
class MinesweeperBoard(
    val neighbors: Array<IntArray>,
    mineCount: Int,
    private val random: Random = Random.Default,
) {
    val cellCount: Int = neighbors.size

    /** Requested count clamped so that the first-click safe zone always fits. */
    var mineCount: Int = mineCount.coerceIn(0, maxOf(0, cellCount - 1 - maxDegree(neighbors)))
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

    /** mineCount - flagCount (may be negative). */
    val minesRemaining: Int get() = mineCount - flagCount

    var revealedCount: Int = 0
        private set

    private val mine = BooleanArray(cellCount)
    private val revealed = BooleanArray(cellCount)
    private val flagged = BooleanArray(cellCount)
    private val adjacent = IntArray(cellCount)

    /** Work stack for the iterative flood fill. Every cell is pushed at most once per fill. */
    private val stack = IntArray(cellCount)

    private val isGameOver: Boolean
        get() = status == GameStatus.WON || status == GameStatus.LOST

    fun isMine(cell: Int): Boolean = mine[cell]

    fun isRevealed(cell: Int): Boolean = revealed[cell]

    fun isFlagged(cell: Int): Boolean = flagged[cell]

    /** Number of mined neighbours. Valid once mines are placed; 0 before. */
    fun adjacentMines(cell: Int): Int = adjacent[cell]

    fun flaggedNeighbors(cell: Int): Int {
        var count = 0
        for (nb in neighbors[cell]) {
            if (flagged[nb]) count++
        }
        return count
    }

    /** True when the game is LOST and this cell carries a flag but no mine. */
    fun isWrongFlag(cell: Int): Boolean = status == GameStatus.LOST && flagged[cell] && !mine[cell]

    /** Reveal a covered, unflagged cell. First call places mines (cell + neighbours safe) and sets PLAYING.
     *  Zero cells flood-fill iteratively (ArrayDeque/IntArray stack). Ignored (NOTHING) when game over,
     *  cell revealed, or flagged. Returns LOST if a mine was hit (all mines then count as shown by the UI),
     *  WON if that completes the board, else REVEALED. */
    fun reveal(cell: Int): MoveResult {
        if (isGameOver || revealed[cell] || flagged[cell]) return MoveResult.NOTHING
        if (!minesPlaced) placeMinesAvoiding(cell)
        if (mine[cell]) {
            explode(cell)
            return MoveResult.LOST
        }
        floodReveal(cell)
        return if (checkWin()) MoveResult.WON else MoveResult.REVEALED
    }

    /** Toggle flag on a covered cell. Allowed in READY and PLAYING. Returns true if the flag state changed. */
    fun toggleFlag(cell: Int): Boolean {
        if (isGameOver || revealed[cell]) return false
        if (flagged[cell]) {
            flagged[cell] = false
            flagCount--
        } else {
            flagged[cell] = true
            flagCount++
        }
        return true
    }

    /** Chording on a revealed numbered cell: if flaggedNeighbors == adjacentMines (> 0), reveal every covered,
     *  unflagged neighbour (with flood fill). Returns NOTHING if not applicable or nothing changed,
     *  LOST if any revealed neighbour was a mine (explodedCell = that mine), WON if board complete, else REVEALED. */
    fun chord(cell: Int): MoveResult {
        if (!canChord(cell)) return MoveResult.NOTHING
        var hitMine = -1
        for (nb in neighbors[cell]) {
            if (revealed[nb] || flagged[nb]) continue
            if (mine[nb]) {
                if (hitMine < 0) hitMine = nb
            } else {
                floodReveal(nb)
            }
        }
        if (hitMine >= 0) {
            explode(hitMine)
            return MoveResult.LOST
        }
        return if (checkWin()) MoveResult.WON else MoveResult.REVEALED
    }

    /** True if chord(cell) would reveal at least one cell (for haptics/UI). */
    fun canChord(cell: Int): Boolean {
        if (status != GameStatus.PLAYING || !revealed[cell] || mine[cell]) return false
        val number = adjacent[cell]
        if (number <= 0 || flaggedNeighbors(cell) != number) return false
        for (nb in neighbors[cell]) {
            if (!revealed[nb] && !flagged[nb]) return true
        }
        return false
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

    /** Places [mineCount] mines uniformly among cells outside {first} ∪ neighbours(first). */
    private fun placeMinesAvoiding(first: Int) {
        val excluded = BooleanArray(cellCount)
        excluded[first] = true
        for (nb in neighbors[first]) excluded[nb] = true

        val candidates = IntArray(cellCount)
        var candidateCount = 0
        for (cell in 0 until cellCount) {
            if (!excluded[cell]) {
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
     * Flagged cells are never revealed.
     */
    private fun floodReveal(start: Int) {
        var top = 0
        revealed[start] = true
        revealedCount++
        stack[top++] = start
        while (top > 0) {
            val cell = stack[--top]
            if (adjacent[cell] != 0) continue
            for (nb in neighbors[cell]) {
                if (!revealed[nb] && !flagged[nb] && !mine[nb]) {
                    revealed[nb] = true
                    revealedCount++
                    stack[top++] = nb
                }
            }
        }
    }

    private fun explode(cell: Int) {
        explodedCell = cell
        status = GameStatus.LOST
    }

    private fun checkWin(): Boolean {
        if (revealedCount == cellCount - mineCount) {
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
