package com.example.spheresweeper.logic

/** Outcome of [CampaignProgress.record]. */
class RecordResult(
    /** Progress after the game; the same object as before when nothing improved. */
    val progress: CampaignProgress,
    /** Stars this game earned, 0 for a loss. */
    val earned: Int,
    /** Best stars before this game. */
    val previous: Int,
) {
    /** Best stars after this game. */
    val best: Int get() = maxOf(previous, earned)

    val improved: Boolean get() = earned > previous

    /** True when this game completed the level for the first time. */
    val firstCompletion: Boolean get() = previous == 0 && earned > 0
}

/**
 * The player's campaign progress: the best stars per level. Immutable; [record] returns a new value.
 *
 * A level is completed once it has at least one star. Unlocking:
 * - the first trunk level is always open, and every level opens when the one before it in its
 *   branch is completed;
 * - the walls, far and lines branches open once the whole trunk is completed;
 * - the mixed branch opens once half of each of those three branches (10 of 20) is completed;
 * - a completed level stays open even if the rules above would close it.
 *
 * [serialize] and [parse] turn progress into one String and back, for simple key-value storage.
 */
class CampaignProgress private constructor(private val best: Map<String, Int>) {

    fun stars(level: LevelSpec): Int = best[level.id] ?: 0

    fun isCompleted(level: LevelSpec): Boolean = stars(level) > 0

    fun completedCount(branch: Branch): Int = Campaign.levels(branch).count { isCompleted(it) }

    val totalStars: Int get() = best.values.sum()

    /** True when the first level of [branch] is open. */
    fun isBranchOpen(branch: Branch): Boolean = when (branch) {
        Branch.TRUNK -> true
        Branch.WALLS, Branch.FAR, Branch.LINES ->
            completedCount(Branch.TRUNK) == Campaign.levels(Branch.TRUNK).size
        Branch.MIXED -> MIXED_FEEDERS.all { completedCount(it) >= mixedUnlockCount(it) }
    }

    fun isUnlocked(level: LevelSpec): Boolean {
        if (isCompleted(level)) return true
        if (!isBranchOpen(level.branch)) return false
        if (level.index == 0) return true
        return isCompleted(Campaign.levels(level.branch)[level.index - 1])
    }

    /**
     * Records a finished game on [level]. [livesLost] is the number of mines that went off without
     * ending the game ([MinesweeperBoard.detonatedCount]), [hintsUsed] the hints the player took.
     * The best result is kept: a worse replay leaves the stored stars as they are.
     */
    fun record(level: LevelSpec, won: Boolean, livesLost: Int, hintsUsed: Int): RecordResult {
        val earned = starsFor(won, livesLost, hintsUsed)
        val previous = stars(level)
        if (earned <= previous) return RecordResult(this, earned, previous)
        val updated = HashMap(best)
        updated[level.id] = earned
        return RecordResult(CampaignProgress(updated), earned, previous)
    }

    /** "id:stars" entries in campaign order, joined by commas; levels without stars are left out. */
    fun serialize(): String =
        Campaign.all.filter { isCompleted(it) }.joinToString(ENTRY_SEPARATOR) { "${it.id}$VALUE_SEPARATOR${stars(it)}" }

    override fun equals(other: Any?): Boolean = other is CampaignProgress && other.best == best

    override fun hashCode(): Int = best.hashCode()

    override fun toString(): String = "CampaignProgress(${serialize()})"

    companion object {
        const val MAX_STARS = 3

        private const val ENTRY_SEPARATOR = ","
        private const val VALUE_SEPARATOR = ":"

        /** Branches whose progress opens [Branch.MIXED]. */
        val MIXED_FEEDERS: List<Branch> = listOf(Branch.WALLS, Branch.FAR, Branch.LINES)

        val EMPTY = CampaignProgress(emptyMap())

        /** Completed levels of [branch] needed to open [Branch.MIXED]: half of the branch, rounded up. */
        fun mixedUnlockCount(branch: Branch): Int = (Campaign.levels(branch).size + 1) / 2

        /**
         * Stars for one game: none for a loss; for a win one star, one more without losing a life
         * and one more without using a hint.
         */
        fun starsFor(won: Boolean, livesLost: Int, hintsUsed: Int): Int {
            if (!won) return 0
            var stars = 1
            if (livesLost <= 0) stars++
            if (hintsUsed <= 0) stars++
            return stars
        }

        /**
         * Reads the output of [serialize]. Entries with an unknown level id, a value that is not a
         * number in 1..[MAX_STARS], or a broken shape are skipped; a repeated id keeps its best value.
         * Null or empty text gives [EMPTY].
         */
        fun parse(text: String?): CampaignProgress {
            if (text.isNullOrBlank()) return EMPTY
            val best = HashMap<String, Int>()
            for (entry in text.split(ENTRY_SEPARATOR)) {
                val parts = entry.split(VALUE_SEPARATOR)
                if (parts.size != 2) continue
                val id = parts[0].trim()
                val stars = parts[1].trim().toIntOrNull() ?: continue
                if (stars !in 1..MAX_STARS || Campaign.byId(id) == null) continue
                best[id] = maxOf(best[id] ?: 0, stars)
            }
            return CampaignProgress(best)
        }
    }
}
