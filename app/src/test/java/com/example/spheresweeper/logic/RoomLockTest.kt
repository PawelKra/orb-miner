package com.example.spheresweeper.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** Locks on boards with rooms: each guards a bridge gate and counts the revealed cells of its own region. */
class RoomLockTest {

    private val geometries: Map<BoardSize, SphereGeometry> =
        BoardSize.values().associateWith { SphereGeometry.build(it.frequency) }

    private val roomSizes = listOf(BoardSize.SMALL, BoardSize.MEDIUM, BoardSize.LARGE, BoardSize.HUGE, BoardSize.GIANT)

    /** Rooms 3 to 7 (as many as fit), the size's walls, far cells and portals; line cells only when [lines]. */
    private fun roomLayout(size: BoardSize, seed: Int, lines: Boolean, far: Boolean = true): BoardLayout {
        val geometry = geometries.getValue(size)
        val rooms = minOf(3 + seed % 5, BoardLayout.maxRoomCount(geometry))
        return BoardLayout.generate(
            geometry, size.walls, size.wallMinLength, size.wallMaxLength, if (far) size.farCells else 0, Random(seed),
            if (lines) size.lineCells else 0, 0, size.portals, rooms,
        )
    }

    /**
     * True when a flood over the scopes from a root cell, as on a board without mines, reaches every
     * land cell: no room is seen only from inside itself.
     */
    private fun everyCellReachable(layout: BoardLayout): Boolean {
        val cellCount = layout.scopes.size
        val start = (0 until cellCount).first { layout.isPresent(it) && layout.regionDepth(layout.regionOf(it)) == 0 }
        val reached = BooleanArray(cellCount)
        val queue = ArrayDeque<Int>()
        reached[start] = true
        queue.add(start)
        while (queue.isNotEmpty()) {
            for (other in layout.scopes[queue.removeFirst()]) {
                if (!reached[other]) {
                    reached[other] = true
                    queue.add(other)
                }
            }
        }
        return (0 until cellCount).all { !layout.isPresent(it) || reached[it] }
    }

    private fun mineArray(cellCount: Int, mines: IntArray): BooleanArray =
        BooleanArray(cellCount).also { array -> for (cell in mines) array[cell] = true }

    /** Undirected graph from edge pairs. */
    private fun graph(cellCount: Int, vararg edges: Int): Array<IntArray> {
        val lists = Array(cellCount) { ArrayList<Int>() }
        for (k in edges.indices step 2) {
            lists[edges[k]].add(edges[k + 1])
            lists[edges[k + 1]].add(edges[k])
        }
        return Array(cellCount) { lists[it].sorted().toIntArray() }
    }

    @Test
    fun regionLocksOpenTheSameOnBoardAndSolver() {
        // Region 0 = 0..3 with a lock on 3, region 1 = 4..7 with a lock on 5, cells 8 and 9 are counted by
        // no lock. Mines on 7 and 9. Thresholds are the safe cells of each region: 4 and 3.
        val neighbors = graph(10, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 8, 9)
        val thresholds = IntArray(10).also {
            it[3] = 4
            it[5] = 3
        }
        val regions = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1, -1, -1)
        val mines = intArrayOf(7, 9)
        val board = MinesweeperBoard(neighbors, 2, lockThresholds = thresholds, lockRegions = regions)
        val solver = LogicSolver(neighbors)
        solver.load(mineArray(10, mines), thresholds, regions)
        assertEquals(4, board.lockRemaining(3))
        assertEquals(3, board.lockRemaining(5))
        assertEquals(0, board.lockRemaining(0))

        fun compare(label: String) {
            for (cell in 0 until 10) {
                assertEquals("$label revealed $cell", board.isRevealed(cell), solver.isRevealed(cell))
                assertEquals("$label lock $cell", board.isLockOpen(cell), solver.isLockOpen(cell))
            }
            assertEquals(label, board.revealedCount, solver.revealedCount)
        }

