package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStatsTest {

    private val trunkFirst = Campaign.levels(Branch.TRUNK)[0]
    private val trunkSixth = Campaign.levels(Branch.TRUNK)[5]
    private val wallsFourth = Campaign.levels(Branch.WALLS)[3]

    private fun free(
        size: BoardSize,
        won: Boolean,
        time: Long = 1000L,
        at: Long = 1L,
        seed: Long = 7L,
        rooms: Int = size.rooms,
        walls: Boolean = true,
    ) = GameRecord(at, GameMode.Free(size, seed, rooms, walls), won, time, livesLost = 0, hintsUsed = 0)

    private fun level(spec: LevelSpec, won: Boolean, time: Long = 1000L, at: Long = 1L) =
        GameRecord(at, GameMode.Level(spec.id), won, time, livesLost = 1, hintsUsed = 2)

    private fun GameStats.addAll(games: List<GameRecord>): GameStats = games.fold(this) { s, g -> s.add(g) }

    @Test
    fun emptyStatsHaveNoValues() {
        assertTrue(GameStats.EMPTY.isEmpty)
        val t = GameStats.EMPTY.totals(BoardSize.SMALL)
        assertEquals(0, t.played)
        assertEquals(0, t.wins)
        assertNull(t.winRate)
        assertNull(t.bestTimeMillis)
        assertNull(t.averageWinMillis)
        assertEquals(0, t.currentStreak)
        assertEquals(0, t.bestStreak)
    }

    @Test
    fun aggregatesPerBoardSize() {
        val stats = GameStats.EMPTY.addAll(
            listOf(
                free(BoardSize.SMALL, won = true, time = 3000L),
                free(BoardSize.SMALL, won = false, time = 500L),
                free(BoardSize.SMALL, won = true, time = 2000L),
                free(BoardSize.SMALL, won = true, time = 4000L),
                free(BoardSize.MEDIUM, won = false, time = 9000L),
            ),
        )
        assertFalse(stats.isEmpty)
        val small = stats.totals(BoardSize.SMALL)
        assertEquals(4, small.played)
        assertEquals(3, small.wins)
        assertEquals(0.75, small.winRate!!, 1e-9)
        assertEquals("a lost game's time is no record", 2000L, small.bestTimeMillis)
        assertEquals("average of the won games only", 3000L, small.averageWinMillis)
        val medium = stats.totals(BoardSize.MEDIUM)
        assertEquals(1, medium.played)
        assertEquals(0, medium.wins)
        assertEquals(0.0, medium.winRate!!, 1e-9)
        assertNull(medium.bestTimeMillis)
        assertNull(medium.averageWinMillis)
        assertEquals(0, stats.totals(BoardSize.TINY).played)
        assertEquals(5, stats.history.size)
    }

    @Test
    fun aggregatesPerCampaignBranchWithTheTrunkAsItsOwnRow() {
        val stats = GameStats.EMPTY.addAll(
            listOf(
                level(trunkFirst, won = true, time = 1500L),
                level(wallsFourth, won = false),
                level(trunkSixth, won = true, time = 2500L),
            ),
        )
        val trunk = stats.totals(Branch.TRUNK)
        assertEquals(2, trunk.played)
        assertEquals(2, trunk.wins)
        assertEquals(1.0, trunk.winRate!!, 1e-9)
        assertEquals(1500L, trunk.bestTimeMillis)
        assertEquals(2000L, trunk.averageWinMillis)
        assertEquals(2, trunk.currentStreak)
        val walls = stats.totals(Branch.WALLS)
        assertEquals(1, walls.played)
        assertEquals(0, walls.wins)
        assertEquals(0, stats.totals(Branch.FAR).played)
        assertEquals("free sizes stay apart from branches", 0, stats.totals(BoardSize.SMALL).played)
        assertEquals(StatsKey.CampaignBranch(Branch.WALLS), GameMode.Level(wallsFourth.id).key)
    }

    @Test
    fun streaksAreBrokenByALossOfTheSameKeyOnly() {
        val results = listOf(true, true, false, true, true, true, false, true)
        val expectedCurrent = listOf(1, 2, 0, 1, 2, 3, 0, 1)
        val expectedBest = listOf(1, 2, 2, 2, 2, 3, 3, 3)
        var stats = GameStats.EMPTY
        for ((k, won) in results.withIndex()) {
            stats = stats.add(free(BoardSize.LARGE, won))
            // A loss on another size or in the campaign does not break the streak.
            stats = stats.add(free(BoardSize.TINY, won = false))
            stats = stats.add(level(trunkFirst, won = false))
            val large = stats.totals(BoardSize.LARGE)
            assertEquals("current after game $k", expectedCurrent[k], large.currentStreak)
            assertEquals("best after game $k", expectedBest[k], large.bestStreak)
        }
        assertEquals(0, stats.totals(BoardSize.TINY).bestStreak)
        assertEquals(0, stats.totals(Branch.TRUNK).currentStreak)
    }

    @Test
    fun historyKeepsTheLastGamesWhileTotalsKeepEveryGame() {
        val total = GameStats.MAX_HISTORY + 120
        var stats = GameStats.EMPTY
        for (k in 0 until total) {
            // Every fourth game is lost; game 1 holds the best time and only the first 120 games are
            // trimmed, so the best time comes from a trimmed game.
            stats = stats.add(free(BoardSize.SMALL, won = k % 4 != 0, time = 1000L + k, at = k.toLong()))
        }
        assertEquals(GameStats.MAX_HISTORY, stats.history.size)
        assertEquals("oldest kept game", (total - GameStats.MAX_HISTORY).toLong(), stats.history.first().finishedAt)
        assertEquals("newest game last", (total - 1).toLong(), stats.history.last().finishedAt)
        val small = stats.totals(BoardSize.SMALL)
        assertEquals(total, small.played)
        assertEquals((0 until total).count { it % 4 != 0 }, small.wins)
        assertEquals("best time from a trimmed game", 1001L, small.bestTimeMillis)
        val winTimes = (0 until total).filter { it % 4 != 0 }.map { 1000L + it }
        assertEquals(winTimes.sum() / winTimes.size, small.averageWinMillis)
        assertEquals(3, small.bestStreak)

        val restored = GameStats.parse(stats.serialize())
        assertEquals(stats, restored)
        assertEquals(total, restored.totals(BoardSize.SMALL).played)
        assertEquals(1001L, restored.totals(BoardSize.SMALL).bestTimeMillis)
    }

    @Test
    fun bestStreakSurvivesTrimming() {
        var stats = GameStats.EMPTY
        repeat(GameStats.MAX_HISTORY + 50) { stats = stats.add(free(BoardSize.HUGE, won = true)) }
        stats = stats.add(free(BoardSize.HUGE, won = false))
        repeat(GameStats.MAX_HISTORY) { stats = stats.add(free(BoardSize.HUGE, won = it % 2 == 0)) }
        val huge = stats.totals(BoardSize.HUGE)
        assertEquals(GameStats.MAX_HISTORY + 50, huge.bestStreak)
        assertEquals(0, huge.currentStreak)
        assertEquals(huge, GameStats.parse(stats.serialize()).totals(BoardSize.HUGE))
    }

    @Test
    fun serializeAndParseRoundTrip() {
        val stats = GameStats.EMPTY.addAll(
            listOf(
                GameRecord(
                    1_700_000_000_000L, GameMode.Free(BoardSize.GIANT, -1234567890123L, 11, true),
                    won = true, timeMillis = 654_321L, livesLost = 2, hintsUsed = 1,
                ),
                GameRecord(
                    1_700_000_100_000L, GameMode.Free(BoardSize.HUGE, Long.MIN_VALUE, 0, false),
                    won = false, timeMillis = 12L, livesLost = 4, hintsUsed = 0,
                ),
                level(trunkFirst, won = true, time = 4321L, at = 1_700_000_200_000L),
                level(wallsFourth, won = false, at = 1_700_000_300_000L),
            ),
        )
        val text = stats.serialize()
        assertTrue(text, text.startsWith("V,${GameStats.VERSION};"))
        val restored = GameStats.parse(text)
        assertEquals(stats, restored)
        assertEquals(stats.history, restored.history)
        assertEquals(text, restored.serialize())
        assertEquals(stats.totals(BoardSize.HUGE), restored.totals(BoardSize.HUGE))
        assertSame(GameStats.EMPTY, GameStats.parse(GameStats.EMPTY.serialize()))
    }

    @Test
    fun parseOfNullOrBlankIsEmpty() {
        assertSame(GameStats.EMPTY, GameStats.parse(null))
        assertSame(GameStats.EMPTY, GameStats.parse(""))
        assertSame(GameStats.EMPTY, GameStats.parse("   "))
    }

    @Test
    fun parseOfAnUnknownOrMissingVersionIsEmpty() {
        val body = GameStats.EMPTY.add(free(BoardSize.SMALL, won = true)).serialize().substringAfter(";")
        assertSame(GameStats.EMPTY, GameStats.parse("V,2;$body"))
        assertSame(GameStats.EMPTY, GameStats.parse("V,x;$body"))
        assertSame(GameStats.EMPTY, GameStats.parse("V;$body"))
        assertSame("no version marker", GameStats.EMPTY, GameStats.parse(body))
        assertSame("marker not first", GameStats.EMPTY, GameStats.parse("$body;V,1"))
        assertEquals(1, GameStats.parse("V,1;$body").totals(BoardSize.SMALL).played)
    }

    @Test
    fun parseSkipsMalformedEntriesUnknownSizesAndLevelIds() {
        val good = GameStats.EMPTY
            .add(free(BoardSize.SMALL, won = true, time = 2000L))
            .add(level(trunkFirst, won = true))
        val goodEntries = good.serialize().split(";").drop(1)
        val noise = listOf(
            "garbage",
            "",
            ",,,",
            "X,1,2,3",
            "V,1",
            "T,F,NOPE,1,1,1,1,1,1",
            "T,C,NOPE,1,1,1,1,1,1",
            "T,Q,SMALL,1,1,1,1,1,1",
            "T,F,MEDIUM,1,2,1,1,1,1", // more wins than games
            "T,F,MEDIUM,2,1,-,5,1,1", // a win without a best time
            "T,F,MEDIUM,2,0,5,0,0,0", // a best time without a win
            "T,F,MEDIUM,2,1,5,5,2,1", // current streak above the best
            "T,F,MEDIUM,3,1,5,5,0,2", // best streak above the wins
            "T,F,MEDIUM,2,1,5,5",
            "T,F,LARGE,x,1,5,5,1,1",
            "T,F,LARGE,2,1,-5,5,1,1",
            "T,F,LARGE,2,1,5,-5,1,1", // negative sum of winning times
            "T,F,LARGE,2,1,5,9223372036854775808,1,1", // sum beyond Long
            "T,F,LARGE,2147483648,1,5,5,1,1", // games beyond Int
            "T,F,SMALL,0,0,-,0,0,0", // no games
            "T,C,TRUNK,0,0,-,0,0,0",
            "G,1,1,1000,0,0,F,MEDIUM,5,3,0", // rooms without walls
            "G,1,1,1000,0,0,F,NOPE,5,3,1",
            "G,1,1,1000,0,0,F,small,5,3,1",
            "G,1,1,1000,0,0,F,MEDIUM,5,-1,1",
            "G,1,2,1000,0,0,F,MEDIUM,5,3,1",
            "G,1,1,-5,0,0,F,MEDIUM,5,3,1",
            "G,1,1,1000,-1,0,F,MEDIUM,5,3,1",
            "G,1,1,1000,0,-1,F,MEDIUM,5,3,1",
            "G,-1,1,1000,0,0,F,MEDIUM,5,3,1",
            "G,1,1,1000,0,0,F,MEDIUM,notaseed,3,1",
            "G,1,1,1000,0,0,F,MEDIUM,5,3",
            "G,1,1,1000,0,0,F,MEDIUM,5,3,1,extra",
            "G,1,1,1000,0,0,L,no-such-level",
            "G,1,1,1000,0,0,L",
            "G,1,1,1000,0,0,Z,${trunkFirst.id}",
            "G,1,1,1000,0,0,L,${trunkFirst.id},extra",
            "G",
        )
        val text = (listOf("V,1") + noise + goodEntries + noise).joinToString(";")
        val parsed = GameStats.parse(text)
        assertEquals(good, parsed)
        assertEquals(0, parsed.totals(BoardSize.MEDIUM).played)
        assertEquals(0, parsed.totals(BoardSize.LARGE).played)
    }

    @Test
    fun parseNeverThrows() {
        val samples = listOf(
            "V,1;;;;", "V,1;T", "V,1;T,F", "V,1;G,1", "V,1;\n\n",
            "V,1;T,F,SMALL,99999999999999999999,1,1,1,1,1",
            "V,1;G,1,1,1000,0,0,F,SMALL,99999999999999999999,3,1",
            "V,1;💥,💥;G,,,,,,,", "V,1;T,C,TRUNK,-1,-1,-1,-1,-1,-1",
            ";", ",", "V", "V,1,2", "\u0000",
            "V,1;T,F,SMALL,0,0,-,0,0,0", "V,1;G,1,1,1000,0,0,F,SMALL,5,3,0",
        )
        for (text in samples) {
            val parsed = GameStats.parse(text)
            assertTrue(text, parsed.history.isEmpty())
            assertTrue(text, parsed.isEmpty)
        }
    }

    @Test
    fun missingTotalsAreCountedFromTheHistory() {
        val text = listOf(
            "V,1",
            "G,1,1,3000,0,0,F,MEDIUM,11,4,1",
            "G,2,0,1000,1,0,F,MEDIUM,12,0,0",
            "G,3,1,2000,0,1,F,MEDIUM,13,4,1",
        ).joinToString(";")
        val medium = GameStats.parse(text).totals(BoardSize.MEDIUM)
        assertEquals(3, medium.played)
        assertEquals(2, medium.wins)
        assertEquals(2000L, medium.bestTimeMillis)
        assertEquals(2500L, medium.averageWinMillis)
        assertEquals(1, medium.currentStreak)
        assertEquals(1, medium.bestStreak)
    }

    @Test
    fun storedTotalsWinOverTheHistory() {
        val text = "V,1;T,F,SMALL,40,30,900,60000,5,9;G,1,0,1000,0,0,F,SMALL,5,3,1"
        val parsed = GameStats.parse(text)
        val small = parsed.totals(BoardSize.SMALL)
        assertEquals(40, small.played)
        assertEquals(30, small.wins)
        assertEquals(900L, small.bestTimeMillis)
        assertEquals(2000L, small.averageWinMillis)
        assertEquals(1, parsed.history.size)
        val after = parsed.add(free(BoardSize.SMALL, won = true, time = 800L)).totals(BoardSize.SMALL)
        assertEquals(41, after.played)
        assertEquals(800L, after.bestTimeMillis)
        assertEquals(6, after.currentStreak)
        assertEquals(9, after.bestStreak)
    }

    @Test
    fun winTimeSumSaturatesInsteadOfOverflowing() {
        val stats = GameStats.EMPTY
            .add(free(BoardSize.SMALL, won = true, time = Long.MAX_VALUE))
            .add(free(BoardSize.SMALL, won = true, time = Long.MAX_VALUE))
            .add(free(BoardSize.SMALL, won = true, time = 1L))
        val small = stats.totals(BoardSize.SMALL)
        assertEquals(Long.MAX_VALUE, small.winTimeSumMillis)
        assertEquals(1L, small.bestTimeMillis)
        assertTrue("average ${small.averageWinMillis}", small.averageWinMillis!! >= 0L)
        assertEquals("saturated totals survive a round trip", stats, GameStats.parse(stats.serialize()))
    }

    @Test
    fun countersSaturateAtIntMax() {
        val max = Int.MAX_VALUE
        val full = StatsTotals(max, max, 1L, Long.MAX_VALUE, max, max)
        val won = full.add(won = true, timeMillis = 5L)
        assertEquals(full, won)
        val lost = StatsTotals(max, max - 1, 1L, 5L, max - 1, max - 1).add(won = false, timeMillis = 5L)
        assertEquals(max, lost.played)
        assertEquals(0, lost.currentStreak)
        assertTrue(lost.isConsistent)
        assertEquals(0L, StatsTotals.EMPTY.add(won = true, timeMillis = -3L).winTimeSumMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aFreeGameWithRoomsButNoWallsIsRejected() {
        GameMode.Free(BoardSize.SMALL, 1L, rooms = 3, walls = false)
    }

    @Test
    fun parseSkipsAFreeGameWithRoomsButNoWalls() {
        val text = "V,1;G,1,1,1000,0,0,F,SMALL,5,3,0;G,2,0,1000,0,0,F,SMALL,6,0,0"
        val parsed = GameStats.parse(text)
        assertEquals(1, parsed.history.size)
        assertEquals(GameMode.Free(BoardSize.SMALL, 6L, 0, false), parsed.history.single().mode)
        assertEquals(1, parsed.totals(BoardSize.SMALL).played)
    }

    @Test
    fun totalsOfNoGamesAreDropped() {
        assertSame(GameStats.EMPTY, GameStats.parse("V,1;T,F,SMALL,0,0,-,0,0,0"))
        val withGame = GameStats.parse("V,1;T,F,SMALL,0,0,-,0,0,0;G,1,1,2000,0,0,F,SMALL,5,${BoardSize.SMALL.rooms},1")
        assertFalse(withGame.isEmpty)
        val small = withGame.totals(BoardSize.SMALL)
        assertEquals("totals counted from the history instead", 1, small.played)
        assertEquals(2000L, small.bestTimeMillis)
    }

    @Test
    fun parseKeepsOnlyTheLastHistoryEntries() {
        val text = "V,1;" + (0 until GameStats.MAX_HISTORY + 10).joinToString(";") { "G,$it,1,1000,0,0,L,${trunkFirst.id}" }
        val parsed = GameStats.parse(text)
        assertEquals(GameStats.MAX_HISTORY, parsed.history.size)
        assertEquals(10L, parsed.history.first().finishedAt)
        // Without stored totals they are counted from every parsed game, not only the kept ones.
        assertEquals(GameStats.MAX_HISTORY + 10, parsed.totals(Branch.TRUNK).played)
    }
}
