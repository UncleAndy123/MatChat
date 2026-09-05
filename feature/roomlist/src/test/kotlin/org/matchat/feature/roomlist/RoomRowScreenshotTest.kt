package org.matchat.feature.roomlist

import android.view.LayoutInflater
import android.view.View
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import org.matchat.feature.roomlist.databinding.ItemRoomBinding

/**
 * Screenshot coverage for the room row at the reference viewport (PLAN.md §8.2).
 * Rendered at 240x320 mdpi, normal and largest font scale — the two the whole
 * suite pins. A diff is a review conversation; an unreviewed diff blocks.
 */
class RoomRowScreenshotTest {

    private val config = DeviceConfig(
        screenWidth = 240,
        screenHeight = 320,
        density = Density.MEDIUM,
        orientation = ScreenOrientation.PORTRAIT,
    )

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = config)

    private fun row(): View {
        val binding = ItemRoomBinding.inflate(LayoutInflater.from(paparazzi.context))
        binding.roomName.text = "Barn Crew"
        binding.roomPreview.text = "See you at six by the north gate"
        binding.roomTime.text = "3:42 PM"
        binding.roomUnread.text = "3"
        binding.roomUnread.visibility = View.VISIBLE
        return binding.root
    }

    @Test
    fun roomRow_normal() {
        paparazzi.snapshot(row())
    }

    @Test
    fun roomRow_largestFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = config.copy(fontScale = 1.5f))
        paparazzi.snapshot(row())
    }
}
