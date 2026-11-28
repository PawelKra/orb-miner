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
     * number shows, on the whole board or in the lock's region), 0 for cells without a lock.
     */
    val lockThresholds: IntArray,
    /**
     * Per cell: the region counted by the locks in it, -1 for cells outside such regions, as
     * [MinesweeperBoard] and [LogicSolver.load] take it. Null when the locks count the whole board.
     */
    val lockRegions: IntArray? = null,
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
 * [Work.unblock]). On boards with rooms a mine on the far cell of a passage, or a row of mines across a
 * room, hides the room behind it in the same way, so these boards move blocking mines too. Boards with
 * neither ocean nor rooms draw a new layout instead and never move a blocking mine, so their draws do
 * not depend on this step.
 *
 * Cells outside the present mask (ocean) are never used. Mine-free cells (portals) never hold a mine,
 * so the solver always walks through a portal to the island behind it; the solver itself does not
 * assume this. Locks are placed here rather than in [BoardLayout] because their thresholds follow the
 * number of safe cells and they must stay out of the start area; see [generate].
 */
object NoGuessGenerator {
    /** Locks that count the whole board draw their thresholds from this share of the safe cells; see [generate]. */
    const val MIN_LOCK_SHARE = 0.25
    const val MAX_LOCK_SHARE = 0.75

    /** Repair steps allowed on one drawn layout before drawing a new one. */
    const val DEFAULT_MAX_REPAIRS = 200

    /** Drawn layouts before giving up. */
    const val DEFAULT_MAX_LAYOUTS = 1000

    /**
     * On boards with rooms: failed layouts in a row with the same lock limit and lock cells before one
     * lock is dropped; see [generate].
     */
    const val LOCK_LAYOUTS = 40

