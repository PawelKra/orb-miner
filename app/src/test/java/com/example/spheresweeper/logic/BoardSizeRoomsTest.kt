package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Free games with rooms: every [BoardSize] is built the way the game builds it ([BoardLayout.generate]
 * with all counts of the size and [BoardSize.OCEAN_SHARE], mines from [BoardSize.minesFor], then
 * [NoGuessGenerator.generate]).
 */
class BoardSizeRoomsTest {

    private val geometries: Map<BoardSize, SphereGeometry> =
        BoardSize.values().associateWith { SphereGeometry.build(it.frequency) }

    @Test
    fun roomCountsFitEverySize() {
        val expected = mapOf(
            BoardSize.TINY to 0, BoardSize.SMALL to 3, BoardSize.MEDIUM to 4,
            BoardSize.LARGE to 6, BoardSize.HUGE to 8, BoardSize.GIANT to 11,
            BoardSize.MEGA to 14, BoardSize.GARGANTUAN to 17,
        )
        for (size in BoardSize.values()) {
            assertEquals("$size rooms", expected.getValue(size), size.rooms)
            val geometry = geometries.getValue(size)
            val max = BoardLayout.maxRoomCount(geometry, size.islands, BoardSize.OCEAN_SHARE)
            assertTrue("$size ${size.rooms} rooms, at most $max", size.rooms <= max)
            val maxIslands = BoardLayout.maxIslandCount(geometry, BoardSize.OCEAN_SHARE)
            assertTrue("$size ${size.islands} islands, at most $maxIslands", size.islands <= maxIslands)
        }
    }

    /** Each size is a bigger sphere than the one before it and never easier on any count. */
    @Test
    fun sizesGrowInOrder() {
        val sizes = BoardSize.values()
        for (k in 1 until sizes.size) {
            val small = sizes[k - 1]
            val big = sizes[k]
            val label = "$small -> $big"
            assertTrue("$label frequency", big.frequency > small.frequency)
            assertTrue(
                "$label density ${small.mineDensity} -> ${big.mineDensity}", big.mineDensity >= small.mineDensity,
            )
            val counts = listOf<Pair<String, (BoardSize) -> Int>>(
                "lives" to { it.lives }, "hints" to { it.hints }, "walls" to { it.walls },
                "wall length" to { it.wallMaxLength }, "far cells" to { it.farCells },
                "line cells" to { it.lineCells }, "islands" to { it.islands }, "portals" to { it.portals },
                "locks" to { it.locks }, "rooms" to { it.rooms },
            )
            for ((name, count) in counts) {
                assertTrue("$label $name ${count(small)} -> ${count(big)}", count(big) >= count(small))
            }
        }
    }

    /**
     * Also checks that the rooms leave enough passages: over the seeds a size places at least
     * [MIN_LINE_SHARE] of its [BoardSize.lineCells] on average, and its rooms keep at least
     * [MIN_AVERAGE_ROOM_CELLS] land cells on average.
     */
    @Test
    fun everySizeGeneratesSolvableGamesOnFiftySeeds() {
        for (size in BoardSize.values()) {
            val geometry = geometries.getValue(size)
            var lines = 0
            var roomCells = 0.0
            for (seed in 0 until SEEDS) {
                val label = "$size seed $seed"
                val random = Random(seed)
                val layout = BoardLayout.generate(
                    geometry, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells, random,
                    size.lineCells, size.islands, size.portals, size.rooms, BoardSize.OCEAN_SHARE,
                )
                val mines = size.minesFor(layout)
                val generated = NoGuessGenerator.generate(layout, mines, random, size.locks)
                val board = MinesweeperBoard(
                    layout.scopes, mines, lives = size.lives,
                    present = layout.presentMask(), lockThresholds = generated.lockThresholds,
                    lockRegions = generated.lockRegions,
                )
                board.startWith(generated.mines, generated.start)

                assertEquals("$label rooms", size.rooms > 1, layout.hasRooms)
                if (layout.hasRooms) assertTrue("$label room count", layout.regionCount <= size.rooms)
                assertTrue("$label line count ${layout.lineCount}", layout.lineCount <= size.lineCells)
                assertTrue("$label lock count ${board.lockCount}", board.lockCount <= size.locks)
                lines += layout.lineCount
                roomCells += layout.presentCount.toDouble() / layout.regionCount
                assertEquals("$label far cells", size.farCells, layout.farCount)
                assertEquals("$label mines", mines, generated.mines.size)
                // A tiny board may be solved by the start reveal alone.
                assertTrue("$label status ${board.status}", board.status != GameStatus.LOST)

                val mineMask = BooleanArray(layout.scopes.size)
                for (cell in generated.mines) mineMask[cell] = true
                val solver = LogicSolver(layout.scopes, layout.presentMask())
                solver.load(mineMask, generated.lockThresholds, generated.lockRegions)
                solver.reveal(generated.start)
                assertTrue("$label solvable without guessing", solver.run())
            }
            if (size.rooms > 1) {
                val lineShare = lines.toDouble() / (SEEDS * size.lineCells)
                assertTrue("$size line share $lineShare", lineShare >= MIN_LINE_SHARE)
                val averageRoom = roomCells / SEEDS
                assertTrue("$size average room $averageRoom cells", averageRoom >= MIN_AVERAGE_ROOM_CELLS)
            }
        }
    }

    private companion object {
        const val SEEDS = 50

        /** Least share of the asked line cells a size with rooms places on average. */
        const val MIN_LINE_SHARE = 0.4

        /** Least average room size, in land cells, that keeps a room worth solving. */
        const val MIN_AVERAGE_ROOM_CELLS = 15.0
    }
}
