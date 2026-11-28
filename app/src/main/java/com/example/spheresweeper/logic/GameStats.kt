package com.example.spheresweeper.logic

/** Which games the statistics add up together: free games of one [BoardSize] or levels of one campaign [Branch]. */
sealed class StatsKey {
    data class FreeSize(val size: BoardSize) : StatsKey()

    /** Levels of [branch]; [Branch.TRUNK] counts as a branch of its own. */
    data class CampaignBranch(val branch: Branch) : StatsKey()
}

/** What a finished game was played on. */
sealed class GameMode {
    /** Statistics row the game counts in. */
    abstract val key: StatsKey

    /**
     * A free game of [size]. [seed] is the seed the board was built from, [rooms] the room count the
     * board was built with (0 for an open board) and [walls] whether walls were on. As in
     * [FreeGameSpec], a board without walls has no rooms.
     */
    data class Free(val size: BoardSize, val seed: Long, val rooms: Int, val walls: Boolean) : GameMode() {
        init {
            require(rooms >= 0) { "negative room count $rooms" }
            require(walls || rooms == 0) { "a board without walls has no rooms, got $rooms" }
        }

        override val key: StatsKey get() = StatsKey.FreeSize(size)
    }

    /** A campaign level, by its [LevelSpec.id]; the id must name a level of [Campaign]. */
    data class Level(val levelId: String) : GameMode() {
        val level: LevelSpec = requireNotNull(Campaign.byId(levelId)) { "unknown level id $levelId" }

        override val key: StatsKey get() = StatsKey.CampaignBranch(level.branch)
    }
}

/**
 * One finished game, won or lost; an abandoned game is not recorded. [finishedAt] is the wall-clock
 * time of the end in epoch milliseconds, [timeMillis] the time the game took, [livesLost] the mines
 * that went off ([MinesweeperBoard.detonatedCount]) and [hintsUsed] the hints the player took.
 */
data class GameRecord(
    val finishedAt: Long,
    val mode: GameMode,
    val won: Boolean,
    val timeMillis: Long,
    val livesLost: Int,
    val hintsUsed: Int,
) {
    init {
        require(finishedAt >= 0L) { "negative finish time $finishedAt" }
        require(timeMillis >= 0L) { "negative game time $timeMillis" }
        require(livesLost >= 0) { "negative lives lost $livesLost" }
        require(hintsUsed >= 0) { "negative hints used $hintsUsed" }
    }
}

/**
 * Running totals of one [StatsKey] over every game ever recorded for it.
 *
 * [played], [wins], [bestTimeMillis], [winTimeSumMillis], [currentStreak] and [bestStreak] are stored;
 * [winRate] and [averageWinMillis] are computed from them. [currentStreak] counts the wins since the
 * last loss of this key (games of other keys do not break it), [bestStreak] the longest such run.
 * [add] saturates: the counters stop at [Int.MAX_VALUE] and [winTimeSumMillis] at [Long.MAX_VALUE],
 * so no total turns negative.
 */
data class StatsTotals(
    val played: Int = 0,
    val wins: Int = 0,
    /** Shortest won game in milliseconds, or null before the first win. */
    val bestTimeMillis: Long? = null,
    /** Sum of the times of all won games, for [averageWinMillis]. */
    val winTimeSumMillis: Long = 0L,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
) {
    /** Share of won games in 0..1, or null before the first game. */
    val winRate: Double? get() = if (played > 0) wins.toDouble() / played else null

    /** Mean time of the won games in milliseconds, or null before the first win. */
    val averageWinMillis: Long? get() = if (wins > 0) winTimeSumMillis / wins else null

    /** Totals after one more game; a negative [timeMillis] counts as 0. */
    fun add(won: Boolean, timeMillis: Long): StatsTotals {
        if (!won) return copy(played = increment(played), currentStreak = 0)
        val time = timeMillis.coerceAtLeast(0L)
        val streak = increment(currentStreak)
        return StatsTotals(
            played = increment(played),
            wins = increment(wins),
            bestTimeMillis = bestTimeMillis?.let { minOf(it, time) } ?: time,
            winTimeSumMillis = if (winTimeSumMillis > Long.MAX_VALUE - time) Long.MAX_VALUE else winTimeSumMillis + time,
            currentStreak = streak,
            bestStreak = maxOf(bestStreak, streak),
        )
    }

    /** True when some sequence of games gives these totals; [GameStats.parse] skips the others. */
    internal val isConsistent: Boolean
        get() = played >= 0 && wins in 0..played && winTimeSumMillis >= 0L &&
            (wins == 0) == (bestTimeMillis == null) && (bestTimeMillis ?: 0L) >= 0L &&
            bestStreak in 0..wins && currentStreak in 0..bestStreak

    companion object {
        val EMPTY = StatsTotals()

        private fun increment(count: Int): Int = if (count == Int.MAX_VALUE) count else count + 1
    }
}

