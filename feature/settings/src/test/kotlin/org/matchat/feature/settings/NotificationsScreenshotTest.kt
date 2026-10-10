package org.matchat.feature.settings

import android.view.LayoutInflater
import android.view.View
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import org.matchat.feature.settings.databinding.FragmentNotificationsBinding

/**
 * Screenshot coverage for S26 Notifications at the reference viewport, after
 * adding the "Hide message on cover" row: 240x320 mdpi, normal and largest
 * font scale, with the new toggle both off and on. Rows are filled the same
 * way NotificationsFragment.render() fills them. A diff is a review
 * conversation; an unreviewed diff blocks.
 */
class NotificationsScreenshotTest {

    private val config = DeviceConfig(
        screenWidth = 240,
        screenHeight = 320,
        density = Density.MEDIUM,
        orientation = ScreenOrientation.PORTRAIT,
    )

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = config)

    private fun screen(coverHidden: Boolean): View {
        val ctx = paparazzi.context
        val b = FragmentNotificationsBinding.inflate(LayoutInflater.from(ctx))
        b.notificationsEnabled.text =
            ctx.getString(R.string.theme_row_selected_format, ctx.getString(R.string.notifications_enabled))
        b.notificationsSoundSub.text = ctx.getString(R.string.notifications_sound_default)
        val coverLabel = ctx.getString(R.string.notifications_cover_hidden)
        b.notificationsCoverHidden.text =
            if (coverHidden) ctx.getString(R.string.theme_row_selected_format, coverLabel) else coverLabel
        return b.root
    }

    @Test
    fun notifications_coverShown() {
        paparazzi.snapshot(screen(coverHidden = false))
    }

    @Test
    fun notifications_coverHidden() {
        paparazzi.snapshot(screen(coverHidden = true))
    }

    @Test
    fun notifications_coverHidden_largestFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = config.copy(fontScale = 1.5f))
        paparazzi.snapshot(screen(coverHidden = true))
    }
}
