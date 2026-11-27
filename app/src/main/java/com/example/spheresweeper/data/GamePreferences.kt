package com.example.spheresweeper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.spheresweeper.logic.BoardSize

/** Settings and best times stored locally in SharedPreferences. */
class GamePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var flagByLongPress: Boolean
        get() = prefs.getBoolean(KEY_FLAG_BY_LONG_PRESS, false)
        set(value) = prefs.edit { putBoolean(KEY_FLAG_BY_LONG_PRESS, value) }

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit { putBoolean(KEY_HAPTICS, value) }

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
        const val NO_TIME = -1L
    }
}
