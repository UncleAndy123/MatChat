package org.matchat.feature.timeline

import android.view.View
import android.widget.TextView
import android.widget.Toast
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
import org.matchat.core.ui.menu.MenuItem
import org.matchat.core.ui.menu.MenuSheet
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
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_options)
    override val centerLabel: CharSequence get() = ""

    private val viewModel: ImageViewerViewModel by viewModels()
    private var image: ZoomPanImageView? = null
    private var status: TextView? = null

    private var pendingSave: (() -> Unit)? = null
    private val storagePermission =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val save = pendingSave
            pendingSave = null
            if (granted) {
                save?.invoke()
            } else {
                Toast.makeText(requireContext(), R.string.timeline_save_denied, Toast.LENGTH_SHORT).show()
            }
        }

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
            org.matchat.core.ui.key.LogicalKey.DIGIT_2 -> {
                img.panUp()
                true
            }
            org.matchat.core.ui.key.LogicalKey.DIGIT_8 -> {
                img.panDown()
                true
            }
            org.matchat.core.ui.key.LogicalKey.DIGIT_4 -> {
                img.panLeft()
                true
            }
            org.matchat.core.ui.key.LogicalKey.DIGIT_6 -> {
                img.panRight()
                true
            }
            org.matchat.core.ui.key.LogicalKey.DIGIT_0 -> {
                img.resetView()
                true
            }
            else -> false
        }
    }

    /** LEFT softkey: save this image to the gallery or to Downloads. Reuses the
     *  already-loaded bytes — no re-download. */
    override fun onOptions(): Boolean {
        val items = listOf(
            MenuItem(OPT_SAVE_GALLERY, getString(R.string.timeline_msg_save_gallery)),
            MenuItem(OPT_SAVE_FILES, getString(R.string.timeline_msg_save_files)),
        )
        MenuSheet.show(requireContext(), items) { selected ->
            when (selected.id) {
                OPT_SAVE_GALLERY -> save(MediaSaver.Target.GALLERY, R.string.timeline_saved_gallery)
                OPT_SAVE_FILES -> save(MediaSaver.Target.DOWNLOADS, R.string.timeline_saved_downloads)
            }
        }.setOnDismissListener { image?.requestFocus() }
        return true
    }

    private fun save(target: MediaSaver.Target, okMessage: Int) = withStoragePermission {
        val ctx = requireContext()
        val bytes = viewModel.state.value.bytes
        if (bytes == null) {
            Toast.makeText(ctx, R.string.timeline_media_failed, Toast.LENGTH_SHORT).show()
            return@withStoragePermission
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val mime = MediaSaver.imageMime(bytes)
                MediaSaver.save(ctx, target, imageFileName(mime), mime, bytes)
            }
            Toast.makeText(
                ctx,
                if (ok) okMessage else R.string.timeline_save_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun imageFileName(mime: String): String {
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
        val stem = requireArguments().getString("eventId").orEmpty()
            .filter { it.isLetterOrDigit() }.takeLast(16).ifBlank { "image" }
        return "MatChat_$stem.$ext"
    }

    private fun withStoragePermission(block: () -> Unit) {
        if (!MediaSaver.needsLegacyPermission() ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            block()
        } else {
            pendingSave = block
            storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
        const val OPT_SAVE_GALLERY = "save_gallery"
        const val OPT_SAVE_FILES = "save_files"
    }
}
