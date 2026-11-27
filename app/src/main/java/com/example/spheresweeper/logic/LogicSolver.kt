package com.example.spheresweeper.logic

/**
 * Plays a board the way a careful human does and reports how far that gets without guessing.
 *
 * The solver knows the true layout only to read the number of a cell once that cell is revealed, the
 * same information the player gets. [neighbors] has the meaning of [MinesweeperBoard.neighbors]:
 * neighbors[i] lists the cells whose mines the number on i counts, and reveals of zero cells spread
 * over the same lists.
 *
 * Every revealed cell with covered cells in its list gives a constraint: its unknown cells U hold
 * exactly r mines, where r is the number minus the mines already marked in the list. Only these rules
 * are used, with no enumeration of layouts:
 *
 * 1. One constraint: r == 0 makes all of U safe, r == |U| makes all of U mines.
 * 2. Two constraints sharing unknown cells: the shared part holds between
 *    max(0, rA - |A only|, rB - |B only|) and min(|shared|, rA, rB) mines. A part whose mine count is
 *    forced to 0 or to its size is resolved. This covers the subset rule as a special case.
 * 3. The mine counter: no mines left makes every unknown cell safe, as many mines as unknown cells
 *    makes them all mines.
 *
 * Safe cells are revealed (zeros spread), mines are marked. Constraints whose cells changed are kept
 * in work queues, so a call to [run] only looks at what changed. Pairs are only formed between
 * constraints that share an unknown cell.
 *
 * Cells outside [present] (ocean) are never unknown and never count, also not for the mine counter.
 *
 * Locks follow [MinesweeperBoard]: the number of a cell with lock threshold T is not used while fewer
 * than T cells are revealed, a zero on such a cell does not spread, and when the count reaches T a
 * revealed zero spreads at once. Its constraint joins the rules from then on.
 *
 * The instance can be reused: [load] starts over with a new layout. [moveMine] changes the layout under
 * unknown cells while keeping everything deduced so far, which lets a generator repair a layout and
 * continue from where the solver got stuck.
 */
class LogicSolver(val neighbors: Array<IntArray>, present: BooleanArray? = null) {
    val cellCount: Int = neighbors.size

    private val present: BooleanArray = present?.copyOf() ?: BooleanArray(cellCount) { true }

    /** Cells that take part in the game. */
    val presentCount: Int = this.present.count { it }

    /** Cells whose lists contain c are watchers[watchStart[c] until watchStart[c + 1]]. */
    private val watchStart = IntArray(cellCount + 1)
    private val watchers: IntArray

    init {
        for (list in neighbors) {
            for (cell in list) watchStart[cell + 1]++
        }
        for (cell in 0 until cellCount) watchStart[cell + 1] += watchStart[cell]
        watchers = IntArray(watchStart[cellCount])
        val fill = watchStart.copyOf(cellCount)
        for (owner in 0 until cellCount) {
            for (cell in neighbors[owner]) watchers[fill[cell]++] = owner
        }
    }

    private val mine = BooleanArray(cellCount)
    private val state = ByteArray(cellCount)

    /** Mines in the list of each cell, from the true layout. */
    private val number = IntArray(cellCount)

    /** Unknown and marked cells in the list of each cell. */
    private val unknownIn = IntArray(cellCount)
    private val markedIn = IntArray(cellCount)

    /** Sequence number of the step that resolved each cell, -1 while unknown. */
    private val order = IntArray(cellCount)
    private var steps = 0

    /** Constraints to check alone; each cell at most once (stack). */
    private val singleStack = IntArray(cellCount)
    private val inSingle = BooleanArray(cellCount)
    private var singleTop = 0

    /** Constraints to pair with their partners; each cell at most once (ring buffer). */
    private val pairQueue = IntArray(cellCount)
    private val inPair = BooleanArray(cellCount)
    private var pairHead = 0
    private var pairSize = 0

    /** Each cell is pushed once when revealed and a lock cell once more when its lock opens. */
    private val floodStack = IntArray(2 * cellCount)
    private var floodTop = 0

    /** Per cell: revealed cells needed before its number is used, 0 without a lock. */
    private val lockThreshold = IntArray(cellCount)

    /** Lock cells by rising threshold; those before [nextLock] are open. */
    private var lockOrder = IntArray(0)
    private var nextLock = 0

    /** Stamps for the unknown cells of the first constraint of a pair and for its partners. */
    private val markA = IntArray(cellCount)
    private val seen = IntArray(cellCount)
    private var stamp = 0

