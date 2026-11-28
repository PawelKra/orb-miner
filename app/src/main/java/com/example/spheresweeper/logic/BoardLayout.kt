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
 * length limit ends it; it never goes through a portal. On a board with rooms every line cell stands
 * on the near cell of a passage (see [gates]) and its row starts across the passage, so it counts the
 * cells on the way into the deeper room; the row never reaches back into a region as shallow as the
 * line cell's own. Line cells never cut a region off from the shallower side: following cells that see
 * each other (v is seen by u when v is in [scopes][u]) without stepping into a shallower region, every
 * land cell is reachable from every root cell that is not a line cell. The far cell of every gate is
 * in the scope of its near cell.
 *
 * [scopes] lists, for every cell, the cells its number counts; it is what [MinesweeperBoard] uses as
 * its neighbour lists.
 *
 * Rooms split the land of an island into parts. The edges between two rooms are walls too (they are
 * part of [walls]), except for one passage: a single open cell edge between two rooms that are joined
 * in a random spanning tree of the rooms. Rooms that touch without being joined in that tree are
 * separated by a full wall.
 *
 * A region is a connected part of the land over [links] once the passage and portal pairs are left
 * out. With rooms every room is a region; without them every island is one. [gates] are the passages
 * and the portal pairs that lead from one region to another: crossing a gate is the only way into
 * another region.
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
    /** Regions and gates (see [buildRegions]). */
    private val regionMap: RegionMap,
) {

    /** Number of regions: the rooms, or the islands when the board has no rooms. */
    val regionCount: Int get() = regionMap.count

    /**
     * Region of [cell], 0 until [regionCount], -1 for ocean. Regions are numbered in the order of their
     * lowest cell.
     */
    fun regionOf(cell: Int): Int = regionMap.of[cell]

    /**
     * Fewest gates crossed from the root region to [region]: the root is the region holding the first
     * room seed, or the region of the lowest land cell when the board has no rooms.
     */
    fun regionDepth(region: Int): Int = regionMap.depth[region]

    /**
     * Every gate as (near, far): the two cells of a passage or of a portal pair lying in different
     * regions. near is in the region reached first by the breadth-first search over the gates from the
     * root region, so its [regionDepth] is never larger than that of far. Sorted by the search order of
     * the near region, then of the far region, then by the cells.
     */
    val gates: List<Pair<Int, Int>> get() = regionMap.gates

    /**
     * True if gate [gate] (an index into [gates]) is a bridge of the graph of regions: without it the
     * regions behind it cannot be reached from the root. A lock on such a gate really holds those
     * regions back; a gate with another way around it would not. Portal pairs inside one region are no
     * gates, and parallel gates between the same two regions are never bridges.
     */
    fun isLockableGate(gate: Int): Boolean = regionMap.lockable[gate]

    /** True if some island is split into two or more rooms. */
    val hasRooms: Boolean get() = regionMap.count > islandCount

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

        /**
         * Share of the cells the ocean covers on a campaign board with islands: from a fifth to about a
         * third. [CAMPAIGN_OCEAN_SHARE] is the default ocean share of [generate].
         */
        const val MIN_OCEAN_SHARE = 0.20
        const val MAX_OCEAN_SHARE = 0.35

        /** [MIN_OCEAN_SHARE]..[MAX_OCEAN_SHARE]: the ocean share of campaign levels. */
        val CAMPAIGN_OCEAN_SHARE: ClosedFloatingPointRange<Double> = MIN_OCEAN_SHARE..MAX_OCEAN_SHARE

        /** Ocean pockets inside one island up to this size are filled in; they would read as holes. */
        private const val MAX_LAKE_CELLS = 3

        private const val ISLAND_ATTEMPTS = 50

        /** Fewest steps on the plain sphere between the two cells of a portal pair. */
        const val MIN_PORTAL_DISTANCE = 4

        private const val PORTAL_ATTEMPTS = 100

        /**
         * Smallest room, in cells: a cell with its ring of six is the least that lets a row of
         * [MIN_LINE_LENGTH] cells run into the room from any side; a few cells more leave room for that
         * row when the room is not round. [minRoomSize] also asks for half of an even share of the island.
         */
        const val MIN_ROOM_CELLS = 12

        private const val ROOM_ATTEMPTS = 50

        /** Plain board: no ocean, no walls, no portals, no far cells, no line cells. */
        fun classic(geometry: SphereGeometry): BoardLayout {
            val cellCount = geometry.cellCount
            return BoardLayout(
                emptyArray(), BooleanArray(cellCount), geometry.neighbors, geometry.neighbors,
                IntArray(cellCount) { -1 }, Array(cellCount) { NO_CELLS },
                IntArray(cellCount), 1, IntArray(cellCount) { -1 }, IntArray(cellCount) { -1 }, 0,
                buildRegions(IntArray(cellCount), geometry.neighbors, IntArray(cellCount) { -1 }, emptyList(), -1),
            )
        }

        fun generate(
            geometry: SphereGeometry,
            size: BoardSize,
            random: Random = Random.Default,
            roomCount: Int = 0,
        ): BoardLayout =
            generate(
                geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, random, size.lineCells,
                roomCount = roomCount,
            )

        /** Longest row a line cell counts on [geometry]: twice the subdivision frequency. */
        fun maxLineLength(geometry: SphereGeometry): Int = 2 * geometry.frequency

        /**
         * Most islands [generate] accepts on [geometry] with [oceanShare]: with the largest ocean share
         * every island must still be able to grow to twice [MIN_ISLAND_CELLS]. 0 when even one island
         * does not fit.
         */
        fun maxIslandCount(
            geometry: SphereGeometry,
            oceanShare: ClosedFloatingPointRange<Double> = CAMPAIGN_OCEAN_SHARE,
        ): Int {
            val land = geometry.cellCount - maxOceanCells(geometry.cellCount, oceanShare)
            if (land < MIN_ISLAND_CELLS) return 0
            return maxOf(1, land / (2 * MIN_ISLAND_CELLS))
        }

        /**
         * Most rooms [generate] accepts on [geometry] with [islandCount] islands and [oceanShare]: the
         * smallest land still gives every room twice [MIN_ROOM_CELLS] on average. At least 1.
         */
        fun maxRoomCount(
            geometry: SphereGeometry,
            islandCount: Int = 0,
            oceanShare: ClosedFloatingPointRange<Double> = CAMPAIGN_OCEAN_SHARE,
        ): Int {
            val cellCount = geometry.cellCount
            val land = if (islandCount > 0) cellCount - maxOceanCells(cellCount, oceanShare) else cellCount
            return maxOf(1, land / (2 * MIN_ROOM_CELLS))
        }

        /**
         * Places, in this order: the ocean that splits the land into [islandCount] islands, the
         * [roomCount] rooms, up to [wallCount] walls of [minLength]..[maxLength] edges, the portals, the
         * passages between rooms, [farCount] far cells and up to [lineCount] line cells.
         *
         * [islandCount] 0 keeps the whole sphere as land. Otherwise the ocean covers [oceanShare] of the
         * cells ([CAMPAIGN_OCEAN_SHARE] unless given) and every island has at least [minIslandSize] cells
         * (see [growIslands]). The share changes how many cells the ocean takes, never how many values
         * are drawn from [random].
         *
         * [roomCount] counts the rooms on the whole board; 0 or 1 leaves the land without rooms. Every
         * island holds at least one room, and the rest go to the islands with the most land per room
         * (see [growRooms]), so a board with small islands may get fewer rooms than asked for. The
         * rooms of an island are joined by a random spanning tree; every edge of the tree gets one
         * passage (see [choosePassages]) and all other edges between rooms become walls. They are added
         * to [walls] after the walls below, cut into chains at the points where three of them meet.
         *
         * Walls never touch each other, themselves, the coast or the edges between rooms, so they never
         * close a loop and never cut a region off: every room and every island stays connected over
         * [links]. A wall that cannot reach [minLength] after several attempts is skipped.
         *
         * Portals: islandCount - 1 pairs join the islands into one connected graph, then [portalCount]
         * extra pairs go anywhere on land. Both cells of a pair are at least [MIN_PORTAL_DISTANCE] steps
         * apart on the plain sphere and every cell belongs to at most one pair. An extra pair that finds
         * no room is skipped.
         *
         * Far cells and line cells are never ocean or portal cells. Line cells are hexagons that are not
         * far cells and have at least one row of [MIN_LINE_LENGTH] or more cells; fewer are placed only
         * when not enough such cells exist. With rooms they stand only at passages, at most one per
         * passage (see [placePassageLines]), so a board with rooms often gets fewer than [lineCount].
         * With ocean or rooms a line cell never leaves a land cell outside every other cell's scope (see
         * [placeLines]), and with rooms it never hides a region from the shallower side (see
         * [placePassageLines]).
         *
         * Every step draws from [random] only when it has something to place, and the earlier steps never
         * depend on the later counts. With [islandCount], [portalCount] and [roomCount] 0 nothing is
         * drawn for these features, so such a layout depends only on the seed and the other counts.
         *
         * @throws IllegalArgumentException when [oceanShare] is not a range within 0..1, [islandCount] is
         *   above [maxIslandCount] or [roomCount] above [maxRoomCount] for that share.
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
            roomCount: Int = 0,
            oceanShare: ClosedFloatingPointRange<Double> = CAMPAIGN_OCEAN_SHARE,
        ): BoardLayout {
            val cellCount = geometry.cellCount
            require(
                oceanShare.start >= 0.0 && oceanShare.start <= oceanShare.endInclusive && oceanShare.endInclusive < 1.0,
            ) { "ocean share $oceanShare is not a range within 0..1" }
            require(islandCount >= 0) { "negative island count $islandCount" }
            val maxIslands = maxIslandCount(geometry, oceanShare)
            require(islandCount <= maxIslands) {
                "$islandCount islands do not fit on $cellCount cells (at most $maxIslands)"
            }
            require(roomCount >= 0) { "negative room count $roomCount" }
            val maxRooms = maxRoomCount(geometry, islandCount, oceanShare)
            require(roomCount <= maxRooms) {
                "$roomCount rooms do not fit on $cellCount cells (at most $maxRooms)"
            }
            val islands = if (islandCount > 0) {
                growIslands(geometry, islandCount, random, oceanShare)
            } else {
                IntArray(cellCount)
            }
            val islandTotal = maxOf(1, islandCount)

            // Rooms come right after the islands; nothing below draws differently without them.
            var rooms: RoomSplit? = null
            var tree: List<IntArray> = emptyList()
            if (roomCount > 0) {
                val split = growRooms(geometry, islands, islandTotal, roomCount, random)
                if (split.count > islandTotal) {
                    rooms = split
                    tree = spanRooms(geometry, split, random)
                }
            }

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
                // For the same reason they stay off the edges between rooms, passages included.
                if (rooms != null) blockRoomBorders(geometry, rooms.room, blocked)
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

            val partners = IntArray(cellCount) { -1 }
            val pairs = IntArray(cellCount) { -1 }
            var pairCount = 0
            if (islandTotal > 1 || portalCount > 0) {
                pairCount = placePortals(geometry, islands, islandTotal, portalCount, random, partners, pairs)
            }

            val passages = ArrayList<Pair<Int, Int>>()
            var rootCell = -1
            if (rooms != null) {
                rootCell = rooms.firstSeed
                choosePassages(geometry, rooms, tree, partners, separated, random, passages)
                val open = HashSet<Long>()
                for ((a, b) in passages) open.add(pairKey(a, b, cellCount))
                for (cell in 0 until cellCount) {
                    val room = rooms.room[cell]
                    if (room < 0) continue
                    for (nb in geometry.neighbors[cell]) {
                        val other = rooms.room[nb]
                        if (other < 0 || other == room) continue
                        val key = pairKey(cell, nb, cellCount)
                        if (!open.contains(key)) separated.add(key)
                    }
                }
                walls.addAll(roomWalls(graph, rooms.room, open, cellCount))
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
            if (pairCount > 0) {
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

            val regions = buildRegions(islands, links, partners, passages, rootCell)
            val lineSides = IntArray(cellCount) { -1 }
            val linePaths = Array(cellCount) { NO_CELLS }
            if (lineCount > 0) {
                placeLines(
                    geometry, links, far, islands, partners, lineCount, random, lineSides, linePaths,
                    keepEveryCellSeen = islandCount > 0 || rooms != null,
                    regions = if (rooms != null) regions else null,
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
                islands, islandTotal, partners, pairs, pairCount, regions,
            )
        }

        /** Smallest room accepted when an island of [islandCells] cells is split into [roomCount] rooms. */
        fun minRoomSize(islandCells: Int, roomCount: Int): Int =
            maxOf(MIN_ROOM_CELLS, islandCells / (2 * roomCount))

        /**
         * Rooms of the land: [RoomSplit.room] holds the room index per cell, -1 for ocean.
         *
         * Every island starts with one room. The remaining rooms go one by one to the island with the
         * most cells per room after the addition, as long as that keeps twice [MIN_ROOM_CELLS] per room;
         * ties go to the lower island. This step draws nothing.
         *
         * An island with two or more rooms grows them like [growIslands] grows islands, but over its own
         * land only and without gaps between rooms: the first seed is a random cell of the island, each
         * next seed is drawn among the island cells farthest from the seeds so far, then the rooms take
         * turns claiming one random free cell next to them until the island is full. A draw with a room
         * below [minRoomSize] is thrown away and drawn again. Islands are handled in index order and
         * [RoomSplit.firstSeed] is the first seed of the first island split this way.
         */
        private fun growRooms(
            geometry: SphereGeometry,
            islands: IntArray,
            islandCount: Int,
            roomCount: Int,
            random: Random,
        ): RoomSplit {
            val cellCount = geometry.cellCount
            val neighbors = geometry.neighbors
            val sizes = IntArray(islandCount)
            for (cell in 0 until cellCount) if (islands[cell] >= 0) sizes[islands[cell]]++
            val shares = IntArray(islandCount) { 1 }
            var left = roomCount - islandCount
            while (left > 0) {
                var best = -1
                for (island in 0 until islandCount) {
                    if (sizes[island] / (shares[island] + 1) < 2 * MIN_ROOM_CELLS) continue
                    // Compare sizes[island] / (shares[island] + 1) without rounding.
                    if (best < 0 || sizes[island].toLong() * (shares[best] + 1) > sizes[best].toLong() * (shares[island] + 1)) {
                        best = island
                    }
                }
                if (best < 0) break
                shares[best]++
                left--
            }

            val room = IntArray(cellCount) { -1 }
            val owner = IntArray(cellCount)
            val distance = IntArray(cellCount)
            val queue = IntArray(cellCount)
            var firstSeed = -1
            var base = 0
            for (island in 0 until islandCount) {
                val cells = (0 until cellCount).filter { islands[it] == island }.toIntArray()
                val count = shares[island]
                if (count == 1) {
                    for (cell in cells) room[cell] = base
                    base++
                    continue
                }
                val frontier = Array(count) { IntArray(cellCount) }
                val frontierSize = IntArray(count)
                val queued = BooleanArray(count * cellCount)
                val size = IntArray(count)
                val minSize = minRoomSize(cells.size, count)
                var done = false
                var attempt = 0
                while (!done && attempt < ROOM_ATTEMPTS) {
                    attempt++
                    owner.fill(-1)
                    frontierSize.fill(0)
                    queued.fill(false)
                    size.fill(0)
                    var claimed = 0
                    var seed0 = -1

                    fun claim(cell: Int, r: Int) {
                        owner[cell] = r
                        size[r]++
                        claimed++
                        for (nb in neighbors[cell]) {
                            val key = r * cellCount + nb
                            if (islands[nb] == island && owner[nb] < 0 && !queued[key]) {
                                queued[key] = true
                                frontier[r][frontierSize[r]++] = nb
                            }
                        }
                    }

                    var seeded = true
                    for (r in 0 until count) {
                        val seed = if (r == 0) {
                            cells[random.nextInt(cells.size)]
                        } else {
                            farCell(neighbors, owner, distance, queue, random, islands, island)
                        }
                        if (seed < 0) {
                            seeded = false
                            break
                        }
                        if (r == 0) seed0 = seed
                        claim(seed, r)
                    }
                    if (!seeded) continue

                    var growing = true
                    while (claimed < cells.size && growing) {
                        growing = false
                        for (r in 0 until count) {
                            val list = frontier[r]
                            while (frontierSize[r] > 0) {
                                val k = random.nextInt(frontierSize[r])
                                val cell = list[k]
                                list[k] = list[--frontierSize[r]]
                                if (owner[cell] < 0) {
                                    claim(cell, r)
                                    growing = true
                                    break
                                }
                            }
                        }
                    }
                    if (claimed == cells.size && size.all { it >= minSize }) {
                        for (cell in cells) room[cell] = base + owner[cell]
                        if (firstSeed < 0) firstSeed = seed0
                        done = true
                    }
                }
                check(done) { "no split of island $island into $count rooms after $ROOM_ATTEMPTS attempts" }
                base += count
            }
            return RoomSplit(room, base, firstSeed)
        }

        /**
         * Random spanning tree over the rooms that share an edge, as pairs of room indices: the room
         * pairs, sorted, are shuffled and taken in that order whenever they join two parts not yet joined.
         * Rooms of different islands never touch, so every island gets a tree of its own.
         */
        private fun spanRooms(geometry: SphereGeometry, rooms: RoomSplit, random: Random): List<IntArray> {
            val room = rooms.room
            val count = rooms.count
            val keys = java.util.TreeSet<Long>()
            for (cell in 0 until geometry.cellCount) {
                val a = room[cell]
                if (a < 0) continue
                for (nb in geometry.neighbors[cell]) {
                    val b = room[nb]
                    if (b >= 0 && b != a) keys.add(minOf(a, b).toLong() * count + maxOf(a, b))
                }
            }
            val edges = keys.toLongArray()
            for (i in 0 until edges.size - 1) {
                val j = i + random.nextInt(edges.size - i)
                val tmp = edges[i]
                edges[i] = edges[j]
                edges[j] = tmp
            }
            val parent = IntArray(count) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) r = parent[r]
                var y = x
                while (parent[y] != r) {
                    val next = parent[y]
                    parent[y] = r
                    y = next
                }
                return r
            }
            val tree = ArrayList<IntArray>()
            for (key in edges) {
                val a = (key / count).toInt()
                val b = (key % count).toInt()
                val ra = find(a)
                val rb = find(b)
                if (ra == rb) continue
                parent[ra] = rb
                tree.add(intArrayOf(a, b))
            }
            return tree
        }

        /** Marks every corner point of an edge between two different rooms in [blocked]. */
        private fun blockRoomBorders(geometry: SphereGeometry, room: IntArray, blocked: BooleanArray) {
            for (cell in 0 until geometry.cellCount) {
                if (room[cell] < 0) continue
                val start = geometry.cornerStart[cell]
                val end = geometry.cornerStart[cell + 1]
                for (k in start until end) {
                    val other = room[geometry.edgeNeighbor[k]]
                    if (other < 0 || other == room[cell]) continue
                    blocked[geometry.cornerPoint[k]] = true
                    blocked[geometry.cornerPoint[if (k + 1 < end) k + 1 else start]] = true
                }
            }
        }

        /**
         * Picks one passage for every edge of the room [tree] and adds it to [passages] as (near, far).
         *
         * The near room is the one the breadth-first search over the rooms reaches first, starting at the
         * room of [RoomSplit.firstSeed] and crossing tree edges and the portal pairs in [partners]; this
         * is the order [gates] uses. Among the cell edges between the two rooms the passage is drawn from
         * those whose straight row (see [straightRow]) from the near cell through the far cell keeps
         * [MIN_LINE_LENGTH] cells in the far room without crossing a wall in [separated]. Edges with a
         * hexagon on the near side come first, since only a hexagon can hold a line cell; when no edge
         * has such a row any edge between the rooms is drawn.
         */
        private fun choosePassages(
            geometry: SphereGeometry,
            rooms: RoomSplit,
            tree: List<IntArray>,
            partners: IntArray,
            separated: HashSet<Long>,
            random: Random,
            passages: MutableList<Pair<Int, Int>>,
        ) {
            val cellCount = geometry.cellCount
            val room = rooms.room
            // Rooms renumbered by their lowest cell, as BoardLayout numbers the regions.
            val region = IntArray(rooms.count) { -1 }
            var next = 0
            for (cell in 0 until cellCount) {
                val r = room[cell]
                if (r >= 0 && region[r] < 0) region[r] = next++
            }
            val joined = Array(rooms.count) { java.util.TreeSet<Int>() }
            for (edge in tree) {
                joined[region[edge[0]]].add(region[edge[1]])
                joined[region[edge[1]]].add(region[edge[0]])
            }
            for (cell in 0 until cellCount) {
                val partner = partners[cell]
                if (partner < 0) continue
                val a = region[room[cell]]
                val b = region[room[partner]]
                if (a != b) {
                    joined[a].add(b)
                    joined[b].add(a)
                }
            }
            val adjacent = Array(rooms.count) { joined[it].toIntArray() }
            val rank = IntArray(rooms.count)
            regionSearch(adjacent, region[room[rooms.firstSeed]], rank, IntArray(rooms.count))

            val best = ArrayList<Int>()
            val good = ArrayList<Int>()
            val any = ArrayList<Int>()
            for (edge in tree) {
                val near = if (rank[region[edge[0]]] < rank[region[edge[1]]]) edge[0] else edge[1]
                val far = if (near == edge[0]) edge[1] else edge[0]
                best.clear()
                good.clear()
                any.clear()
                for (cell in 0 until cellCount) {
                    if (room[cell] != near) continue
                    val start = geometry.cornerStart[cell]
                    for (k in start until geometry.cornerStart[cell + 1]) {
                        if (room[geometry.edgeNeighbor[k]] != far) continue
                        // Corner index of the edge: identifies both the near cell and the side.
                        any.add(k)
                        if (rowDepth(geometry, room, separated, cell, k - start) >= MIN_LINE_LENGTH) {
                            if (geometry.isPentagon(cell)) good.add(k) else best.add(k)
                        }
                    }
                }
                val pool = if (best.isNotEmpty()) best else if (good.isNotEmpty()) good else any
                val k = pool[random.nextInt(pool.size)]
                passages.add(Pair(cellOfCorner(geometry, k), geometry.edgeNeighbor[k]))
            }
        }

        /** Cell whose corner list holds corner index [k]. */
        private fun cellOfCorner(geometry: SphereGeometry, k: Int): Int {
            var low = 0
            var high = geometry.cellCount - 1
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (geometry.cornerStart[mid] <= k) low = mid else high = mid - 1
            }
            return low
        }

        /**
         * Number of cells at the start of the straight row from [cell] through edge [side] that lie in
         * the room across that edge, stopping at a wall in [separated]. Counts up to [MIN_LINE_LENGTH].
         */
        private fun rowDepth(geometry: SphereGeometry, room: IntArray, separated: HashSet<Long>, cell: Int, side: Int): Int {
            val row = straightRow(geometry, geometry.neighbors, cell, side, MIN_LINE_LENGTH)
            val target = room[row[0]]
            var depth = 0
            var previous = -1
            for (c in row) {
                if (room[c] != target) break
                if (previous >= 0 && separated.contains(pairKey(previous, c, geometry.cellCount))) break
                depth++
                previous = c
            }
            return depth
        }

        /**
         * The walls between rooms as polylines of corner indices: every cell edge between two rooms that
         * is not a passage in [open]. A polyline runs between points where one or three such edges meet
         * and goes on through points where two meet, so walls cross only at their ends. A closed ring
         * without such a point starts and ends at its lowest point.
         */
        private fun roomWalls(graph: PointGraph, room: IntArray, open: HashSet<Long>, cellCount: Int): List<IntArray> {
            val pointCount = graph.pointCount
            val border = BooleanArray(pointCount * 3)
            val degree = IntArray(pointCount)
            for (slot in 0 until pointCount * 3) {
                if (graph.adjacent[slot] < 0) continue
                val a = room[graph.cellA[slot]]
                val b = room[graph.cellB[slot]]
                if (a < 0 || b < 0 || a == b) continue
                if (open.contains(pairKey(graph.cellA[slot], graph.cellB[slot], cellCount))) continue
                border[slot] = true
                degree[slot / 3]++
            }
            val used = BooleanArray(pointCount * 3)
            val result = ArrayList<IntArray>()

            fun walk(start: Int, firstSlot: Int) {
                val path = ArrayList<Int>()
                path.add(start)
                var slot = firstSlot
                while (true) {
                    val next = graph.adjacent[slot]
                    used[slot] = true
                    used[graph.slotBetween(next, slot / 3)] = true
                    path.add(next)
                    if (degree[next] != 2 || next == start) break
                    var follow = -1
                    for (q in 0 until 3) {
                        val s = next * 3 + q
                        if (border[s] && !used[s]) {
                            follow = s
                            break
                        }
                    }
                    if (follow < 0) break
                    slot = follow
                }
                result.add(IntArray(path.size) { graph.cornerIndex[path[it]] })
            }

            for (pass in 0 until 2) {
                for (p in 0 until pointCount) {
                    // First the chains from their ends, then whatever is left: closed rings.
                    if (pass == 0 && (degree[p] == 0 || degree[p] == 2)) continue
                    for (q in 0 until 3) {
                        val s = p * 3 + q
                        if (border[s] && !used[s]) walk(p, s)
                    }
                }
            }
            return result
        }

        /** Smallest island accepted when [landCells] cells of land are split into [islandCount] islands. */
        fun minIslandSize(landCells: Int, islandCount: Int): Int =
            maxOf(MIN_ISLAND_CELLS, landCells / (2 * islandCount))

        /** Fewest ocean cells: the smallest [oceanShare] of the cells rounded up, so the share never falls below it. */
        private fun minOceanCells(cellCount: Int, oceanShare: ClosedFloatingPointRange<Double>): Int {
            val exact = cellCount * oceanShare.start
            val whole = exact.toInt()
            return if (whole < exact) whole + 1 else whole
        }

        /** Most ocean cells: the largest [oceanShare] of the cells rounded down, so the share never goes above it. */
        private fun maxOceanCells(cellCount: Int, oceanShare: ClosedFloatingPointRange<Double>): Int =
            (cellCount * oceanShare.endInclusive).toInt()

        /**
         * Island index per cell, -1 for ocean.
         *
         * The ocean size is drawn from [oceanShare] of the cells. Island seeds are
         * spread out: the first is random, each next one is drawn among the cells farthest from the seeds
         * so far. The islands then grow in turns, each by one random cell next to it, until the land
         * reaches its size. A cell joins an island only when no other island touches it, so islands stay
         * apart by at least one ocean cell and each one is connected. Small lakes (ocean pockets of up to
         * [MAX_LAKE_CELLS] cells touching a single island) become part of that island while the ocean
         * stays at its minimum share or above. A draw with an island below [minIslandSize] is thrown away
         * and drawn again. The islands stop growing when no free cell can join any of them, so the ocean
         * that keeps many islands apart can stay above the drawn size: on a small share most draws end
         * above its smallest value.
         */
        private fun growIslands(
            geometry: SphereGeometry,
            islandCount: Int,
            random: Random,
            oceanShare: ClosedFloatingPointRange<Double>,
        ): IntArray {
            val cellCount = geometry.cellCount
            val neighbors = geometry.neighbors
            val owner = IntArray(cellCount)
            val size = IntArray(islandCount)
            val frontier = Array(islandCount) { IntArray(cellCount) }
            val frontierSize = IntArray(islandCount)
            val queued = BooleanArray(islandCount * cellCount)
            val distance = IntArray(cellCount)
            val queue = IntArray(cellCount)
            val minOcean = minOceanCells(cellCount, oceanShare)
            val maxOcean = maxOceanCells(cellCount, oceanShare)
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
         * are scratch arrays of the cell count. With [area] given, steps stay on cells whose area is
         * [areaId], so distances run over that land only.
         */
        private fun farCell(
            neighbors: Array<IntArray>,
            owner: IntArray,
            distance: IntArray,
            queue: IntArray,
            random: Random,
            area: IntArray? = null,
            areaId: Int = 0,
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
                    if (distance[nb] < 0 && (area == null || area[nb] == areaId)) {
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
         * With [regions] (a board with rooms) the line cells stand at the passages instead, see
         * [placePassageLines]; this draws nothing for other cells.
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
            regions: RegionMap? = null,
        ) {
            // seenBy[c] = cells other than c whose scope holds c, as the scopes stand so far.
            val seenBy: IntArray? = if (keepEveryCellSeen) IntArray(geometry.cellCount) else null
            if (seenBy != null) {
                for (cell in 0 until geometry.cellCount) {
                    val scope = if (far[cell]) twoStepScope(links, cell) else links[cell]
                    for (other in scope) seenBy[other]++
                }
            }
            if (regions != null) {
                placePassageLines(geometry, links, far, partners, lineCount, random, lineSides, linePaths, seenBy, regions)
                return
            }
            val candidates = (0 until geometry.cellCount)
                .filter { !far[it] && !geometry.isPentagon(it) && islands[it] >= 0 && partners[it] < 0 }
                .toIntArray()
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
         * Line cells on a board with rooms: up to [lineCount] of them, at most one per passage.
         *
         * A line cell stands on the near cell of a passage (in the shallower region, see [gates]) and its
         * row starts through the passage edge: it begins on the far cell and runs straight into the
         * deeper room, so its number tells how many mines lie on the way in. A passage qualifies when its
         * near cell is a hexagon that is neither a far cell nor a portal and the row over [links] holds
         * at least [MIN_LINE_LENGTH] cells of the far region. The row may go on through further passages,
         * but every cell of it must lie in a region deeper than that of the near cell. Portal pairs hold
         * no line cells, and a row never goes through a portal (see [straightRow]).
         *
         * A near cell of two or more gates (portal pairs included) gets no line: its row would look
         * through one of them only. So every gate keeps its far cell in the scope of its near cell: a near
         * cell that is no line cell links to it, and a line cell's row starts on it.
         *
         * The qualifying passages are visited in random order like the candidates of [placeLines]. A
         * passage is skipped when its row would leave a land cell in no other cell's scope (with [seenBy])
         * or would break the invariant of [seesInward]: every land cell stays reachable from every root
         * cell that is not a line cell, along cells that each see the next and never step into a shallower
         * region. A board gets fewer line cells than [lineCount] when fewer passages qualify.
         */
        private fun placePassageLines(
            geometry: SphereGeometry,
            links: Array<IntArray>,
            far: BooleanArray,
            partners: IntArray,
            lineCount: Int,
            random: Random,
            lineSides: IntArray,
            linePaths: Array<IntArray>,
            seenBy: IntArray?,
            regions: RegionMap,
        ) {
            val maxLength = maxLineLength(geometry)
            val cellCount = geometry.cellCount
            val gateCount = IntArray(cellCount)
            for ((near, _) in regions.gates) gateCount[near]++
            val cells = ArrayList<Int>()
            val sides = ArrayList<Int>()
            val rows = ArrayList<IntArray>()
            for ((near, across) in regions.gates) {
                if (partners[near] == across || gateCount[near] > 1) continue
                if (far[near] || geometry.isPentagon(near) || partners[near] >= 0) continue
                val start = geometry.cornerStart[near]
                var side = -1
                for (k in start until geometry.cornerStart[near + 1]) {
                    if (geometry.edgeNeighbor[k] == across) side = k - start
                }
                val row = straightRow(geometry, links, near, side, maxLength)
                if (row.isEmpty() || row[0] != across) continue
                val nearDepth = regions.depth[regions.of[near]]
                val farRegion = regions.of[across]
                if (row.any { regions.depth[regions.of[it]] <= nearDepth }) continue
                if (row.count { regions.of[it] == farRegion } < MIN_LINE_LENGTH) continue
                cells.add(near)
                sides.add(side)
                rows.add(row)
            }
            // Scopes as they stand so far; near cells are never far cells, so only line rows replace links.
            val scopes = Array(cellCount) { if (far[it]) twoStepScope(links, it) else links[it] }
            val order = IntArray(cells.size) { it }
            var placed = 0
            var remaining = order.size
            while (placed < lineCount && remaining > 0) {
                // Partial Fisher-Yates over the passages, as in placeLines.
                val pick = random.nextInt(remaining)
                val index = order[pick]
                order[pick] = order[remaining - 1]
                remaining--
                val cell = cells[index]
                val row = rows[index]
                if (seenBy != null && leavesUnseen(links[cell], row, seenBy)) continue
                lineSides[cell] = sides[index]
                scopes[cell] = row
                if (!seesInward(scopes, lineSides, regions)) {
                    lineSides[cell] = -1
                    scopes[cell] = links[cell]
                    continue
                }
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
         * Invariant of the line cells on a board with rooms. Cell u sees cell v when v is in [scopes][u];
         * a step from u to v goes inward when the region of v is at least as deep as that of u. True when
         * every land cell is reachable over inward steps from every root cell (depth 0) that is not a line
         * cell ([lineSides] -1). So no region is seen only from inside itself or from deeper regions, and
         * each one can be solved before any deeper region is opened.
         *
         * Without line cells every scope holds the cell's [links], so the invariant holds: each region is
         * connected over its links and the near cell of a gate links to its far cell. A line cell keeps
         * only the inward row, which may cut a region apart or hide the far cell of another gate.
         */
        private fun seesInward(scopes: Array<IntArray>, lineSides: IntArray, regions: RegionMap): Boolean {
            val of = regions.of
            val depth = regions.depth
            val cellCount = scopes.size
            fun isRoot(cell: Int) = of[cell] >= 0 && depth[of[cell]] == 0
            val source = (0 until cellCount).firstOrNull { isRoot(it) && lineSides[it] < 0 } ?: return false

            // Forward: inward steps from the source reach every land cell.
            val reached = BooleanArray(cellCount)
            val queue = IntArray(cellCount)
            var head = 0
            var tail = 0
            reached[source] = true
            queue[tail++] = source
            while (head < tail) {
                val cell = queue[head++]
                val cellDepth = depth[of[cell]]
                for (other in scopes[cell]) {
                    if (reached[other] || depth[of[other]] < cellDepth) continue
                    reached[other] = true
                    queue[tail++] = other
                }
            }
            for (cell in 0 until cellCount) {
                if (of[cell] >= 0 && !reached[cell]) return false
            }

            // Backward: every other root cell that is not a line cell reaches the source. Inward paths
            // between root cells never leave the root region, so only root cells are scanned.
            val back = BooleanArray(cellCount)
            back[source] = true
            var changed = true
            while (changed) {
                changed = false
                for (cell in 0 until cellCount) {
                    if (back[cell] || !isRoot(cell)) continue
                    if (scopes[cell].any { back[it] }) {
                        back[cell] = true
                        changed = true
                    }
                }
            }
            for (cell in 0 until cellCount) {
                if (isRoot(cell) && lineSides[cell] < 0 && !back[cell]) return false
            }
            return true
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

        /**
         * Breadth-first search over a graph of regions from [root]: rank[r] is the position of region r
         * in the search order and depth[r] its distance from [root], both -1 when r is not reached.
         * [adjacent] lists must be sorted, so the order depends on the region numbers only.
         */
        private fun regionSearch(adjacent: Array<IntArray>, root: Int, rank: IntArray, depth: IntArray) {
            rank.fill(-1)
            depth.fill(-1)
            val queue = IntArray(adjacent.size)
            var head = 0
            var tail = 0
            queue[tail++] = root
            rank[root] = 0
            depth[root] = 0
            while (head < tail) {
                val r = queue[head++]
                for (other in adjacent[r]) {
                    if (rank[other] >= 0) continue
                    rank[other] = tail
                    depth[other] = depth[r] + 1
                    queue[tail++] = other
                }
            }
        }

        /**
         * Regions and gates of a layout (see [BoardLayout]): components of the land over [links] without
         * the [passages] and the portal pairs of [partners], numbered by their lowest cell, then a
         * breadth-first search over the gates from the region of [rootCell] (region 0 when -1).
         */
        private fun buildRegions(
            islands: IntArray,
            links: Array<IntArray>,
            partners: IntArray,
            passages: List<Pair<Int, Int>>,
            rootCell: Int,
        ): RegionMap {
            val cellCount = islands.size
            val open = HashSet<Long>()
            for ((a, b) in passages) open.add(pairKey(a, b, cellCount))
            val of = IntArray(cellCount) { -1 }
            val queue = IntArray(cellCount)
            var count = 0
            for (first in 0 until cellCount) {
                if (islands[first] < 0 || of[first] >= 0) continue
                var head = 0
                var tail = 0
                queue[tail++] = first
                of[first] = count
                while (head < tail) {
                    val cell = queue[head++]
                    for (nb in links[cell]) {
                        if (of[nb] >= 0 || partners[cell] == nb || open.contains(pairKey(cell, nb, cellCount))) continue
                        of[nb] = count
                        queue[tail++] = nb
                    }
                }
                count++
            }

            val raw = ArrayList<Pair<Int, Int>>(passages)
            for (cell in 0 until cellCount) {
                val partner = partners[cell]
                if (partner > cell && of[cell] != of[partner]) raw.add(Pair(cell, partner))
            }
            val sets = Array(count) { java.util.TreeSet<Int>() }
            for ((a, b) in raw) {
                sets[of[a]].add(of[b])
                sets[of[b]].add(of[a])
            }
            val adjacent = Array(count) { sets[it].toIntArray() }
            val rank = IntArray(count)
            val depth = IntArray(count)
            if (count > 0) {
                regionSearch(adjacent, if (rootCell >= 0) of[rootCell] else 0, rank, depth)
                check(rank.all { it >= 0 }) { "a region is not reachable through the gates" }
            }

            val gates = raw.map { (a, b) -> if (rank[of[a]] <= rank[of[b]]) Pair(a, b) else Pair(b, a) }
                .sortedWith(
                    compareBy<Pair<Int, Int>>({ rank[of[it.first]] }, { rank[of[it.second]] }, { it.first }, { it.second }),
                )

            // A gate is a bridge when it is the only gate between its two regions and they fall apart without it.
            val lockable = BooleanArray(gates.size)
            val seen = BooleanArray(count)
            for ((index, gate) in gates.withIndex()) {
                val from = of[gate.first]
                val to = of[gate.second]
                if (gates.count { setOf(of[it.first], of[it.second]) == setOf(from, to) } > 1) continue
                seen.fill(false)
                var head = 0
                var tail = 0
                queue[tail++] = from
                seen[from] = true
                while (head < tail) {
                    val r = queue[head++]
                    for (other in adjacent[r]) {
                        if (seen[other] || (r == from && other == to) || (r == to && other == from)) continue
                        seen[other] = true
                        queue[tail++] = other
                    }
                }
                lockable[index] = !seen[to]
            }
            return RegionMap(of, count, depth, gates, lockable)
        }
    }

    /** Room index per cell (-1 for ocean), number of rooms and the first room seed (-1 if none was drawn). */
    private class RoomSplit(val room: IntArray, val count: Int, val firstSeed: Int)

    /** Result of [buildRegions]: region per cell, region count, depth per region, gates and their bridge flags. */
    private class RegionMap(
        val of: IntArray,
        val count: Int,
        val depth: IntArray,
        val gates: List<Pair<Int, Int>>,
        val lockable: BooleanArray,
    )

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
