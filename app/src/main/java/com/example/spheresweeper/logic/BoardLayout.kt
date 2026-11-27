package com.example.spheresweeper.logic

import kotlin.random.Random

/**
 * Per-game board features on top of the fixed [SphereGeometry]: ocean and islands, walls, portals,
 * far-reaching cells and line cells.
 *
 * Ocean cells do not exist in the game ([isPresent] is false): they hold no mine, show no number and
 * appear in no list. The remaining land falls apart into [islandCount] islands; cells on different
 * islands never touch, so only portals lead from one island to another.
 *
 * A wall is a chain of cell edges. Two cells separated by a wall do not see each other: the number of
 * one does not count a mine in the other, and reveals do not spread across. [links] is the neighbour
 * graph with those pairs and the ocean removed and the portal pairs added.
 *
 * A portal is a pair of distant land cells that are neighbours of each other on top of their own
 * neighbours: each one is in the other's [links], so its number counts the partner and reveals of zero
 * cells spread through the pair. Portals joining the islands are always present, so the islands and
 * portals form one connected graph.
 *
 * A far cell's number counts every cell reachable in at most two steps through [links], so a wall
 * blocks that view and a portal carries it over.
 *
 * A line cell's number counts the cells of one straight row instead of its neighbours. The row starts
 * at the neighbour across one edge of the cell and keeps going straight: it enters each hexagon through
 * one edge and leaves through the opposite one (see [straightRow]). A wall, the coast, a pentagon or the
 * length limit ends it; it never goes through a portal.
 *
 * [scopes] lists, for every cell, the cells its number counts; it is what [MinesweeperBoard] uses as
 * its neighbour lists.
 */
