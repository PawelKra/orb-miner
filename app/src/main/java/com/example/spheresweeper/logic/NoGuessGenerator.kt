package com.example.spheresweeper.logic

import kotlin.random.Random

/** A mine layout that [LogicSolver] clears from [start] without guessing. */
class NoGuessLayout(
    /** First cell to reveal. It and every cell in its list are mine-free, so it shows 0. */
    val start: Int,
    /** Mine cells, sorted ascending. */
    val mines: IntArray,
    /** Repair steps taken over all drawn layouts; each step moves one or more mines. */
    val repairs: Int,
    /** Layouts drawn from scratch after the first one. */
    val redraws: Int,
    /**
     * Per cell: lock threshold for [MinesweeperBoard] and [LogicSolver] (revealed cells needed before the
     * number shows), 0 for cells without a lock.
     */
    val lockThresholds: IntArray,
)

/**
 * Builds layouts that never force a guess: [LogicSolver] reveals every safe cell starting from the
 * chosen start cell.
 *
 * Mines are first placed uniformly outside the start cell and its list. When the solver gets stuck,
 * one stuck constraint is picked (a revealed cell whose unknown cells U hold r mines, 0 < r < |U|) and
 * U is made all safe or all mines, whichever moves fewer mines. The moved mines go to, or come from,
 * unknown cells outside U, preferring cells no revealed number sees, so that no known number changes.
 * Only unknown cells change, so the mine count and the start area stay as they are and the solver
 * continues from its current knowledge. A layout changed this way is solved again from the start
 * before it is accepted, so the result never depends on numbers that changed during repairs. After
 * too many repairs the layout is drawn again.
 *
 * On boards with ocean the coast leaves narrow necks of land, and a mine in a neck hides the land
 * behind it from every number. When no revealed number sees an unknown cell any more, such a blocking
 * mine is moved onto an unknown cell and the layout is solved again from the start (see
 * [Work.unblock]). Boards without ocean keep drawing a new layout instead, so they come out as before.
 *
 * Cells outside the present mask (ocean) are never used. Mine-free cells (portals) never hold a mine,
 * so the solver always walks through a portal to the island behind it; the solver itself does not
 * assume this. Locks are placed here rather than in [BoardLayout] because their thresholds follow the
 * number of safe cells and they must stay out of the start area; see [generate].
 */
object NoGuessGenerator {
    /** Lock thresholds are drawn from this share of the safe cells; see [generate]. */
    const val MIN_LOCK_SHARE = 0.25
    const val MAX_LOCK_SHARE = 0.75

    /** Repair steps allowed on one drawn layout before drawing a new one. */
    const val DEFAULT_MAX_REPAIRS = 200

    /** Drawn layouts before giving up. */
    const val DEFAULT_MAX_LAYOUTS = 1000

    /** Layout for the board [layout]: its ocean is left out and its portals stay mine-free. */
    fun generate(
        layout: BoardLayout,
        mineCount: Int,
        random: Random = Random.Default,
        lockCount: Int = 0,
    ): NoGuessLayout = generate(
        layout.scopes, mineCount, random,
        present = layout.presentMask(), mineFree = layout.portalMask(), lockCount = lockCount,
    )

