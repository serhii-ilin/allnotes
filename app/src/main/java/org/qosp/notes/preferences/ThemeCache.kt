package org.qosp.notes.preferences

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/** Theme values that must be applied before the activity draws its first frame. */
data class ThemeSnapshot(
    val colorSchemeStyle: Int,
    val themeMode: Int,
    val darkThemeStyle: Int?,
)

fun AppPreferences.toThemeSnapshot() = ThemeSnapshot(
    colorSchemeStyle = colorScheme.styleResource,
    themeMode = themeMode.mode,
    darkThemeStyle = darkThemeMode.styleResource,
)

/**
 * File-backed copy of the theme preferences.
 *
 * Activities need the theme synchronously in [android.app.Activity.onCreate], but the values live in
 * DataStore, whose first read is slow enough to be visible in the cold start. Reading this
 * [android.content.SharedPreferences] file instead costs a single file load, and the cache is kept
 * in sync by [org.qosp.notes.ui.BaseActivity] whenever the real preferences change.
 */
class ThemeCache(context: Context) {

    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Returns the cached theme, or null when nothing has been cached yet (first run or upgrade). */
    fun read(): ThemeSnapshot? {
        if (!preferences.contains(KEY_COLOR_SCHEME)) return null

        return ThemeSnapshot(
            colorSchemeStyle = preferences.getInt(KEY_COLOR_SCHEME, NO_STYLE),
            themeMode = preferences.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
            darkThemeStyle = preferences.getInt(KEY_DARK_THEME, NO_STYLE).takeIf { it != NO_STYLE },
        )
    }

    fun write(snapshot: ThemeSnapshot) {
        preferences.edit {
            putInt(KEY_COLOR_SCHEME, snapshot.colorSchemeStyle)
            putInt(KEY_THEME_MODE, snapshot.themeMode)
            putInt(KEY_DARK_THEME, snapshot.darkThemeStyle ?: NO_STYLE)
        }
    }

    companion object {
        private const val FILE_NAME = "theme_cache"
        private const val KEY_COLOR_SCHEME = "color_scheme"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DARK_THEME = "dark_theme_mode"

        /** [DarkThemeMode.STANDARD] has no style of its own. */
        private const val NO_STYLE = -1
    }
}