    /**
     * Layout for the board [layout]: its ocean is left out and its portals stay mine-free.
     *
     * Without rooms ([BoardLayout.hasRooms] false) this is [generate] on [BoardLayout.scopes], with locks
     * that count the whole board.
     *
     * With rooms the start cell lies in the root region (depth 0) and the locks guard gates. Up to
     * [lockCount] locks go on the near cells of gates that are bridges ([BoardLayout.isLockableGate]):
     * near cells that are not line cells first, since a lock would hide the line's count, then line
     * cells. Near cells inside the start area are skipped, and so are those whose lock could never open
     * because the cell or a cell of its region in its scope is seen by no other cell at the same or a
     * smaller region depth, or because the region falls apart without the lock cell. When more near
     * cells qualify than [lockCount], the locks are drawn among them; with fewer, the board simply gets
     * fewer locks.
     *
     * Such a lock counts only the revealed cells of its own region ([BoardLayout.regionOf] of the lock
     * cell), and its threshold is the number of safe cells of that region, the lock cell included. So
     * its number shows exactly when the region is solved, and the near cell, which sees across the gate,
     * gives its help towards the next region only then. It is not always the only cell of its region that
     * sees across: a far cell ([BoardLayout.isFar], two-step scope) beside the passage sees through it
     * as well. The threshold follows the mines, so it is counted again from the final layout. The result
     * carries the regions in
     * [NoGuessLayout.lockRegions]; with [lockCount] 0 nothing is drawn for locks and that is null.
     *
     * A drawn near cell is kept only when every present cell can still be seen with the locks drawn so
     * far: numbers spread from the start cell over the scopes, ignoring mines, and a lock cell adds its
     * number only once every cell of its region has been seen. Otherwise a part of the lock's region
     * would be seen only by the lock cell itself (a pocket behind the lock cell, or the whole region
     * when the lock cell is also where its region is entered), so the lock could never open.
     *
     * Locks that still keep layouts from being solved are dropped one at a time: after [LOCK_LAYOUTS]
     * failed layouts in a row, the near cell whose closed lock most often blocked the solver (the lowest
     * cell on a tie) is never drawn again; when no closed lock blocked it, the board gets one lock fewer.
     * Without any lock left the layouts are drawn as with [lockCount] 0, with [DEFAULT_MAX_LAYOUTS]
     * attempts of their own; [NoGuessLayout.lockRegions] then marks no cell but is not null.
     */
    fun generate(
        layout: BoardLayout,
        mineCount: Int,
        random: Random = Random.Default,
        lockCount: Int = 0,
    ): NoGuessLayout {
        val present = layout.presentMask()
        val mineFree = layout.portalMask()
        if (!layout.hasRooms) {
            return generate(
                layout.scopes, mineCount, random, present = present, mineFree = mineFree, lockCount = lockCount,
            )
        }
        return run(
            layout.scopes, mineCount, random, DEFAULT_MAX_REPAIRS, DEFAULT_MAX_LAYOUTS, present, mineFree, lockCount,
            RoomGates(layout),
        )
    }

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
    ): NoGuessLayout = run(neighbors, mineCount, random, maxRepairs, maxLayouts, present, mineFree, lockCount, null)

    /**
     * Most mines [generate] accepts on [layout] with [lockCount] locks: the room the best start cell
     * leaves for mines (present cells that are not mine-free, outside the start cell and its list),
     * minus the locks. With rooms only start cells in the root region count. Never below 0.
     */
    fun maxMineCount(layout: BoardLayout, lockCount: Int = 0): Int {
        val present = layout.presentMask()
        val mineFree = layout.portalMask()
        val mineable = present.indices.count { present[it] && !mineFree[it] }
        val rooms = layout.hasRooms
        var best = 0
        for (cell in present.indices) {
            if (!present[cell] || (rooms && layout.regionDepth(layout.regionOf(cell)) != 0)) continue
            best = maxOf(best, mineable - startArea(layout.scopes, mineFree, cell) - lockCount)
        }
        return best
    }

    /**
     * Both forms of [generate]: [gates] null for locks that count the whole board and any start cell,
     * otherwise for locks on gates and a start in the root region.
     */
    private fun run(
        neighbors: Array<IntArray>,
        mineCount: Int,
        random: Random,
        maxRepairs: Int,
        maxLayouts: Int,
        present: BooleanArray?,
        mineFree: BooleanArray?,
        lockCount: Int,
        gates: RoomGates?,
    ): NoGuessLayout {
        val cellCount = neighbors.size
        require(mineCount >= 0) { "negative mine count $mineCount" }
        require(lockCount >= 0) { "negative lock count $lockCount" }
        require(present == null || present.size == cellCount) { "present needs $cellCount cells" }
        require(mineFree == null || mineFree.size == cellCount) { "mineFree needs $cellCount cells" }
        val work = Work(neighbors, present, mineFree, gates)
        val starts = IntArray(cellCount)
        var startCount = 0
        for (cell in 0 until cellCount) {
            if (!work.isPresent(cell) || (gates != null && !gates.root[cell])) continue
            if (work.room(cell) - lockCount >= mineCount) starts[startCount++] = cell
        }
        require(startCount > 0) { "$mineCount mines do not fit next to any start area on $cellCount cells" }

        val solver = work.solver
        val layout = BooleanArray(cellCount)
        val safeCells = work.presentCount - mineCount
        val lowThreshold = maxOf(1, ceilShare(safeCells, MIN_LOCK_SHARE))
        val highThreshold = maxOf(lowThreshold, (safeCells * MAX_LOCK_SHARE).toInt())
        val locks: IntArray? = if (lockCount > 0) IntArray(cellCount) else null
        // Region locks count their own region; their thresholds are counted from the mines before every load.
        val lockRegions: IntArray? = if (locks != null && gates != null) IntArray(cellCount) else null
        fun load() {
            if (lockRegions != null) work.countRegionThresholds(locks!!, layout)
            solver.load(layout, locks, lockRegions)
        }
        var repairs = 0
        // Locks still drawn on a board with rooms; it only shrinks, see dropLock.
        var lockLimit = if (lockRegions != null) lockCount else 0
        var placed = 0
        // Failed layouts since the lock limit or the lock cells last changed.
        var failures = 0
        var attempt = 0
        while (true) {
            if (lockLimit > 0 && failures >= LOCK_LAYOUTS) {
                lockLimit = work.dropLock(lockLimit, placed)
                failures = 0
            }
            if (lockLimit == 0 && failures >= maxLayouts) break
            val start = starts[random.nextInt(startCount)]
            if (locks != null) {
                if (lockRegions != null) {
                    placed = work.placeGateLocks(locks, lockRegions, start, lockLimit, random)
                } else {
                    work.placeLocks(locks, start, lockCount, lowThreshold, highThreshold, random)
                }
            }
            work.placeRandom(layout, start, mineCount, random)
            load()
            solver.reveal(start)
            var steps = 0
            var changed = false
            while (true) {
                if (solver.run()) {
                    if (!changed) {
                        return NoGuessLayout(
                            start, work.mineCells(), repairs, attempt, locks?.copyOf() ?: IntArray(cellCount),
                            lockRegions?.copyOf(),
                        )
                    }
                    // Replay from the start on the final layout, so that every deduction uses final numbers.
                    for (cell in 0 until cellCount) layout[cell] = solver.isMine(cell)
                    load()
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
                if (!work.canUnblock || !work.unblock(layout, random)) break
                steps++
                repairs++
                load()
                solver.reveal(start)
                changed = false
            }
            if (lockLimit > 0) work.blameLocks(placed)
            failures++
            attempt++
        }
        throw IllegalStateException("no layout without guessing after $attempt attempts")
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

    /**
     * What [generate] needs to know about the regions of a board with rooms: the region of every cell,
     * the cells of the root region and the near cells that may hold a lock.
     */
    private class RoomGates(layout: BoardLayout) {
        val cellCount = layout.scopes.size

        /** [BoardLayout.regionOf] of every cell. */
        val region = IntArray(cellCount) { layout.regionOf(it) }

        /** Present cells of the root region, where the start cell lies. */
        val root = BooleanArray(cellCount) { region[it] >= 0 && layout.regionDepth(region[it]) == 0 }

        /** Present cells per region, indexed by region. */
        val regionSize = IntArray(layout.regionCount).also { sizes ->
            for (r in region) if (r >= 0) sizes[r]++
        }

        /**
         * Near cells of bridge gates whose lock can open, each once, in gate order: first those that
         * are not line cells ([preferredCount] of them), then line cells.
         */
        val lockCells: IntArray

        val preferredCount: Int

        init {
            val scopes = layout.scopes
            val depth = IntArray(cellCount) { if (region[it] >= 0) layout.regionDepth(region[it]) else -1 }
            val preferred = ArrayList<Int>()
            val lines = ArrayList<Int>()
            for ((index, gate) in layout.gates.withIndex()) {
                val near = gate.first
                if (!layout.isLockableGate(index) || near in preferred || near in lines) continue
                if (!canOpen(layout, scopes, depth, near)) continue
                if (layout.isLine(near)) lines.add(near) else preferred.add(near)
            }
            preferredCount = preferred.size
            lockCells = (preferred + lines).toIntArray()
        }

        /**
         * False when a lock on [cell] could never open whatever the mines: its region needs the number of
         * [cell] to be solved. That happens when [cell] or a cell of its region in its scope is seen by
         * no other cell whose region is at most as deep, or when the rest of the region is not connected
         * over [BoardLayout.links] without [cell].
         */
        private fun canOpen(layout: BoardLayout, scopes: Array<IntArray>, depth: IntArray, cell: Int): Boolean {
            val own = region[cell]
            val seen = BooleanArray(cellCount)
            for (other in 0 until cellCount) {
                if (other == cell || depth[other] < 0 || depth[other] > depth[cell]) continue
                for (c in scopes[other]) seen[c] = true
            }
            if (!seen[cell]) return false
            for (c in scopes[cell]) {
                if (region[c] == own && !seen[c]) return false
            }
            val queue = IntArray(cellCount)
            val reached = BooleanArray(cellCount)
            var size = 0
            var first = -1
            for (c in 0 until cellCount) {
                if (region[c] != own || c == cell) continue
                size++
                if (first < 0) first = c
            }
            if (first < 0) return true
            var head = 0
            var tail = 0
            queue[tail++] = first
            reached[first] = true
            while (head < tail) {
                val c = queue[head++]
                for (nb in layout.links[c]) {
                    if (nb == cell || reached[nb] || region[nb] != own) continue
                    reached[nb] = true
                    queue[tail++] = nb
                }
            }
            return tail == size
        }
    }

    /** Solver and scratch arrays reused across repairs and drawn layouts. */
    private class Work(
        private val neighbors: Array<IntArray>,
        present: BooleanArray?,
        mineFree: BooleanArray?,
        private val gates: RoomGates?,
    ) {
        private val cellCount = neighbors.size
        private val present: BooleanArray = present?.copyOf() ?: BooleanArray(cellCount) { true }
        private val mineFree: BooleanArray = mineFree?.copyOf() ?: BooleanArray(cellCount)
        val presentCount = this.present.count { it }
        val solver = LogicSolver(neighbors, this.present)
        private val excluded = BooleanArray(cellCount)
        private val candidates = IntArray(cellCount)

        /** Regions holding a lock, indexed by region (regions are numbered below the cell count). */
        private val lockedRegion = BooleanArray(cellCount)

        /** Cells that never get a mine in the current draw: [mineFree] cells and lock cells. */
        private val noMine = this.mineFree.copyOf()

        /** Present cells that may hold a mine. */
        private val mineable = (0 until cellCount).count { this.present[it] && !this.mineFree[it] }

        fun isPresent(cell: Int): Boolean = present[cell]

        /** True when [unblock] is used: on boards with ocean or rooms, whose necks can cut land off. */
        val canUnblock: Boolean = presentCount < cellCount || gates != null

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

        /** Lock cells of the current draw, in the order drawn ([placeGateLocks] returns how many). */
        private val placedLocks = IntArray(cellCount)

        /** Near cells never drawn again for a lock in this run (see [dropLock]). */
        private val banned = BooleanArray(cellCount)

        /** Per near cell: failed layouts in which its closed lock blocked the solver (see [blameLocks]). */
        private val blame = IntArray(cellCount)

        /** Scratch arrays of [seesAll]. */
        private val seen = BooleanArray(cellCount)
        private val waiting = BooleanArray(cellCount)
        private val lockMark = BooleanArray(cellCount)
        private val seenInRegion = IntArray(cellCount)
        private val sightQueue = IntArray(2 * cellCount)

        /**
         * Puts up to [lockCount] locks on the lock cells of [gates] outside {start} ∪ neighbors[start]
         * and not banned: drawn among the preferred cells first, then among the line cells. A drawn cell
         * is kept only when [seesAll] holds with it and the locks kept before it, otherwise the next one
         * is drawn. Marks the regions of the locks in [lockRegions], sets each lock's threshold to 1 until
         * [countRegionThresholds] counts it and marks the lock cells mine-free. Returns the number of locks.
         */
        fun placeGateLocks(locks: IntArray, lockRegions: IntArray, start: Int, lockCount: Int, random: Random): Int {
            val gates = checkNotNull(gates)
            locks.fill(0)
            excluded.fill(false)
            excluded[start] = true
            for (nb in neighbors[start]) excluded[nb] = true
            var placed = 0
            for (group in 0 until 2) {
                val from = if (group == 0) 0 else gates.preferredCount
                val until = if (group == 0) gates.preferredCount else gates.lockCells.size
                var count = 0
                for (k in from until until) {
                    val cell = gates.lockCells[k]
                    if (!excluded[cell] && !banned[cell]) candidates[count++] = cell
                }
                var drawn = 0
                while (placed < lockCount && drawn < count) {
                    val cell = swapPick(candidates, drawn++, count, random)
                    placedLocks[placed] = cell
                    if (!seesAll(start, placed + 1)) continue
                    locks[cell] = 1
                    placed++
                }
            }
            lockedRegion.fill(false)
            for (cell in 0 until cellCount) {
                if (locks[cell] > 0) lockedRegion[gates.region[cell]] = true
            }
            for (cell in 0 until cellCount) {
                val region = gates.region[cell]
                lockRegions[cell] = if (region >= 0 && lockedRegion[region]) region else -1
            }
            for (cell in 0 until cellCount) noMine[cell] = mineFree[cell] || locks[cell] > 0
            return placed
        }

        /**
         * True when, with region locks on placedLocks[0 until lockTotal], numbers spreading from [start]
         * over the scopes reach every present cell, mines left aside. A lock cell is reached like any
         * cell but adds its own scope only once every present cell of its region is reached, as its lock
         * opens only when its region is solved.
         */
        private fun seesAll(start: Int, lockTotal: Int): Boolean {
            val gates = checkNotNull(gates)
            seen.fill(false)
            waiting.fill(false)
            lockMark.fill(false)
            seenInRegion.fill(0)
            for (k in 0 until lockTotal) lockMark[placedLocks[k]] = true
            var head = 0
            var tail = 0
            seen[start] = true
            seenInRegion[gates.region[start]]++
            sightQueue[tail++] = start
            var seenCount = 1
            while (head < tail) {
                val cell = sightQueue[head++]
                val own = gates.region[cell]
                if (lockMark[cell] && seenInRegion[own] < gates.regionSize[own]) {
                    waiting[cell] = true
                    continue
                }
                for (other in neighbors[cell]) {
                    if (seen[other] || !present[other]) continue
                    seen[other] = true
                    seenCount++
                    sightQueue[tail++] = other
                    val region = gates.region[other]
                    if (++seenInRegion[region] < gates.regionSize[region]) continue
                    // The region is complete: its waiting lock cells add their scopes now.
                    for (k in 0 until lockTotal) {
                        val lock = placedLocks[k]
                        if (waiting[lock] && gates.region[lock] == region) {
                            waiting[lock] = false
                            sightQueue[tail++] = lock
                        }
                    }
                }
            }
            return seenCount == presentCount
        }

        /**
         * Counts a failed layout against the closed locks among the first [placed] lock cells that
         * blocked the solver: revealed, with unknown cells in their scope.
         */
        fun blameLocks(placed: Int) {
            for (k in 0 until placed) {
                val lock = placedLocks[k]
                if (!solver.isRevealed(lock) || solver.isLockOpen(lock)) continue
                if (solver.unknownNeighbors(lock) > 0) blame[lock]++
            }
        }

        /**
         * Drops one lock after [LOCK_LAYOUTS] failed layouts with lock limit [lockLimit], the last of
         * which placed [placed] locks. Bans the most blamed near cell (the lowest on a tie) when any was
         * blamed and keeps the limit; otherwise returns a limit one below the locks placed. Clears the blame.
         */
        fun dropLock(lockLimit: Int, placed: Int): Int {
            var worst = -1
            for (cell in 0 until cellCount) {
                if (blame[cell] > 0 && (worst < 0 || blame[cell] > blame[worst])) worst = cell
            }
            blame.fill(0)
            if (worst >= 0) {
                banned[worst] = true
                return lockLimit
            }
            return maxOf(0, minOf(lockLimit, placed) - 1)
        }

        /** Sets the threshold of every lock in [locks] to the safe present cells of its region in [layout]. */
        fun countRegionThresholds(locks: IntArray, layout: BooleanArray) {
            val gates = checkNotNull(gates)
            for (lock in 0 until cellCount) {
                if (locks[lock] == 0) continue
                val region = gates.region[lock]
                var safe = 0
                for (cell in 0 until cellCount) {
                    if (gates.region[cell] == region && present[cell] && !layout[cell]) safe++
                }
                locks[lock] = safe
            }
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
         *
         * Such a mine sits in a coastal neck, on the far cell of a passage or in a row of mines across a
         * room: the land behind it is seen only by mines. Moving it lets the solver look past it.
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
