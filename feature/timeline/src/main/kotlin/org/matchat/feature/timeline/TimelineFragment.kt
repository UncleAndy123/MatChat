package org.matchat.feature.timeline

import android.util.TypedValue
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
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.key.LogicalKey
import org.matchat.core.ui.menu.MenuItem
import org.matchat.core.ui.menu.MenuSheet
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.DirectionalKeyReceiver
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.core.ui.theme.themeColor
import org.matchat.core.ui.theme.themeDimenPx
import org.matchat.feature.timeline.databinding.FragmentTimelineBinding

/** S9 Timeline. Compose is the initial focus (people come here to reply). */
@AndroidEntryPoint
class TimelineFragment : SoftkeyFragment(), DirectionalKeyReceiver {

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
            if (granted) {
                beginRecording()
            } else {
                Toast.makeText(requireContext(), R.string.timeline_record_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private val adapter = TimelineAdapter(
        onMessageFocused = { viewModel.onAction(TimelineAction.MessageFocused(it)) },
        onFixEncryption = { viewModel.onAction(TimelineAction.FixEncryption(it)) },
        onMessageActivated = { openMessageMenu(menuContextFor(it)) },
        onImageBind = { eventId, image -> loadImageInto(eventId, image) },
        onImageActivated = { openMessageMenu(menuContextFor(it)) },
        onAttachmentActivated = { openMessageMenu(menuContextFor(it)) },
        onVoiceBubbleActivated = { openMessageMenu(menuContextFor(it)) },
        onAvatarBind = { url, name, id, image -> loadAvatarInto(url, name, id, image) },
        onSeenByBind = { seenBy, container -> bindSeenBy(seenBy, container) },
        onReactionsBind = { reactions, container -> bindReactions(reactions, container) },
    )

    // Generic result launcher for both "Send Photo" (a bare GET_CONTENT
    // intent, launchPhotoPicker) and "Send File" (the Documents UI + device
    // Gallery chooser, launchFileChooser/launchAttachmentChooser) — the
    // picked file's kind is derived from its MIME either way.
    private val attachmentPicker =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { sendPicked(it) }
            }
        }