/**
 * The player's statistics. Immutable; [add] returns a new value.
 *
 * - [history]: the last [MAX_HISTORY] finished games, oldest first, for views of single games.
 * - totals per [StatsKey] ([totals]): every aggregate the statistics show. They are kept apart from
 *   [history] and only grow, so games played, wins, best time, average winning time and both streaks
 *   stay right after old games drop out of [history]. No aggregate is computed from [history].
 *
 * [serialize] and [parse] turn the statistics into one String and back, for simple key-value storage.
 * Entries are separated by ";" and fields by ",". The first entry is the format version, `V,1`. Then:
 * - totals: `T,F,<BoardSize>` or `T,C,<Branch>`, followed by played, wins, best time ([NO_VALUE] before
 *   the first win), sum of winning times, current streak and best streak;
 * - games, oldest first: `G,<finishedAt>,<won 1/0>,<timeMillis>,<livesLost>,<hintsUsed>`, followed by
 *   `F,<BoardSize>,<seed>,<rooms>,<walls 1/0>` for a free game or `L,<level id>` for a campaign level.
 */
class GameStats private constructor(
    val history: List<GameRecord>,
    private val totalsByKey: Map<StatsKey, StatsTotals>,
) {
    /** True before the first recorded game. */
    val isEmpty: Boolean get() = totalsByKey.values.none { it.played > 0 }

    /** Totals of [key]; [StatsTotals.EMPTY] when no game of it was recorded. */
    fun totals(key: StatsKey): StatsTotals = totalsByKey[key] ?: StatsTotals.EMPTY

    fun totals(size: BoardSize): StatsTotals = totals(StatsKey.FreeSize(size))

    fun totals(branch: Branch): StatsTotals = totals(StatsKey.CampaignBranch(branch))

    /** Statistics with [game] added at the end: its key's totals grow, and the oldest game beyond [MAX_HISTORY] drops out. */
    fun add(game: GameRecord): GameStats {
        val key = game.mode.key
        val updated = HashMap(totalsByKey)
        updated[key] = totals(key).add(game.won, game.timeMillis)
        val kept = if (history.size >= MAX_HISTORY) history.subList(history.size - MAX_HISTORY + 1, history.size) else history
        return GameStats(kept + game, updated)
    }

    fun serialize(): String {
        val entries = ArrayList<String>(1 + totalsByKey.size + history.size)
        entries.add(fields(VERSION_TAG, VERSION))
        for ((key, t) in totalsByKey.entries.sortedBy { keyOrder(it.key) }) {
            entries.add(
                fields(
                    TOTALS_TAG, keyKind(key), keyName(key), t.played, t.wins, t.bestTimeMillis ?: NO_VALUE,
                    t.winTimeSumMillis, t.currentStreak, t.bestStreak,
                ),
            )
        }
        for (game in history) {
            val head = listOf(GAME_TAG, game.finishedAt, flag(game.won), game.timeMillis, game.livesLost, game.hintsUsed)
            val tail = when (val mode = game.mode) {
                is GameMode.Free -> listOf(FREE_TAG, mode.size.name, mode.seed, mode.rooms, flag(mode.walls))
                is GameMode.Level -> listOf(LEVEL_TAG, mode.levelId)
            }
            entries.add((head + tail).joinToString(FIELD_SEPARATOR))
        }
        return entries.joinToString(ENTRY_SEPARATOR)
    }

    override fun equals(other: Any?): Boolean =
        other is GameStats && other.history == history && other.totalsByKey == totalsByKey

    override fun hashCode(): Int = 31 * history.hashCode() + totalsByKey.hashCode()

    override fun toString(): String = "GameStats(${serialize()})"

    companion object {
        /** Finished games kept in [history]; older ones drop out, their totals stay. */
        const val MAX_HISTORY = 500

        /** Format version [serialize] writes and [parse] reads. */
        const val VERSION = 1

        /** Field written for a missing best time. */
        const val NO_VALUE = "-"

        private const val ENTRY_SEPARATOR = ";"
        private const val FIELD_SEPARATOR = ","
        private const val VERSION_TAG = "V"
        private const val TOTALS_TAG = "T"
        private const val GAME_TAG = "G"
        private const val FREE_TAG = "F"
        private const val CAMPAIGN_TAG = "C"
        private const val LEVEL_TAG = "L"

        val EMPTY = GameStats(emptyList(), emptyMap())

        /**
         * Reads the output of [serialize]. Never throws.
         * - Null or blank text, or text whose first entry is not the version marker of [VERSION],
         *   gives [EMPTY]: a newer or unknown format is not guessed at.
         * - Entries with an unknown tag, board size, branch or level id, a wrong field count, a field
         *   that is not a number or out of its type's range, a negative value, a free game with rooms
         *   but no walls, totals of no games, or totals no sequence of games gives are skipped.
         * - When a key has totals twice, the last ones count.
         * - A key with games in the history but no usable totals gets totals counted from those games.
         * - Beyond [MAX_HISTORY] games only the last ones are kept.
         */
        fun parse(text: String?): GameStats {
            if (text.isNullOrBlank()) return EMPTY
            val entries = text.split(ENTRY_SEPARATOR).map { entry -> entry.split(FIELD_SEPARATOR).map { it.trim() } }
            if (entries.first() != listOf(VERSION_TAG, VERSION.toString())) return EMPTY
            val totals = HashMap<StatsKey, StatsTotals>()
            val history = ArrayList<GameRecord>()
            for (fields in entries.drop(1)) {
                when (fields[0]) {
                    TOTALS_TAG -> parseTotals(fields)?.let { (key, t) -> totals[key] = t }
                    GAME_TAG -> parseGame(fields)?.let { history.add(it) }
                }
            }
            val keysWithoutTotals = history.map { it.mode.key }.filter { it !in totals }.toSet()
            for (key in keysWithoutTotals) {
                totals[key] = history.filter { it.mode.key == key }
                    .fold(StatsTotals.EMPTY) { t, game -> t.add(game.won, game.timeMillis) }
            }
            if (totals.isEmpty() && history.isEmpty()) return EMPTY
            val kept = if (history.size > MAX_HISTORY) history.subList(history.size - MAX_HISTORY, history.size).toList() else history
            return GameStats(kept, totals)
        }

        private fun parseTotals(fields: List<String>): Pair<StatsKey, StatsTotals>? {
            if (fields.size != 9) return null
            val key = parseKey(fields[1], fields[2]) ?: return null
            val played = fields[3].toIntOrNull() ?: return null
            val wins = fields[4].toIntOrNull() ?: return null
            val best = if (fields[5] == NO_VALUE) null else fields[5].toLongOrNull() ?: return null
            val sum = fields[6].toLongOrNull() ?: return null
            val current = fields[7].toIntOrNull() ?: return null
            val bestStreak = fields[8].toIntOrNull() ?: return null
            val totals = StatsTotals(played, wins, best, sum, current, bestStreak)
            return if (totals.played > 0 && totals.isConsistent) key to totals else null
        }

        private fun parseKey(kind: String, name: String): StatsKey? = when (kind) {
            FREE_TAG -> sizeNamed(name)?.let { StatsKey.FreeSize(it) }
            CAMPAIGN_TAG -> Branch.values().firstOrNull { it.name == name }?.let { StatsKey.CampaignBranch(it) }
            else -> null
        }

        private fun parseGame(fields: List<String>): GameRecord? {
            if (fields.size < 8) return null
            val finishedAt = fields[1].toLongOrNull() ?: return null
            val won = parseFlag(fields[2]) ?: return null
            val time = fields[3].toLongOrNull() ?: return null
            val lives = fields[4].toIntOrNull() ?: return null
            val hints = fields[5].toIntOrNull() ?: return null
            if (finishedAt < 0L || time < 0L || lives < 0 || hints < 0) return null
            val mode = when (fields[6]) {
                FREE_TAG -> {
                    if (fields.size != 11) return null
                    val size = sizeNamed(fields[7]) ?: return null
                    val seed = fields[8].toLongOrNull() ?: return null
                    val rooms = fields[9].toIntOrNull() ?: return null
                    val walls = parseFlag(fields[10]) ?: return null
                    if (rooms < 0 || (!walls && rooms > 0)) return null
                    GameMode.Free(size, seed, rooms, walls)
                }
                LEVEL_TAG -> {
                    if (fields.size != 8 || Campaign.byId(fields[7]) == null) return null
                    GameMode.Level(fields[7])
                }
                else -> return null
            }
            return GameRecord(finishedAt, mode, won, time, lives, hints)
        }

        private fun sizeNamed(name: String): BoardSize? = BoardSize.values().firstOrNull { it.name == name }

        private fun fields(vararg values: Any): String = values.joinToString(FIELD_SEPARATOR)

        private fun flag(value: Boolean): String = if (value) "1" else "0"

        private fun parseFlag(text: String): Boolean? = when (text) {
            "1" -> true
            "0" -> false
            else -> null
        }

        private fun keyKind(key: StatsKey): String = when (key) {
            is StatsKey.FreeSize -> FREE_TAG
            is StatsKey.CampaignBranch -> CAMPAIGN_TAG
        }

        private fun keyName(key: StatsKey): String = when (key) {
            is StatsKey.FreeSize -> key.size.name
            is StatsKey.CampaignBranch -> key.branch.name
        }

        /** Free sizes first, then campaign branches, each in enum order, so [serialize] is stable. */
        private fun keyOrder(key: StatsKey): Int = when (key) {
            is StatsKey.FreeSize -> key.size.ordinal
            is StatsKey.CampaignBranch -> BoardSize.values().size + key.branch.ordinal
        }
    }
}
