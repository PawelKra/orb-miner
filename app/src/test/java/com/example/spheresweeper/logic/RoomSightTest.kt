package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * On boards with rooms and line cells every region is seen from the shallower side: following cells
 * that see each other (v is in the scope of u) without stepping into a shallower region, every land
 * cell is reachable from every root cell that is not a line cell.
 */
class RoomSightTest {

    private val geometries: Map<BoardSize, SphereGeometry> =
        BoardSize.values().associateWith { SphereGeometry.build(it.frequency) }

    private val roomSizes = listOf(BoardSize.SMALL, BoardSize.MEDIUM, BoardSize.LARGE, BoardSize.HUGE, BoardSize.GIANT)

    /**
     * Rooms 3 to 7 and line cells 2 to 8 by seed; islands (as many as the size has) on odd seeds and
     * 0 to 2 extra portals.
     */
    private fun layout(size: BoardSize, seed: Int): BoardLayout {
        val geometry = geometries.getValue(size)
        val islands = if (seed % 2 == 1) minOf(size.islands, BoardLayout.maxIslandCount(geometry)) else 0
        val rooms = minOf(3 + seed % 5, BoardLayout.maxRoomCount(geometry, islands))
        return BoardLayout.generate(
            geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, Random(seed),
            2 + seed % 7, islands, (seed / 2) % 3, rooms,
        )
    }

    /** The layout of [RoomLockTest] with line cells: rooms 3 to 7 by seed, the size's lines and portals. */
    private fun lockTestLayout(size: BoardSize, seed: Int): BoardLayout {
        val geometry = geometries.getValue(size)
        val rooms = minOf(3 + seed % 5, BoardLayout.maxRoomCount(geometry))
        return BoardLayout.generate(
            geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, Random(seed),
            size.lineCells, 0, size.portals, rooms,
        )
    }

    private fun depthOf(layout: BoardLayout, cell: Int): Int = layout.regionDepth(layout.regionOf(cell))

    /** Land cells not reached from [source] over steps that never go into a shallower region. */
    private fun unreachedFrom(layout: BoardLayout, source: Int): List<Int> {
        val cellCount = layout.scopes.size
        val reached = BooleanArray(cellCount)
        val queue = ArrayDeque<Int>()
        reached[source] = true
        queue.add(source)
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            for (other in layout.scopes[cell]) {
                if (reached[other] || depthOf(layout, other) < depthOf(layout, cell)) continue
                reached[other] = true
                queue.add(other)
            }
        }
        return (0 until cellCount).filter { layout.isPresent(it) && !reached[it] }
    }

    private fun assertSeenInward(label: String, layout: BoardLayout) {
        assertTrue("$label has rooms", layout.hasRooms)
        val cellCount = layout.scopes.size
        for ((near, far) in layout.gates) {
            assertTrue("$label gate $near -> $far: far not in the scope of near", far in layout.scopes[near])
        }
        val nearCount = IntArray(cellCount)
        for ((near, _) in layout.gates) nearCount[near]++
        for (cell in 0 until cellCount) {
            if (nearCount[cell] > 1) assertFalse("$label near cell $cell of ${nearCount[cell]} gates is a line", layout.isLine(cell))
        }
        val sources = (0 until cellCount).filter { layout.isPresent(it) && depthOf(layout, it) == 0 && !layout.isLine(it) }
        assertTrue("$label no root cell", sources.isNotEmpty())
        for (source in sources) {
            val missed = unreachedFrom(layout, source)
            assertTrue("$label from $source not reached: $missed", missed.isEmpty())
        }
    }

    @Test
    fun everyLandCellIsReachedFromTheRootOverInwardSight() {
        var boards = 0
        var lines = 0
        var withIslands = 0
        var withPortals = 0
        for (size in roomSizes) {
            for (seed in 0 until 30) {
                val layout = layout(size, seed)
                // Islands that each hold a single room leave the board without rooms.
                if (!layout.hasRooms) continue
                boards++
                assertSeenInward("$size seed $seed", layout)
                lines += layout.lineCount
                if (layout.islandCount > 1) withIslands++
                if (layout.portalPairCount > layout.islandCount - 1) withPortals++
            }
        }
        assertTrue("boards with rooms $boards", boards >= 120)
        assertTrue("line cells $lines", lines > 300)
        assertTrue("boards with islands $withIslands", withIslands > 0)
        assertTrue("boards with extra portals $withPortals", withPortals > 0)
    }

    @Test
    fun boardsThatHadARoomSeenOnlyFromInsideAreSolvable() {
        for ((size, seed) in listOf(BoardSize.MEDIUM to 17, BoardSize.LARGE to 9, BoardSize.HUGE to 29)) {
            val label = "$size seed $seed"
            val layout = lockTestLayout(size, seed)
            assertTrue("$label has line cells", layout.lineCount > 0)
            assertSeenInward(label, layout)
            val lockCount = 1 + seed % 6
            val mineCount = minesOnLand(size.mineDensity, layout, lockCount)
            val generated = NoGuessGenerator.generate(layout, mineCount, Random(seed + 1000), lockCount)
            val mines = BooleanArray(layout.scopes.size).also { array -> for (cell in generated.mines) array[cell] = true }
            assertEquals(label, mineCount, generated.mines.size)
            assertTrue(
                "$label not solvable",
                LogicSolver.solves(
                    layout.scopes, mines, generated.start, layout.presentMask(),
                    generated.lockThresholds, generated.lockRegions,
                ),
            )
        }
    }
}
