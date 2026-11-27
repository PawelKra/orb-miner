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
 */
object NoGuessGenerator {
    /** Repair steps allowed on one drawn layout before drawing a new one. */
    const val DEFAULT_MAX_REPAIRS = 200

    /** Drawn layouts before giving up. */
    const val DEFAULT_MAX_LAYOUTS = 1000

    /**
     * Returns a layout of exactly [mineCount] mines on [neighbors] (lists as in [MinesweeperBoard]).
     * The start cell is chosen at random among cells whose safe area leaves room for all mines.
     * The result depends only on the inputs and the sequence drawn from [random].
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
    ): NoGuessLayout {
        val cellCount = neighbors.size
        require(mineCount >= 0) { "negative mine count $mineCount" }
        val starts = IntArray(cellCount)
        var startCount = 0
        for (cell in 0 until cellCount) {
            if (cellCount - safeAreaSize(neighbors, cell) >= mineCount) starts[startCount++] = cell
        }
        require(startCount > 0) { "$mineCount mines do not fit next to any start area on $cellCount cells" }

        val work = Work(neighbors)
        val solver = work.solver
        val layout = BooleanArray(cellCount)
        var repairs = 0
        for (attempt in 0 until maxLayouts) {
            val start = starts[random.nextInt(startCount)]
            work.placeRandom(layout, start, mineCount, random)
            solver.load(layout)
            solver.reveal(start)
            var steps = 0
            var changed = false
            while (true) {
                if (solver.run()) {
                    if (!changed) return NoGuessLayout(start, work.mineCells(), repairs, attempt)
                    // Replay from the start on the final layout, so that every deduction uses final numbers.
                    for (cell in 0 until cellCount) layout[cell] = solver.isMine(cell)
                    solver.load(layout)
                    solver.reveal(start)
                    changed = false
                    continue
                }
                if (steps >= maxRepairs || !work.repair(random)) break
                steps++
                repairs++
                changed = true
            }
        }
        throw IllegalStateException("no layout without guessing after $maxLayouts attempts")
    }

    /** Size of {cell} ∪ neighbors[cell]. */
    private fun safeAreaSize(neighbors: Array<IntArray>, cell: Int): Int {
        var size = 1
        for (nb in neighbors[cell]) {
            if (nb != cell) size++
        }
        return size
    }

    /** Solver and scratch arrays reused across repairs and drawn layouts. */
    private class Work(private val neighbors: Array<IntArray>) {
        val solver = LogicSolver(neighbors)
        private val cellCount = neighbors.size
        private val excluded = BooleanArray(cellCount)
        private val candidates = IntArray(cellCount)

        /** Unknown cells that some revealed number sees. */
        private val watched = BooleanArray(cellCount)

        /** Unknown cells of the constraint being repaired. */
        private val target = BooleanArray(cellCount)

        /** Swap candidates that no revealed number sees, and the rest. */
        private val unwatched = IntArray(cellCount)
        private val nearby = IntArray(cellCount)
        private var unwatchedCount = 0
        private var nearbyCount = 0

        /** Places [mineCount] mines uniformly outside [start] and its list. */
        fun placeRandom(layout: BooleanArray, start: Int, mineCount: Int, random: Random) {
            layout.fill(false)
            excluded.fill(false)
            excluded[start] = true
            for (nb in neighbors[start]) excluded[nb] = true
            var count = 0
            for (cell in 0 until cellCount) {
                if (!excluded[cell]) candidates[count++] = cell
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
         * Makes one stuck constraint resolvable. Among revealed cells with unknown cells in their list,
         * picks one at random among those needing the fewest mine moves, then empties or fills its
         * unknown cells. Returns false when there is no such cell or not enough cells to swap with.
         */
        fun repair(random: Random): Boolean {
            watched.fill(false)
            var chosen = -1
            var bestCost = Int.MAX_VALUE
            var ties = 0
            for (cell in 0 until cellCount) {
                if (!solver.isRevealed(cell) || solver.unknownNeighbors(cell) == 0) continue
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
            for (nb in neighbors[chosen]) {
                if (!solver.isUnknown(nb)) continue
                target[nb] = true
                unknown++
                if (solver.isMine(nb)) mines++
            }
            val emptyFirst = mines <= unknown - mines
            val done = if (emptyFirst) {
                empty(chosen, mines, random) || fill(chosen, unknown - mines, random)
            } else {
                fill(chosen, unknown - mines, random) || empty(chosen, mines, random)
            }
            for (nb in neighbors[chosen]) target[nb] = false
            return done
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
         */
        private fun collect(wantMine: Boolean, needed: Int): Boolean {
            unwatchedCount = 0
            nearbyCount = 0
            for (c in 0 until cellCount) {
                if (target[c] || !solver.isUnknown(c) || solver.isMine(c) != wantMine) continue
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
