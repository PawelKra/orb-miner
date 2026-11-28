package com.example.spheresweeper.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RoomLayoutTest {

    private val geometries: Map<BoardSize, SphereGeometry> =
        BoardSize.values().associateWith { SphereGeometry.build(it.frequency) }

    private fun layout(size: BoardSize, random: Random, rooms: Int, islands: Int = 0, portals: Int = 0): BoardLayout =
        BoardLayout.generate(
            geometries.getValue(size), size.walls, size.wallMinLength, size.wallMaxLength, size.farCells,
            random, size.lineCells, islands, portals, rooms,
        )

    /** Board setups with rooms: size, rooms, islands, extra portals. */
    private val setups = listOf(
        Setup(BoardSize.SMALL, 3, 0, 0),
        Setup(BoardSize.MEDIUM, 4, 0, 1),
        Setup(BoardSize.LARGE, 5, 0, 1),
        Setup(BoardSize.HUGE, 6, 2, 1),
        Setup(BoardSize.GIANT, 7, 0, 2),
        Setup(BoardSize.GIANT, 9, 3, 2),
    )

    private data class Setup(val size: BoardSize, val rooms: Int, val islands: Int, val portals: Int) {
        override fun toString() = "$size rooms $rooms islands $islands portals $portals"
    }

    private fun forEachRoomLayout(seeds: Int, body: (String, SphereGeometry, BoardLayout) -> Unit) =
        forEachRoomSetup(seeds) { label, _, geometry, layout -> body(label, geometry, layout) }

    private fun forEachRoomSetup(seeds: Int, body: (String, Setup, SphereGeometry, BoardLayout) -> Unit) {
        for (setup in setups) {
            val geometry = geometries.getValue(setup.size)
            for (seed in 0 until seeds) {
                val layout = layout(setup.size, Random(seed), setup.rooms, setup.islands, setup.portals)
                body("$setup seed $seed", setup, geometry, layout)
            }
        }
    }

    @Test
    fun zeroOrOneRoomKeepsTheLayoutAndTheDraws() {
        for (size in BoardSize.values()) {
            val geometry = geometries.getValue(size)
            val islandOptions = listOf(0, minOf(size.islands, BoardLayout.maxIslandCount(geometry))).distinct()
            for (islands in islandOptions) {
                for (seed in 0 until 8) {
                    val label = "$size islands $islands seed $seed"
                    val plainRandom = Random(seed)
                    val plain = BoardLayout.generate(
                        geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells,
                        plainRandom, size.lineCells, islands, size.portals,
                    )
                    val plainNext = plainRandom.nextLong()
                    for (rooms in listOf(0, 1)) {
                        val random = Random(seed)
                        val other = layout(size, random, rooms, islands, size.portals)
                        assertSameLayout("$label rooms $rooms", geometry, plain, other)
                        assertEquals("$label rooms $rooms next draw", plainNext, random.nextLong())
                        assertFalse("$label rooms $rooms has rooms", other.hasRooms)
                    }
                    // The overload taking a BoardSize passes the room count on.
                    val sizedRandom = Random(seed)
                    val sized = BoardLayout.generate(geometry, size, sizedRandom, 0)
                    val sizedPlainRandom = Random(seed)
                    assertSameLayout("$label sized", geometry, BoardLayout.generate(geometry, size, sizedPlainRandom), sized)
                    assertEquals("$label sized next draw", sizedPlainRandom.nextLong(), sizedRandom.nextLong())
                }
            }
        }
    }

    @Test
    fun roomsHaveRequestedCountAndMinimumSize() {
        forEachRoomSetup(12) { label, setup, geometry, layout ->
            assertTrue("$label has rooms", layout.hasRooms)
            val sizes = IntArray(layout.regionCount)
            val islandOfRegion = IntArray(layout.regionCount) { -1 }
            for (cell in 0 until geometry.cellCount) {
                val region = layout.regionOf(cell)
                assertEquals("$label cell $cell presence", layout.isPresent(cell), region >= 0)
                if (region < 0) continue
                sizes[region]++
                if (islandOfRegion[region] < 0) islandOfRegion[region] = layout.islandOf(cell)
                assertEquals("$label room $region spans islands", islandOfRegion[region], layout.islandOf(cell))
            }
            val islandCells = IntArray(layout.islandCount)
            for (cell in 0 until geometry.cellCount) if (layout.isPresent(cell)) islandCells[layout.islandOf(cell)]++
            for (island in 0 until layout.islandCount) {
                val rooms = (0 until layout.regionCount).filter { islandOfRegion[it] == island }
                assertTrue("$label island $island without a room", rooms.isNotEmpty())
                if (rooms.size < 2) continue
                val minSize = BoardLayout.minRoomSize(islandCells[island], rooms.size)
                assertTrue("$label min size $minSize", minSize >= BoardLayout.MIN_ROOM_CELLS)
                for (room in rooms) assertTrue("$label room $room has ${sizes[room]} cells", sizes[room] >= minSize)
            }
            if (layout.islandCount == 1) {
                assertEquals("$label room count", setup.rooms, layout.regionCount)
            } else {
                assertTrue("$label room count", layout.regionCount > layout.islandCount)
            }
        }
    }

    @Test
    fun roomsAreSeparatedByWallsExceptOnePassagePerTreeEdge() {
        forEachRoomLayout(12) { label, geometry, layout ->
            val cellCount = geometry.cellCount
            val passages = layout.gates.filter { layout.portalPartner(it.first) != it.second }
            val passageKeys = passages.map { key(it.first, it.second) }.toSet()
            assertEquals("$label passages are distinct", passages.size, passageKeys.size)
            // A spanning tree on every island: rooms - islands passages, each between two touching rooms.
            assertEquals("$label passage count", layout.regionCount - layout.islandCount, passages.size)
            val roomPairs = HashSet<Pair<Int, Int>>()
            for ((near, far) in passages) {
                assertTrue("$label passage $near-$far are neighbours", geometry.neighbors[near].contains(far))
                assertTrue("$label passage $near-$far linked", layout.links[near].contains(far))
                val a = layout.regionOf(near)
                val b = layout.regionOf(far)
                assertTrue("$label passage $near-$far inside one room", a != b)
                assertTrue("$label two passages between rooms $a and $b", roomPairs.add(Pair(minOf(a, b), maxOf(a, b))))
            }
            for (cell in 0 until cellCount) {
                if (!layout.isPresent(cell)) continue
                for (nb in geometry.neighbors[cell]) {
                    if (!layout.isPresent(nb) || layout.regionOf(nb) == layout.regionOf(cell)) continue
                    val isPassage = passageKeys.contains(key(cell, nb))
                    assertEquals("$label $cell-$nb across rooms", isPassage, layout.links[cell].contains(nb))
                }
            }

            // The drawn walls cover exactly the edges missing from links, each once.
            val across = cornerPairs(geometry)
            val drawn = HashSet<Pair<Int, Int>>()
            for (wall in layout.walls) {
                for (k in 0 until wall.size - 1) {
                    val pair = across.getValue(pointKey(geometry, wall[k], wall[k + 1]))
                    assertTrue("$label edge $pair drawn twice", drawn.add(pair))
                }
            }
            for (cell in 0 until cellCount) {
                if (!layout.isPresent(cell)) continue
                for (nb in geometry.neighbors[cell]) {
                    if (!layout.isPresent(nb)) continue
                    val pair = Pair(minOf(cell, nb), maxOf(cell, nb))
                    assertEquals("$label edge $pair wall vs links", !layout.links[cell].contains(nb), drawn.contains(pair))
                }
            }

            // Walls are cut where three wall edges meet: inside a wall every point has exactly two.
            val degree = HashMap<Int, Int>()
            for (wall in layout.walls) {
                for (k in 0 until wall.size - 1) {
                    for (corner in intArrayOf(wall[k], wall[k + 1])) {
                        val point = geometry.cornerPoint[corner]
                        degree[point] = (degree[point] ?: 0) + 1
                    }
                }
            }
            for (wall in layout.walls) {
                for (k in 1 until wall.size - 1) {
                    val point = geometry.cornerPoint[wall[k]]
                    assertEquals("$label wall passes through point $point", 2, degree[point])
                }
            }
        }
    }

    @Test
    fun linksStayConnectedOnEveryIsland() {
        forEachRoomLayout(12) { label, geometry, layout ->
            val seen = BooleanArray(geometry.cellCount)
            var parts = 0
            for (first in 0 until geometry.cellCount) {
                if (!layout.isPresent(first) || seen[first]) continue
                parts++
                val island = layout.islandOf(first)
                val queue = ArrayDeque<Int>()
                queue.add(first)
                seen[first] = true
                while (queue.isNotEmpty()) {
                    val cell = queue.removeFirst()
                    for (nb in layout.links[cell]) {
                        if (layout.portalPartner(cell) == nb || seen[nb]) continue
                        assertEquals("$label link $cell-$nb leaves the island", island, layout.islandOf(nb))
                        seen[nb] = true
                        queue.add(nb)
                    }
                }
            }
            assertEquals("$label parts", layout.islandCount, parts)
        }
    }

    @Test
    fun openWallsStayOffRoomBorders() {
        var openWalls = 0
        forEachRoomLayout(12) { label, geometry, layout ->
            val across = cornerPairs(geometry)
            val borderPoints = HashSet<Int>()
            for (cell in 0 until geometry.cellCount) {
                if (!layout.isPresent(cell)) continue
                val start = geometry.cornerStart[cell]
                val end = geometry.cornerStart[cell + 1]
                for (k in start until end) {
                    val nb = geometry.edgeNeighbor[k]
                    if (!layout.isPresent(nb) || layout.regionOf(nb) == layout.regionOf(cell)) continue
                    borderPoints.add(geometry.cornerPoint[k])
                    borderPoints.add(geometry.cornerPoint[if (k + 1 < end) k + 1 else start])
                }
            }
            for (wall in layout.walls) {
                val (a, b) = across.getValue(pointKey(geometry, wall[0], wall[1]))
                if (layout.regionOf(a) != layout.regionOf(b)) continue
                openWalls++
                for (corner in wall) {
                    assertFalse("$label open wall touches a room border", borderPoints.contains(geometry.cornerPoint[corner]))
                }
                for (k in 0 until wall.size - 1) {
                    val (x, y) = across.getValue(pointKey(geometry, wall[k], wall[k + 1]))
                    assertEquals("$label open wall crosses rooms", layout.regionOf(x), layout.regionOf(y))
                }
            }
        }
        assertTrue("open walls on boards with rooms", openWalls > 0)
    }

    @Test
    fun passageRowRunsIntoTheFarRoomWhereverPossible() {
        var deep = 0
        var total = 0
        forEachRoomLayout(15) { label, geometry, layout ->
            for ((near, far) in layout.gates) {
                if (layout.portalPartner(near) == far) continue
                total++
                val nearRoom = layout.regionOf(near)
                val farRoom = layout.regionOf(far)
                val chosen = rowDepth(geometry, layout, near, far)
                var possible = 0
                for (cell in 0 until geometry.cellCount) {
                    if (layout.regionOf(cell) != nearRoom) continue
                    for (nb in geometry.neighbors[cell]) {
                        if (layout.regionOf(nb) == farRoom) possible = maxOf(possible, rowDepth(geometry, layout, cell, nb))
                    }
                }
                if (possible >= BoardLayout.MIN_LINE_LENGTH) {
                    assertTrue("$label passage $near-$far row $chosen", chosen >= BoardLayout.MIN_LINE_LENGTH)
                    deep++
                }
                // The row through the passage over the final links starts with those cells.
                val side = localEdge(geometry, near, far)
                val row = BoardLayout.straightRow(geometry, layout.links, near, side, BoardLayout.MIN_LINE_LENGTH)
                assertTrue("$label row over links", row.size >= minOf(chosen, BoardLayout.MIN_LINE_LENGTH))
            }
        }
        assertTrue("deep passages $deep of $total", deep * 2 > total)
    }

    @Test
    fun regionsGatesAndDepthsAgree() {
        val boards = ArrayList<Pair<String, BoardLayout>>()
        forEachRoomLayout(10) { label, _, layout -> boards.add(Pair(label, layout)) }
        // Islands and portals without rooms: regions are the islands.
        for (size in listOf(BoardSize.HUGE, BoardSize.GIANT)) {
            val geometry = geometries.getValue(size)
            for (seed in 0 until 10) {
                val islands = minOf(3, BoardLayout.maxIslandCount(geometry))
                val layout = layout(size, Random(seed), 0, islands, 3)
                assertFalse(layout.hasRooms)
                assertEquals("$size seed $seed regions", layout.islandCount, layout.regionCount)
                val islandOfRegion = HashMap<Int, Int>()
                for (cell in 0 until geometry.cellCount) {
                    if (!layout.isPresent(cell)) continue
                    assertEquals(islandOfRegion.getOrPut(layout.regionOf(cell)) { layout.islandOf(cell) }, layout.islandOf(cell))
                }
                for ((near, far) in layout.gates) assertEquals(far, layout.portalPartner(near))
                val crossing = (0 until geometry.cellCount).count {
                    layout.isPortal(it) && it < layout.portalPartner(it) && layout.islandOf(it) != layout.islandOf(layout.portalPartner(it))
                }
                assertEquals("$size seed $seed gates", crossing, layout.gates.size)
                boards.add(Pair("$size islands seed $seed", layout))
            }
        }
        // Classic and plain boards: one region, no gates.
        val classic = BoardLayout.classic(geometries.getValue(BoardSize.MEDIUM))
        assertEquals(1, classic.regionCount)
        assertEquals(0, classic.gates.size)
        assertEquals(0, classic.regionDepth(0))
        boards.add(Pair("plain", layout(BoardSize.LARGE, Random(1), 0)))

        var lockable = 0
        var open = 0
        for ((label, layout) in boards) {
            checkRegions(label, layout)
            for (i in layout.gates.indices) if (layout.isLockableGate(i)) lockable++ else open++
        }
        assertTrue("lockable gates $lockable", lockable > 0)
        assertTrue("gates with a way around $open", open > 0)
    }

    @Test
    fun farScopesDoNotCrossRoomWalls() {
        forEachRoomLayout(12) { label, geometry, layout ->
            val gateKeys = layout.gates.map { key(it.first, it.second) }.toSet()
            fun step(a: Int, b: Int): Boolean =
                layout.regionOf(a) == layout.regionOf(b) || gateKeys.contains(key(a, b))
            for (cell in 0 until geometry.cellCount) {
                for (nb in layout.links[cell]) {
                    assertTrue("$label link $cell-$nb crosses a room wall", step(cell, nb) || layout.portalPartner(cell) == nb)
                }
                if (!layout.isFar(cell)) continue
                for (other in layout.scopes[cell]) {
                    val direct = layout.links[cell].contains(other)
                    val twoSteps = layout.links[cell].any { mid -> layout.links[mid].contains(other) }
                    assertTrue("$label far $cell sees $other through a wall", direct || twoSteps)
                }
            }
        }
    }

    @Test
    fun roomLayoutsRepeatForTheSameSeed() {
        val size = BoardSize.GIANT
        val geometry = geometries.getValue(size)
        val a = layout(size, Random(21), 7, 0, 2)
        val b = layout(size, Random(21), 7, 0, 2)
        assertSameLayout("GIANT seed 21", geometry, a, b)
        assertEquals(a.gates, b.gates)
    }

    /**
     * Regions are the components over links without the gate pairs and the in-region portal pairs,
     * numbered by their lowest cell; depths are a breadth-first search over the gates from the single
     * region of depth 0; gates are oriented and sorted by that search; lockable gates are the bridges.
     */
    private fun checkRegions(label: String, layout: BoardLayout) {
        val cellCount = layout.links.size
        val gateKeys = layout.gates.map { key(it.first, it.second) }.toSet()
        val region = IntArray(cellCount) { -1 }
        var count = 0
        for (first in 0 until cellCount) {
            if (!layout.isPresent(first) || region[first] >= 0) continue
            val queue = ArrayDeque<Int>()
            queue.add(first)
            region[first] = count
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                for (nb in layout.links[cell]) {
                    if (region[nb] >= 0 || layout.portalPartner(cell) == nb || gateKeys.contains(key(cell, nb))) continue
                    region[nb] = count
                    queue.add(nb)
                }
            }
            count++
        }
        assertEquals("$label region count", count, layout.regionCount)
        for (cell in 0 until cellCount) assertEquals("$label region of $cell", region[cell], layout.regionOf(cell))

        val roots = (0 until count).filter { layout.regionDepth(it) == 0 }
        assertEquals("$label roots", 1, roots.size)
        val adjacent = Array(count) { HashSet<Int>() }
        for ((near, far) in layout.gates) {
            val a = region[near]
            val b = region[far]
            assertTrue("$label gate $near-$far inside a region", a != b)
            adjacent[a].add(b)
            adjacent[b].add(a)
        }
        val depth = IntArray(count) { -1 }
        depth[roots[0]] = 0
        val queue = ArrayDeque<Int>()
        queue.add(roots[0])
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            for (o in adjacent[r]) if (depth[o] < 0) {
                depth[o] = depth[r] + 1
                queue.add(o)
            }
        }
        for (r in 0 until count) assertEquals("$label depth of $r", depth[r], layout.regionDepth(r))

        var previous = -1
        for ((i, gate) in layout.gates.withIndex()) {
            val (near, far) = gate
            val nearDepth = layout.regionDepth(region[near])
            assertTrue("$label gate $i near deeper than far", nearDepth <= layout.regionDepth(region[far]))
            assertTrue("$label gate $i out of order", nearDepth >= previous)
            previous = nearDepth
            val isPortal = layout.portalPartner(near) == far
            val isPassage = geometryOf(layout).neighbors[near].contains(far) && layout.links[near].contains(far)
            assertTrue("$label gate $i is neither passage nor portal", isPortal || isPassage)

            // Bridge: removing this gate leaves the far region unreachable from the near one.
            val seen = BooleanArray(count)
            seen[region[near]] = true
            val q = ArrayDeque<Int>()
            q.add(region[near])
            while (q.isNotEmpty()) {
                val r = q.removeFirst()
                for ((j, other) in layout.gates.withIndex()) {
                    if (j == i) continue
                    val a = region[other.first]
                    val b = region[other.second]
                    val next = if (a == r) b else if (b == r) a else continue
                    if (!seen[next]) {
                        seen[next] = true
                        q.add(next)
                    }
                }
            }
            assertEquals("$label gate $i bridge", !seen[region[far]], layout.isLockableGate(i))
        }
    }

    private fun geometryOf(layout: BoardLayout): SphereGeometry =
        geometries.values.first { it.cellCount == layout.links.size }

    /**
     * Cells in [far]'s region at the start of the straight row from [near] through [far], stopping at
     * a wall inside that region; up to [BoardLayout.MIN_LINE_LENGTH].
     */
    private fun rowDepth(geometry: SphereGeometry, layout: BoardLayout, near: Int, far: Int): Int {
        val row = BoardLayout.straightRow(geometry, geometry.neighbors, near, localEdge(geometry, near, far), BoardLayout.MIN_LINE_LENGTH)
        val room = layout.regionOf(far)
        var depth = 0
        var previous = -1
        for (c in row) {
            if (layout.regionOf(c) != room) break
            if (previous >= 0 && !layout.links[previous].contains(c)) break
            depth++
            previous = c
        }
        return depth
    }

    private fun assertSameLayout(label: String, geometry: SphereGeometry, a: BoardLayout, b: BoardLayout) {
        assertEquals("$label wall count", a.walls.size, b.walls.size)
        for (w in a.walls.indices) assertArrayEquals("$label wall $w", a.walls[w], b.walls[w])
        assertEquals("$label portal pairs", a.portalPairCount, b.portalPairCount)
        assertEquals("$label islands", a.islandCount, b.islandCount)
        for (cell in 0 until geometry.cellCount) {
            assertArrayEquals("$label links $cell", a.links[cell], b.links[cell])
            assertArrayEquals("$label scope $cell", a.scopes[cell], b.scopes[cell])
            assertEquals("$label island $cell", a.islandOf(cell), b.islandOf(cell))
            assertEquals("$label far $cell", a.isFar(cell), b.isFar(cell))
            assertEquals("$label line side $cell", a.lineSide(cell), b.lineSide(cell))
            assertArrayEquals("$label line path $cell", a.linePath(cell), b.linePath(cell))
            assertEquals("$label partner $cell", a.portalPartner(cell), b.portalPartner(cell))
            assertEquals("$label pair $cell", a.portalPair(cell), b.portalPair(cell))
            assertEquals("$label region $cell", a.regionOf(cell), b.regionOf(cell))
        }
        assertEquals("$label gates", a.gates, b.gates)
    }

    private fun key(a: Int, b: Int): Pair<Int, Int> = Pair(minOf(a, b), maxOf(a, b))

    /** Index 0 until cornerCount(cell) of the edge [cell] shares with [other]. */
    private fun localEdge(geometry: SphereGeometry, cell: Int, other: Int): Int {
        val start = geometry.cornerStart[cell]
        for (k in start until geometry.cornerStart[cell + 1]) {
            if (geometry.edgeNeighbor[k] == other) return k - start
        }
        throw AssertionError("cells $cell and $other are not neighbours")
    }

    private fun pointKey(geometry: SphereGeometry, cornerA: Int, cornerB: Int): Pair<Int, Int> {
        val a = geometry.cornerPoint[cornerA]
        val b = geometry.cornerPoint[cornerB]
        return Pair(minOf(a, b), maxOf(a, b))
    }

    /** Cell pair across every edge, keyed by its two corner points. */
    private fun cornerPairs(geometry: SphereGeometry): Map<Pair<Int, Int>, Pair<Int, Int>> {
        val map = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        for (cell in 0 until geometry.cellCount) {
            val start = geometry.cornerStart[cell]
            val end = geometry.cornerStart[cell + 1]
            for (k in start until end) {
                val next = if (k + 1 < end) k + 1 else start
                map[pointKey(geometry, k, next)] = key(cell, geometry.edgeNeighbor[k])
            }
        }
        return map
    }
}
