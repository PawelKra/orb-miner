package com.example.spheresweeper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.spheresweeper.logic.BoardSize
import com.example.spheresweeper.logic.GameStats
import com.example.spheresweeper.logic.MatchMode

/** Settings, best times and campaign progress stored locally in SharedPreferences. */
class GamePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var flagByLongPress: Boolean
        get() = prefs.getBoolean(KEY_FLAG_BY_LONG_PRESS, false)
        set(value) = prefs.edit { putBoolean(KEY_FLAG_BY_LONG_PRESS, value) }

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit { putBoolean(KEY_HAPTICS, value) }

    /** Identifier of the chosen theme, or null before the first choice. */
    var themeId: String?
        get() = prefs.getString(KEY_THEME, null)
        set(value) = prefs.edit { putString(KEY_THEME, value) }

    /** Campaign progress in the text form of [com.example.spheresweeper.logic.CampaignProgress.serialize]. */
    var campaignProgress: String?
        get() = prefs.getString(KEY_CAMPAIGN_PROGRESS, null)
        set(value) = prefs.edit { putString(KEY_CAMPAIGN_PROGRESS, value) }

    /** Whether free games are drawn with the walls and rooms of their board size. */
    var randomWalls: Boolean
        get() = prefs.getBoolean(KEY_RANDOM_WALLS, true)
        set(value) = prefs.edit { putBoolean(KEY_RANDOM_WALLS, value) }

    /**
     * Board size last chosen for a two-player match, stored by its name, or null before the first choice
     * and for a name this version does not know.
     */
    var matchSize: BoardSize?
        get() {
            val name = prefs.getString(KEY_MATCH_SIZE, null) ?: return null
            return BoardSize.values().firstOrNull { it.name == name }
        }
        set(value) = prefs.edit { putString(KEY_MATCH_SIZE, value?.name) }

    /**
     * Two-player mode last chosen, stored by its name, or null before the first choice and for a name
     * this version does not know.
     */
    var matchMode: MatchMode?
        get() {
            val name = prefs.getString(KEY_MATCH_MODE, null) ?: return null
            return MatchMode.values().firstOrNull { it.name == name }
        }
        set(value) = prefs.edit { putString(KEY_MATCH_MODE, value?.name) }

    /** Moves per turn last chosen for a two-player match, or null before the first choice. */
    var matchMovesPerTurn: Int?
        get() = if (prefs.contains(KEY_MATCH_MOVES)) prefs.getInt(KEY_MATCH_MOVES, 0) else null
        set(value) = prefs.edit { if (value == null) remove(KEY_MATCH_MOVES) else putInt(KEY_MATCH_MOVES, value) }

    /** Statistics of finished games, stored in the text form of [GameStats.serialize]. */
    var gameStats: GameStats
        get() = GameStats.parse(prefs.getString(KEY_GAME_STATS, null))
        set(value) = prefs.edit { putString(KEY_GAME_STATS, value.serialize()) }

    /** Best time in milliseconds for [size], or null when the board was never won. */
    fun bestTimeMillis(size: BoardSize): Long? {
        val value = prefs.getLong(bestKey(size), NO_TIME)
        return if (value > 0L) value else null
    }

    fun saveBestTime(size: BoardSize, millis: Long) {
        prefs.edit { putLong(bestKey(size), millis) }
    }

    /** All stored best times, keyed by board size. */
    fun loadBestTimes(): Map<BoardSize, Long> {
        val result = HashMap<BoardSize, Long>()
        for (size in BoardSize.values()) {
            val best = bestTimeMillis(size)
            if (best != null) result[size] = best
        }
        return result
    }

    private fun bestKey(size: BoardSize): String = KEY_BEST_PREFIX + size.name

    private companion object {
        const val PREFS_NAME = "sphere_sweeper"
        const val KEY_FLAG_BY_LONG_PRESS = "flag_by_long_press"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_BEST_PREFIX = "best_time_"
        const val KEY_CAMPAIGN_PROGRESS = "campaign_progress"
        const val KEY_THEME = "theme"
        const val KEY_MATCH_SIZE = "match_size"
        const val KEY_MATCH_MOVES = "match_moves_per_turn"
        const val KEY_MATCH_MODE = "match_mode"
        const val KEY_RANDOM_WALLS = "random_walls"
        const val KEY_GAME_STATS = "game_stats"
        const val NO_TIME = -1L
    }
}
