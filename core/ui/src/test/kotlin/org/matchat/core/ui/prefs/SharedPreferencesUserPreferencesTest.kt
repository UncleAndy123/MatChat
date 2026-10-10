package org.matchat.core.ui.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Read-after-write and defaults for the real SharedPreferences-backed impl
 *  (AGENTS.md §6). The fake's round-trip is covered separately in
 *  :core:testing, since it doesn't touch Android APIs. */
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesUserPreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `defaults are light, rust, Normal, and unswapped`() {
        val prefs = SharedPreferencesUserPreferences(context)
        assertEquals(ThemeMode.LIGHT, prefs.themeMode.value)
        assertEquals(AccentColor.RUST, prefs.accentColor.value)
        assertEquals(TextSizePreference.NORMAL, prefs.textSize.value)
        assertEquals(false, prefs.softkeysSwapped.value)
        assertEquals(true, prefs.notificationsEnabled.value)
        assertEquals(null, prefs.notificationSoundUri.value)
        assertEquals(0, prefs.notificationChannelVersion.value)
        assertEquals(false, prefs.coverMessageHidden.value)
    }

    @Test
    fun `cover-message-hidden read-after-write, including a fresh instance`() = runTest {
        val prefs = SharedPreferencesUserPreferences(context)
        prefs.setCoverMessageHidden(true)
        assertEquals(true, prefs.coverMessageHidden.value)

        val reloaded = SharedPreferencesUserPreferences(context)
        assertEquals(true, reloaded.coverMessageHidden.value)
    }

    @Test
    fun `theme mode read-after-write, including a fresh instance`() = runTest {
        val prefs = SharedPreferencesUserPreferences(context)
        prefs.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, prefs.themeMode.value)

        val reloaded = SharedPreferencesUserPreferences(context)
        assertEquals(ThemeMode.DARK, reloaded.themeMode.value)
    }

    @Test
    fun `accent color read-after-write, including a fresh instance`() = runTest {
        val prefs = SharedPreferencesUserPreferences(context)
        prefs.setAccentColor(AccentColor.BLUE)
        assertEquals(AccentColor.BLUE, prefs.accentColor.value)

        val reloaded = SharedPreferencesUserPreferences(context)
        assertEquals(AccentColor.BLUE, reloaded.accentColor.value)
    }

    @Test
    fun `text size read-after-write, including a fresh instance`() = runTest {
        val prefs = SharedPreferencesUserPreferences(context)
        prefs.setTextSize(TextSizePreference.SMALL)
        assertEquals(TextSizePreference.SMALL, prefs.textSize.value)

        val reloaded = SharedPreferencesUserPreferences(context)
        assertEquals(TextSizePreference.SMALL, reloaded.textSize.value)
    }

    @Test
    fun `softkeys-swapped read-after-write, including a fresh instance`() = runTest {
        val prefs = SharedPreferencesUserPreferences(context)
        prefs.setSoftkeysSwapped(true)
        assertEquals(true, prefs.softkeysSwapped.value)

        val reloaded = SharedPreferencesUserPreferences(context)
        assertEquals(true, reloaded.softkeysSwapped.value)
    }

    @Test
    fun `toggling softkeys-swapped on still works, even though off is the default`() = runTest {
        val prefs = SharedPreferencesUserPreferences(context)
        prefs.setSoftkeysSwapped(true)
        assertEquals(true, prefs.softkeysSwapped.value)

        val reloaded = SharedPreferencesUserPreferences(context)
        assertEquals(true, reloaded.softkeysSwapped.value)
    }

    @Test
    fun `notifications-enabled read-after-write, including a fresh instance`() = runTest {
        val prefs = SharedPreferencesUserPreferences(context)
        prefs.setNotificationsEnabled(false)
        assertEquals(false, prefs.notificationsEnabled.value)

        val reloaded = SharedPreferencesUserPreferences(context)
        assertEquals(false, reloaded.notificationsEnabled.value)
    }

    @Test
    fun `notification sound uri read-after-write bumps the channel version, including a fresh instance`() = runTest {
        val prefs = SharedPreferencesUserPreferences(context)
        prefs.setNotificationSoundUri("content://media/custom")
        assertEquals("content://media/custom", prefs.notificationSoundUri.value)
        assertEquals(1, prefs.notificationChannelVersion.value)

        val reloaded = SharedPreferencesUserPreferences(context)
        assertEquals("content://media/custom", reloaded.notificationSoundUri.value)
        assertEquals(1, reloaded.notificationChannelVersion.value)
    }

    @Test
    fun `each sound change bumps the channel version again`() = runTest {
        val prefs = SharedPreferencesUserPreferences(context)
        prefs.setNotificationSoundUri("content://media/one")
        prefs.setNotificationSoundUri("content://media/two")
        assertEquals(2, prefs.notificationChannelVersion.value)
    }
}