    /** Stamps for the cells of the second constraint of a pair. */
    private val markB = IntArray(cellCount)
    private var stampB = 0

    /** Cells resolved by one pair, applied after the pair has been fully read. */
    private val safeBuffer = IntArray(cellCount)
    private val mineBuffer = IntArray(cellCount)

    var mineCount: Int = 0
        private set

    var revealedCount: Int = 0
        private set

    var markedCount: Int = 0
        private set

    val unknownCount: Int get() = presentCount - revealedCount - markedCount

    /** True once every present safe cell is revealed. */
    val isSolved: Boolean get() = revealedCount == presentCount - mineCount

    fun isMine(cell: Int): Boolean = mine[cell]

    fun isUnknown(cell: Int): Boolean = state[cell] == UNKNOWN

    fun isRevealed(cell: Int): Boolean = state[cell] == REVEALED

    fun isMarked(cell: Int): Boolean = state[cell] == MARKED

    fun isPresent(cell: Int): Boolean = present[cell]

    /** True when the number of [cell] may be used: it has no lock or enough cells are revealed. */
    fun isLockOpen(cell: Int): Boolean = lockThreshold[cell] <= revealedCount

    /** Number shown on [cell] once revealed: mines in its list. */
    fun number(cell: Int): Int = number[cell]

    /** Unknown cells in the list of [cell]. */
    fun unknownNeighbors(cell: Int): Int = unknownIn[cell]

    /** Order in which cells were resolved: 0 for the first, -1 while unknown. */
    internal fun resolveOrder(cell: Int): Int = order[cell]

    /**
     * Starts over with the layout [mines] (true = mine) and the locks [lockThresholds] (per cell, 0 =
     * no lock; null = no locks); every present cell becomes unknown.
     */
    fun load(mines: BooleanArray, lockThresholds: IntArray? = null) {
        require(mines.size == cellCount) { "layout has ${mines.size} cells, expected $cellCount" }
        require(lockThresholds == null || lockThresholds.size == cellCount) { "lock thresholds need $cellCount cells" }
        var count = 0
        var locks = 0
        for (cell in 0 until cellCount) {
            require(present[cell] || !mines[cell]) { "cell $cell is not present but holds a mine" }
            mine[cell] = mines[cell]
            if (mines[cell]) count++
            val threshold = lockThresholds?.get(cell) ?: 0
            require(threshold >= 0) { "cell $cell has negative lock threshold" }
            lockThreshold[cell] = threshold
            if (threshold > 0) locks++
        }
        if (lockOrder.size != locks) lockOrder = IntArray(locks)
        var k = 0
        for (cell in 0 until cellCount) {
            if (lockThreshold[cell] > 0) lockOrder[k++] = cell
        }
        sortByThreshold(lockOrder)
        nextLock = 0
        mineCount = count
        for (cell in 0 until cellCount) {
            var n = 0
            for (nb in neighbors[cell]) {
                if (mine[nb]) n++
            }
            number[cell] = n
            unknownIn[cell] = neighbors[cell].size
        }
        markedIn.fill(0)
        for (cell in 0 until cellCount) state[cell] = if (present[cell]) UNKNOWN else ABSENT
        order.fill(-1)
        steps = 0
        revealedCount = 0
        markedCount = 0
        inSingle.fill(false)
        singleTop = 0
        inPair.fill(false)
        pairHead = 0
        pairSize = 0
    }

    /** Reveals a safe unknown [cell] given from outside (the start cell); zeros spread as in the game. */
    fun reveal(cell: Int) {
        require(state[cell] == UNKNOWN) { "cell $cell is not unknown or not present" }
        require(!mine[cell]) { "cell $cell is a mine" }
        flood(cell)
    }

    /**
     * Applies the rules until nothing more follows. Returns [isSolved]. Can be called again after
     * [reveal] or [moveMine] to continue from the current knowledge.
     */
    fun run(): Boolean {
        while (true) {
            if (singleTop > 0) {
                val cell = singleStack[--singleTop]
                inSingle[cell] = false
                applySingle(cell)
                continue
            }
            if (pairSize > 0) {
                val cell = pairQueue[pairHead]
                pairHead = if (pairHead + 1 == cellCount) 0 else pairHead + 1
                pairSize--
                inPair[cell] = false
                applyPairs(cell)
                continue
            }
            if (applyCounter()) continue
            return isSolved
        }
    }

