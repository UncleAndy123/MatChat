package org.matchat.feature.invites

import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.menu.TextPromptSheet
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.invites.databinding.FragmentJoinRoomBinding

/** S28 Join group. One action button (Join / Ask to join / Open); no Options. */
@AndroidEntryPoint
class JoinRoomFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_join_room
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: JoinRoomViewModel by viewModels()
    private var binding: FragmentJoinRoomBinding? = null
    private val navigator: Navigator get() = requireActivity() as Navigator

    override fun onContentViewCreated(content: View) {
        val b = FragmentJoinRoomBinding.bind(content)
        binding = b
        setTitle(getString(R.string.join_title))
        b.joinAction.setOnClickListener { viewModel.onAction(JoinRoomAction.Activate) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.navEvents.collect { nav ->
                        when (nav) {
                            // Replace this screen with the room, so Back leaves it.
                            is JoinRoomNav.OpenRoom -> {
                                navigator.back()
                                navigator.toRoom(nav.roomId)
                            }
                            is JoinRoomNav.OpenSpace -> {
                                navigator.back()
                                navigator.toSpace(nav.spaceId)
                            }
                            JoinRoomNav.PromptForMessage -> TextPromptSheet.show(
                                requireContext(),
                                getString(R.string.join_message_prompt),
                                singleLine = false,
                            ) { viewModel.onAction(JoinRoomAction.SendRequest(it)) }
                        }
                    }
                }
            }
        }
    }

    private fun render(state: JoinRoomState) {
        val b = binding ?: return
        b.joinName.text = state.name
        b.joinAddress.text = state.address
        b.joinAddress.isVisible = state.address.isNotEmpty() && state.address != state.name
        b.joinTopic.isVisible = state.topic != null
        b.joinTopic.text = state.topic
        b.joinMembers.isVisible = state.memberCount != null
        state.memberCount?.let {
            b.joinMembers.text = resources.getQuantityString(R.plurals.join_members, it, it)
        }
        b.joinLoading.isVisible = state.isLoading || state.isBusy
        b.joinLoading.setText(if (state.isBusy) R.string.join_working else R.string.join_loading)
        b.joinNote.isVisible = state.note != null
        state.note?.let { b.joinNote.setText(noteText(it)) }
        b.joinBlocked.isVisible = state.blockedDomain != null
        state.blockedDomain?.let { b.joinBlocked.text = getString(R.string.invite_blocked, it) }
        b.joinError.isVisible = state.error != null
        state.error?.let { b.joinError.setText(errorText(it)) }
        b.joinAction.isVisible = state.button != JoinButton.NONE
        buttonText(state.button)?.let { b.joinAction.setText(it) }
        if (b.joinAction.isVisible && b.root.findFocus() == null) FocusEngine.requestInitialFocus(b.joinAction)
    }

    private fun errorText(error: JoinError): Int = when (error) {
        JoinError.JOIN_FAILED -> R.string.join_error_join
        JoinError.KNOCK_FAILED -> R.string.join_error_knock
    }

    private fun buttonText(button: JoinButton): Int? = when (button) {
        JoinButton.JOIN -> R.string.join_button_join
        JoinButton.ASK -> R.string.join_button_ask
        JoinButton.OPEN -> R.string.join_button_open
        JoinButton.NONE -> null
    }

    private fun noteText(note: JoinNote): Int = when (note) {
        JoinNote.KNOCK_ROOM -> R.string.join_note_knock
        JoinNote.INVITE_ONLY -> R.string.join_note_invite_only
        JoinNote.NEEDS_INVITE -> R.string.join_note_needs_invite
        JoinNote.INVITED -> R.string.join_note_invited
        JoinNote.REQUEST_SENT -> R.string.join_note_request_sent
        JoinNote.ALREADY_JOINED -> R.string.join_note_joined
        JoinNote.BANNED -> R.string.join_note_banned
        JoinNote.BAD_LINK -> R.string.join_note_bad_link
        JoinNote.NO_PREVIEW -> R.string.join_note_no_preview
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
