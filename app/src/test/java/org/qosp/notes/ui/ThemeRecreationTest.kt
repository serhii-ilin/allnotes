package org.qosp.notes.ui

import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qosp.notes.preferences.ThemeSnapshot

class ThemeRecreationTest {

    private val lightTheme = ThemeSnapshot(
        colorSchemeStyle = 1,
        themeMode = AppCompatDelegate.MODE_NIGHT_NO,
        darkThemeStyle = null,
    )

    @Test
    fun `does not recreate when appcompat handles the night change`() {
        val darkTheme = lightTheme.copy(themeMode = AppCompatDelegate.MODE_NIGHT_YES)

        assertFalse(
            shouldRecreateForThemeChange(
                newTheme = darkTheme,
                previousTheme = lightTheme,
                nightModeWasSet = true,
                currentNightMode = Configuration.UI_MODE_NIGHT_NO,
                systemNightMode = Configuration.UI_MODE_NIGHT_NO,
            ),
        )
    }

    @Test
    fun `recreates when the new mode resolves to the current night state`() {
        val darkTheme = lightTheme.copy(themeMode = AppCompatDelegate.MODE_NIGHT_YES)

        assertTrue(
            shouldRecreateForThemeChange(
                newTheme = darkTheme,
                previousTheme = lightTheme,
                nightModeWasSet = true,
                currentNightMode = Configuration.UI_MODE_NIGHT_YES,
                systemNightMode = Configuration.UI_MODE_NIGHT_YES,
            ),
        )
    }

    @Test
    fun `recreates when the color scheme changed`() {
        val recolored = lightTheme.copy(colorSchemeStyle = 2)

        assertTrue(
            shouldRecreateForThemeChange(
                newTheme = recolored,
                previousTheme = lightTheme,
                nightModeWasSet = true,
                currentNightMode = Configuration.UI_MODE_NIGHT_NO,
                systemNightMode = Configuration.UI_MODE_NIGHT_NO,
            ),
        )
    }

    @Test
    fun `recreates when setDefaultNightMode was not called`() {
        assertTrue(
            shouldRecreateForThemeChange(
                newTheme = lightTheme,
                previousTheme = lightTheme,
                nightModeWasSet = false,
                currentNightMode = Configuration.UI_MODE_NIGHT_NO,
                systemNightMode = Configuration.UI_MODE_NIGHT_NO,
            ),
        )
    }

    @Test
    fun `system mode uses the system night state`() {
        val systemTheme = lightTheme.copy(themeMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        assertTrue(
            shouldRecreateForThemeChange(
                newTheme = systemTheme,
                previousTheme = lightTheme,
                nightModeWasSet = true,
                currentNightMode = Configuration.UI_MODE_NIGHT_YES,
                systemNightMode = Configuration.UI_MODE_NIGHT_YES,
            ),
        )
    }
}
