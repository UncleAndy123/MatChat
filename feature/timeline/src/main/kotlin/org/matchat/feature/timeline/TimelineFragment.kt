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
        get() = getString(
            if (viewModel.state.value.isComposeFocused) {
                org.matchat.core.ui.R.string.softkey_send
            } else {
                org.matchat.core.ui.R.string.softkey_select
            },
        )

    private val viewModel: TimelineViewModel by viewModels()
    private var binding: FragmentTimelineBinding? = null
    private var lastMarkedStableId: String? = null
    private val navigator: Navigator get() = requireActivity() as Navigator

    private val adapter = TimelineAdapter(
        onMessageFocused = { viewModel.onAction(TimelineAction.MessageFocused(it)) },
        onFixEncryption = { viewModel.onAction(TimelineAction.FixEncryption(it)) },
        onMessageActivated = { openMessageMenu(it) },
        onImageBind = { eventId, image -> loadImageInto(eventId, image) },
        onAttachmentActivated = { openAttachment(it) },
    )

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
        val b = binding ?: return false
        if (viewModel.state.value.isComposeFocused) {
            viewModel.onAction(TimelineAction.Send(b.composeInput.text.toString()))
            b.composeInput.text?.clear()
            return true
        }
        return super.onCenter()
    }

    override fun onOptions(): Boolean {
        val items = listOf(
            MenuItem(OPT_INFO, getString(R.string.timeline_opt_room_info)),
            MenuItem(OPT_READ, getString(R.string.timeline_opt_mark_read)),
            MenuItem(OPT_MUTE, getString(R.string.timeline_opt_mute)),
            MenuItem(OPT_HELP, getString(R.string.timeline_opt_help)),
        )
        MenuSheet.show(requireContext(), items) { selected ->
            when (selected.id) {
                OPT_HELP -> navigator.toHelp()
                else -> Unit // room info / mark read / mute wire up in M2–M4
            }
        }
        return true
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
            val bytes = viewModel.loadMedia(row.eventId)
            if (bytes == null) {
                Toast.makeText(requireContext(), R.string.timeline_media_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MediaFiles.writeToCache(requireContext(), row.label, bytes)
            }
            MediaFiles.open(requireContext(), file, row.mimeType) {
                Toast.makeText(requireContext(), R.string.timeline_media_no_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        binding?.timelineList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val OPT_INFO = "info"
        const val OPT_READ = "read"
        const val OPT_MUTE = "mute"
        const val OPT_HELP = "help"
        const val MSG_REPLY = "reply"
        const val MSG_COPY = "copy"
        const val MSG_INFO = "msg_info"
        const val MAX_IMAGE_PX = 480 // ~2x the 240 px screen; Coil-free downsample
    }
}
