package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignProgressTest {

    private val trunk = Campaign.levels(Branch.TRUNK)
    private val feeders = listOf(Branch.WALLS, Branch.FAR, Branch.LINES)

    private fun win(progress: CampaignProgress, level: LevelSpec, stars: Int = 1): CampaignProgress {
        val livesLost = if (stars >= 2) 0 else 1
        val hintsUsed = if (stars >= 3) 0 else 1
        return progress.record(level, won = true, livesLost = livesLost, hintsUsed = hintsUsed).progress
    }

    private fun winAll(progress: CampaignProgress, levels: List<LevelSpec>): CampaignProgress =
        levels.fold(progress) { p, level -> win(p, level) }

    @Test
    fun starsForCountsWinCleanRunAndNoHints() {
        assertEquals(0, CampaignProgress.starsFor(won = false, livesLost = 0, hintsUsed = 0))
        assertEquals(1, CampaignProgress.starsFor(won = true, livesLost = 2, hintsUsed = 1))
        assertEquals(2, CampaignProgress.starsFor(won = true, livesLost = 0, hintsUsed = 1))
        assertEquals(2, CampaignProgress.starsFor(won = true, livesLost = 1, hintsUsed = 0))
        assertEquals(3, CampaignProgress.starsFor(won = true, livesLost = 0, hintsUsed = 0))
    }

    @Test
    fun onlyFirstTrunkLevelIsOpenAtStart() {
        val progress = CampaignProgress.EMPTY
        for (level in Campaign.all) {
            assertEquals(level.id, level == trunk.first(), progress.isUnlocked(level))
            assertEquals(0, progress.stars(level))
        }
        assertEquals(0, progress.totalStars)
    }

    @Test
    fun trunkOpensOneLevelAtATime() {
        var progress = CampaignProgress.EMPTY
        for (i in trunk.indices) {
            assertTrue(trunk[i].id, progress.isUnlocked(trunk[i]))
            if (i + 1 < trunk.size) assertFalse(trunk[i + 1].id, progress.isUnlocked(trunk[i + 1]))
            // A loss does not open anything.
            progress = progress.record(trunk[i], won = false, livesLost = 1, hintsUsed = 0).progress
            if (i + 1 < trunk.size) assertFalse(trunk[i + 1].id, progress.isUnlocked(trunk[i + 1]))
            progress = win(progress, trunk[i])
        }
        assertEquals(trunk.size, progress.completedCount(Branch.TRUNK))
    }

    @Test
    fun sideBranchesOpenOnlyAfterTheWholeTrunk() {
        val almost = winAll(CampaignProgress.EMPTY, trunk.dropLast(1))
        for (branch in feeders + Branch.MIXED) {
            assertFalse("$branch", almost.isBranchOpen(branch))
            assertFalse("$branch", almost.isUnlocked(Campaign.levels(branch).first()))
        }
        val done = win(almost, trunk.last())
        for (branch in feeders) {
            val levels = Campaign.levels(branch)
            assertTrue("$branch", done.isUnlocked(levels[0]))
            assertFalse("$branch", done.isUnlocked(levels[1]))
            val next = win(done, levels[0])
            assertTrue("$branch", next.isUnlocked(levels[1]))
            assertFalse("$branch", next.isUnlocked(levels[2]))
        }
        assertFalse(done.isBranchOpen(Branch.MIXED))
    }

    @Test
    fun mixedOpensAfterHalfOfEachOtherBranch() {
        for (branch in feeders) assertEquals(10, CampaignProgress.mixedUnlockCount(branch))
        var progress = winAll(CampaignProgress.EMPTY, trunk)
        progress = winAll(progress, Campaign.levels(Branch.WALLS))
        progress = winAll(progress, Campaign.levels(Branch.FAR).take(10))
        progress = winAll(progress, Campaign.levels(Branch.LINES).take(9))
        val mixed = Campaign.levels(Branch.MIXED)
        assertFalse(progress.isBranchOpen(Branch.MIXED))
        assertFalse(progress.isUnlocked(mixed[0]))
        progress = win(progress, Campaign.levels(Branch.LINES)[9])
        assertEquals(10, progress.completedCount(Branch.LINES))
        assertTrue(progress.isUnlocked(mixed[0]))
        assertFalse(progress.isUnlocked(mixed[1]))
    }

    @Test
    fun completedLevelStaysOpenWithoutItsPrerequisites() {
        val walls = Campaign.levels(Branch.WALLS)
        val progress = CampaignProgress.parse("${walls[4].id}:2")
        assertTrue(progress.isUnlocked(walls[4]))
        assertFalse(progress.isUnlocked(walls[5]))
        assertFalse(progress.isUnlocked(walls[3]))
    }

    @Test
    fun bestStarsNeverDrop() {
        val level = trunk.first()
        val first = CampaignProgress.EMPTY.record(level, won = true, livesLost = 0, hintsUsed = 0)
        assertEquals(3, first.earned)
        assertEquals(0, first.previous)
        assertTrue(first.improved)
        assertTrue(first.firstCompletion)
        assertEquals(3, first.progress.stars(level))

        val worse = first.progress.record(level, won = true, livesLost = 1, hintsUsed = 1)
        assertEquals(1, worse.earned)
        assertEquals(3, worse.best)
        assertFalse(worse.improved)
        assertFalse(worse.firstCompletion)
        assertSame(first.progress, worse.progress)

        val lost = first.progress.record(level, won = false, livesLost = 1, hintsUsed = 0)
        assertEquals(0, lost.earned)
        assertEquals(3, lost.progress.stars(level))

        val low = CampaignProgress.EMPTY.record(level, won = true, livesLost = 1, hintsUsed = 0)
        val better = low.progress.record(level, won = true, livesLost = 0, hintsUsed = 0)
        assertEquals(2, low.earned)
        assertTrue(better.improved)
        assertFalse(better.firstCompletion)
        assertEquals(3, better.progress.stars(level))
        assertEquals(3, better.progress.totalStars)
    }

    @Test
    fun serializationRoundTrips() {
        assertEquals("", CampaignProgress.EMPTY.serialize())
        assertEquals(CampaignProgress.EMPTY, CampaignProgress.parse(""))
        assertEquals(CampaignProgress.EMPTY, CampaignProgress.parse(null))

        var progress = winAll(CampaignProgress.EMPTY, trunk)
        progress = win(progress, trunk[3], stars = 3)
        progress = win(progress, Campaign.levels(Branch.FAR)[0], stars = 2)
        val text = progress.serialize()
        val restored = CampaignProgress.parse(text)
        assertEquals(progress, restored)
        assertEquals(text, restored.serialize())
        // One star per trunk level, two more on trunk[3], two on the far level.
        assertEquals(trunk.size + 2 + 2, restored.totalStars)
        assertEquals(1, restored.completedCount(Branch.FAR))
        assertEquals(3, restored.stars(trunk[3]))
    }

    @Test
    fun parseSkipsBrokenAndUnknownEntries() {
        val text = "trunk-01:3,bogus-01:2,trunk-02:x,trunk-03:9,trunk-04:0,::,trunk-05,walls-01:2:1," +
            " trunk-06 : 2 ,trunk-01:1,,far-99:3,trunk-07:-1"
        val progress = CampaignProgress.parse(text)
        assertEquals(3, progress.stars(trunk[0]))
        assertEquals(2, progress.stars(trunk[5]))
        assertEquals(5, progress.totalStars)
        for (i in listOf(1, 2, 3, 4, 6)) assertEquals(trunk[i].id, 0, progress.stars(trunk[i]))
        assertEquals(0, progress.stars(Campaign.levels(Branch.WALLS)[0]))

        for (garbage in listOf("%%%", ",,,", ":", "trunk-01", "\u0000\n\t", "x".repeat(1000))) {
            assertEquals(garbage, CampaignProgress.EMPTY, CampaignProgress.parse(garbage))
        }
    }
}
