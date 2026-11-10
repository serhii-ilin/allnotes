package org.qosp.notes.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.preferences.ThemeCache
import org.qosp.notes.preferences.ThemeMode
import org.qosp.notes.preferences.ThemeSnapshot
import org.qosp.notes.preferences.toThemeSnapshot
import org.qosp.notes.ui.utils.Toaster
import org.qosp.notes.ui.utils.collect

open class BaseActivity : AppCompatActivity() {
    val preferenceRepository: PreferenceRepository by inject()
    private val themeCache: ThemeCache by inject()
    private val toaster: Toaster by inject()

    /** Theme that this activity instance already applied; used to avoid recreating for no reason. */
    private var appliedTheme: ThemeSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        applyInitialTheme()
        observeThemeChanges()

        // Observe toast messages with lifecycle awareness
        toaster.messages.collect(this) { (message, duration) ->
            Toast.makeText(this@BaseActivity, message, duration).show()
        }
    }

    private fun applyInitialTheme() {
        val cached = themeCache.read()

        // Style ids are not stable across app versions, so an old cache may not apply anymore.
        if (cached != null && tryApplyTheme(cached)) return

        applyTheme(readThemeFromDataStore())
    }

    private fun tryApplyTheme(snapshot: ThemeSnapshot): Boolean = try {
        applyTheme(snapshot)
        true
    } catch (_: Resources.NotFoundException) {
        false
    }

    /**
     * The cache is only empty on the first start after an install or upgrade, so paying for the
     * blocking DataStore read here is acceptable: every later start takes the fast path above.
     */
    private fun readThemeFromDataStore(): ThemeSnapshot = runBlocking {
        val theme = withContext(Dispatchers.IO) {
            preferenceRepository.getAll().first().toThemeSnapshot()
        }
        themeCache.write(theme)
        theme
    }

    private fun observeThemeChanges() {
        preferenceRepository.getAll().collect(this) { preferences ->
            val theme = preferences.toThemeSnapshot()
            themeCache.write(theme)

            if (theme != appliedTheme) {
                appliedTheme = theme
                recreate()
            }
        }
    }

    private fun applyTheme(snapshot: ThemeSnapshot) {
        theme.applyStyle(snapshot.colorSchemeStyle, true)

        if (snapshot.themeMode != AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.setDefaultNightMode(snapshot.themeMode)
        }

        val isAutoDark =
            snapshot.themeMode == ThemeMode.SYSTEM.mode && (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // Check which theme should be used (light, dark, black) and set navbar color accordingly
        // if dark
        if (snapshot.themeMode == ThemeMode.DARK.mode || isAutoDark) {
            // if black
            snapshot.darkThemeStyle?.let {
                theme.applyStyle(it, true)
            }
        }

        appliedTheme = snapshot
    }
}