        // 0 spreads to 3, which completes region 0: its lock opens at once and its zero spreads into
        // region 1 up to the closed lock on 5.
        assertEquals(MoveResult.REVEALED, board.startWith(mines, 0))
        solver.reveal(0)
        compare("after 0")
        for (cell in 0..5) assertTrue("cell $cell", board.isRevealed(cell))
        assertFalse(board.isRevealed(6))
        assertTrue(board.isLockOpen(3))
        assertFalse(board.isLockOpen(5))
        assertEquals(0, board.lockRemaining(3))
        assertEquals(1, board.lockRemaining(5))

        // Cells outside region 1 do not count for its lock, although the board count passes 3.
        assertEquals(MoveResult.REVEALED, board.reveal(8))
        solver.reveal(8)
        compare("after 8")
        assertEquals(7, board.revealedCount)
        assertFalse(board.isLockOpen(5))
        assertEquals(1, board.lockRemaining(5))

        assertEquals(MoveResult.WON, board.reveal(6))
        solver.reveal(6)
        compare("after 6")
        assertTrue(board.isLockOpen(5))
        assertEquals(0, board.lockRemaining(5))
        assertTrue(solver.run())
    }

    @Test
    fun lockedZeroSpreadsWhenItsRegionIsSolved() {
        // Lock on 1 (region 0 = 0, 1, 2); 1 shows 0 and holds back 3 and 4 in region 1. Mine on 5.
        val neighbors = graph(6, 0, 1, 1, 2, 1, 3, 3, 4, 4, 5)
        val thresholds = IntArray(6).also { it[1] = 3 }
        val regions = intArrayOf(0, 0, 0, -1, -1, -1)
        val board = MinesweeperBoard(neighbors, 1, lockThresholds = thresholds, lockRegions = regions)
        board.placeMinesAt(intArrayOf(5))
        val solver = LogicSolver(neighbors)
        solver.load(mineArray(6, intArrayOf(5)), thresholds, regions)
        for (move in intArrayOf(1, 0, 2)) {
            board.reveal(move)
            solver.reveal(move)
            for (cell in 0 until 6) {
                assertEquals("after $move cell $cell", board.isRevealed(cell), solver.isRevealed(cell))
                assertEquals("after $move lock $cell", board.isLockOpen(cell), solver.isLockOpen(cell))
            }
        }
        // The third reveal in region 0 opens the lock and the zero on 1 spreads to 3 and 4.
        assertTrue(board.isRevealed(3))
        assertTrue(board.isRevealed(4))
        assertEquals(GameStatus.WON, board.status)
    }

    @Test
    fun boardAndSolverAgreeOnGeneratedRoomBoards() {
        var moves = 0
        var closedSeen = 0
        for (size in listOf(BoardSize.MEDIUM, BoardSize.LARGE, BoardSize.GIANT)) {
            for (seed in 0 until 8) {
                val label = "$size seed $seed"
                val layout = roomLayout(size, seed, lines = false)
                val lockCount = 1 + seed % 6
                val mineCount = minesOnLand(size.mineDensity, layout, lockCount)
                val generated = NoGuessGenerator.generate(layout, mineCount, Random(seed + 50), lockCount)
                val regions = generated.lockRegions
                assertNotNull(label, regions)
                val cellCount = layout.scopes.size
                val board = MinesweeperBoard(
                    layout.scopes, mineCount, present = layout.presentMask(),
                    lockThresholds = generated.lockThresholds, lockRegions = regions,
                )
                board.startWith(generated.mines, generated.start)
                val solver = LogicSolver(layout.scopes, layout.presentMask())
                solver.load(mineArray(cellCount, generated.mines), generated.lockThresholds, regions)
                solver.reveal(generated.start)
                val safe = (0 until cellCount).filter { layout.isPresent(it) && !board.isMine(it) }.shuffled(Random(seed))
                for (cell in safe) {
                    if (board.isRevealed(cell)) continue
                    board.reveal(cell)
                    solver.reveal(cell)
                    moves++
                    assertEquals("$label after $cell", board.revealedCount, solver.revealedCount)
                    for (other in 0 until cellCount) {
                        assertEquals("$label after $cell revealed $other", board.isRevealed(other), solver.isRevealed(other))
                        val threshold = generated.lockThresholds[other]
                        if (threshold == 0) continue
                        assertEquals("$label after $cell lock $other", board.isLockOpen(other), solver.isLockOpen(other))
                        val region = layout.regionOf(other)
                        val counted = (0 until cellCount).count { layout.regionOf(it) == region && board.isRevealed(it) }
                        val remaining = maxOf(0, threshold - counted)
                        assertEquals("$label after $cell remaining $other", remaining, board.lockRemaining(other))
                        if (!board.isLockOpen(other)) closedSeen++
                    }
                }
                assertEquals(label, GameStatus.WON, board.status)
            }
        }
        assertTrue("moves $moves", moves > 1000)
        assertTrue("no lock seen closed", closedSeen > 0)
    }

    @Test
    fun locksStandOnBridgeGatesAndCountTheSafeCellsOfTheirRegion() {
        var placed = 0
        var requested = 0
        for (size in roomSizes) {
            for (seed in 0 until 10) {
                for (lines in listOf(false, true)) {
                    val label = "$size seed $seed lines $lines"
                    val layout = roomLayout(size, seed, lines)
                    assertTrue("$label has a room seen only from inside", everyCellReachable(layout))
                    val lockCount = 1 + seed % 6
                    val mineCount = minesOnLand(size.mineDensity, layout, lockCount)
                    val generated = NoGuessGenerator.generate(layout, mineCount, Random(seed + 7), lockCount)
                    val cellCount = layout.scopes.size
                    val mines = mineArray(cellCount, generated.mines)
                    val locks = generated.lockThresholds
                    val regions = generated.lockRegions!!
                    val start = generated.start
                    assertEquals("$label start depth", 0, layout.regionDepth(layout.regionOf(start)))
                    val bridgeNears = layout.gates.indices.filter { layout.isLockableGate(it) }
                        .map { layout.gates[it].first }.toSet()
                    val lockCells = (0 until cellCount).filter { locks[it] > 0 }
                    assertTrue("$label ${lockCells.size} locks", lockCells.size <= lockCount)
                    requested += lockCount
                    placed += lockCells.size
                    val lockedRegions = lockCells.map { layout.regionOf(it) }.toSet()
                    for (lock in lockCells) {
                        assertTrue("$label lock $lock not on a bridge gate", lock in bridgeNears)
                        assertFalse("$label lock $lock on a mine", mines[lock])
                        assertFalse("$label lock $lock is the start", lock == start)
                        assertFalse("$label lock $lock next to the start", lock in layout.scopes[start])
                        val region = layout.regionOf(lock)
                        val safe = (0 until cellCount).count { layout.regionOf(it) == region && !mines[it] }
                        assertEquals("$label threshold of $lock", safe, locks[lock])
                    }
                    for (cell in 0 until cellCount) {
                        val region = layout.regionOf(cell)
                        val expected = if (region >= 0 && region in lockedRegions) region else -1
                        assertEquals("$label lock region of $cell", expected, regions[cell])
                    }
                    assertTrue(
                        "$label not solvable",
                        LogicSolver.solves(layout.scopes, mines, start, layout.presentMask(), locks, regions),
                    )
                }
            }
        }
        assertTrue("locks $placed of $requested", placed * 4 >= requested)
    }

    /**
     * Without far and line cells only the lock cell sees across its gate, so the solver reveals no cell
     * behind a lock before the step that completes the lock's region.
     */
    @Test
    fun solverRevealsNothingBehindAClosedLock() {
        var checkedLocks = 0
        for (size in roomSizes) {
            for (seed in 0 until 10) {
                val label = "$size seed $seed"
                val layout = roomLayout(size, seed, lines = false, far = false)
                val lockCount = 1 + seed % 6
                val mineCount = minesOnLand(size.mineDensity, layout, lockCount)
                val generated = NoGuessGenerator.generate(layout, mineCount, Random(seed + 11), lockCount)
                val cellCount = layout.scopes.size
                val mines = mineArray(cellCount, generated.mines)
                val solver = LogicSolver(layout.scopes, layout.presentMask())
                solver.load(mines, generated.lockThresholds, generated.lockRegions)
                solver.reveal(generated.start)
                assertTrue("$label not solvable", solver.run())
                for (lock in 0 until cellCount) {
                    if (generated.lockThresholds[lock] == 0) continue
                    val region = layout.regionOf(lock)
                    val opened = (0 until cellCount)
                        .filter { layout.regionOf(it) == region && !mines[it] }
                        .maxOf { solver.resolveOrder(it) }
                    val behind = regionsBehind(layout, lock)
                    assertTrue("$label lock $lock guards nothing", behind.any { it })
                    for (cell in 0 until cellCount) {
                        if (!layout.isPresent(cell) || !behind[layout.regionOf(cell)]) continue
                        assertTrue(
                            "$label cell $cell behind lock $lock resolved at ${solver.resolveOrder(cell)}, lock opens at $opened",
                            solver.resolveOrder(cell) > opened,
                        )
                    }
                    checkedLocks++
                }
            }
        }
        assertTrue("checked locks $checkedLocks", checkedLocks > 50)
    }

    /** Regions the root cannot reach over the gates once the gates whose near cell is [lock] are taken out. */
    private fun regionsBehind(layout: BoardLayout, lock: Int): BooleanArray {
        val count = layout.regionCount
        val adjacent = Array(count) { ArrayList<Int>() }
        for ((near, far) in layout.gates) {
            if (near == lock) continue
            adjacent[layout.regionOf(near)].add(layout.regionOf(far))
            adjacent[layout.regionOf(far)].add(layout.regionOf(near))
        }
        val root = (0 until count).first { layout.regionDepth(it) == 0 }
        val reached = BooleanArray(count)
        val queue = ArrayDeque<Int>()
        reached[root] = true
        queue.add(root)
        while (queue.isNotEmpty()) {
            for (other in adjacent[queue.removeFirst()]) {
                if (!reached[other]) {
                    reached[other] = true
                    queue.add(other)
                }
            }
        }
        return BooleanArray(count) { !reached[it] }
    }

    @Test
    fun noLocksRequestedGivesNoLocksOnRoomBoards() {
        for (size in roomSizes) {
            for (seed in 0 until 5) {
                val label = "$size seed $seed"
                val layout = roomLayout(size, seed, lines = false)
                assertTrue(label, layout.hasRooms)
                val mineCount = minesOnLand(size.mineDensity, layout)
                val a = NoGuessGenerator.generate(layout, mineCount, Random(seed))
                val b = NoGuessGenerator.generate(layout, mineCount, Random(seed), 0)
                assertTrue(label, a.lockThresholds.all { it == 0 })
                assertNull(label, a.lockRegions)
                assertEquals(label, 0, layout.regionDepth(layout.regionOf(a.start)))
                assertEquals(label, a.start, b.start)
                assertArrayEquals(label, a.mines, b.mines)
            }
        }
    }

    @Test
    fun boardsWithoutRoomsKeepLocksThatCountTheWholeBoard() {
        for (size in listOf(BoardSize.LARGE, BoardSize.HUGE, BoardSize.GIANT)) {
            val geometry = geometries.getValue(size)
            for (seed in 0 until 6) {
                val label = "$size seed $seed"
                val layout = BoardLayout.generate(geometry, size, StableRandom(seed.toLong()))
                assertFalse(label, layout.hasRooms)
                val lockCount = maxOf(1, size.locks)
                val mineCount = size.minesFor(layout)
                val random = StableRandom(seed + 40L)
                val generated = NoGuessGenerator.generate(layout, mineCount, random, lockCount)
                val plainRandom = StableRandom(seed + 40L)
                val plain = NoGuessGenerator.generate(
                    layout.scopes, mineCount, plainRandom,
                    present = layout.presentMask(), mineFree = layout.portalMask(), lockCount = lockCount,
                )
                assertNull(label, generated.lockRegions)
                assertEquals(label, plain.start, generated.start)
                assertArrayEquals(label, plain.mines, generated.mines)
                assertArrayEquals(label, plain.lockThresholds, generated.lockThresholds)
                assertEquals("$label next draw", plainRandom.nextLong(), random.nextLong())
                val safeCells = layout.presentCount - mineCount
                assertEquals(label, lockCount, generated.lockThresholds.count { it > 0 })
                for (threshold in generated.lockThresholds) {
                    if (threshold == 0) continue
                    assertTrue(
                        "$label threshold $threshold of $safeCells",
                        threshold * 4 >= safeCells && threshold * 4 <= safeCells * 3,
                    )
                }
            }
        }
    }

    @Test
    fun generatorFinishesWithRoomsAndLocksOnEverySize() {
        var boards = 0
        var redraws = 0
        for (size in roomSizes) {
            for (seed in 0 until 12) for (lines in listOf(false, true)) {
                val label = "$size seed $seed lines $lines"
                val layout = roomLayout(size, seed, lines)
                val lockCount = 1 + seed % 6
                val mineCount = minesOnLand(size.mineDensity, layout, lockCount)
                val generated = NoGuessGenerator.generate(layout, mineCount, Random(seed + 300), lockCount)
                boards++
                redraws += generated.redraws
                val cellCount = layout.scopes.size
                val mines = mineArray(cellCount, generated.mines)
                assertEquals(label, mineCount, generated.mines.size)
                for (cell in 0 until cellCount) {
                    if (layout.isPortal(cell) || !layout.isPresent(cell)) assertFalse("$label mine on $cell", mines[cell])
                }

                // A board started from the layout is won by revealing the safe cells in solver order.
                val solver = LogicSolver(layout.scopes, layout.presentMask())
                solver.load(mines, generated.lockThresholds, generated.lockRegions)
                solver.reveal(generated.start)
                assertTrue("$label not solvable", solver.run())
                val board = MinesweeperBoard(
                    layout.scopes, mineCount, present = layout.presentMask(),
                    lockThresholds = generated.lockThresholds, lockRegions = generated.lockRegions,
                )
                board.startWith(generated.mines, generated.start)
                var last = MoveResult.REVEALED
                val safeInOrder = (0 until cellCount).filter { layout.isPresent(it) && !mines[it] }.sortedBy { solver.resolveOrder(it) }
                for (cell in safeInOrder) {
                    if (board.isRevealed(cell)) continue
                    last = board.reveal(cell)
                    if (last == MoveResult.WON) break
                }
                assertEquals(label, MoveResult.WON, last)
            }
        }
        assertTrue("$redraws redraws for $boards boards", redraws <= 2 * boards)
    }

    @Test
    fun maxMineCountCountsOnlyStartsInTheRootRegion() {
        for (seed in 0 until 5) {
            val size = BoardSize.LARGE
            val layout = roomLayout(size, seed, lines = false)
            val max = NoGuessGenerator.maxMineCount(layout, 2)
            val present = layout.presentMask()
            val mineFree = layout.portalMask()
            val mineable = present.indices.count { present[it] && !mineFree[it] }
            val rootBest = present.indices
                .filter { present[it] && layout.regionDepth(layout.regionOf(it)) == 0 }
                .maxOf { cell -> mineable - 2 - (layout.scopes[cell] + cell).distinct().count { !mineFree[it] } }
            assertEquals("seed $seed", rootBest, max)
            try {
                NoGuessGenerator.generate(layout, max + 1, Random(seed), 2)
                fail("seed $seed: ${max + 1} mines accepted")
            } catch (expected: IllegalArgumentException) {
                // No root start leaves room for one mine more than the limit.
            }
        }
    }
}
