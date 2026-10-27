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
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.menu.MenuItem
import org.matchat.core.ui.menu.MenuSheet
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.core.ui.theme.themeDimenPx
import org.matchat.feature.roomlist.databinding.FragmentSpaceBinding

/** S29 Space: a joined space's subspaces and joined rooms, in S8's row shape. */
@AndroidEntryPoint
class SpaceFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_space
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_options)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_open)

    private val viewModel: SpaceViewModel by viewModels()
    private var binding: FragmentSpaceBinding? = null
    private val navigator: Navigator get() = requireActivity() as Navigator
    private var leftGone = false

    private val adapter = RoomListAdapter(
        onOpen = { viewModel.onAction(SpaceAction.OpenRow(it.id)) },
        onFocused = { },
        onAvatarBind = { url, name, id, image ->
            viewLifecycleOwner.lifecycleScope.launch {
                org.matchat.core.ui.media.AvatarBinder.bind(image, url, name, id, avatarMaxPx()) {
                    viewModel.loadAvatar(it)
                }
            }
        },
    )

    override fun onContentViewCreated(content: View) {
        val b = FragmentSpaceBinding.bind(content)
        binding = b
        b.spaceList.layoutManager = LinearLayoutManager(requireContext())
        b.spaceList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.navEvents.collect { nav ->
                        when (nav) {
                            is SpaceNav.Room -> navigator.toRoom(nav.roomId)
                            is SpaceNav.Space -> navigator.toSpace(nav.spaceId)
                            SpaceNav.Back -> navigator.back()
                        }
                    }
                }
            }
        }
    }

    private fun render(state: SpaceState) {
        val b = binding ?: return
        // Left on another device (or here): the folder no longer exists.
        if (state.isGone && !leftGone) {
            leftGone = true
            navigator.back()
            return
        }
        setTitle(state.title)
        b.spaceError.isVisible = state.leaveFailed
        b.spaceEmpty.isVisible = state.isEmpty
        b.spaceList.isVisible = !state.isEmpty
        adapter.submitList(state.rows)
        if (b.root.findFocus() == null && state.rows.isNotEmpty()) FocusEngine.requestInitialFocus(b.spaceList)
    }

    override fun onOptions(): Boolean {
        MenuSheet.show(
            requireContext(),
            listOf(MenuItem(OPT_LEAVE, getString(R.string.space_opt_leave))),
        ) {
            // Confirm, like Room info's Leave: a second, explicit choice.
            MenuSheet.show(
                requireContext(),
                listOf(MenuItem(OPT_LEAVE, getString(R.string.space_leave_confirm))),
            ) { viewModel.onAction(SpaceAction.Leave) }
        }
        return true
    }

    override fun onDestroyView() {
        binding?.spaceList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun avatarMaxPx(): Int =
        (requireContext().themeDimenPx(org.matchat.core.ui.R.attr.avatarSizeList) * 2).toInt()

    private companion object {
        const val OPT_LEAVE = "leave"
    }
}