    // Camera capture writes into our own cache/media file; on success we
    // stage it (Attachment staging round), same as a gallery/file pick.
    private var pendingCameraFile: java.io.File? = null
    private val cameraCapture =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
        ) { success ->
            val file = pendingCameraFile
            pendingCameraFile = null
            if (success && file != null && file.length() > 0) {
                stageAttachment(
                    PendingAttachment(
                        file.absolutePath,
                        "image/jpeg",
                        org.matchat.core.model.MediaKind.IMAGE,
                        getString(R.string.timeline_attachment_camera_name),
                    ),
                )
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

        b.pinnedBand.setOnClickListener { navigator.toPinnedMessages(roomId()) }

        // Restore a saved draft (on-device report: don't lose a partially
        // typed message on leaving the room) before attaching the watcher
        // below, so this doesn't fire onComposeTextChanged/re-persist the
        // very draft it's restoring. Covers both a fresh open (the
        // ViewModel just loaded it from disk) and returning here from Room
        // Info/Pinned Messages/Message Info/Image Viewer (the same
        // TimelineViewModel instance survived, so composeText.value is
        // already exactly what the user last typed).
        b.composeInput.setText(viewModel.composeText.value)
        b.composeInput.setSelection(b.composeInput.text?.length ?: 0)

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
        // Sync/connection indicator (Online indicator round) is now handled
        // centrally by SoftkeyFragment — no per-screen call needed.
        b.pinnedBand.isVisible = state.pinnedCount > 0
        if (state.pinnedCount > 0) {
            b.pinnedBand.text = resources.getQuantityString(
                R.plurals.timeline_pinned_band,
                state.pinnedCount,
                state.pinnedCount,
            )
        }
        b.unencryptedBand.isVisible = state.showUnencryptedBand
        b.emptyView.isVisible = state.isEmpty
        b.timelineList.isVisible = !state.isEmpty
        b.typingBar.isVisible = state.typingText != null
        b.typingBar.text = state.typingText.orEmpty()
        b.attachmentPreview.isVisible = state.pendingAttachment != null
        state.pendingAttachment?.let {
            b.attachmentPreview.text = getString(R.string.timeline_attachment_preview_format, it.displayName)
        }
        adapter.submitList(state.rows)

        // Viewing the room clears its unread count (a read receipt on the latest
        // message). Only send when the newest row changes, not on every render.
        val newest = state.rows.lastOrNull()?.stableId
        if (newest != null && newest != lastMarkedStableId) {
            lastMarkedStableId = newest
            viewModel.onAction(TimelineAction.MarkRead)
        }
    }

    /** RIGHT jumps to Pinned messages (quick-access round) — never while
     *  composing (RIGHT stays with the EditText's own caret movement there,
     *  per the user's own explicit ask), and only when there's something to
     *  jump to. See DirectionalKeyReceiver's doc comment for why this
     *  narrow exception exists at all. */
    override fun onDirectionalKey(key: LogicalKey): Boolean {
        if (key != LogicalKey.RIGHT) return false
        if (binding?.composeInput?.isFocused == true) return false
        if (viewModel.state.value.pinnedCount == 0) return false
        navigator.toPinnedMessages(roomId())
        return true
    }

    private fun navigate(nav: TimelineNav) {
        when (nav) {
            TimelineNav.Verification -> navigator.toVerification()
            TimelineNav.RoomInfo -> Unit // S12 Room info is built in a later milestone
            is TimelineNav.Toast -> {
                val res = when (nav.key) {
                    TimelineToastKey.PIN_FAILED -> R.string.timeline_pin_failed
                }
                Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCenter(): Boolean {
        if (isRecording) {
            stopRecordingAndSend()
            return true
        }
        // Bug fix: sending on a plain CENTER press was too easy to trigger by
        // accident. A quick press while composing is now swallowed (does
        // nothing) rather than sending — CENTER_HOLD (below, a genuine
        // ~500ms hold) is the real "send" while composing. binding == null
        // still means "swallow, nothing to activate" either way.
        if (binding != null && viewModel.state.value.isComposeFocused) return true
        return super.onCenter()
    }

    /** CENTER_HOLD (a held CENTER/ENTER) and the hardware CALL key both send
     *  while composing — CENTER_HOLD is the deliberate hold-to-confirm
     *  replacement for the plain-CENTER send [onCenter] used to do; CALL is
     *  the fast, no-hold alternative (repurposing the phone's physical green
     *  call button, per explicit request — it still falls through to the
     *  system dialer when compose isn't focused, unchanged). Neither key
     *  does anything special on any other screen — CallFragment's own
     *  onOtherKey handles CALL/END there independently. */
    override fun onOtherKey(key: LogicalKey): Boolean = when (key) {
        LogicalKey.CENTER_HOLD -> {
            if (isRecording) {
                stopRecordingAndSend()
                true
            } else if (viewModel.state.value.isComposeFocused) {
                sendCompose()
                true
            } else {
                false
            }
        }
        LogicalKey.CALL -> {
            if (viewModel.state.value.isComposeFocused) {
                sendCompose()
                true
            } else {
                false
            }
        }
        else -> false
    }

    private fun sendCompose() {
        val b = binding ?: return
        viewModel.onAction(TimelineAction.Send(b.composeInput.text.toString()))
        b.composeInput.text?.clear()
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
            if (viewModel.state.value.pendingAttachment != null) {
                add(MenuItem(OPT_REMOVE_ATTACHMENT, getString(R.string.timeline_opt_remove_attachment)))
            }
            if (viewModel.canSendMedia) {
                add(MenuItem(OPT_SEND_PHOTO, getString(R.string.timeline_opt_send_photo)))
                if (hasCamera()) add(MenuItem(OPT_TAKE_PHOTO, getString(R.string.timeline_opt_take_photo)))
                if (hasMic()) add(MenuItem(OPT_RECORD_VOICE, getString(R.string.timeline_opt_record_voice)))
                add(MenuItem(OPT_SEND_FILE, getString(R.string.timeline_opt_send_file)))
            }
            add(MenuItem(OPT_CALL, getString(R.string.timeline_opt_call)))
            add(MenuItem(OPT_INFO, getString(R.string.timeline_opt_room_info)))
            add(MenuItem(OPT_READ, getString(R.string.timeline_opt_mark_read)))
            add(MenuItem(OPT_MUTE, getString(R.string.timeline_opt_mute)))
            add(MenuItem(OPT_HELP, getString(R.string.timeline_opt_help)))
        }
        MenuSheet.show(requireContext(), items) { selected ->
            when (selected.id) {
                OPT_REMOVE_ATTACHMENT -> viewModel.onAction(TimelineAction.ClearPendingAttachment)
                OPT_SEND_PHOTO -> launchPhotoPicker()
                OPT_TAKE_PHOTO -> launchCamera()
                OPT_RECORD_VOICE -> startRecording()
                OPT_SEND_FILE -> launchFileChooser()
                OPT_CALL -> navigator.toCall(roomId(), viewModel.state.value.title, incoming = false)
                OPT_INFO -> navigator.toRoomInfo(roomId())
                OPT_HELP -> navigator.toHelp()
                else -> Unit // mark read / mute wire up in a later milestone
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

    /** Stages the recording (Attachment staging round) rather than sending it
     *  immediately — same treatment as a picked photo/file/camera capture,
     *  so a caption can be typed first here too. */
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
        val secs = result.durationMs / 1000
        val name = getString(
            R.string.timeline_attachment_voice_name_format,
            "%d:%02d".format(secs / 60, secs % 60),
        )
        stageAttachment(
            PendingAttachment(
                result.file.absolutePath,
                rec.mimeType,
                org.matchat.core.model.MediaKind.VOICE,
                name,
                durationMs = result.durationMs,
                waveform = result.waveform,
            ),
        )
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

    /** Opens the device's own Gallery app directly via a bare ACTION_GET_CONTENT
     *  (images only) — no `Intent.createChooser` wrapper, so Android launches
     *  the single matching app directly when exactly one exists (the normal
     *  case), confirmed on-device: this exact intent shape resolves straight
     *  to `jp.kyocera.datafolder/jp.kyocera.gallery.GalleryActivity` on the
     *  reference hardware, with no intermediate screen at all. Deliberately
     *  drops `PickVisualMedia`/`isPhotoPickerAvailable` entirely — that check
     *  was found reporting a (modern) Photo Picker as available on this
     *  device even though none of the target hardware has one (ADR 0004: no
     *  Play Services, no Photo Picker backport), so its own internal
     *  silent-degrade to `ACTION_OPEN_DOCUMENT` ran anyway, landing back on
     *  DocumentsUI's root browser ("Open from": Images / Recent / Downloads /
     *  SD card / Bug reports) — the on-device report this replaces ("it
     *  should open the gallery directly instead of opening files"). If a
     *  device genuinely has more than one app registered for GET_CONTENT and
     *  images, Android's own normal disambiguation dialog appears — that's
     *  standard implicit-intent behavior, not something to special-case. */
    private fun launchPhotoPicker() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching { attachmentPicker.launch(intent) }.onFailure {
            Toast.makeText(requireContext(), R.string.timeline_media_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** Not photo-specific (any file type), so a document chooser is still
     *  the right shape here — see [launchAttachmentChooser]. */
    private fun launchFileChooser() = launchAttachmentChooser(mimeType = "*/*")

    /** Offer the Documents UI AND the device Gallery (via ACTION_GET_CONTENT
     *  initial intents) — on a feature phone the Gallery is often the only
     *  D-pad-navigable image browser, and typically only answers the older
     *  GET_CONTENT convention, not the full Storage Access Framework
     *  ACTION_OPEN_DOCUMENT alone would reach. Mirrors the DPAD-Messaging
     *  approach. Only [launchFileChooser] uses this now — [launchPhotoPicker]
     *  above launches its own bare GET_CONTENT intent directly, no chooser. */
    private fun launchAttachmentChooser(mimeType: String) {
        val openDocument = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = mimeType
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val getContent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = mimeType
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

    /** Copy the picked content to the cache (the SDK uploads from a file path)
     *  and stage it (Attachment staging round) rather than sending it right
     *  away, deriving the media kind from the resolved MIME type. The user
     *  can then type a caption into compose_input before actually sending. */
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
            // displayName does a ContentResolver query — kept on IO, same as
            // the original immediate-send code did.
            val staged = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val name = MediaFiles.displayName(ctx, uri)
                val bytes = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: return@withContext null
                MediaFiles.writeToCache(ctx, name, bytes)?.let { it to name }
            }
            if (staged == null) {
                Toast.makeText(ctx, R.string.timeline_media_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (file, name) = staged
            stageAttachment(PendingAttachment(file.absolutePath, mime, kind, name))
        }
    }

    /** Stages [attachment] and moves focus to compose_input so the caption
     *  hint and the "Send" center label are immediately visible — the same
     *  focus-restore pattern already used when a menu/dialog dismisses
     *  (`setOnDismissListener { binding?.composeInput?.requestFocus() }`). */
    private fun stageAttachment(attachment: PendingAttachment) {
        viewModel.onAction(TimelineAction.StageAttachment(attachment))
        binding?.composeInput?.requestFocus()
    }

    /** S11 message menu, opened with CENTER on a message, image, or attachment
     *  row alike — media rows used to jump straight to the viewer/opener,
     *  bypassing this entirely; they now get the same Reply/React/Pin/etc.
     *  menu a text message does, with "Open" (reaching that same
     *  viewer/opener) added at the top for media only. [menuContextFor]'s
     *  three overloads adapt each row type's own fields into one shared
     *  shape. Both this menu and the edit prompt it can open are plain
     *  Dialogs over the still-alive Fragment view (S9 stays the visible
     *  screen underneath), so nothing restores focus to compose_input on
     *  dismiss unless we do it here — one general hook on each dialog, not
     *  per-branch logic (Phase 9, UI improvement plan; also correctly
     *  covers the still-TODO MSG_REPLY branch once it lands, since it'll
     *  open a dialog off this same menu). Navigation-based destinations
     *  (toImageViewer, toMessageInfo, toRoomInfo) are untouched — those
     *  already restore focus correctly via Fragment view recreation. */
    private fun openMessageMenu(ctx: MessageMenuContext) {
        val items = buildList {
            if (ctx.openAction != null) add(MenuItem(MSG_OPEN, getString(R.string.timeline_msg_open)))
            add(MenuItem(MSG_REACT, getString(R.string.timeline_msg_react)))
            add(MenuItem(MSG_REPLY, getString(R.string.timeline_msg_reply)))
            add(
                MenuItem(
                    MSG_PIN,
                    getString(if (ctx.isPinned) R.string.timeline_msg_unpin else R.string.timeline_msg_pin),
                ),
            )
            if (ctx.isOwn && ctx.editAction != null) add(MenuItem(MSG_EDIT, getString(R.string.timeline_msg_edit)))
            if (ctx.copyText != null) add(MenuItem(MSG_COPY, getString(R.string.timeline_msg_copy)))
            add(MenuItem(MSG_INFO, getString(R.string.timeline_msg_info)))
        }
        val menu = MenuSheet.show(requireContext(), items) { selected ->
            when (selected.id) {
                MSG_OPEN -> ctx.openAction?.invoke()
                MSG_REACT -> openReactionPicker(ctx.eventId, ctx.reactions)
                MSG_EDIT -> ctx.editAction?.invoke()
                MSG_PIN -> viewModel.setPinned(ctx.eventId, !ctx.isPinned)
                MSG_COPY -> ctx.copyText?.let { copyText(it) }
                MSG_INFO -> navigator.toMessageInfo(
                    roomId(),
                    ctx.eventId,
                    org.matchat.core.model.UserId(ctx.senderId),
                    ctx.timestampEpochMs,
                )
                else -> Unit // reply lands in a later milestone
            }
        }
        menu.setOnDismissListener { binding?.composeInput?.requestFocus() }
    }

    /** What [openMessageMenu] needs, independent of which of the three
     *  [TimelineRow] subtypes triggered it. [openAction]/[editAction] null
     *  omits that menu item entirely (Open: text messages aren't "opened";
     *  Edit: only a message's own text body is editable, never media). */
    private data class MessageMenuContext(
        val eventId: org.matchat.core.model.EventId,
        val senderId: String,
        val timestampEpochMs: Long,
        val isOwn: Boolean,
        val isPinned: Boolean,
        val reactions: List<org.matchat.core.model.ReactionSummary>,
        val copyText: String?,
        val openAction: (() -> Unit)?,
        val editAction: (() -> Unit)?,
    )

    private fun menuContextFor(row: TimelineRow.Message) = MessageMenuContext(
        eventId = row.eventId,
        senderId = row.senderId,
        timestampEpochMs = row.timestampEpochMs,
        isOwn = row.isOwn,
        isPinned = row.isPinned,
        reactions = row.reactions,
        copyText = row.body,
        openAction = null,
        editAction = {
            org.matchat.core.ui.menu.TextPromptSheet.show(
                requireContext(),
                getString(R.string.timeline_edit_title),
                row.body,
                singleLine = false,
            ) { viewModel.editMessage(row.eventId, it) }
                .setOnDismissListener { binding?.composeInput?.requestFocus() }
        },
    )

    private fun menuContextFor(row: TimelineRow.Image) = MessageMenuContext(
        eventId = row.eventId,
        senderId = row.senderId,
        timestampEpochMs = row.timestampEpochMs,
        isOwn = row.isOwn,
        isPinned = row.isPinned,
        reactions = row.reactions,
        copyText = row.caption?.takeIf { it.isNotBlank() },
        openAction = { navigator.toImageViewer(row.eventId) },
        editAction = null,
    )

    private fun menuContextFor(row: TimelineRow.Attachment) = MessageMenuContext(
        eventId = row.eventId,
        senderId = row.senderId,
        timestampEpochMs = row.timestampEpochMs,
        isOwn = row.isOwn,
        isPinned = row.isPinned,
        reactions = row.reactions,
        copyText = null,
        openAction = { openAttachment(row) },
        editAction = null,
    )

    private fun menuContextFor(row: TimelineRow.VoiceBubble) = MessageMenuContext(
        eventId = row.eventId,
        senderId = row.senderId,
        timestampEpochMs = row.timestampEpochMs,
        isOwn = row.isOwn,
        isPinned = row.isPinned,
        reactions = row.reactions,
        copyText = null,
        openAction = { openVoiceBubble(row) },
        editAction = null,
    )

    private fun copyText(text: String) {
        val clipboard = requireContext()
            .getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
    }

    private fun roomId(): org.matchat.core.model.RoomId =
        org.matchat.core.model.RoomId(requireArguments().getString(ARG_ROOM_ID).orEmpty())

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

    /** Avatars round: loadAvatar/AvatarCache/AvatarBinder are the same shared
     *  path room list and Room Info use (core/ui, since features can't
     *  depend on each other) — this Fragment only supplies the byte fetch.
     *  name/id are the no-avatar-fallback's color+initial source
     *  (AvatarFallback round). */
    private fun loadAvatarInto(url: String?, name: String, id: String, image: android.widget.ImageView) {
        viewLifecycleOwner.lifecycleScope.launch {
            org.matchat.core.ui.media.AvatarBinder.bind(
                image,
                url,
                name,
                id,
                avatarMaxPx(),
            ) { viewModel.loadAvatar(it) }
        }
    }

    /** Decode-quality cap, ~2x avatarSizeSender — small on purpose. A compile-time
     *  literal can't respond to the runtime Text size choice, so this is computed
     *  from the theme attr at bind time instead of a const. */
    private fun avatarMaxPx(): Int =
        (requireContext().themeDimenPx(org.matchat.core.ui.R.attr.avatarSizeSender) * 2).toInt()

    /** Populates the "seen by" row with up to [SEEN_BY_MAX] avatars plus a
     *  "+N" overflow label — plain Views built here, not a nested
     *  RecyclerView (this app's convention for a handful of small items;
     *  the reaction-chip row uses the same shape). Avatars overlap (a
     *  negative marginEnd) rather than sit side by side — later views draw
     *  on top of earlier ones under Android's normal z-order, so no extra
     *  container/ring is needed for the stacked look. */
    private fun bindSeenBy(seenBy: List<org.matchat.core.model.SeenBy>, container: android.widget.LinearLayout) {
        container.removeAllViews()
        val avatarPx = requireContext().themeDimenPx(org.matchat.core.ui.R.attr.avatarSizeSeenBy).toInt()
        val overlapPx = -(avatarPx / SEEN_BY_OVERLAP_DIVISOR)
        seenBy.take(SEEN_BY_MAX).forEachIndexed { index, entry ->
            val avatar = android.widget.ImageView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(avatarPx, avatarPx).apply {
                    if (index > 0) marginStart = overlapPx
                }
                contentDescription = null
            }
            container.addView(avatar)
            loadAvatarInto(entry.avatarUrl, entry.displayName ?: entry.userId.value, entry.userId.value, avatar)
        }
        val overflow = seenBy.size - SEEN_BY_MAX
        if (overflow > 0) {
            container.addView(
                android.widget.TextView(requireContext()).apply {
                    text = "+$overflow"
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        requireContext().themeDimenPx(org.matchat.core.ui.R.attr.textSizeMeta),
                    )
                    setTextColor(requireContext().themeColor(org.matchat.core.ui.R.attr.colorTextMetaOnFocus))
                },
            )
        }
    }

    /** Reactions round: chips are display-only (see item_message.xml's header
     *  comment — a D-pad row can't usefully offer several separately
     *  focusable chips), each "<emoji> <count>", bolder/accent-colored when
     *  we reacted with it. Reacting always goes through openReactionPicker,
     *  reached from the message's Options menu. */
    private fun bindReactions(
        reactions: List<org.matchat.core.model.ReactionSummary>,
        container: android.widget.LinearLayout,
    ) {
        container.removeAllViews()
        reactions.forEach { r ->
            container.addView(
                android.widget.TextView(requireContext()).apply {
                    text = "${r.key} ${r.count}"
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        requireContext().themeDimenPx(org.matchat.core.ui.R.attr.textSizeMeta),
                    )
                    setTextColor(
                        requireContext().themeColor(
                            if (r.reactedByMe) {
                                org.matchat.core.ui.R.attr.colorFocusAccent
                            } else {
                                org.matchat.core.ui.R.attr.colorTextMetaOnFocus
                            },
                        ),
                    )
                    if (r.reactedByMe) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, REACTION_CHIP_SPACING_PX, 0)
                },
            )
        }
    }

    /** Opened from the message Options menu (MSG_REACT), for any of the
     *  three row types alike — takes just what it needs (eventId +
     *  reactions) rather than a whole [TimelineRow.Message], since Image and
     *  Attachment rows react the same way. Reuses MenuSheet — the app's
     *  only menu construct — for a 10-choice list (now scrollable,
     *  MenuSheet's own Reactions-round change) rather than a new dialog
     *  type. Selecting an already-active reaction removes it (toggleReaction
     *  is itself a toggle). Each choice's toggle key is resolved against the
     *  message's own existing reactions first (resolveReactionKey) — bug
     *  fix: reacting with an emoji visually already on the message must
     *  bump that chip's count, not create a byte-different duplicate. */
    private fun openReactionPicker(
        eventId: org.matchat.core.model.EventId,
        reactions: List<org.matchat.core.model.ReactionSummary>,
    ) {
        val items = REACTION_CHOICES.map { (key, label) ->
            val toggleKey = resolveReactionKey(reactions, key)
            val reacted = reactions.any { it.key == toggleKey && it.reactedByMe }
            val text = "$key $label"
            MenuItem(toggleKey, if (reacted) getString(R.string.timeline_row_selected_format, text) else text)
        }
        MenuSheet.show(requireContext(), items) { selected ->
            viewModel.toggleReaction(eventId, selected.id)
        }.setOnDismissListener { binding?.composeInput?.requestFocus() }
    }

    /** Attachment rows are video/file only now (VOICE/AUDIO get
     *  [openVoiceBubble] instead), so this always opens externally. */
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
            openExternally(file, row.mimeType)
        }
    }

    /** Every VoiceBubble row is playable in-app (unlike Attachment, which also
     *  covers video/file — those open externally instead). */
    private fun openVoiceBubble(row: TimelineRow.VoiceBubble) {
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
            playAudio(file)
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
        const val OPT_CALL = "call"
        const val OPT_SEND_PHOTO = "send_photo"
        const val OPT_TAKE_PHOTO = "take_photo"
        const val OPT_RECORD_VOICE = "record_voice"
        const val OPT_SEND_FILE = "send_file"
        const val OPT_REMOVE_ATTACHMENT = "remove_attachment"
        const val RECORD_TICK_MS = 200L
        const val MIN_VOICE_MS = 1_000L // ignore accidental sub-second taps
        const val ARG_ROOM_ID = "roomId"
        const val MSG_OPEN = "open"
        const val MSG_REPLY = "reply"
        const val MSG_EDIT = "edit"
        const val MSG_REACT = "react"
        const val MSG_PIN = "pin"
        const val MSG_COPY = "copy"
        const val MSG_INFO = "msg_info"
        const val MAX_IMAGE_PX = 480 // ~2x the 240 px screen; Coil-free downsample
        const val SEEN_BY_MAX = 4 // beyond this, show "+N" instead of more circles
        const val SEEN_BY_OVERLAP_DIVISOR = 3 // later avatars overlap ~1/3 of the previous one
        const val REACTION_CHIP_SPACING_PX = 10

        // Thumbs up/down + 8 common smileys — ~10 total, per the user's own
        // "simple thumbs up/down, and smileys" ask. The MenuItem id IS the
        // emoji itself (the SDK's reaction key), so toggleReaction gets it
        // straight from MenuSheet's selection, no lookup table needed there.
        val REACTION_CHOICES = listOf(
            "👍" to "Thumbs up",
            "👎" to "Thumbs down",
            "😀" to "Smile",
            "😂" to "Laughing",
            "❤️" to "Heart",
            "😮" to "Surprised",
            "😢" to "Sad",
            "😡" to "Angry",
            "🙏" to "Thanks",
            "🎉" to "Celebrate",
        )
    }
}