class BoardLayout private constructor(
    /** Each wall as a polyline of corner indices into [SphereGeometry.corners] (index k = x,y,z at 3k). */
    val walls: Array<IntArray>,
    private val far: BooleanArray,
    /**
     * links[i] = neighbours of i not separated from it by a wall, plus its portal partner, sorted
     * ascending. Empty for ocean cells, and no list holds an ocean cell.
     */
    val links: Array<IntArray>,
    /** scopes[i] = cells whose mines the number on i counts, sorted ascending. Empty for ocean cells. */
    val scopes: Array<IntArray>,
    /** Per cell: the edge a line cell's row leaves through (see [lineSide]), -1 for other cells. */
    private val lineSides: IntArray,
    /** Per cell: the row of a line cell in order from the cell outwards, empty for other cells. */
    private val linePaths: Array<IntArray>,
    /** Per cell: island index 0 until [islandCount], -1 for ocean. */
    private val islands: IntArray,
    /** Number of islands; 1 when the board has no ocean. */
    val islandCount: Int,
    /** Per cell: the other cell of its portal pair, -1 for other cells. */
    private val partners: IntArray,
    /** Per cell: index of its portal pair, -1 for other cells. */
    private val pairs: IntArray,
    /** Number of portal pairs; pairs 0 until [islandCount] - 1 join the islands, the rest are extra. */
    val portalPairCount: Int,
) {
    fun isFar(cell: Int): Boolean = far[cell]

    val farCount: Int get() = far.count { it }

    fun isLine(cell: Int): Boolean = lineSides[cell] >= 0

    /**
     * Edge of a line cell its row starts through, as an index 0 until cornerCount(cell): the edge runs
     * from corner cornerStart[cell] + side to the next corner of the cell. -1 for other cells.
     */
    fun lineSide(cell: Int): Int = lineSides[cell]

    /** Cells of the row a line cell counts, ordered from the cell outwards; empty for other cells. */
    fun linePath(cell: Int): IntArray = linePaths[cell]

    val lineCount: Int get() = lineSides.count { it >= 0 }

    /** False for ocean cells, which take no part in the game. */
    fun isPresent(cell: Int): Boolean = islands[cell] >= 0

    /** Cells that exist (land). */
    val presentCount: Int = islands.count { it >= 0 }

    /** A new array with [isPresent] of every cell, as [MinesweeperBoard] and [NoGuessGenerator] take it. */
    fun presentMask(): BooleanArray = BooleanArray(islands.size) { islands[it] >= 0 }

    /** Island of [cell], 0 until [islandCount]; -1 for ocean. */
    fun islandOf(cell: Int): Int = islands[cell]

    /** The other cell of the portal pair [cell] belongs to, -1 if it is not a portal. */
    fun portalPartner(cell: Int): Int = partners[cell]

    /** Index 0 until [portalPairCount] of the portal pair [cell] belongs to, -1 if it is not a portal. */
    fun portalPair(cell: Int): Int = pairs[cell]

    fun isPortal(cell: Int): Boolean = partners[cell] >= 0

    /** A new array marking both cells of every portal pair. */
    fun portalMask(): BooleanArray = BooleanArray(partners.size) { partners[it] >= 0 }

    companion object {
        private const val WALL_ATTEMPTS = 60

        private const val HEXAGON_SIDES = 6

        /** Most wall edges a single cell may have; keeps walls from wrapping tightly around one cell. */
        private const val MAX_WALL_EDGES_PER_CELL = 2

        /** Shortest row a line cell may count; shorter rows would make it a weak, odd-looking clue. */
        const val MIN_LINE_LENGTH = 3

        private val NO_CELLS = IntArray(0)

        /**
         * Smallest island, in cells: a cell with two full rings around it. Smaller islands are nearly
         * all coast, with few cells whose neighbours are all on land, and play like scraps of a board.
         * [minIslandSize] also asks for half of an even share of the land.
         */
        const val MIN_ISLAND_CELLS = 19

        /** Share of the cells the ocean covers when the board has islands: from a fifth to about a third. */
        const val MIN_OCEAN_SHARE = 0.20
        const val MAX_OCEAN_SHARE = 0.35

        /** Ocean pockets inside one island up to this size are filled in; they would read as holes. */
        private const val MAX_LAKE_CELLS = 3

        private const val ISLAND_ATTEMPTS = 50

        /** Fewest steps on the plain sphere between the two cells of a portal pair. */
        const val MIN_PORTAL_DISTANCE = 4

        private const val PORTAL_ATTEMPTS = 100

        /** Plain board: no ocean, no walls, no portals, no far cells, no line cells. */
        fun classic(geometry: SphereGeometry): BoardLayout {
            val cellCount = geometry.cellCount
            return BoardLayout(
                emptyArray(), BooleanArray(cellCount), geometry.neighbors, geometry.neighbors,
                IntArray(cellCount) { -1 }, Array(cellCount) { NO_CELLS },
                IntArray(cellCount), 1, IntArray(cellCount) { -1 }, IntArray(cellCount) { -1 }, 0,
            )
        }

        fun generate(geometry: SphereGeometry, size: BoardSize, random: Random = Random.Default): BoardLayout =
            generate(
                geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, random, size.lineCells,
            )

        /** Longest row a line cell counts on [geometry]: twice the subdivision frequency. */
        fun maxLineLength(geometry: SphereGeometry): Int = 2 * geometry.frequency

        /**
         * Most islands [generate] accepts on [geometry]: with the largest ocean share every island must
         * still be able to grow to twice [MIN_ISLAND_CELLS]. 0 when even one island does not fit.
         */
        fun maxIslandCount(geometry: SphereGeometry): Int {
            val land = geometry.cellCount - maxOceanCells(geometry.cellCount)
            if (land < MIN_ISLAND_CELLS) return 0
            return maxOf(1, land / (2 * MIN_ISLAND_CELLS))
        }

        /**
         * Places, in this order: the ocean that splits the land into [islandCount] islands, up to
         * [wallCount] walls of [minLength]..[maxLength] edges, the portals, [farCount] far cells and up
         * to [lineCount] line cells.
         *
         * [islandCount] 0 keeps the whole sphere as land. Otherwise the ocean covers
         * [MIN_OCEAN_SHARE]..[MAX_OCEAN_SHARE] of the cells and every island has at least
         * [minIslandSize] cells (see [growIslands]).
         *
         * Walls never touch each other, themselves or the coast, so they never close a loop and never cut
         * a region off: every island stays connected over [links]. A wall that cannot reach [minLength]
         * after several attempts is skipped.
         *
         * Portals: islandCount - 1 pairs join the islands into one connected graph, then [portalCount]
         * extra pairs go anywhere on land. Both cells of a pair are at least [MIN_PORTAL_DISTANCE] steps
         * apart on the plain sphere and every cell belongs to at most one pair. An extra pair that finds
         * no room is skipped.
         *
         * Far cells and line cells are never ocean or portal cells. Line cells are hexagons that are not
         * far cells and have at least one row of [MIN_LINE_LENGTH] or more cells; fewer are placed only
         * when not enough such cells exist. With ocean a line cell never leaves a land cell outside every
         * other cell's scope (see [placeLines]).
         *
         * Every step draws from [random] only when it has something to place, and the earlier steps never
         * depend on the later counts. With [islandCount] and [portalCount] 0 the draws are those of a
         * layout without these features, so such layouts stay the same for a given seed.
         *
         * @throws IllegalArgumentException when [islandCount] is above [maxIslandCount].
         */
        fun generate(
            geometry: SphereGeometry,
            wallCount: Int,
            minLength: Int,
            maxLength: Int,
            farCount: Int,
            random: Random = Random.Default,
            lineCount: Int = 0,
            islandCount: Int = 0,
            portalCount: Int = 0,
        ): BoardLayout {
            val cellCount = geometry.cellCount
            require(islandCount >= 0) { "negative island count $islandCount" }
            require(islandCount <= maxIslandCount(geometry)) {
                "$islandCount islands do not fit on $cellCount cells (at most ${maxIslandCount(geometry)})"
            }
            val islands = if (islandCount > 0) growIslands(geometry, islandCount, random) else IntArray(cellCount)
            val islandTotal = maxOf(1, islandCount)

            val graph = PointGraph(geometry)
            val walls = ArrayList<IntArray>()
            val separated = HashSet<Long>()
            if (wallCount > 0 && maxLength > 0) {
                val blocked = BooleanArray(graph.pointCount)
                // Walls stay off the coast: a wall from coast to coast would cut an island in two.
                for (cell in 0 until cellCount) {
                    if (islands[cell] >= 0) continue
                    for (k in geometry.cornerStart[cell] until geometry.cornerStart[cell + 1]) {
                        blocked[geometry.cornerPoint[k]] = true
                    }
                }
                val wallEdges = IntArray(cellCount)
                repeat(wallCount) {
                    val path = growWall(graph, blocked, wallEdges, minLength, maxLength, random)
                    if (path != null) {
                        for (k in 0 until path.size - 1) {
                            val slot = graph.slotBetween(path[k], path[k + 1])
                            val a = graph.cellA[slot]
                            val b = graph.cellB[slot]
                            separated.add(pairKey(a, b, cellCount))
                            wallEdges[a]++
                            wallEdges[b]++
                        }
                        // Keep the next walls one point away from this one.
                        for (p in path) {
                            blocked[p] = true
                            for (q in 0 until 3) {
                                val nb = graph.adjacent[p * 3 + q]
                                if (nb >= 0) blocked[nb] = true
                            }
                        }
                        walls.add(IntArray(path.size) { graph.cornerIndex[path[it]] })
                    }
                }
            }

            val links = Array(cellCount) { cell ->
                if (islands[cell] < 0) {
                    NO_CELLS
                } else {
                    geometry.neighbors[cell].filter {
                        islands[it] >= 0 && !separated.contains(pairKey(cell, it, cellCount))
                    }.toIntArray()
                }
            }

            val partners = IntArray(cellCount) { -1 }
            val pairs = IntArray(cellCount) { -1 }
            var pairCount = 0
            if (islandTotal > 1 || portalCount > 0) {
                pairCount = placePortals(geometry, islands, islandTotal, portalCount, random, partners, pairs)
                for (cell in 0 until cellCount) {
                    val partner = partners[cell]
                    if (partner >= 0) links[cell] = (links[cell] + partner).also { it.sort() }
                }
            }

            val far = BooleanArray(cellCount)
            val order = (0 until cellCount).filter { islands[it] >= 0 && partners[it] < 0 }.toIntArray()
            val eligible = order.size
            val farTarget = farCount.coerceIn(0, eligible)
            for (i in 0 until farTarget) {
                val j = i + random.nextInt(eligible - i)
                val tmp = order[i]
                order[i] = order[j]
                order[j] = tmp
                far[order[i]] = true
            }

            val lineSides = IntArray(cellCount) { -1 }
            val linePaths = Array(cellCount) { NO_CELLS }
            if (lineCount > 0) {
                placeLines(
                    geometry, links, far, islands, partners, lineCount, random, lineSides, linePaths,
                    keepEveryCellSeen = islandCount > 0,
                )
            }

            val scopes = Array(cellCount) { cell ->
                when {
                    far[cell] -> twoStepScope(links, cell)
                    lineSides[cell] >= 0 -> linePaths[cell].copyOf().also { it.sort() }
                    else -> links[cell]
                }
            }
            return BoardLayout(
                walls.toTypedArray(), far, links, scopes, lineSides, linePaths,
                islands, islandTotal, partners, pairs, pairCount,
            )
        }

        /** Smallest island accepted when [landCells] cells of land are split into [islandCount] islands. */
        fun minIslandSize(landCells: Int, islandCount: Int): Int =
            maxOf(MIN_ISLAND_CELLS, landCells / (2 * islandCount))

        /** Fewest ocean cells: [MIN_OCEAN_SHARE] of the cells rounded up, so the share never falls below it. */
        private fun minOceanCells(cellCount: Int): Int {
            val exact = cellCount * MIN_OCEAN_SHARE
            val whole = exact.toInt()
            return if (whole < exact) whole + 1 else whole
        }

        /** Most ocean cells: [MAX_OCEAN_SHARE] of the cells rounded down, so the share never goes above it. */
        private fun maxOceanCells(cellCount: Int): Int = (cellCount * MAX_OCEAN_SHARE).toInt()

        /**
         * Island index per cell, -1 for ocean.
         *
         * The ocean size is drawn from [MIN_OCEAN_SHARE]..[MAX_OCEAN_SHARE] of the cells. Island seeds are
         * spread out: the first is random, each next one is drawn among the cells farthest from the seeds
         * so far. The islands then grow in turns, each by one random cell next to it, until the land
         * reaches its size. A cell joins an island only when no other island touches it, so islands stay
         * apart by at least one ocean cell and each one is connected. Small lakes (ocean pockets of up to
         * [MAX_LAKE_CELLS] cells touching a single island) become part of that island while the ocean
         * stays at its minimum share or above. A draw with an island below [minIslandSize] is thrown away
         * and drawn again.
         */
        private fun growIslands(geometry: SphereGeometry, islandCount: Int, random: Random): IntArray {
            val cellCount = geometry.cellCount
            val neighbors = geometry.neighbors
            val owner = IntArray(cellCount)
            val size = IntArray(islandCount)
            val frontier = Array(islandCount) { IntArray(cellCount) }
            val frontierSize = IntArray(islandCount)
            val queued = BooleanArray(islandCount * cellCount)
            val distance = IntArray(cellCount)
            val queue = IntArray(cellCount)
            val minOcean = minOceanCells(cellCount)
            val maxOcean = maxOceanCells(cellCount)
            repeat(ISLAND_ATTEMPTS) {
                val landTarget = cellCount - (minOcean + random.nextInt(maxOcean - minOcean + 1))
                owner.fill(-1)
                size.fill(0)
                frontierSize.fill(0)
                queued.fill(false)
                var land = 0

                fun claim(cell: Int, island: Int) {
                    owner[cell] = island
                    size[island]++
                    land++
                    for (nb in neighbors[cell]) {
                        val key = island * cellCount + nb
                        if (owner[nb] < 0 && !queued[key]) {
                            queued[key] = true
                            frontier[island][frontierSize[island]++] = nb
                        }
                    }
                }

                for (island in 0 until islandCount) {
                    val seed = if (island == 0) random.nextInt(cellCount) else farCell(neighbors, owner, distance, queue, random)
                    if (seed < 0) return@repeat
                    claim(seed, island)
                }

                var growing = true
                while (land < landTarget && growing) {
                    growing = false
                    for (island in 0 until islandCount) {
                        if (land >= landTarget) break
                        val list = frontier[island]
                        while (frontierSize[island] > 0) {
                            val k = random.nextInt(frontierSize[island])
                            val cell = list[k]
                            list[k] = list[--frontierSize[island]]
                            if (canJoin(neighbors, owner, cell, island)) {
                                claim(cell, island)
                                growing = true
                                break
                            }
                        }
                    }
                }

                fillLakes(neighbors, owner, size, queue, cellCount - land - minOcean)
                land = size.sum()
                val minSize = minIslandSize(land, islandCount)
                if (size.all { it >= minSize }) return owner
            }
            throw IllegalStateException("no split into $islandCount islands after $ISLAND_ATTEMPTS attempts")
        }

        /** True if [cell] is free and touches no island other than [island]. */
        private fun canJoin(neighbors: Array<IntArray>, owner: IntArray, cell: Int, island: Int): Boolean {
            if (owner[cell] >= 0) return false
            for (nb in neighbors[cell]) {
                if (owner[nb] >= 0 && owner[nb] != island) return false
            }
            return true
        }

        /**
         * A random free cell among those farthest (by steps on the sphere, give or take one) from every
         * claimed cell, at least two steps from all of them; -1 if there is none. [distance] and [queue]
         * are scratch arrays of the cell count.
         */
        private fun farCell(
            neighbors: Array<IntArray>,
            owner: IntArray,
            distance: IntArray,
            queue: IntArray,
            random: Random,
        ): Int {
            distance.fill(-1)
            var head = 0
            var tail = 0
            for (cell in owner.indices) {
                if (owner[cell] >= 0) {
                    distance[cell] = 0
                    queue[tail++] = cell
                }
            }
            var farthest = 0
            while (head < tail) {
                val cell = queue[head++]
                for (nb in neighbors[cell]) {
                    if (distance[nb] < 0) {
                        distance[nb] = distance[cell] + 1
                        if (distance[nb] > farthest) farthest = distance[nb]
                        queue[tail++] = nb
                    }
                }
            }
            if (farthest < 2) return -1
            var count = 0
            for (cell in owner.indices) {
                if (distance[cell] >= maxOf(2, farthest - 1)) queue[count++] = cell
            }
            return queue[random.nextInt(count)]
        }

        /**
         * Turns ocean pockets of at most [MAX_LAKE_CELLS] cells that touch a single island into land of
         * that island, in index order, as long as they fit into [spare] ocean cells in total. Islands
         * stay apart, since a pocket touching two islands is kept.
         */
        private fun fillLakes(neighbors: Array<IntArray>, owner: IntArray, size: IntArray, queue: IntArray, spare: Int) {
            var left = spare
            val seen = BooleanArray(owner.size)
            for (first in owner.indices) {
                if (owner[first] >= 0 || seen[first]) continue
                var head = 0
                var tail = 0
                queue[tail++] = first
                seen[first] = true
                var shore = -1
                var shared = false
                while (head < tail) {
                    val cell = queue[head++]
                    for (nb in neighbors[cell]) {
                        val island = owner[nb]
                        if (island >= 0) {
                            if (shore < 0) shore = island else if (shore != island) shared = true
                        } else if (!seen[nb]) {
                            seen[nb] = true
                            queue[tail++] = nb
                        }
                    }
                }
                if (tail <= MAX_LAKE_CELLS && tail <= left && !shared && shore >= 0) {
                    for (i in 0 until tail) owner[queue[i]] = shore
                    size[shore] += tail
                    left -= tail
                }
            }
        }

        /**
         * Picks the portal pairs into [partners] and [pairs] and returns how many there are. First a
         * random spanning tree over the islands: the islands are shuffled and each one after the first
         * is joined to a random earlier one. Then [extraCount] pairs between any land cells.
         */
        private fun placePortals(
            geometry: SphereGeometry,
            islands: IntArray,
            islandCount: Int,
            extraCount: Int,
            random: Random,
            partners: IntArray,
            pairs: IntArray,
        ): Int {
            val cellCount = geometry.cellCount
            val byIsland = Array(islandCount) { island -> (0 until cellCount).filter { islands[it] == island }.toIntArray() }
            val land = (0 until cellCount).filter { islands[it] >= 0 }.toIntArray()
            val near = NearCells(cellCount)
            var count = 0
            if (islandCount > 1) {
                val order = IntArray(islandCount) { it }
                for (i in 0 until islandCount - 1) {
                    val j = i + random.nextInt(islandCount - i)
                    val tmp = order[i]
                    order[i] = order[j]
                    order[j] = tmp
                }
                for (k in 1 until islandCount) {
                    val other = order[random.nextInt(k)]
                    val placed = placePair(geometry, byIsland[order[k]], byIsland[other], count, random, partners, pairs, near)
                    check(placed) { "no portal fits between islands ${order[k]} and $other" }
                    count++
                }
            }
            repeat(extraCount) {
                if (placePair(geometry, land, land, count, random, partners, pairs, near)) count++
            }
            return count
        }

        /**
         * Joins a cell of [from] and a cell of [to] as pair [index]. Both must be free of portals and at
         * least [MIN_PORTAL_DISTANCE] steps apart. Tries random cells first, then scans in index order.
         * Returns false when no such pair exists.
         */
        private fun placePair(
            geometry: SphereGeometry,
            from: IntArray,
            to: IntArray,
            index: Int,
            random: Random,
            partners: IntArray,
            pairs: IntArray,
            near: NearCells,
        ): Boolean {
            fun join(a: Int, b: Int) {
                partners[a] = b
                partners[b] = a
                pairs[a] = index
                pairs[b] = index
            }
            repeat(PORTAL_ATTEMPTS) {
                val a = from[random.nextInt(from.size)]
                val b = to[random.nextInt(to.size)]
                if (partners[a] < 0 && partners[b] < 0) {
                    near.mark(geometry.neighbors, a)
                    if (!near.contains(b)) {
                        join(a, b)
                        return true
                    }
                }
            }
            for (a in from) {
                if (partners[a] >= 0) continue
                near.mark(geometry.neighbors, a)
                for (b in to) {
                    if (partners[b] < 0 && !near.contains(b)) {
                        join(a, b)
                        return true
                    }
                }
            }
            return false
        }

        /**
         * Turns up to [lineCount] random land hexagons that are neither far cells nor portals into line
         * cells. Candidates are visited in random order; each one gets a random edge among those whose
         * row has at least [MIN_LINE_LENGTH] cells, and a candidate without such an edge is skipped.
         *
         * A line cell stops counting its neighbours. On the coast a cell may have a single land
         * neighbour, so with [keepEveryCellSeen] an edge is also left out when its row would leave some
         * land cell in no other cell's scope: such a cell could only be settled by the mine counter.
         * Boards without ocean skip this check, so their draws stay as they were; there every cell has at
         * least three links, which makes such a cell very unlikely.
         */
        private fun placeLines(
            geometry: SphereGeometry,
            links: Array<IntArray>,
            far: BooleanArray,
            islands: IntArray,
            partners: IntArray,
            lineCount: Int,
            random: Random,
            lineSides: IntArray,
            linePaths: Array<IntArray>,
            keepEveryCellSeen: Boolean,
        ) {
            val candidates = (0 until geometry.cellCount)
                .filter { !far[it] && !geometry.isPentagon(it) && islands[it] >= 0 && partners[it] < 0 }
                .toIntArray()
            // seenBy[c] = cells other than c whose scope holds c, as the scopes stand so far.
            val seenBy: IntArray? = if (keepEveryCellSeen) IntArray(geometry.cellCount) else null
            if (seenBy != null) {
                for (cell in 0 until geometry.cellCount) {
                    val scope = if (far[cell]) twoStepScope(links, cell) else links[cell]
                    for (other in scope) seenBy[other]++
                }
            }
            val maxLength = maxLineLength(geometry)
            val sides = IntArray(HEXAGON_SIDES)
            val rows = arrayOfNulls<IntArray>(HEXAGON_SIDES)
            var placed = 0
            var remaining = candidates.size
            while (placed < lineCount && remaining > 0) {
                // Partial Fisher-Yates: draw one of the remaining candidates and drop it from the pool.
                val pick = random.nextInt(remaining)
                val cell = candidates[pick]
                candidates[pick] = candidates[remaining - 1]
                remaining--
                var count = 0
                for (side in 0 until HEXAGON_SIDES) {
                    val row = straightRow(geometry, links, cell, side, maxLength)
                    if (row.size < MIN_LINE_LENGTH) continue
                    if (seenBy != null && leavesUnseen(links[cell], row, seenBy)) continue
                    sides[count] = side
                    rows[count] = row
                    count++
                }
                if (count == 0) continue
                val choice = random.nextInt(count)
                val row = requireNotNull(rows[choice])
                lineSides[cell] = sides[choice]
                linePaths[cell] = row
                if (seenBy != null) {
                    for (other in links[cell]) seenBy[other]--
                    for (other in row) seenBy[other]++
                }
                placed++
            }
        }

        /**
         * True if a cell with links [scope] that switches to counting [row] instead would leave one of
         * its links seen by no cell, given [seenBy] as in [placeLines].
         */
        private fun leavesUnseen(scope: IntArray, row: IntArray, seenBy: IntArray): Boolean {
            for (other in scope) {
                if (seenBy[other] <= 1 && !row.contains(other)) return true
            }
            return false
        }

        /**
         * Straight row of cells seen from [cell] through its edge [side] (an index 0 until
         * cornerCount(cell), the edge from corner cornerStart[cell] + side to the next corner).
         *
         * The row starts at the neighbour across that edge. In a hexagon entered through edge k it goes
         * on through the opposite edge k + 3. It ends before a step across a wall (a pair missing from
         * [links]), before a step back onto [cell] or a cell already in the row, after a pentagon (which
         * has no opposite edge) and once it holds [maxLength] cells. [cell] itself is never part of it.
         */
        fun straightRow(
            geometry: SphereGeometry,
            links: Array<IntArray>,
            cell: Int,
            side: Int,
            maxLength: Int,
        ): IntArray {
            require(side in 0 until geometry.cornerCount(cell)) { "cell $cell has no edge $side" }
            val row = ArrayList<Int>()
            var current = cell
            var edge = geometry.cornerStart[cell] + side
            while (row.size < maxLength) {
                val next = geometry.edgeNeighbor[edge]
                if (!links[current].contains(next)) break
                if (next == cell || row.contains(next)) break
                row.add(next)
                if (geometry.isPentagon(next)) break
                val start = geometry.cornerStart[next]
                var entry = -1
                for (k in start until geometry.cornerStart[next + 1]) {
                    if (geometry.edgeNeighbor[k] == current) {
                        entry = k
                        break
                    }
                }
                check(entry >= 0) { "cells $current and $next do not share an edge" }
                edge = start + (entry - start + HEXAGON_SIDES / 2) % HEXAGON_SIDES
                current = next
            }
            return row.toIntArray()
        }

        /** Self-avoiding random walk over corner points; null when it cannot reach [minLength] edges. */
        private fun growWall(
            graph: PointGraph,
            blocked: BooleanArray,
            wallEdges: IntArray,
            minLength: Int,
            maxLength: Int,
            random: Random,
        ): IntArray? {
            val inPath = BooleanArray(graph.pointCount)
            val candidates = IntArray(3)
            repeat(WALL_ATTEMPTS) {
                val start = random.nextInt(graph.pointCount)
                if (blocked[start]) return@repeat
                val target = minLength + random.nextInt(maxLength - minLength + 1)
                val path = ArrayList<Int>()
                path.add(start)
                inPath[start] = true
                // Edges per cell used by this attempt, on top of the committed walls.
                val local = HashMap<Int, Int>()
                while (path.size - 1 < target) {
                    val current = path[path.size - 1]
                    var count = 0
                    for (q in 0 until 3) {
                        val next = graph.adjacent[current * 3 + q]
                        if (next < 0 || blocked[next] || inPath[next]) continue
                        if (touchesPath(graph, next, current, inPath)) continue
                        val slot = current * 3 + q
                        val a = graph.cellA[slot]
                        val b = graph.cellB[slot]
                        if (wallEdges[a] + (local[a] ?: 0) >= MAX_WALL_EDGES_PER_CELL) continue
                        if (wallEdges[b] + (local[b] ?: 0) >= MAX_WALL_EDGES_PER_CELL) continue
                        candidates[count++] = next
                    }
                    if (count == 0) break
                    val next = candidates[random.nextInt(count)]
                    val slot = graph.slotBetween(current, next)
                    local[graph.cellA[slot]] = (local[graph.cellA[slot]] ?: 0) + 1
                    local[graph.cellB[slot]] = (local[graph.cellB[slot]] ?: 0) + 1
                    path.add(next)
                    inPath[next] = true
                }
                for (p in path) inPath[p] = false
                if (path.size - 1 >= minLength) return path.toIntArray()
            }
            return null
        }

        /** True if [point] is adjacent to a point of the path other than [from]. */
        private fun touchesPath(graph: PointGraph, point: Int, from: Int, inPath: BooleanArray): Boolean {
            for (q in 0 until 3) {
                val nb = graph.adjacent[point * 3 + q]
                if (nb >= 0 && nb != from && inPath[nb]) return true
            }
            return false
        }

        /** Cells reachable from [cell] in one or two steps over [links], without [cell] itself. */
        private fun twoStepScope(links: Array<IntArray>, cell: Int): IntArray {
            val seen = LinkedHashSet<Int>()
            for (a in links[cell]) {
                seen.add(a)
                for (b in links[a]) {
                    if (b != cell) seen.add(b)
                }
            }
            return seen.toIntArray().also { it.sort() }
        }

        private fun pairKey(a: Int, b: Int, cellCount: Int): Long {
            val low = minOf(a, b)
            val high = maxOf(a, b)
            return low.toLong() * cellCount + high
        }
    }

    /** Cells fewer than [MIN_PORTAL_DISTANCE] steps from one cell on the plain sphere. */
    private class NearCells(cellCount: Int) {
        private val stamp = IntArray(cellCount)
        private val queue = IntArray(cellCount)
        private var current = 0

        /** Replaces the marked cells with those near [cell], [cell] included. */
        fun mark(neighbors: Array<IntArray>, cell: Int) {
            current++
            var head = 0
            var tail = 0
            queue[tail++] = cell
            stamp[cell] = current
            var levelEnd = tail
            var depth = 0
            while (head < tail && depth < MIN_PORTAL_DISTANCE - 1) {
                val c = queue[head++]
                for (nb in neighbors[c]) {
                    if (stamp[nb] != current) {
                        stamp[nb] = current
                        queue[tail++] = nb
                    }
                }
                if (head == levelEnd) {
                    depth++
                    levelEnd = tail
                }
            }
        }

        fun contains(cell: Int): Boolean = stamp[cell] == current
    }

    /**
     * Corner points of the cell polygons and the cell edges between them. Every point touches three
     * cells and three edges; slot p * 3 + q describes the q-th edge of point p.
     */
    private class PointGraph(geometry: SphereGeometry) {
        val pointCount = geometry.pointCount

        /** Neighbouring point per slot, -1 while unused. */
        val adjacent = IntArray(pointCount * 3) { -1 }

        /** The two cells the edge in a slot separates. */
        val cellA = IntArray(pointCount * 3)
        val cellB = IntArray(pointCount * 3)

        /** One corner index of each point, for drawing. */
        val cornerIndex = IntArray(pointCount)

        init {
            for (cell in 0 until geometry.cellCount) {
                val start = geometry.cornerStart[cell]
                val end = geometry.cornerStart[cell + 1]
                for (k in start until end) {
                    val next = if (k + 1 < end) k + 1 else start
                    val p = geometry.cornerPoint[k]
                    val q = geometry.cornerPoint[next]
                    cornerIndex[p] = k
                    val other = geometry.edgeNeighbor[k]
                    addEdge(p, q, cell, other)
                    addEdge(q, p, cell, other)
                }
            }
        }

        private fun addEdge(from: Int, to: Int, a: Int, b: Int) {
            for (q in 0 until 3) {
                val slot = from * 3 + q
                if (adjacent[slot] == to) return
                if (adjacent[slot] < 0) {
                    adjacent[slot] = to
                    cellA[slot] = a
                    cellB[slot] = b
                    return
                }
            }
            error("point $from has more than three edges")
        }

        fun slotBetween(from: Int, to: Int): Int {
            for (q in 0 until 3) {
                if (adjacent[from * 3 + q] == to) return from * 3 + q
            }
            error("points $from and $to are not adjacent")
        }
    }
}
