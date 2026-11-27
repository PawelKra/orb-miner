package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class IslandPortalTest {

    private val geometries: Map<BoardSize, SphereGeometry> =
        BoardSize.values().associateWith { SphereGeometry.build(it.frequency) }

    private fun layout(size: BoardSize, seed: Int, islands: Int, portals: Int): BoardLayout =
        BoardLayout.generate(
            geometries.getValue(size), size.walls, size.wallMinLength, size.wallMaxLength, size.farCells,
            Random(seed), size.lineCells, islands, portals,
        )

    @Test
    fun landSplitsIntoRequestedIslandsOfMinimumSize() {
        for (size in BoardSize.values()) {
            val geometry = geometries.getValue(size)
            val cellCount = geometry.cellCount
            for (islands in 1..minOf(4, BoardLayout.maxIslandCount(geometry))) {
                for (seed in 0 until 15) {
                    val label = "$size islands $islands seed $seed"
                    val layout = layout(size, seed, islands, 2)
                    assertEquals("$label island count", islands, layout.islandCount)

                    val ocean = cellCount - layout.presentCount
                    assertTrue("$label ocean $ocean too small", ocean >= cellCount * BoardLayout.MIN_OCEAN_SHARE)
                    assertTrue("$label ocean $ocean too large", ocean <= cellCount * BoardLayout.MAX_OCEAN_SHARE)

                    // Components over links without the portal pairs are exactly the islands.
                    val component = components(layout, withPortals = false)
                    val sizes = IntArray(islands)
                    val islandOfComponent = HashMap<Int, Int>()
                    for (cell in 0 until cellCount) {
                        val island = layout.islandOf(cell)
                        assertEquals("$label cell $cell presence", island >= 0, layout.isPresent(cell))
                        if (island < 0) continue
                        sizes[island]++
                        val known = islandOfComponent.getOrPut(component[cell]) { island }
                        assertEquals("$label cell $cell component spans islands", known, island)
                    }
                    assertEquals("$label components", islands, islandOfComponent.size)
                    val minSize = BoardLayout.minIslandSize(layout.presentCount, islands)
                    for (island in 0 until islands) {
                        assertTrue("$label island $island has ${sizes[island]} cells", sizes[island] >= minSize)
                    }

                    // With the portal pairs all land is one graph.
                    val joined = components(layout, withPortals = true)
                    val ids = (0 until cellCount).filter { layout.isPresent(it) }.map { joined[it] }.toSet()
                    assertEquals("$label islands joined by portals", 1, ids.size)
                }
            }
        }
    }

    @Test
    fun oceanCellsTakeNoPartInAnyList() {
        for (size in listOf(BoardSize.MEDIUM, BoardSize.HUGE, BoardSize.GIANT)) {
            val geometry = geometries.getValue(size)
            for (seed in 0 until 10) {
                val label = "$size seed $seed"
                val layout = layout(size, seed, minOf(3, BoardLayout.maxIslandCount(geometry)), 2)
                for (cell in 0 until geometry.cellCount) {
                    if (layout.isPresent(cell)) {
                        for (other in layout.links[cell]) assertTrue("$label link $cell-$other", layout.isPresent(other))
                        for (other in layout.scopes[cell]) assertTrue("$label scope $cell-$other", layout.isPresent(other))
                        for (other in layout.linePath(cell)) assertTrue("$label row $cell-$other", layout.isPresent(other))
                        continue
                    }
                    assertEquals("$label ocean $cell links", 0, layout.links[cell].size)
                    assertEquals("$label ocean $cell scope", 0, layout.scopes[cell].size)
                    assertFalse("$label ocean $cell far", layout.isFar(cell))
                    assertFalse("$label ocean $cell line", layout.isLine(cell))
                    assertEquals("$label ocean $cell portal", -1, layout.portalPartner(cell))
                    assertEquals("$label ocean $cell island", -1, layout.islandOf(cell))
                }
            }
        }
    }

    @Test
    fun portalsAreSymmetricDistantAndKeptApartFromFarAndLineCells() {
        // Cells of a pair are never neighbours nor close: at least three cells lie between them.
        assertTrue(BoardLayout.MIN_PORTAL_DISTANCE >= 4)
        for (size in listOf(BoardSize.SMALL, BoardSize.LARGE, BoardSize.GIANT)) {
            val geometry = geometries.getValue(size)
            for (islands in listOf(0, minOf(4, BoardLayout.maxIslandCount(geometry)))) {
                for (seed in 0 until 15) {
                    val label = "$size islands $islands seed $seed"
                    val extra = 3
                    val layout = layout(size, seed, islands, extra)
                    val bridges = maxOf(1, islands) - 1
                    assertEquals("$label pair count", bridges + extra, layout.portalPairCount)
                    val members = IntArray(layout.portalPairCount)
                    for (cell in 0 until geometry.cellCount) {
                        val partner = layout.portalPartner(cell)
                        if (partner < 0) {
                            assertEquals("$label cell $cell pair", -1, layout.portalPair(cell))
                            assertFalse("$label cell $cell portal", layout.isPortal(cell))
                            continue
                        }
                        members[layout.portalPair(cell)]++
                        assertEquals("$label pair symmetric", cell, layout.portalPartner(partner))
                        assertEquals("$label pair index", layout.portalPair(cell), layout.portalPair(partner))
                        assertTrue("$label portal $cell present", layout.isPresent(cell))
                        assertFalse("$label portal $cell far", layout.isFar(cell))
                        assertFalse("$label portal $cell line", layout.isLine(cell))
                        assertTrue("$label $cell links partner", layout.links[cell].contains(partner))
                        assertTrue("$label $cell scope has partner", layout.scopes[cell].contains(partner))
                        val steps = plainDistance(geometry, cell, partner)
                        assertTrue("$label pair $cell-$partner $steps steps", steps >= BoardLayout.MIN_PORTAL_DISTANCE)
                    }
                    // Every pair index belongs to exactly two cells, so no cell is in two pairs.
                    for (pair in members.indices) assertEquals("$label pair $pair members", 2, members[pair])
                    for (cell in 0 until geometry.cellCount) {
                        assertTrue("$label links sorted", layout.links[cell].toList() == layout.links[cell].sorted())
                        // Rows run only between cells that share an edge, never through a portal.
                        var previous = cell
                        for (next in layout.linePath(cell)) {
                            assertTrue("$label row of $cell jumps", geometry.neighbors[previous].contains(next))
                            previous = next
                        }
                    }
                }
            }
        }
    }

    @Test
    fun farCellsSeeThroughPortalsInTwoSteps() {
        val size = BoardSize.GIANT
        val geometry = geometries.getValue(size)
        var checked = 0
        for (seed in 0 until 20) {
            val layout = BoardLayout.generate(geometry, 0, 0, 0, 120, Random(seed), 0, 3, 6)
            for (cell in 0 until geometry.cellCount) {
                if (!layout.isFar(cell)) continue
                for (nb in layout.links[cell]) {
                    val partner = layout.portalPartner(nb)
                    if (partner < 0) continue
                    checked++
                    assertTrue("seed $seed far $cell sees partner $partner", layout.scopes[cell].contains(partner))
                }
                val twoSteps = HashSet<Int>()
                for (a in layout.links[cell]) {
                    twoSteps.add(a)
                    for (b in layout.links[a]) if (b != cell) twoSteps.add(b)
                }
                assertEquals("seed $seed far $cell scope", twoSteps.sorted(), layout.scopes[cell].toList())
            }
        }
        assertTrue("no far cell next to a portal: $checked", checked > 0)
    }

    @Test
    fun zerosSpreadThroughPortalsAndTheWinNeedsNoOcean() {
        for (size in listOf(BoardSize.LARGE, BoardSize.GIANT)) {
            val geometry = geometries.getValue(size)
            for (seed in 0 until 10) {
                val label = "$size seed $seed"
                // No line cells: a zero on a line cell spreads only along its row, not to its neighbours.
                val layout = BoardLayout.generate(
                    geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, Random(seed), 0, 3, 0,
                )
                assertTrue(label, layout.walls.isNotEmpty())
                val board = MinesweeperBoard(layout.scopes, 0, present = layout.presentMask())
                board.placeMinesAt(IntArray(0))
                val start = (0 until geometry.cellCount).first { layout.isPresent(it) }
                // No mines: one reveal crosses every portal and wins without touching the ocean.
                assertEquals(label, MoveResult.WON, board.reveal(start))
                assertEquals(label, layout.presentCount, board.revealedCount)
                for (cell in 0 until geometry.cellCount) {
                    assertEquals("$label cell $cell", layout.isPresent(cell), board.isRevealed(cell))
                }
            }
        }
    }

    @Test
    fun zeroOnPortalRevealsItsPartner() {
        val size = BoardSize.LARGE
        val geometry = geometries.getValue(size)
        val layout = BoardLayout.generate(geometry, 0, 0, 0, 0, Random(5), 0, 0, 1)
        val portal = (0 until geometry.cellCount).first { layout.isPortal(it) }
        val partner = layout.portalPartner(portal)
        // Mines on every cell two steps from the portal keep the reveal from going any further.
        val ring = HashSet<Int>()
        for (nb in layout.links[portal]) for (far in layout.links[nb]) ring.add(far)
        ring.removeAll(layout.links[portal].toSet())
        ring.remove(portal)
        val board = MinesweeperBoard(layout.scopes, ring.size, present = layout.presentMask())
        board.placeMinesAt(ring.toIntArray())
        assertEquals(0, board.adjacentMines(portal))
        assertEquals(MoveResult.REVEALED, board.reveal(portal))
        assertTrue("partner revealed", board.isRevealed(partner))
        assertFalse("partner is not a plain neighbour", geometry.neighbors[portal].contains(partner))
    }

    @Test
    fun freeGameMinesKeepTheSizeDensityOnTheLand() {
        for (size in BoardSize.values()) {
            val plain = layout(size, 1, 0, size.portals)
            assertEquals("$size without ocean", size.mines, size.minesFor(plain))
            if (size.islands == 0) {
                assertEquals("$size expected", size.mines, size.expectedMines)
                continue
            }
            val expectedLand = Campaign.expectedLand(size.cellCount)
            assertEquals("$size expected", Math.round(size.mineDensity * expectedLand).toInt(), size.expectedMines)
            for (seed in 0 until 10) {
                val label = "$size seed $seed"
                val layout = layout(size, seed, size.islands, size.portals)
                val land = layout.presentCount
                val mines = size.minesFor(layout)
                assertEquals(label, size.mineDensity, mines.toDouble() / land, 0.5 / land + 1e-9)
                assertTrue("$label fits the generator", mines <= NoGuessGenerator.maxMineCount(layout, size.locks))
            }
        }
    }

    @Test
    fun maxMineCountIsTheGeneratorLimit() {
        val size = BoardSize.HUGE
        for (seed in 0 until 5) {
            val layout = layout(size, seed, size.islands, size.portals)
            val max = NoGuessGenerator.maxMineCount(layout, size.locks)
            assertTrue("seed $seed below the land", max < layout.presentCount)
            try {
                NoGuessGenerator.generate(layout, max + 1, Random(seed), size.locks)
                fail("seed $seed: ${max + 1} mines accepted")
            } catch (expected: IllegalArgumentException) {
                // No start cell leaves room for one mine more than the limit.
            }
        }
    }

    @Test
    fun oceanCellsCannotBeRevealedFlaggedOrMined() {
        val size = BoardSize.HUGE
        val geometry = geometries.getValue(size)
        for (seed in 0 until 10) {
            val layout = layout(size, seed, 2, 1)
            val present = layout.presentMask()
            val ocean = (0 until geometry.cellCount).first { !present[it] }
            val land = (0 until geometry.cellCount).first { present[it] }
            val board = MinesweeperBoard(layout.scopes, size.mines, Random(seed), present = present)
            assertFalse(board.isPresent(ocean))
            assertTrue(board.isPresent(land))
            assertEquals(layout.presentCount, board.presentCount)
            assertFalse("seed $seed flag ocean", board.toggleFlag(ocean))
            assertEquals("seed $seed reveal ocean", MoveResult.NOTHING, board.reveal(ocean))
            assertEquals(GameStatus.READY, board.status)
            board.reveal(land)
            for (cell in 0 until geometry.cellCount) {
                if (!present[cell]) {
                    assertFalse("seed $seed mine in ocean $cell", board.isMine(cell))
                    assertFalse("seed $seed ocean $cell revealed", board.isRevealed(cell))
                }
            }
            assertEquals(size.mines, (0 until geometry.cellCount).count { board.isMine(it) })
        }
    }

    @Test
    fun everyLandCellIsInTheScopeOfAnotherCell() {
        // Three times the line and far cells of each size, so that coast cells often border a line cell.
        for (size in BoardSize.values()) {
            val geometry = geometries.getValue(size)
            for (islands in 1..minOf(4, BoardLayout.maxIslandCount(geometry))) {
                for (seed in 0 until 30) {
                    val label = "$size islands $islands seed $seed"
                    val layout = BoardLayout.generate(
                        geometry, size.walls, size.wallMinLength, size.wallMaxLength, 3 * size.farCells,
                        Random(seed), 3 * size.lineCells, islands, 2,
                    )
                    val seen = BooleanArray(geometry.cellCount)
                    for (cell in 0 until geometry.cellCount) {
                        for (other in layout.scopes[cell]) if (other != cell) seen[other] = true
                    }
                    for (cell in 0 until geometry.cellCount) {
                        if (layout.isPresent(cell)) assertTrue("$label cell $cell is in no other scope", seen[cell])
                    }
                }
            }
        }
    }

    @Test
    fun sameSeedGivesSameIslandsAndPortals() {
        val size = BoardSize.GIANT
        val a = layout(size, 42, 4, 3)
        val b = layout(size, 42, 4, 3)
        for (cell in 0 until a.scopes.size) {
            assertEquals(a.islandOf(cell), b.islandOf(cell))
            assertEquals(a.portalPartner(cell), b.portalPartner(cell))
            assertEquals(a.portalPair(cell), b.portalPair(cell))
            assertEquals(a.scopes[cell].toList(), b.scopes[cell].toList())
        }
        assertEquals(a.walls.map { it.toList() }, b.walls.map { it.toList() })
    }

    @Test
    fun zeroIslandsAndPortalsDrawAsBefore() {
        val size = BoardSize.GIANT
        val geometry = geometries.getValue(size)
        val plain = BoardLayout.generate(geometry, size, Random(9))
        val explicit = layout(size, 9, 0, 0)
        assertEquals(geometry.cellCount, explicit.presentCount)
        assertEquals(1, explicit.islandCount)
        assertEquals(0, explicit.portalPairCount)
        assertEquals(plain.walls.map { it.toList() }, explicit.walls.map { it.toList() })
        for (cell in 0 until geometry.cellCount) assertEquals(plain.scopes[cell].toList(), explicit.scopes[cell].toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooManyIslandsAreRejected() {
        val geometry = geometries.getValue(BoardSize.SMALL)
        BoardLayout.generate(geometry, 0, 0, 0, 0, Random(1), 0, BoardLayout.maxIslandCount(geometry) + 1, 0)
    }

    /** Component id per cell over [BoardLayout.links] of present cells, optionally without portal pairs. */
    private fun components(layout: BoardLayout, withPortals: Boolean): IntArray {
        val cellCount = layout.links.size
        val id = IntArray(cellCount) { -1 }
        var next = 0
        val stack = ArrayDeque<Int>()
        for (first in 0 until cellCount) {
            if (!layout.isPresent(first) || id[first] >= 0) continue
            id[first] = next
            stack.addLast(first)
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                for (nb in layout.links[cell]) {
                    if (!withPortals && layout.portalPartner(cell) == nb) continue
                    if (id[nb] < 0) {
                        id[nb] = next
                        stack.addLast(nb)
                    }
                }
            }
            next++
        }
        return id
    }

    private fun plainDistance(geometry: SphereGeometry, from: Int, to: Int): Int {
        val distance = IntArray(geometry.cellCount) { -1 }
        val queue = ArrayDeque<Int>()
        distance[from] = 0
        queue.addLast(from)
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            if (cell == to) return distance[cell]
            for (nb in geometry.neighbors[cell]) {
                if (distance[nb] < 0) {
                    distance[nb] = distance[cell] + 1
                    queue.addLast(nb)
                }
            }
        }
        return Int.MAX_VALUE
    }
}