    /**
     * Returns a layout of exactly [mineCount] mines on [neighbors] (lists as in [MinesweeperBoard]).
     * The start cell is chosen at random among present cells whose safe area leaves room for all mines.
     * The result depends only on the inputs and the sequence drawn from [random].
     *
     * [present] marks the cells that exist (null: all); the others get no mine, no lock and are never
     * the start. [mineFree] marks cells that never get a mine (null: none).
     *
     * [lockCount] locks go on random present cells outside the start area and outside [mineFree], after
     * the start cell is drawn and before the mines; fewer only when not enough such cells exist. Lock
     * cells never get a mine either, since a lock on a mine would never show anything. Each threshold
     * is drawn uniformly from [MIN_LOCK_SHARE]..[MAX_LOCK_SHARE] of the safe cells (present cells minus
     * mines): below a quarter the start reveal and the first moves open the lock before it matters, above
     * three quarters the number shows only when its surroundings are mostly solved, so it no longer
     * helps. With no locks requested nothing is drawn for them.
     *
     * @throws IllegalArgumentException when no start cell leaves room for [mineCount] mines.
     * @throws IllegalStateException when [maxLayouts] layouts all fail, which the board sizes of
     *   [BoardSize] with [BoardLayout.generate] do not reach in practice.
     */
    fun generate(
        neighbors: Array<IntArray>,
        mineCount: Int,
        random: Random = Random.Default,
        maxRepairs: Int = DEFAULT_MAX_REPAIRS,
        maxLayouts: Int = DEFAULT_MAX_LAYOUTS,
        present: BooleanArray? = null,
        mineFree: BooleanArray? = null,
        lockCount: Int = 0,
    ): NoGuessLayout {
        val cellCount = neighbors.size
        require(mineCount >= 0) { "negative mine count $mineCount" }
        require(lockCount >= 0) { "negative lock count $lockCount" }
        require(present == null || present.size == cellCount) { "present needs $cellCount cells" }
        require(mineFree == null || mineFree.size == cellCount) { "mineFree needs $cellCount cells" }
        val work = Work(neighbors, present, mineFree)
        val starts = IntArray(cellCount)
        var startCount = 0
        for (cell in 0 until cellCount) {
            if (work.isPresent(cell) && work.room(cell) - lockCount >= mineCount) starts[startCount++] = cell
        }
        require(startCount > 0) { "$mineCount mines do not fit next to any start area on $cellCount cells" }

        val solver = work.solver
        val layout = BooleanArray(cellCount)
        val safeCells = work.presentCount - mineCount
        val lowThreshold = maxOf(1, ceilShare(safeCells, MIN_LOCK_SHARE))
        val highThreshold = maxOf(lowThreshold, (safeCells * MAX_LOCK_SHARE).toInt())
        val locks: IntArray? = if (lockCount > 0) IntArray(cellCount) else null
        var repairs = 0
        for (attempt in 0 until maxLayouts) {
            val start = starts[random.nextInt(startCount)]
            if (locks != null) work.placeLocks(locks, start, lockCount, lowThreshold, highThreshold, random)
            work.placeRandom(layout, start, mineCount, random)
            solver.load(layout, locks)
            solver.reveal(start)
            var steps = 0
            var changed = false
            while (true) {
                if (solver.run()) {
                    if (!changed) {
                        return NoGuessLayout(start, work.mineCells(), repairs, attempt, locks?.copyOf() ?: IntArray(cellCount))
                    }
                    // Replay from the start on the final layout, so that every deduction uses final numbers.
                    for (cell in 0 until cellCount) layout[cell] = solver.isMine(cell)
                    solver.load(layout, locks)
                    solver.reveal(start)
                    changed = false
                    continue
                }
                if (steps >= maxRepairs) break
                if (work.repair(random)) {
                    steps++
                    repairs++
                    changed = true
                    continue
                }
                if (!work.hasOcean || !work.unblock(layout, random)) break
                steps++
                repairs++
                solver.load(layout, locks)
                solver.reveal(start)
                changed = false
            }
        }
        throw IllegalStateException("no layout without guessing after $maxLayouts attempts")
    }

    /**
     * Most mines [generate] accepts on [layout] with [lockCount] locks: the room the best start cell
     * leaves for mines (present cells that are not mine-free, outside the start cell and its list),
     * minus the locks. Never below 0.
     */
    fun maxMineCount(layout: BoardLayout, lockCount: Int = 0): Int {
        val present = layout.presentMask()
        val mineFree = layout.portalMask()
        val mineable = present.indices.count { present[it] && !mineFree[it] }
        var best = 0
        for (cell in present.indices) {
            if (present[cell]) best = maxOf(best, mineable - startArea(layout.scopes, mineFree, cell) - lockCount)
        }
        return best
    }

    /** Cells of {start} ∪ neighbors[start] that could hold a mine, that is, are not [mineFree]. */
    private fun startArea(neighbors: Array<IntArray>, mineFree: BooleanArray, start: Int): Int {
        var area = if (mineFree[start]) 0 else 1
        for (nb in neighbors[start]) {
            if (nb != start && !mineFree[nb]) area++
        }
        return area
    }

    /** Smallest whole number of cells at least [share] of [cells]. */
    private fun ceilShare(cells: Int, share: Double): Int {
        val exact = cells * share
        val whole = exact.toInt()
        return if (whole < exact) whole + 1 else whole
    }

