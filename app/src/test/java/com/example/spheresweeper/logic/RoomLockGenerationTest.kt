package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The generator always finishes on boards with rooms and locks, and boards without rooms come out
 * exactly as recorded for a given seed.
 */
class RoomLockGenerationTest {

    private val geometries: Map<BoardSize, SphereGeometry> =
        BoardSize.values().associateWith { SphereGeometry.build(it.frequency) }

    private fun mineArray(cellCount: Int, mines: IntArray): BooleanArray =
        BooleanArray(cellCount).also { array -> for (cell in mines) array[cell] = true }

    /**
     * True when numbers spreading from [start] over the scopes reach every land cell, mines left aside,
     * with a lock cell adding its scope only once every land cell of its region is reached.
     */
    private fun locksCanOpen(layout: BoardLayout, start: Int, locks: Set<Int>): Boolean {
        val cellCount = layout.scopes.size
        val regionSize = IntArray(layout.regionCount)
        for (cell in 0 until cellCount) if (layout.isPresent(cell)) regionSize[layout.regionOf(cell)]++
        val reachedIn = IntArray(layout.regionCount)
        val reached = BooleanArray(cellCount)
        val spread = BooleanArray(cellCount)
        val queue = ArrayDeque<Int>()
        reached[start] = true
        reachedIn[layout.regionOf(start)]++
        queue.add(start)
        while (true) {
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                val region = layout.regionOf(cell)
                if (cell in locks && reachedIn[region] < regionSize[region]) continue
                spread[cell] = true
                for (other in layout.scopes[cell]) {
                    if (reached[other] || !layout.isPresent(other)) continue
                    reached[other] = true
                    reachedIn[layout.regionOf(other)]++
                    queue.add(other)
                }
            }
            val opened = locks.filter {
                reached[it] && !spread[it] && reachedIn[layout.regionOf(it)] == regionSize[layout.regionOf(it)]
            }
            if (opened.isEmpty()) break
            queue.addAll(opened)
        }
        return (0 until cellCount).all { !layout.isPresent(it) || reached[it] }
    }

    /** Generates with [lockCount] locks and checks the layout: mine count, locks off mines and solvable. */
    private fun generateAndCheck(
        label: String,
        layout: BoardLayout,
        mineCount: Int,
        random: Random,
        lockCount: Int,
    ): Int {
        val generated = NoGuessGenerator.generate(layout, mineCount, random, lockCount)
        val cellCount = layout.scopes.size
        val mines = mineArray(cellCount, generated.mines)
        assertEquals(label, mineCount, generated.mines.size)
        val locks = (0 until cellCount).filter { generated.lockThresholds[it] > 0 }
        assertTrue("$label ${locks.size} locks for $lockCount", locks.size <= lockCount)
        for (lock in locks) assertFalse("$label lock $lock on a mine", mines[lock])
        assertTrue("$label locks that cannot open", locksCanOpen(layout, generated.start, locks.toSet()))
        assertTrue(
            "$label not solvable",
            LogicSolver.solves(
                layout.scopes, mines, generated.start, layout.presentMask(),
                generated.lockThresholds, generated.lockRegions,
            ),
        )
        return locks.size
    }

    /**
     * Each of these boards has a near cell whose region is seen only through that cell: a pocket of
     * the region behind it, or the whole region when the cell is also its entrance. A lock there could
     * never open and no layout would be solvable, so the generator must skip such cells and still
     * finish with locks that can open.
     */
    @Test
    fun boardsWithALockThatCouldNeverOpenFinish() {
        class Case(val size: BoardSize, val seed: Int, val lockCount: Int, val farAndLines: Boolean)
        val cases = listOf(
            Case(BoardSize.MEDIUM, 92, 3, true),
            Case(BoardSize.LARGE, 89, 6, true),
            Case(BoardSize.HUGE, 198, 1, true),
            Case(BoardSize.MEDIUM, 59, 6, false),
        )
        for (case in cases) {
            val size = case.size
            val geometry = geometries.getValue(size)
            val rooms = minOf(3 + case.seed % 5, BoardLayout.maxRoomCount(geometry))
            val layout = BoardLayout.generate(
                geometry, size.walls, size.wallMinLength, size.wallMaxLength,
                if (case.farAndLines) size.farCells else 0, Random(case.seed),
                if (case.farAndLines) size.lineCells else 0, 0, 0, rooms,
            )
            assertTrue("$size seed ${case.seed}", layout.hasRooms)
            val mineCount = minOf(size.minesFor(layout), NoGuessGenerator.maxMineCount(layout, case.lockCount))
            for (draw in 1..5) {
                val label = "$size seed ${case.seed} locks ${case.lockCount} draw $draw"
                generateAndCheck(label, layout, mineCount, Random(draw.toLong()), case.lockCount)
            }
        }
    }

    /**
     * Every room size with 3 to 7 rooms, 0 to 8 line cells and 0 to 6 locks, once without and once with
     * islands and portals: the generator finishes on every board and places at least 40% of the locks
     * asked for. Only some passages can hold a lock, so fewer are placed than asked for: these seeds
     * place 50%, and other blocks of 300 seeds place 50% to 51%. The bound leaves room for tuning
     * and still fails when lock placement loses a fifth of its locks.
     */
    @Test
    fun generatorFinishesOnManyRoomBoardsWithLocks() {
        var requested = 0
        var placed = 0
        for (size in listOf(BoardSize.SMALL, BoardSize.MEDIUM, BoardSize.LARGE, BoardSize.HUGE, BoardSize.GIANT)) {
            val geometry = geometries.getValue(size)
            for (extras in listOf(false, true)) {
                val islands = if (extras) minOf(maxOf(size.islands, 2), BoardLayout.maxIslandCount(geometry)) else 0
                val portals = if (extras) maxOf(size.portals, 1) else 0
                for (seed in 1..300) {
                    val rooms = minOf(3 + seed % 5, BoardLayout.maxRoomCount(geometry, islands))
                    val lines = seed % 9
                    val lockCount = seed % 7
                    val layout = BoardLayout.generate(
                        geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, Random(seed),
                        lines, islands, portals, rooms,
                    )
                    if (!layout.hasRooms) continue
                    val label = "$size extras $extras seed $seed rooms $rooms lines $lines locks $lockCount"
                    val mineCount = minOf(size.minesFor(layout), NoGuessGenerator.maxMineCount(layout, lockCount))
                    placed += generateAndCheck(label, layout, mineCount, Random(seed * 31L), lockCount)
                    requested += lockCount
                }
            }
        }
        assertTrue("locks $placed of $requested", placed * 5 >= requested * 2)
    }

    /** start, hash of the mines, hash of the thresholds, repairs, redraws and the next draw of the random source. */
    private class Golden(
        val size: BoardSize,
        val seed: Int,
        val start: Int,
        val mines: Int,
        val thresholds: Int,
        val repairs: Int,
        val redraws: Int,
        val next: Long,
    )

    /**
     * Boards without rooms, with locks that count the whole board, come out exactly as recorded: same
     * start, mines, thresholds, repairs, redraws and draws from the random source. Half of the cases
     * use a quarter more mines than the size so that layouts are redrawn.
     */
    @Test
    fun boardsWithoutRoomsKeepTheirLayoutsWithLocks() {
        val plain = listOf(
            Golden(BoardSize.MEDIUM, 0, 65, 814004590, -615657956, 0, 0, 5870762152573324491L),
            Golden(BoardSize.MEDIUM, 1, 132, -2002390730, 1715659631, 0, 0, 1684144253827148227L),
            Golden(BoardSize.MEDIUM, 2, 90, 306273797, -1900369453, 0, 0, 8149196235943306731L),
            Golden(BoardSize.LARGE, 0, 101, 1513921795, 1233400905, 0, 0, 7242597288614529137L),
            Golden(BoardSize.LARGE, 1, 205, -545885081, -943132599, 0, 0, 2050910276809339964L),
            Golden(BoardSize.LARGE, 2, 140, 2135260757, -1377347114, 0, 0, -3833961652809013491L),
            Golden(BoardSize.HUGE, 0, 145, 879165985, 186616108, 1, 0, 3508408999983036139L),
            Golden(BoardSize.HUGE, 1, 295, 804353889, -407927488, 1, 0, -4884325015268847986L),
            Golden(BoardSize.HUGE, 2, 201, -1394844386, -1091234124, 0, 0, -8237738308913341258L),
            Golden(BoardSize.GIANT, 0, 258, 1029361641, 605218996, 0, 0, -4293479268255714383L),
            Golden(BoardSize.GIANT, 1, 523, -1074822251, -793172658, 1, 0, 2424836609451124304L),
            Golden(BoardSize.GIANT, 2, 357, 786935244, 347739703, 2, 0, 3071856535879721365L),
        )
        val crowded = listOf(
            Golden(BoardSize.MEDIUM, 178, 87, 918587742, -988913889, 5, 2, -931755292948503700L),
            Golden(BoardSize.MEDIUM, 275, 109, 1475019447, -1037176774, 2, 3, 6104649365204326333L),
            Golden(BoardSize.LARGE, 14, 223, 801628430, 444945270, 9, 2, -8416103588162658358L),
            Golden(BoardSize.LARGE, 85, 8, 447789786, -2006195741, 4, 2, -5215219638997483146L),
            Golden(BoardSize.LARGE, 213, 65, -271045397, 1550366748, 10, 3, 7311742381096347908L),
            Golden(BoardSize.HUGE, 16, 177, 1936089314, 1985858970, 10, 2, 6663745965641308062L),
            Golden(BoardSize.HUGE, 25, 275, -385081638, 1212818262, 6, 2, -7881822354474882768L),
            Golden(BoardSize.HUGE, 97, 207, -515596428, -770708587, 10, 2, -5051468749952906488L),
            Golden(BoardSize.GIANT, 34, 506, 1514601898, 736720697, 4, 2, 1975279439498008193L),
            Golden(BoardSize.GIANT, 38, 521, 1542862276, -323076231, 7, 2, 386650932887627192L),
            Golden(BoardSize.GIANT, 71, 450, -1869626993, 1542874230, 16, 2, -8991540480362793698L),
        )
        for ((golden, crowd) in plain.map { it to false } + crowded.map { it to true }) {
            val size = golden.size
            val seed = golden.seed
            val label = "$size seed $seed crowded $crowd"
            val layout = BoardLayout.generate(geometries.getValue(size), size, StableRandom(seed.toLong()))
            assertFalse(label, layout.hasRooms)
            val lockCount = if (crowd) 2 + seed % 3 else 2 + seed
            val mineCount = if (crowd) size.minesFor(layout) * 5 / 4 else size.minesFor(layout)
            val random = StableRandom(seed + 70L)
            val generated = NoGuessGenerator.generate(layout, mineCount, random, lockCount)
            assertEquals("$label start", golden.start, generated.start)
            assertEquals("$label mines", golden.mines, generated.mines.contentHashCode())
            assertEquals("$label thresholds", golden.thresholds, generated.lockThresholds.contentHashCode())
            assertEquals("$label repairs", golden.repairs, generated.repairs)
            assertEquals("$label redraws", golden.redraws, generated.redraws)
            assertEquals("$label next draw", golden.next, random.nextLong())
        }
    }
}