    /**
     * Moves the mine under unknown cell [from] to unknown safe cell [to]. Knowledge stays valid
     * because both cells are unknown; numbers of revealed cells around them change and those cells
     * are queued again.
     */
    fun moveMine(from: Int, to: Int) {
        require(state[from] == UNKNOWN && state[to] == UNKNOWN) { "cells $from, $to must be unknown" }
        require(mine[from] && !mine[to]) { "cell $from must be a mine and $to safe" }
        mine[from] = false
        mine[to] = true
        for (k in watchStart[from] until watchStart[from + 1]) {
            val w = watchers[k]
            number[w]--
            if (state[w] == REVEALED) enqueue(w)
        }
        for (k in watchStart[to] until watchStart[to + 1]) {
            val w = watchers[k]
            number[w]++
            if (state[w] == REVEALED) enqueue(w)
        }
    }

    private fun applySingle(cell: Int) {
        if (state[cell] != REVEALED || unknownIn[cell] == 0 || !isLockOpen(cell)) return
        val left = number[cell] - markedIn[cell]
        if (left == 0) {
            for (nb in neighbors[cell]) {
                if (state[nb] == UNKNOWN) flood(nb)
            }
        } else if (left == unknownIn[cell]) {
            for (nb in neighbors[cell]) {
                if (state[nb] == UNKNOWN) markMine(nb)
            }
        }
    }

    /**
     * Pairs the constraint of [a] with every revealed cell that shares an unknown cell with it. Stops at
     * the first pair that resolves something and queues [a] again for the partners not yet checked.
     */
    private fun applyPairs(a: Int) {
        if (state[a] != REVEALED || unknownIn[a] == 0 || !isLockOpen(a)) return
        val leftA = number[a] - markedIn[a]
        val s = nextStamp()
        for (nb in neighbors[a]) {
            if (state[nb] == UNKNOWN) markA[nb] = s
        }
        seen[a] = s
        for (cell in neighbors[a]) {
            if (state[cell] != UNKNOWN) continue
            for (k in watchStart[cell] until watchStart[cell + 1]) {
                val b = watchers[k]
                if (seen[b] == s) continue
                seen[b] = s
                if (state[b] != REVEALED || unknownIn[b] == 0 || !isLockOpen(b)) continue
                if (checkPair(a, leftA, b, s)) {
                    enqueuePair(a)
                    return
                }
            }
        }
    }

    /** Applies rule 2 to constraints [a] and [b]; [s] stamps the unknown cells of [a] in [markA]. */
    private fun checkPair(a: Int, leftA: Int, b: Int, s: Int): Boolean {
        var shared = 0
        for (nb in neighbors[b]) {
            if (state[nb] == UNKNOWN && markA[nb] == s) shared++
        }
        val onlyA = unknownIn[a] - shared
        val onlyB = unknownIn[b] - shared
        val leftB = number[b] - markedIn[b]
        val low = maxOf(0, leftA - onlyA, leftB - onlyB)
        val high = minOf(shared, leftA, leftB)
        if (low > high) return false

        val onlyASafe = onlyA > 0 && leftA - low == 0
        val onlyAMines = onlyA > 0 && leftA - high == onlyA
        val onlyBSafe = onlyB > 0 && leftB - low == 0
        val onlyBMines = onlyB > 0 && leftB - high == onlyB
        val sharedSafe = shared > 0 && high == 0
        val sharedMines = shared > 0 && low == shared
        if (!(onlyASafe || onlyAMines || onlyBSafe || onlyBMines || sharedSafe || sharedMines)) return false

        var safeCount = 0
        var mineCountFound = 0
        if (onlyASafe || onlyAMines) {
            val sb = nextStampB()
            for (nb in neighbors[b]) markB[nb] = sb
            for (nb in neighbors[a]) {
                if (state[nb] != UNKNOWN || markB[nb] == sb) continue
                if (onlyASafe) safeBuffer[safeCount++] = nb else mineBuffer[mineCountFound++] = nb
            }
        }
        for (nb in neighbors[b]) {
            if (state[nb] != UNKNOWN) continue
            if (markA[nb] == s) {
                if (sharedSafe) safeBuffer[safeCount++] = nb else if (sharedMines) mineBuffer[mineCountFound++] = nb
            } else {
                if (onlyBSafe) safeBuffer[safeCount++] = nb else if (onlyBMines) mineBuffer[mineCountFound++] = nb
            }
        }
        for (i in 0 until mineCountFound) {
            if (state[mineBuffer[i]] == UNKNOWN) markMine(mineBuffer[i])
        }
        for (i in 0 until safeCount) {
            if (state[safeBuffer[i]] == UNKNOWN) flood(safeBuffer[i])
        }
        return true
    }