    /** Solver and scratch arrays reused across repairs and drawn layouts. */
    private class Work(
        private val neighbors: Array<IntArray>,
        present: BooleanArray?,
        mineFree: BooleanArray?,
    ) {
        private val cellCount = neighbors.size
        private val present: BooleanArray = present?.copyOf() ?: BooleanArray(cellCount) { true }
        private val mineFree: BooleanArray = mineFree?.copyOf() ?: BooleanArray(cellCount)
        val presentCount = this.present.count { it }
        val solver = LogicSolver(neighbors, this.present)
        private val excluded = BooleanArray(cellCount)
        private val candidates = IntArray(cellCount)

        /** Cells that never get a mine in the current draw: [mineFree] cells and lock cells. */
        private val noMine = this.mineFree.copyOf()

        /** Present cells that may hold a mine. */
        private val mineable = (0 until cellCount).count { this.present[it] && !this.mineFree[it] }

        fun isPresent(cell: Int): Boolean = present[cell]

        val hasOcean: Boolean = presentCount < cellCount

        /** Cells that may hold a mine when [start] is the start: present, not mine-free, outside its area. */
        fun room(start: Int): Int = mineable - startArea(neighbors, mineFree, start)

        /**
         * Puts up to [lockCount] locks on random present cells outside {start} ∪ neighbors[start] and
         * outside the mine-free cells, each with a threshold drawn from [low]..[high]. Cells are drawn
         * first, then the thresholds in the same order. Writes [locks] and marks the lock cells mine-free.
         */
        fun placeLocks(locks: IntArray, start: Int, lockCount: Int, low: Int, high: Int, random: Random) {
            locks.fill(0)
            excluded.fill(false)
            excluded[start] = true
            for (nb in neighbors[start]) excluded[nb] = true
            var count = 0
            for (cell in 0 until cellCount) {
                if (present[cell] && !mineFree[cell] && !excluded[cell]) candidates[count++] = cell
            }
            val placed = minOf(lockCount, count)
            for (i in 0 until placed) swapPick(candidates, i, count, random)
            for (i in 0 until placed) locks[candidates[i]] = low + random.nextInt(high - low + 1)
            for (cell in 0 until cellCount) noMine[cell] = mineFree[cell] || locks[cell] > 0
        }

        /** Unknown cells that some revealed number sees. */
        private val watched = BooleanArray(cellCount)

        /** Unknown cells of the constraint being repaired. */
        private val target = BooleanArray(cellCount)

        /** Swap candidates that no revealed number sees, and the rest. */
        private val unwatched = IntArray(cellCount)
        private val nearby = IntArray(cellCount)
        private var unwatchedCount = 0
        private var nearbyCount = 0

        /** Places [mineCount] mines uniformly on present cells outside [start], its list and [noMine]. */
        fun placeRandom(layout: BooleanArray, start: Int, mineCount: Int, random: Random) {
            layout.fill(false)
            excluded.fill(false)
            excluded[start] = true
            for (nb in neighbors[start]) excluded[nb] = true
            var count = 0
            for (cell in 0 until cellCount) {
                if (!excluded[cell] && present[cell] && !noMine[cell]) candidates[count++] = cell
            }
            // Partial Fisher-Yates shuffle: the first mineCount picks form a uniform random subset.
            for (i in 0 until mineCount) layout[swapPick(candidates, i, count, random)] = true
        }

        fun mineCells(): IntArray {
            var count = 0
            for (cell in 0 until cellCount) {
                if (solver.isMine(cell)) count++
            }
            val result = IntArray(count)
            var i = 0
            for (cell in 0 until cellCount) {
                if (solver.isMine(cell)) result[i++] = cell
            }
            return result
        }

        /**
         * Makes one stuck constraint resolvable. Among revealed cells with an open lock (or none) and
         * unknown cells in their list, picks one at random among those needing the fewest mine moves,
         * then empties or fills its unknown cells. Filling is skipped when one of those cells must stay
         * mine-free. Returns false when there is no such cell or not enough cells to swap with.
         */
        fun repair(random: Random): Boolean {
            watched.fill(false)
            var chosen = -1
            var bestCost = Int.MAX_VALUE
            var ties = 0
            for (cell in 0 until cellCount) {
                if (!solver.isRevealed(cell) || solver.unknownNeighbors(cell) == 0 || !solver.isLockOpen(cell)) continue
                var unknown = 0
                var mines = 0
                for (nb in neighbors[cell]) {
                    if (!solver.isUnknown(nb)) continue
                    watched[nb] = true
                    unknown++
                    if (solver.isMine(nb)) mines++
                }
                val cost = minOf(mines, unknown - mines)
                if (cost < bestCost) {
                    bestCost = cost
                    chosen = cell
                    ties = 1
                } else if (cost == bestCost) {
                    ties++
                    if (random.nextInt(ties) == 0) chosen = cell
                }
            }
            if (chosen < 0) return false

            var unknown = 0
            var mines = 0
            var fixedSafe = false
            for (nb in neighbors[chosen]) {
                if (!solver.isUnknown(nb)) continue
                target[nb] = true
                unknown++
                if (solver.isMine(nb)) mines++
                if (noMine[nb]) fixedSafe = true
            }
            val emptyFirst = mines <= unknown - mines
            val done = if (fixedSafe) {
                empty(chosen, mines, random)
            } else if (emptyFirst) {
                empty(chosen, mines, random) || fill(chosen, unknown - mines, random)
            } else {
                fill(chosen, unknown - mines, random) || empty(chosen, mines, random)
            }
            for (nb in neighbors[chosen]) target[nb] = false
            return done
        }

        /**
         * For a solver stuck with no revealed number seeing an unknown cell: writes the solver's current
         * mines into [layout], then moves one random marked mine that has unknown cells in its list onto
         * a random safe unknown cell that may hold a mine. Returns false when either cell is missing.
         * The caller solves [layout] again from the start, since a marked mine changed.
         */
        fun unblock(layout: BooleanArray, random: Random): Boolean {
            var blockers = 0
            var spots = 0
            for (cell in 0 until cellCount) {
                layout[cell] = solver.isMine(cell)
                if (solver.isMarked(cell) && solver.unknownNeighbors(cell) > 0) unwatched[blockers++] = cell
                if (solver.isUnknown(cell) && !solver.isMine(cell) && !noMine[cell]) nearby[spots++] = cell
            }
            if (blockers == 0 || spots == 0) return false
            layout[unwatched[random.nextInt(blockers)]] = false
            layout[nearby[random.nextInt(spots)]] = true
            return true
        }

        /** Moves every mine in the unknown cells of [cell] to safe unknown cells elsewhere. */
        private fun empty(cell: Int, mines: Int, random: Random): Boolean {
            if (!collect(wantMine = false, needed = mines)) return false
            var next = 0
            for (nb in neighbors[cell]) {
                if (target[nb] && solver.isMine(nb)) {
                    solver.moveMine(nb, pick(next++, random))
                }
            }
            return true
        }

        /** Fills every safe unknown cell of [cell] with a mine taken from unknown cells elsewhere. */
        private fun fill(cell: Int, safe: Int, random: Random): Boolean {
            if (!collect(wantMine = true, needed = safe)) return false
            var next = 0
            for (nb in neighbors[cell]) {
                if (target[nb] && !solver.isMine(nb)) {
                    solver.moveMine(pick(next++, random), nb)
                }
            }
            return true
        }

        /**
         * Gathers unknown cells outside the target with the given mine state, split into cells no
         * revealed number sees ([unwatched]) and the rest ([nearby]). True if there are at least [needed].
         * Safe cells that must stay mine-free are left out.
         */
        private fun collect(wantMine: Boolean, needed: Int): Boolean {
            unwatchedCount = 0
            nearbyCount = 0
            for (c in 0 until cellCount) {
                if (target[c] || !solver.isUnknown(c) || solver.isMine(c) != wantMine) continue
                if (!wantMine && noMine[c]) continue
                if (watched[c]) nearby[nearbyCount++] = c else unwatched[unwatchedCount++] = c
            }
            return unwatchedCount + nearbyCount >= needed
        }

        /** The [index]-th distinct random pick, from [unwatched] while it lasts and then from [nearby]. */
        private fun pick(index: Int, random: Random): Int {
            if (index < unwatchedCount) return swapPick(unwatched, index, unwatchedCount, random)
            return swapPick(nearby, index - unwatchedCount, nearbyCount, random)
        }

        /** Partial Fisher-Yates step: moves a random entry of pool[index until count] to index. */
        private fun swapPick(pool: IntArray, index: Int, count: Int, random: Random): Int {
            val j = index + random.nextInt(count - index)
            val tmp = pool[index]
            pool[index] = pool[j]
            pool[j] = tmp
            return pool[index]
        }
    }
}
