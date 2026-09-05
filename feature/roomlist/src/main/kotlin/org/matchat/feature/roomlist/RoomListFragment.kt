package org.matchat.feature.roomlist

import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.model.SyncState
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.menu.MenuItem
import org.matchat.core.ui.menu.MenuSheet
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.roomlist.databinding.FragmentRoomListBinding

/**
 * S8 — the home screen. Renders [RoomListState] and emits [RoomListAction];
 * contains no logic beyond render() (AGENTS.md §3). It never imports :core:matrix.
 */
@AndroidEntryPoint
class RoomListFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_room_list
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_options)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_open)
    // Room list is top level: RIGHT is Exit, not Back (UX-SPEC S8).
    override val rightLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_exit)

    private val viewModel: RoomListViewModel by viewModels()
    private var binding: FragmentRoomListBinding? = null
    private val navigator: Navigator get() = requireActivity() as Navigator

    private val adapter = RoomListAdapter(
        onOpen = { viewModel.onAction(RoomListAction.OpenRoom(it.id)) },
        onFocused = { viewModel.onAction(RoomListAction.RoomFocused(it)) },
    )

    override fun onContentViewCreated(content: View) {
        val b = FragmentRoomListBinding.bind(content)
        binding = b
        b.roomList.layoutManager = LinearLayoutManager(requireContext())
        b.roomList.adapter = adapter
        b.inviteBand.setOnClickListener { viewModel.onAction(RoomListAction.OpenInvites) }

        setTitle(getString(R.string.roomlist_title))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.navEvents.collect(::navigate) }
            }
        }
    }

    private fun render(state: RoomListState) {
        val b = binding ?: return
        setSyncGlyph(if (state.isOffline) SyncState.OFFLINE else SyncState.IDLE)

        b.offlineBanner.isVisible = state.isOffline
        b.inviteBand.isVisible = state.inviteBand != null
        state.inviteBand?.let {
            b.inviteBand.text = resources.getQuantityString(
                R.plurals.roomlist_invitations, it.count, it.count,
            )
        }

        b.emptyView.isVisible = state.isEmpty
        b.roomList.isVisible = !state.isEmpty
        adapter.submitList(state.rooms)

        // Deterministic initial focus: invitation band, else first room (S8).
        if (b.root.findFocus() == null) {
            val target = if (state.inviteBand != null) b.inviteBand else b.roomList
            FocusEngine.requestInitialFocus(target)
        }
    }

    private fun navigate(nav: RoomListNav) {
        when (nav) {
            is RoomListNav.Room -> navigator.toRoom(nav.roomId)
            RoomListNav.Invites -> navigator.toInvites()
            RoomListNav.NewChat -> navigator.toNewChat()
            RoomListNav.Settings -> navigator.toSettings()
            RoomListNav.Help -> navigator.toHelp()
            RoomListNav.SignOut -> navigator.toSettings() // sign-out confirm lives in Settings (S13)
        }
    }

    override fun onOptions(): Boolean {
        val items = buildList {
            if (viewModel.state.value.newMessageEnabled) {
                add(MenuItem(OPT_NEW, getString(R.string.roomlist_opt_new_message)))
            }
            add(MenuItem(OPT_READ, getString(R.string.roomlist_opt_mark_read)))
            add(MenuItem(OPT_SETTINGS, getString(R.string.roomlist_opt_settings)))
            add(MenuItem(OPT_HELP, getString(R.string.roomlist_opt_help)))
            add(MenuItem(OPT_SIGNOUT, getString(R.string.roomlist_opt_sign_out)))
        }
        MenuSheet.show(requireContext(), items) { selected ->
            viewModel.onAction(
                when (selected.id) {
                    OPT_NEW -> RoomListAction.NewMessage
                    OPT_READ -> RoomListAction.MarkAllRead
                    OPT_SETTINGS -> RoomListAction.OpenSettings
                    OPT_HELP -> RoomListAction.OpenHelp
                    else -> RoomListAction.SignOut
                },
            )
        }
        return true
    }

    override fun onDestroyView() {
        binding?.roomList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val OPT_NEW = "new"
        const val OPT_READ = "read"
        const val OPT_SETTINGS = "settings"
        const val OPT_HELP = "help"
        const val OPT_SIGNOUT = "signout"
    }
}