    /** Rule 3, the mine counter. */
    private fun applyCounter(): Boolean {
        val unknown = unknownCount
        if (unknown == 0) return false
        val left = mineCount - markedCount
        if (left == 0) {
            for (cell in 0 until cellCount) {
                if (state[cell] == UNKNOWN) flood(cell)
            }
            return true
        }
        if (left == unknown) {
            for (cell in 0 until cellCount) {
                if (state[cell] == UNKNOWN) markMine(cell)
            }
            return true
        }
        return false
    }

    /**
     * Reveals the safe unknown [start] and spreads through zero cells, like [MinesweeperBoard]: a zero
     * under a closed lock waits, and locks opened on the way spread their revealed zeros.
     */
    private fun flood(start: Int) {
        floodTop = 0
        setRevealed(start)
        while (floodTop > 0) {
            val cell = floodStack[--floodTop]
            if (number[cell] != 0 || !isLockOpen(cell)) continue
            for (nb in neighbors[cell]) {
                if (state[nb] == UNKNOWN) setRevealed(nb)
            }
        }
    }

    /** Reveals [cell], queues it for spreading in [flood] and opens the locks the new count reaches. */
    private fun setRevealed(cell: Int) {
        check(!mine[cell]) { "deduced cell $cell as safe but it is a mine" }
        state[cell] = REVEALED
        order[cell] = steps++
        revealedCount++
        floodStack[floodTop++] = cell
        for (k in watchStart[cell] until watchStart[cell + 1]) {
            val w = watchers[k]
            unknownIn[w]--
            if (state[w] == REVEALED) enqueue(w)
        }
        if (unknownIn[cell] > 0) enqueue(cell)
        while (nextLock < lockOrder.size && lockThreshold[lockOrder[nextLock]] <= revealedCount) {
            val opened = lockOrder[nextLock++]
            if (state[opened] != REVEALED) continue
            if (unknownIn[opened] > 0) enqueue(opened)
            if (number[opened] == 0) floodStack[floodTop++] = opened
        }
    }

    private fun markMine(cell: Int) {
        check(mine[cell]) { "deduced cell $cell as a mine but it is safe" }
        state[cell] = MARKED
        order[cell] = steps++
        markedCount++
        for (k in watchStart[cell] until watchStart[cell + 1]) {
            val w = watchers[k]
            unknownIn[w]--
            markedIn[w]++
            if (state[w] == REVEALED) enqueue(w)
        }
    }

    private fun enqueue(cell: Int) {
        if (!inSingle[cell]) {
            inSingle[cell] = true
            singleStack[singleTop++] = cell
        }
        enqueuePair(cell)
    }

    private fun enqueuePair(cell: Int) {
        if (inPair[cell]) return
        inPair[cell] = true
        var tail = pairHead + pairSize
        if (tail >= cellCount) tail -= cellCount
        pairQueue[tail] = cell
        pairSize++
    }

    private fun nextStamp(): Int {
        if (stamp == Int.MAX_VALUE) {
            markA.fill(0)
            seen.fill(0)
            stamp = 0
        }
        return ++stamp
    }

    private fun nextStampB(): Int {
        if (stampB == Int.MAX_VALUE) {
            markB.fill(0)
            stampB = 0
        }
        return ++stampB
    }

    /** Sorts [cells] by rising lock threshold, then by index (insertion sort; there are few locks). */
    private fun sortByThreshold(cells: IntArray) {
        for (i in 1 until cells.size) {
            val cell = cells[i]
            var j = i - 1
            while (j >= 0 && lockThreshold[cells[j]] > lockThreshold[cell]) {
                cells[j + 1] = cells[j]
                j--
            }
            cells[j + 1] = cell
        }
    }

    companion object {
        private const val UNKNOWN: Byte = 0
        private const val REVEALED: Byte = 1
        private const val MARKED: Byte = 2

        /** Not present (ocean): never unknown, revealed or marked. */
        private const val ABSENT: Byte = 3

        /**
         * Solves [mines] on [neighbors] from the revealed [start]; true if no guess is needed. [present]
         * and [lockThresholds] are as in the constructor and [load].
         */
        fun solves(
            neighbors: Array<IntArray>,
            mines: BooleanArray,
            start: Int,
            present: BooleanArray? = null,
            lockThresholds: IntArray? = null,
        ): Boolean {
            val solver = LogicSolver(neighbors, present)
            solver.load(mines, lockThresholds)
            solver.reveal(start)
            return solver.run()
        }
    }
}
