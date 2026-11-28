package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Levels with rooms: the lines, mixed and locks branches. With rooms, line cells and locks stand only
 * at passages, so a built level often has fewer than its [LevelSpec.lineCells] or [LevelSpec.locks].
 * These tests check that the placed counts still teach the branch's feature and grow along it, and
 * that the levels build fast enough to be generated on the device.
 */
class CampaignRoomsTest {

    private val roomBranches = listOf(Branch.LINES, Branch.MIXED, Branch.LOCKS)

    private val geometries = HashMap<Int, SphereGeometry>()

    private fun geometry(frequency: Int): SphereGeometry =
        geometries.getOrPut(frequency) { SphereGeometry.build(frequency) }

    /** Line cells for the lines and mixed branches, locks for the locks branch. */
    private fun placed(level: PreparedLevel): Int =
        if (level.spec.branch == Branch.LOCKS) level.board.lockCount else level.layout.lineCount

    @Test
    fun onlyTheRoomBranchesHaveRoomsAndTheyFit() {
        for (spec in Campaign.all) {
            if (spec.branch in roomBranches) {
                assertTrue("${spec.id} rooms ${spec.rooms}", spec.rooms >= 2)
                assertTrue(
                    "${spec.id} rooms fit",
                    spec.rooms <= BoardLayout.maxRoomCount(geometry(spec.frequency), spec.islands),
                )
            } else {
                assertEquals("${spec.id} rooms", 0, spec.rooms)
            }
        }
        for (branch in roomBranches) {
            val levels = Campaign.levels(branch)
            for (i in 1 until levels.size) {
                assertTrue("${levels[i].id} rooms never drop", levels[i].rooms >= levels[i - 1].rooms)
            }
        }
        assertEquals(3, Campaign.levels(Branch.LINES).first().rooms)
        assertEquals(9, Campaign.levels(Branch.LINES).last().rooms)
        assertEquals(3, Campaign.levels(Branch.MIXED).first().rooms)
        assertEquals(6, Campaign.levels(Branch.MIXED).last().rooms)
        assertEquals(2, Campaign.levels(Branch.LOCKS).first().rooms)
        assertEquals(7, Campaign.levels(Branch.LOCKS).last().rooms)
    }

    /**
     * Every level has at least one line cell (lines, mixed) or lock (locks), never more than asked
     * for, and the placed count falls by at most [MAX_DROP] from one level to the next.
     */
    @Test
    fun placedLinesAndLocksStayPresentAndGrowAlongTheBranch() {
        for (branch in roomBranches) {
            var previous = -1
            for (spec in Campaign.levels(branch)) {
                val level = Campaign.buildLevel(spec, geometry(spec.frequency))
                assertTrue("${spec.id} has rooms", level.layout.hasRooms)
                assertEquals("${spec.id} room count", spec.rooms, level.layout.regionCount)
                val asked = if (branch == Branch.LOCKS) spec.locks else spec.lineCells
                val count = placed(level)
                assertTrue("${spec.id} placed $count of $asked", count in 1..asked)
                if (previous >= 0) {
                    assertTrue("${spec.id} placed $count after $previous", count >= previous - MAX_DROP)
                }
                previous = count
            }
            val levels = Campaign.levels(branch)
            val first = placed(Campaign.buildLevel(levels.first(), geometry(levels.first().frequency)))
            val last = placed(Campaign.buildLevel(levels.last(), geometry(levels.last().frequency)))
            assertTrue("$branch grows from $first to $last", last > first)
        }
    }

    /** The 60 room levels build within [MAX_TOTAL_MILLIS] together, each with few redrawn layouts. */
    @Test
    fun roomLevelsBuildQuickly() {
        val specs = roomBranches.flatMap { Campaign.levels(it) }
        val startNanos = System.nanoTime()
        for (spec in specs) {
            val level = Campaign.buildLevel(spec, geometry(spec.frequency))
            assertTrue("${spec.id} redraws ${level.redraws}", level.redraws < MAX_REDRAWS)
        }
        val millis = (System.nanoTime() - startNanos) / 1_000_000
        assertEquals(60, specs.size)
        assertTrue("60 room levels took $millis ms", millis < MAX_TOTAL_MILLIS)
    }

    private companion object {
        const val MAX_DROP = 2
        const val MAX_REDRAWS = 50
        const val MAX_TOTAL_MILLIS = 20_000L
    }
}
