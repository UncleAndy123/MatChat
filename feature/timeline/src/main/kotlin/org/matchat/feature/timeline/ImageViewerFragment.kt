package org.matchat.feature.timeline

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.softkey.SoftkeyFragment

/**
 * S9 full-screen image viewer. Opened with CENTER on a timeline image. The image
 * fills the screen; `*` zooms in, `#` zooms out, the D-pad pans, and Back returns.
 * All of that is handled by [ZoomPanImageView], which holds focus so it receives
 * the keys through the platform (MainActivity passes them straight through).
 */
@AndroidEntryPoint
class ImageViewerFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_image_viewer
    override val leftLabel: CharSequence get() = ""
    override val centerLabel: CharSequence get() = ""

    private val viewModel: ImageViewerViewModel by viewModels()
    private var image: ZoomPanImageView? = null
    private var status: TextView? = null

    override fun onContentViewCreated(content: View) {
        setTitle(getString(R.string.viewer_title))
        val img = content.findViewById<ZoomPanImageView>(R.id.viewer_image)
        val statusView = content.findViewById<TextView>(R.id.viewer_status)
        image = img
        status = statusView

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        FocusEngine.requestInitialFocus(img)
    }

    // Digits are delivered to the screen, not the focused view, so the keypad
    // cluster (2/4/6/8 pan, 0 reset) is forwarded to the image here. * / # and the
    // D-pad reach the view directly.
    override fun onOtherKey(key: org.matchat.core.ui.key.LogicalKey): Boolean {
        val img = image ?: return false
        return when (key) {
            org.matchat.core.ui.key.LogicalKey.DIGIT_2 -> { img.panUp(); true }
            org.matchat.core.ui.key.LogicalKey.DIGIT_8 -> { img.panDown(); true }
            org.matchat.core.ui.key.LogicalKey.DIGIT_4 -> { img.panLeft(); true }
            org.matchat.core.ui.key.LogicalKey.DIGIT_6 -> { img.panRight(); true }
            org.matchat.core.ui.key.LogicalKey.DIGIT_0 -> { img.resetView(); true }
            else -> false
        }
    }

    private fun render(state: ImageViewerState) {
        val statusView = status ?: return
        when {
            state.isLoading -> {
                statusView.isVisible = true
                statusView.setText(R.string.viewer_loading)
            }
            state.failed || state.bytes == null -> {
                statusView.isVisible = true
                statusView.setText(R.string.timeline_media_failed)
            }
            else -> {
                statusView.isVisible = false
                decodeInto(state.bytes)
            }
        }
    }

    private fun decodeInto(bytes: ByteArray) {
        val img = image ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                MediaFiles.decodeSampled(bytes, MAX_VIEWER_PX)
            }
            if (bitmap == null) {
                status?.isVisible = true
                status?.setText(R.string.timeline_media_failed)
                return@launch
            }
            img.setBitmap(bitmap)
            img.requestFocus()
        }
    }

    override fun onDestroyView() {
        image = null
        status = null
        super.onDestroyView()
    }

    private companion object {
        // Larger than the inline row cap so there is real detail to zoom into,
        // but still bounded to protect the 2 GB device from a huge decode.
        const val MAX_VIEWER_PX = 1280
    }
}
