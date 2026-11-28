package com.example.spheresweeper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.spheresweeper.logic.BoardFeature
import com.example.spheresweeper.logic.BoardFeatures
import com.example.spheresweeper.logic.BoardSize

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

    /** Board features whose introduction card the player has closed, in the text form of [BoardFeatures.serialize]. */
    var seenFeatures: Set<BoardFeature>
        get() = BoardFeatures.parse(prefs.getString(KEY_SEEN_FEATURES, null))
        set(value) = prefs.edit { putString(KEY_SEEN_FEATURES, BoardFeatures.serialize(value)) }

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
        const val KEY_SEEN_FEATURES = "seen_features"
        const val NO_TIME = -1L
    }
}
