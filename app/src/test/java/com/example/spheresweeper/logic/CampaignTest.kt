package com.example.spheresweeper.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignTest {

    private val geometries = HashMap<Int, SphereGeometry>()

    private fun geometry(frequency: Int): SphereGeometry =
        geometries.getOrPut(frequency) { SphereGeometry.build(frequency) }

    private val sideBranches = Branch.values().filter { it != Branch.TRUNK }

    @Test
    fun trunkHasTenLevelsAndEachBranchTwenty() {
        assertEquals(10, Campaign.levels(Branch.TRUNK).size)
        assertEquals(7, sideBranches.size)
        for (branch in sideBranches) {
            assertEquals("$branch", 20, Campaign.levels(branch).size)
        }
        assertEquals(150, Campaign.all.size)
    }

    @Test
    fun newBranchesComeAfterTheOldOnesWithTheirOwnIds() {
        assertEquals(
            listOf(Branch.TRUNK, Branch.WALLS, Branch.FAR, Branch.LINES, Branch.MIXED, Branch.PORTALS, Branch.LOCKS, Branch.ISLANDS),
            Branch.values().toList(),
        )
        assertEquals("portals-01", Campaign.levels(Branch.PORTALS).first().id)
        assertEquals("locks-20", Campaign.levels(Branch.LOCKS).last().id)
        assertEquals("islands-01", Campaign.levels(Branch.ISLANDS).first().id)
        // The first 90 levels keep their order: trunk, walls, far, lines, mixed.
        assertEquals("mixed-20", Campaign.all[89].id)
        assertEquals("portals-01", Campaign.all[90].id)
    }

    @Test
    fun idsAndSeedsAreUniqueAndLookupsAgree() {
        assertEquals(Campaign.all.size, Campaign.all.map { it.id }.toSet().size)
        assertEquals(Campaign.all.size, Campaign.all.map { it.seed }.toSet().size)
        for (branch in Branch.values()) {
            val levels = Campaign.levels(branch)
            for ((i, level) in levels.withIndex()) {
                assertEquals(branch, level.branch)
                assertEquals(i, level.index)
                assertTrue(level.id, level.id.matches(Regex("[a-z]+-\\d{2}")))
                assertTrue(level.id, level.id.endsWith("-%02d".format(level.number)))
                assertSame(level, Campaign.byId(level.id))
                assertEquals(Campaign.seedFor(level.id), level.seed)
                assertEquals(levels.getOrNull(i + 1), Campaign.next(level))
            }
            assertNull(Campaign.next(levels.last()))
        }
        assertNull(Campaign.byId("walls-21"))
        assertNull(Campaign.byId(""))
    }

    @Test
    fun seedIsStandardFnv1aOfTheId() {
        // Reference values of 64-bit FNV-1a; they pin every level's seed.
        assertEquals(0xcbf29ce484222325uL.toLong(), Campaign.seedFor(""))
        assertEquals(0xaf63dc4c8601ec8cuL.toLong(), Campaign.seedFor("a"))
    }

    @Test
    fun parametersStayInRange() {
        for (level in Campaign.all) {
            val label = level.id
            assertTrue("$label frequency", level.frequency in 2..8)
            if (level.branch != Branch.TRUNK) assertTrue("$label frequency", level.frequency in 3..8)
            assertTrue("$label lives", level.lives in 1..3)
            assertTrue("$label hints", level.hints in 0..2)
            assertTrue("$label mines", level.mines > 0)
            // Biggest list a start cell can have: a far cell sees up to 18 cells, a line cell 2 * frequency,
            // a portal one more than its neighbours.
            val widestScope = maxOf(
                if (level.portals > 0 || level.islands > 1) 7 else 6,
                if (level.farCells > 0) 18 else 0,
                if (level.lineCells > 0) 2 * level.frequency else 0,
            )
            assertTrue("$label mines fit", level.mines + level.locks < level.playCells - 1 - widestScope)

            val hasWalls = level.branch == Branch.WALLS || level.branch == Branch.MIXED
            val hasFar = level.branch == Branch.FAR || level.branch == Branch.MIXED
            val hasLines = level.branch == Branch.LINES || level.branch == Branch.MIXED
            val hasIslands = level.branch == Branch.ISLANDS
            val hasLocks = level.branch == Branch.LOCKS
            assertEquals("$label walls", hasWalls, level.walls > 0)
            assertEquals("$label wall length", hasWalls, level.wallMaxLength > 0)
            assertEquals("$label far", hasFar, level.farCells > 0)
            assertEquals("$label lines", hasLines, level.lineCells > 0)
            assertEquals("$label islands", hasIslands, level.islands > 0)
            assertEquals("$label locks", hasLocks, level.locks > 0)
            when (level.branch) {
                Branch.PORTALS -> assertTrue("$label portals", level.portals in 1..4)
                Branch.ISLANDS -> assertTrue("$label portals", level.portals in 0..2)
                else -> assertEquals("$label portals", 0, level.portals)
            }
            if (hasIslands) assertTrue("$label islands", level.islands in 2..4)
            if (hasLocks) assertTrue("$label locks", level.locks in 1..6)
            if (hasWalls) assertTrue("$label wall lengths", level.wallMinLength in 2..level.wallMaxLength)
        }
    }

    @Test
    fun branchesGrowFromSmallToLargeBoards() {
        val trunk = Campaign.levels(Branch.TRUNK)
        assertEquals(2, trunk.first().frequency)
        assertTrue(trunk.first().mineDensity in 0.10..0.12)
        for (branch in sideBranches) {
            val levels = Campaign.levels(branch)
            // Two islands need frequency 4 (BoardLayout.maxIslandCount), so that branch starts there.
            assertEquals("$branch first", if (branch == Branch.ISLANDS) 4 else 3, levels.first().frequency)
            assertEquals("$branch last", 8, levels.last().frequency)
            assertTrue("$branch last density", levels.last().mineDensity in 0.19..0.20)
            assertEquals("$branch first lives", 3, levels.first().lives)
            assertEquals("$branch last lives", 1, levels.last().lives)
            assertEquals("$branch first hints", 2, levels.first().hints)
            assertEquals("$branch last hints", 0, levels.last().hints)
        }
        for (branch in listOf(Branch.PORTALS, Branch.LOCKS, Branch.ISLANDS)) {
            assertTrue("$branch first density", Campaign.levels(branch).first().mineDensity in 0.13..0.15)
        }
        assertEquals(1, Campaign.levels(Branch.PORTALS).first().portals)
        assertEquals(4, Campaign.levels(Branch.PORTALS).last().portals)
        assertEquals(1, Campaign.levels(Branch.LOCKS).first().locks)
        assertEquals(6, Campaign.levels(Branch.LOCKS).last().locks)
        assertEquals(2, Campaign.levels(Branch.ISLANDS).first().islands)
        assertEquals(4, Campaign.levels(Branch.ISLANDS).last().islands)
        assertEquals(0, Campaign.levels(Branch.ISLANDS).first().portals)
        assertEquals(2, Campaign.levels(Branch.ISLANDS).last().portals)
    }

    @Test
    fun islandLevelsFitTheirSphereAndCountMinesOnLand() {
        for (level in Campaign.all) {
            if (level.islands == 0) {
                assertEquals(level.id, level.cellCount, level.playCells)
                continue
            }
            assertTrue(level.id, level.islands <= BoardLayout.maxIslandCount(geometry(level.frequency)))
            assertTrue(level.id, level.playCells < level.cellCount)
        }
    }

    /**
     * Islands levels put the branch's density on the land of the built board. Each level is the ramp
     * density rounded to whole mines, so it is off by at most half a mine. Between neighbouring levels
     * the ramp rises by about 0.29 points, while rounding may move each level by up to 0.48 points on
     * the smallest land (about 105 cells): the real density may dip, but by less than the ramp step
     * plus one rounding. MAX_ROUNDING_DIP of 0.5 points allows that dip on the pinned boards, while a
     * count based on anything other than the real land (such as the expected land, which gave dips of
     * 1.6 points) fails.
     */
    @Test
    fun islandLevelsPutTheBranchDensityOnTheirRealLand() {
        var previous: Double? = null
        for (spec in Campaign.levels(Branch.ISLANDS)) {
            val level = Campaign.buildLevel(spec, geometry(spec.frequency))
            val land = level.layout.presentCount
            val density = level.mines.size.toDouble() / land
            val nominal = Campaign.nominalDensity(spec)
            assertEquals("${spec.id} density on $land land cells", nominal, density, 0.5 / land + 1e-9)
            if (previous != null) {
                assertTrue("${spec.id} density $density after $previous", density >= previous - MAX_ROUNDING_DIP)
            }
            previous = density
        }
        val densities = Campaign.levels(Branch.ISLANDS).map { Campaign.nominalDensity(it) }
        for (i in 1 until densities.size) assertTrue("nominal density rises", densities[i] > densities[i - 1])
    }

    @Test
    fun difficultyNeverDropsWithinABranch() {
        for (branch in Branch.values()) {
            val levels = Campaign.levels(branch)
            for (i in 1 until levels.size) {
                val a = levels[i - 1]
                val b = levels[i]
                val label = "${a.id} -> ${b.id}"
                assertTrue("$label frequency", b.frequency >= a.frequency)
                assertTrue("$label density", b.mineDensity >= a.mineDensity)
                assertTrue("$label mines", b.mines >= a.mines)
                assertTrue("$label walls", b.walls >= a.walls)
                assertTrue("$label wall length", b.wallMaxLength >= a.wallMaxLength)
                assertTrue("$label far", b.farCells >= a.farCells)
                assertTrue("$label lines", b.lineCells >= a.lineCells)
                assertTrue("$label islands", b.islands >= a.islands)
                assertTrue("$label portals", b.portals >= a.portals)
                assertTrue("$label locks", b.locks >= a.locks)
                assertTrue("$label lives", b.lives <= a.lives)
                assertTrue("$label hints", b.hints <= a.hints)
            }
        }
    }

    @Test
    fun everyLevelBuildsASolvableStartedBoard() {
        for (spec in Campaign.all) {
            val label = spec.id
            val level = Campaign.buildLevel(spec, geometry(spec.frequency))
            val layout = level.layout

            // Walls between rooms are part of layout.walls; the open walls are the rest.
            val open = openWalls(geometry(spec.frequency), layout)
            assertEquals("$label rooms", spec.rooms > 1, layout.hasRooms)
            if (spec.rooms == 0) assertEquals("$label only open walls", layout.walls.size, open.size)
            if (layout.hasRooms) assertTrue("$label room walls", layout.walls.size > open.size)
            assertTrue("$label open wall count", open.size <= spec.walls)
            if (spec.walls > 0) assertTrue("$label has open walls", open.isNotEmpty())
            for (wall in open) {
                assertTrue("$label wall length", wall.size - 1 in spec.wallMinLength..spec.wallMaxLength)
            }
            assertEquals("$label far cells", spec.farCells, layout.farCount)
            // With rooms line cells and locks stand only at passages: at least one, never more than asked.
            if (spec.rooms == 0) {
                assertEquals("$label line cells", spec.lineCells, layout.lineCount)
            } else {
                val lineRange = if (spec.lineCells > 0) 1..spec.lineCells else 0..0
                assertTrue("$label line cells ${layout.lineCount}", layout.lineCount in lineRange)
            }
            assertEquals("$label islands", maxOf(1, spec.islands), layout.islandCount)
            assertEquals("$label portal pairs", maxOf(0, spec.islands - 1) + spec.portals, layout.portalPairCount)
            if (spec.islands == 0) assertEquals("$label no ocean", layout.scopes.size, layout.presentCount)

            assertSame(spec, level.spec)
            // Without ocean the board has the nominal count; with islands the count follows the land.
            val mineCount = Campaign.mineCount(spec, layout)
            if (spec.islands == 0) assertEquals("$label nominal mines", spec.mines, mineCount)
            assertEquals("$label mines", mineCount, level.mines.size)
            val board = level.board
            assertEquals("$label board mines", mineCount, board.mineCount)
            assertEquals("$label lives", spec.lives, board.lives)
            assertEquals("$label status", GameStatus.PLAYING, board.status)
            assertTrue("$label start revealed", board.isRevealed(level.start))
            assertEquals("$label start shows 0", 0, board.adjacentMines(level.start))
            for (cell in level.mines) assertTrue("$label mine $cell", board.isMine(cell))
            assertEquals("$label present cells", layout.presentCount, board.presentCount)
            if (spec.rooms == 0) {
                assertEquals("$label locks", spec.locks, board.lockCount)
            } else {
                val lockRange = if (spec.locks > 0) 1..spec.locks else 0..0
                assertTrue("$label locks ${board.lockCount}", board.lockCount in lockRange)
            }
            val locks = IntArray(layout.scopes.size) { board.lockThreshold(it) }
            for (cell in 0 until layout.scopes.size) {
                assertEquals("$label presence $cell", layout.isPresent(cell), board.isPresent(cell))
                if (locks[cell] > 0) assertFalse("$label lock on mine $cell", board.isMine(cell))
                if (layout.isPortal(cell)) assertFalse("$label mine on portal $cell", board.isMine(cell))
            }

            // The regions the locks count, as the generator returned them for this board.
            val generated = generateAgain(spec, geometry(spec.frequency))
            assertEquals("$label generated start", level.start, generated.start)
            assertArrayEquals("$label generated mines", level.mines, generated.mines)
            assertArrayEquals("$label generated locks", generated.lockThresholds, locks)
            assertEquals("$label lock regions", layout.hasRooms && spec.locks > 0, generated.lockRegions != null)

            val mines = BooleanArray(layout.scopes.size)
            for (cell in level.mines) mines[cell] = true
            val solver = LogicSolver(layout.scopes, layout.presentMask())
            solver.load(mines, locks, generated.lockRegions)
            solver.reveal(level.start)
            assertTrue("$label solvable without guessing", solver.run())
        }
    }

    @Test
    fun buildingTwiceGivesTheSameBoard() {
        for (spec in Campaign.all) {
            val geometry = geometry(spec.frequency)
            val a = Campaign.buildLevel(spec, geometry)
            val b = Campaign.buildLevel(spec, geometry)
            assertEquals("${spec.id} start", a.start, b.start)
            assertArrayEquals("${spec.id} mines", a.mines, b.mines)
            assertEquals("${spec.id} walls", a.layout.walls.size, b.layout.walls.size)
            for (w in a.layout.walls.indices) {
                assertArrayEquals("${spec.id} wall $w", a.layout.walls[w], b.layout.walls[w])
            }
            for (cell in 0 until geometry.cellCount) {
                assertEquals("${spec.id} far $cell", a.layout.isFar(cell), b.layout.isFar(cell))
                assertEquals("${spec.id} line $cell", a.layout.lineSide(cell), b.layout.lineSide(cell))
                assertEquals("${spec.id} island $cell", a.layout.islandOf(cell), b.layout.islandOf(cell))
                assertEquals("${spec.id} portal $cell", a.layout.portalPartner(cell), b.layout.portalPartner(cell))
                assertEquals("${spec.id} lock $cell", a.board.lockThreshold(cell), b.board.lockThreshold(cell))
            }
        }
    }

    @Test
    fun levelsDifferFromEachOther() {
        val spec = Campaign.levels(Branch.WALLS)[0]
        val other = Campaign.levels(Branch.WALLS)[1]
        assertEquals(spec.frequency, other.frequency)
        val a = Campaign.buildLevel(spec, geometry(spec.frequency))
        val b = Campaign.buildLevel(other, geometry(other.frequency))
        assertFalse(a.start == b.start && a.mines.contentEquals(b.mines))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildRejectsGeometryOfAnotherSize() {
        val spec = Campaign.levels(Branch.TRUNK).first()
        Campaign.buildLevel(spec, geometry(spec.frequency + 1))
    }

    @Test
    fun rampInterpolatesBetweenControlPoints() {
        val ramp = Ramp(0.0, 10.0, 0.0)
        assertEquals(0.0, ramp.at(0.0), 1e-9)
        assertEquals(5.0, ramp.at(0.25), 1e-9)
        assertEquals(10.0, ramp.at(0.5), 1e-9)
        assertEquals(0.0, ramp.at(1.0), 1e-9)
        assertEquals(0.0, ramp.at(2.0), 1e-9)
        assertEquals(4.0, Ramp(4.0).at(0.7), 1e-9)
    }

    /**
     * Repeats the draws of [Campaign.buildLevel] for [spec] and returns what [NoGuessGenerator] gave,
     * which carries the lock regions that [PreparedLevel] does not keep.
     */
    private fun generateAgain(spec: LevelSpec, geometry: SphereGeometry): NoGuessLayout {
        val random = StableRandom(spec.seed)
        val layout = BoardLayout.generate(
            geometry, spec.walls, spec.wallMinLength, spec.wallMaxLength, spec.farCells, random, spec.lineCells,
            spec.islands, spec.portals, spec.rooms,
        )
        return NoGuessGenerator.generate(layout, Campaign.mineCount(spec, layout), random, spec.locks)
    }

    /**
     * Walls of [layout] that are not walls between rooms: the two cells across their first edge lie in
     * the same region. Walls between rooms always separate two regions, open walls never do.
     */
    private fun openWalls(geometry: SphereGeometry, layout: BoardLayout): List<IntArray> {
        val across = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        for (cell in 0 until geometry.cellCount) {
            val start = geometry.cornerStart[cell]
            val end = geometry.cornerStart[cell + 1]
            for (k in start until end) {
                val next = if (k + 1 < end) k + 1 else start
                across[pointPair(geometry, k, next)] = Pair(cell, geometry.edgeNeighbor[k])
            }
        }
        return layout.walls.filter { wall ->
            val (a, b) = across.getValue(pointPair(geometry, wall[0], wall[1]))
            layout.regionOf(a) == layout.regionOf(b)
        }
    }

    private fun pointPair(geometry: SphereGeometry, cornerA: Int, cornerB: Int): Pair<Int, Int> {
        val a = geometry.cornerPoint[cornerA]
        val b = geometry.cornerPoint[cornerB]
        return Pair(minOf(a, b), maxOf(a, b))
    }

    private companion object {
        /** Largest drop of real mine density between neighbouring islands levels, as a share (0.5 points). */
        const val MAX_ROUNDING_DIP = 0.005
    }
}
