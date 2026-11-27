package com.example.spheresweeper.logic

import kotlin.random.Random

/**
 * Per-game board features on top of the fixed [SphereGeometry]: walls and far-reaching cells.
 *
 * A wall is a chain of cell edges. Two cells separated by a wall do not see each other: the number of
 * one does not count a mine in the other, and reveals do not spread across. [links] is the neighbour
 * graph with those pairs removed.
 *
 * A far cell's number counts every cell reachable in at most two steps through [links], so a wall
 * blocks that view too. [scopes] lists, for every cell, the cells its number counts; it is what
 * [MinesweeperBoard] uses as its neighbour lists.
 */
class BoardLayout private constructor(
    /** Each wall as a polyline of corner indices into [SphereGeometry.corners] (index k = x,y,z at 3k). */
    val walls: Array<IntArray>,
    private val far: BooleanArray,
    /** links[i] = neighbours of i not separated from it by a wall, sorted ascending. */
    val links: Array<IntArray>,
    /** scopes[i] = cells whose mines the number on i counts, sorted ascending. */
    val scopes: Array<IntArray>,
) {
    fun isFar(cell: Int): Boolean = far[cell]

    val farCount: Int get() = far.count { it }

    companion object {
        private const val WALL_ATTEMPTS = 60

        /** Most wall edges a single cell may have; keeps walls from wrapping tightly around one cell. */
        private const val MAX_WALL_EDGES_PER_CELL = 2

        /** Plain board: no walls, no far cells. */
        fun classic(geometry: SphereGeometry): BoardLayout =
            BoardLayout(emptyArray(), BooleanArray(geometry.cellCount), geometry.neighbors, geometry.neighbors)

        fun generate(geometry: SphereGeometry, size: BoardSize, random: Random = Random.Default): BoardLayout =
            generate(geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, random)

        /**
         * Places up to [wallCount] walls of [minLength]..[maxLength] edges and [farCount] far cells.
         * Walls never touch each other or themselves, so they never close a loop and never cut a
         * region off. A wall that cannot reach [minLength] after several attempts is skipped.
         */
        fun generate(
            geometry: SphereGeometry,
            wallCount: Int,
            minLength: Int,
            maxLength: Int,
            farCount: Int,
            random: Random = Random.Default,
        ): BoardLayout {
            val cellCount = geometry.cellCount
            val graph = PointGraph(geometry)
            val walls = ArrayList<IntArray>()
            val separated = HashSet<Long>()
            if (wallCount > 0 && maxLength > 0) {
                val blocked = BooleanArray(graph.pointCount)
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
                geometry.neighbors[cell].filter { !separated.contains(pairKey(cell, it, cellCount)) }.toIntArray()
            }

            val far = BooleanArray(cellCount)
            val farTarget = farCount.coerceIn(0, cellCount)
            val order = IntArray(cellCount) { it }
            for (i in 0 until farTarget) {
                val j = i + random.nextInt(cellCount - i)
                val tmp = order[i]
                order[i] = order[j]
                order[j] = tmp
                far[order[i]] = true
            }

            val scopes = Array(cellCount) { cell ->
                if (far[cell]) twoStepScope(links, cell) else links[cell]
            }
            return BoardLayout(walls.toTypedArray(), far, links, scopes)
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
