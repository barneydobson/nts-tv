package io.github.barneydobson.ntstv

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** User settings, saved on the device. Shared by the UI and the playback service. */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Index into [VOLUME_DB]. */
    var volumeStep: Int
        get() = prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME_STEP).coerceIn(VOLUME_DB.indices)
        set(value) = prefs.edit { putInt(KEY_VOLUME, value.coerceIn(VOLUME_DB.indices)) }

    val volumeDb: Int get() = VOLUME_DB[volumeStep]

    /** Index into [DARK_AFTER_MINUTES]. */
    var darkAfterStep: Int
        get() = prefs.getInt(KEY_DARK_AFTER, DEFAULT_DARK_AFTER_STEP).coerceIn(DARK_AFTER_MINUTES.indices)
        set(value) = prefs.edit { putInt(KEY_DARK_AFTER, value.coerceIn(DARK_AFTER_MINUTES.indices)) }

    /** Minutes of no button presses (while playing) before the screen goes dark; 0 = never. */
    val darkAfterMinutes: Int get() = DARK_AFTER_MINUTES[darkAfterStep]

    fun onChange(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun removeOnChange(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val KEY_VOLUME = "volume_step"
        const val KEY_DARK_AFTER = "dark_after_step"

        /** Volume relative to the stream: below 0 turns it down, above 0 boosts it. */
        val VOLUME_DB = intArrayOf(-6, -3, 0, 3, 6, 9, 12)
        const val DEFAULT_VOLUME_STEP = 2

        val DARK_AFTER_MINUTES = intArrayOf(0, 2, 5, 10, 30)
        const val DEFAULT_DARK_AFTER_STEP = 2
    }
}
