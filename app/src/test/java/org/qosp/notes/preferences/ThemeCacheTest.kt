package org.qosp.notes.preferences

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.qosp.notes.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cache = ThemeCache(context)

    @Test
    fun emptyCache_returnsNull() {
        assertNull(cache.read())
    }

    @Test
    fun writeThenRead_returnsStoredValues() {
        val snapshot = ThemeSnapshot(
            colorSchemeStyle = R.style.Green,
            themeMode = AppCompatDelegate.MODE_NIGHT_YES,
            darkThemeStyle = R.style.DarkBlack,
        )

        cache.write(snapshot)

        assertEquals(snapshot, cache.read())
    }

    @Test
    fun writeThenRead_preservesMissingDarkThemeStyle() {
        val snapshot = ThemeSnapshot(
            colorSchemeStyle = R.style.Blue,
            themeMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            darkThemeStyle = null,
        )

        cache.write(snapshot)

        assertEquals(snapshot, cache.read())
    }

    @Test
    fun writeThenRead_survivesNewInstance() {
        cache.write(
            ThemeSnapshot(
                colorSchemeStyle = R.style.Red,
                themeMode = AppCompatDelegate.MODE_NIGHT_NO,
                darkThemeStyle = null,
            )
        )

        // A new instance reads the same file, which is what happens on every cold start.
        assertEquals(R.style.Red, ThemeCache(context).read()?.colorSchemeStyle)
    }

    @Test
    fun write_overwritesPreviousValues() {
        cache.write(ThemeSnapshot(R.style.Blue, AppCompatDelegate.MODE_NIGHT_NO, null))
        cache.write(ThemeSnapshot(R.style.Purple, AppCompatDelegate.MODE_NIGHT_YES, R.style.DarkBlack))

        assertEquals(
            ThemeSnapshot(R.style.Purple, AppCompatDelegate.MODE_NIGHT_YES, R.style.DarkBlack),
            cache.read(),
        )
    }
}
