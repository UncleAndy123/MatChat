package org.matchat.feature.timeline

import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.model.EventId
import org.matchat.core.model.SyncState
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.menu.MenuItem
import org.matchat.core.ui.menu.MenuSheet
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.timeline.databinding.FragmentTimelineBinding

/** S9 Timeline. Compose is the initial focus (people come here to reply). */
@AndroidEntryPoint
class TimelineFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_timeline
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_options)

    // CENTER label reads Send while the input is focused, Select otherwise (S10).
    override val centerLabel: CharSequence
        get() = when {
            isRecording -> getString(R.string.timeline_softkey_send_voice)
            viewModel.state.value.isComposeFocused -> getString(org.matchat.core.ui.R.string.softkey_send)
            else -> getString(org.matchat.core.ui.R.string.softkey_select)
        }

    private val viewModel: TimelineViewModel by viewModels()
    private var binding: FragmentTimelineBinding? = null
    private var lastMarkedStableId: String? = null
    private val audio = AudioPlayback()
    private var recorder: VoiceRecorder? = null
    private var isRecording = false
    private val navigator: Navigator get() = requireActivity() as Navigator

    private val recordPermission =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) beginRecording() else {
                Toast.makeText(requireContext(), R.string.timeline_record_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private val adapter = TimelineAdapter(
        onMessageFocused = { viewModel.onAction(TimelineAction.MessageFocused(it)) },
        onFixEncryption = { viewModel.onAction(TimelineAction.FixEncryption(it)) },
        onMessageActivated = { openMessageMenu(it) },
        onImageBind = { eventId, image -> loadImageInto(eventId, image) },
        onImageActivated = { navigator.toImageViewer(it) },
        onAttachmentActivated = { openAttachment(it) },
    )

    // A chooser (Documents UI + the device Gallery) returns a content Uri via
    // StartActivityForResult; the picked file's kind is derived from its MIME.
    private val attachmentPicker =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { sendPicked(it) }
            }
        }

    // Camera capture writes into our own cache/media file; on success we send it.
    private var pendingCameraFile: java.io.File? = null
    private val cameraCapture =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
        ) { success ->
            val file = pendingCameraFile
            pendingCameraFile = null
            if (success && file != null && file.length() > 0) {
                Toast.makeText(requireContext(), R.string.timeline_sending, Toast.LENGTH_SHORT).show()
                viewModel.sendMedia(file.absolutePath, "image/jpeg", org.matchat.core.model.MediaKind.IMAGE, null)
            }
        }

    override fun onContentViewCreated(content: View) {
        val b = FragmentTimelineBinding.bind(content)
        binding = b
        b.timelineList.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        b.timelineList.adapter = adapter
        b.timelineList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findFirstVisibleItemPosition() == 0) {
                    viewModel.onAction(TimelineAction.ReachedTop)
                }
            }
        })

        b.composeInput.setOnFocusChangeListener { _, focused ->
            viewModel.onAction(TimelineAction.ComposeFocusChanged(focused))
            refreshSoftkeys()
        }

        b.composeInput.addTextChangedListener { text ->
            viewModel.onComposeTextChanged(text?.toString().orEmpty())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.navEvents.collect(::navigate) }
            }
        }
        // Initial focus is the compose strip (UX-SPEC S9).
        FocusEngine.requestInitialFocus(b.composeInput)
    }

    private fun render(state: TimelineState) {
        val b = binding ?: return
        setTitle(state.title)
        setSyncGlyph(SyncState.IDLE)
        b.unencryptedBand.isVisible = state.showUnencryptedBand
        b.emptyView.isVisible = state.isEmpty
        b.timelineList.isVisible = !state.isEmpty
        b.typingBar.isVisible = state.typingText != null
        b.typingBar.text = state.typingText.orEmpty()
        adapter.submitList(state.rows)

        // Viewing the room clears its unread count (a read receipt on the latest
        // message). Only send when the newest row changes, not on every render.
        val newest = state.rows.lastOrNull()?.stableId
        if (newest != null && newest != lastMarkedStableId) {
            lastMarkedStableId = newest
            viewModel.onAction(TimelineAction.MarkRead)
        }
    }

    private fun navigate(nav: TimelineNav) {
        when (nav) {
            TimelineNav.Verification -> navigator.toVerification()
            TimelineNav.RoomInfo -> Unit // S12 Room info is built in a later milestone
        }
    }

    override fun onCenter(): Boolean {
        if (isRecording) {
            stopRecordingAndSend()
            return true
        }
        val b = binding ?: return false
        if (viewModel.state.value.isComposeFocused) {
            viewModel.onAction(TimelineAction.Send(b.composeInput.text.toString()))
            b.composeInput.text?.clear()
            return true
        }
        return super.onCenter()
    }

    override fun onBack(): Boolean {
        if (isRecording) {
            cancelRecording()
            return true
        }
        return super.onBack()
    }

    override fun onOptions(): Boolean {
        val items = buildList {
            if (viewModel.canSendMedia) {
                add(MenuItem(OPT_SEND_PHOTO, getString(R.string.timeline_opt_send_photo)))
                if (hasCamera()) add(MenuItem(OPT_TAKE_PHOTO, getString(R.string.timeline_opt_take_photo)))
                if (hasMic()) add(MenuItem(OPT_RECORD_VOICE, getString(R.string.timeline_opt_record_voice)))
                add(MenuItem(OPT_SEND_FILE, getString(R.string.timeline_opt_send_file)))
            }
            add(MenuItem(OPT_INFO, getString(R.string.timeline_opt_room_info)))
            add(MenuItem(OPT_READ, getString(R.string.timeline_opt_mark_read)))
            add(MenuItem(OPT_MUTE, getString(R.string.timeline_opt_mute)))
            add(MenuItem(OPT_HELP, getString(R.string.timeline_opt_help)))
        }
        MenuSheet.show(requireContext(), items) { selected ->
            when (selected.id) {
                OPT_SEND_PHOTO -> launchAttachmentChooser(imageOnly = true)
                OPT_TAKE_PHOTO -> launchCamera()
                OPT_RECORD_VOICE -> startRecording()
                OPT_SEND_FILE -> launchAttachmentChooser(imageOnly = false)
                OPT_HELP -> navigator.toHelp()
                else -> Unit // room info / mark read / mute wire up in M2–M4
            }
        }
        return true
    }

    private fun hasCamera(): Boolean =
        requireContext().packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)

    private fun hasMic(): Boolean =
        requireContext().packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)

    // --- Voice recording ----------------------------------------------------

    private fun startRecording() {
        if (isRecording) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            requireContext(),
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) beginRecording() else recordPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun beginRecording() {
        val rec = VoiceRecorder(requireContext())
        if (!rec.start()) {
            Toast.makeText(requireContext(), R.string.timeline_record_failed, Toast.LENGTH_SHORT).show()
            return
        }
        audio.stop() // don't record over playback
        recorder = rec
        isRecording = true
        binding?.recordingBar?.isVisible = true
        binding?.composeInput?.isVisible = false
        refreshSoftkeys()
        tickRecording()
    }

    /** Updates the timer and samples the input level ~5x/second while recording. */
    private fun tickRecording() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isRecording) {
                val rec = recorder ?: break
                rec.sampleAmplitude()
                val secs = rec.elapsedMs() / 1000
                binding?.recordingBar?.text =
                    getString(R.string.timeline_recording, "%d:%02d".format(secs / 60, secs % 60))
                kotlinx.coroutines.delay(RECORD_TICK_MS)
            }
        }
    }

    private fun stopRecordingAndSend() {
        val rec = recorder ?: return
        val result = rec.stop()
        endRecordingUi()
        if (result == null) {
            Toast.makeText(requireContext(), R.string.timeline_record_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (result.durationMs < MIN_VOICE_MS) {
            runCatching { result.file.delete() }
            Toast.makeText(requireContext(), R.string.timeline_record_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.sendVoice(result.file.absolutePath, rec.mimeType, result.durationMs, result.waveform)
    }

    private fun cancelRecording() {
        recorder?.cancel()
        endRecordingUi()
    }

    private fun endRecordingUi() {
        isRecording = false
        recorder = null
        binding?.recordingBar?.isVisible = false
        binding?.composeInput?.isVisible = true
        refreshSoftkeys()
    }

    /** Offer the Documents UI AND the device Gallery (via ACTION_GET_CONTENT
     *  initial intents) — on a feature phone the Gallery is often the only
     *  D-pad-navigable image browser. Mirrors the DPAD-Messaging approach. */
    private fun launchAttachmentChooser(imageOnly: Boolean) {
        val type = if (imageOnly) "image/*" else "*/*"
        val openDocument = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            this.type = type
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val getContent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            this.type = type
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(
            openDocument,
            getString(R.string.timeline_choose_source),
        ).apply {
            putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS, arrayOf(getContent))
        }
        runCatching { attachmentPicker.launch(chooser) }.onFailure {
            Toast.makeText(requireContext(), R.string.timeline_media_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchCamera() {
        val ctx = requireContext()
        val file = MediaFiles.newCameraFile(ctx)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        pendingCameraFile = file
        runCatching { cameraCapture.launch(uri) }.onFailure {
            pendingCameraFile = null
            Toast.makeText(ctx, R.string.timeline_media_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** Copy the picked content to the cache (the SDK uploads from a file path) and
     *  send it, deriving the media kind from the resolved MIME type. */
    private fun sendPicked(uri: android.net.Uri) {
        val ctx = requireContext()
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val kind = when {
            mime.startsWith("image/") -> org.matchat.core.model.MediaKind.IMAGE
            mime.startsWith("video/") -> org.matchat.core.model.MediaKind.VIDEO
            mime.startsWith("audio/") -> org.matchat.core.model.MediaKind.AUDIO
            else -> org.matchat.core.model.MediaKind.FILE
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val bytes = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: return@withContext null
                MediaFiles.writeToCache(ctx, MediaFiles.displayName(ctx, uri), bytes)
            }
            if (file == null) {
                Toast.makeText(ctx, R.string.timeline_media_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(ctx, R.string.timeline_sending, Toast.LENGTH_SHORT).show()
            viewModel.sendMedia(file.absolutePath, mime, kind, caption = null)
        }
    }

    /** S11 message menu, opened with CENTER on a message row. */
    private fun openMessageMenu(eventId: EventId) {
        val items = listOf(
            MenuItem(MSG_REPLY, getString(R.string.timeline_msg_reply)),
            MenuItem(MSG_COPY, getString(R.string.timeline_msg_copy)),
            MenuItem(MSG_INFO, getString(R.string.timeline_msg_info)),
        )
        MenuSheet.show(requireContext(), items) { /* actions land in M3 */ }
    }

    private fun loadImageInto(eventId: org.matchat.core.model.EventId, image: android.widget.ImageView) {
        image.tag = eventId
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = viewModel.loadMedia(eventId) ?: return@launch
            val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                MediaFiles.decodeSampled(bytes, MAX_IMAGE_PX)
            }
            if (bitmap != null && image.tag == eventId) image.setImageBitmap(bitmap)
        }
    }

    private fun openAttachment(row: TimelineRow.Attachment) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val bytes = viewModel.loadMedia(row.eventId)
            if (bytes == null) {
                Toast.makeText(ctx, R.string.timeline_media_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MediaFiles.writeToCache(ctx, MediaFiles.ensureExtension(row.label, row.mimeType), bytes)
            }
            if (row.play) playAudio(file) else openExternally(file, row.mimeType)
        }
    }

    /** Voice/audio: play in-app (the phone has no media-player app). CENTER on a
     *  track that is already playing stops it. */
    private fun playAudio(file: java.io.File) {
        val ctx = requireContext()
        if (audio.isPlaying(file.absolutePath)) {
            audio.stop()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { audio.prepare(file) }
            if (!ok) {
                Toast.makeText(ctx, R.string.timeline_audio_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            audio.start { /* completion: nothing to update yet */ }
        }
    }

    private fun openExternally(file: java.io.File, mimeType: String?) {
        MediaFiles.open(requireContext(), file, mimeType) {
            Toast.makeText(requireContext(), R.string.timeline_media_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        audio.stop()
        if (isRecording) cancelRecording()
        binding?.timelineList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val OPT_INFO = "info"
        const val OPT_READ = "read"
        const val OPT_MUTE = "mute"
        const val OPT_HELP = "help"
        const val OPT_SEND_PHOTO = "send_photo"
        const val OPT_TAKE_PHOTO = "take_photo"
        const val OPT_RECORD_VOICE = "record_voice"
        const val OPT_SEND_FILE = "send_file"
        const val RECORD_TICK_MS = 200L
        const val MIN_VOICE_MS = 1_000L // ignore accidental sub-second taps
        const val MSG_REPLY = "reply"
        const val MSG_COPY = "copy"
        const val MSG_INFO = "msg_info"
        const val MAX_IMAGE_PX = 480 // ~2x the 240 px screen; Coil-free downsample
    }
}
