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
import org.matchat.core.ui.menu.MenuItem
import org.matchat.core.ui.menu.MenuSheet
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.invites.databinding.FragmentInviteDetailBinding

/** S19 Invitation detail. Accept / Decline; Options → Decline and ignore. */
@AndroidEntryPoint
class InviteDetailFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_invite_detail
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_options)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: InviteDetailViewModel by viewModels()
    private var binding: FragmentInviteDetailBinding? = null
    private val navigator: Navigator get() = requireActivity() as Navigator

    override fun onContentViewCreated(content: View) {
        val b = FragmentInviteDetailBinding.bind(content)
        binding = b
        setTitle(getString(R.string.invite_detail_title))
        b.detailAccept.setOnClickListener { viewModel.onAction(InviteDetailAction.Accept) }
        b.detailDecline.setOnClickListener { viewModel.onAction(InviteDetailAction.Decline) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.navEvents.collect { navigator.back() } }
            }
        }
    }

    private fun render(state: InviteDetailState) {
        val b = binding ?: return
        b.detailName.text = state.name
        b.detailInvitedBy.text = getString(R.string.invite_invited_by, state.invitedBy)
        b.detailAddress.text = state.address
        b.detailEncryption.text = getString(
            if (state.isEncrypted) R.string.invite_encrypted else R.string.invite_unencrypted,
        )
        // Blocked by policy: no Accept, explanation shown, focus starts on Decline.
        b.detailAccept.isVisible = !state.blocked
        b.detailBlocked.isVisible = state.blocked
        if (state.blocked) {
            b.detailBlocked.text = getString(R.string.invite_blocked, state.blockedDomain)
        }
        b.detailError.isVisible = state.error != null
        state.error?.let { b.detailError.text = getString(R.string.invite_error_gone) }

        if (b.root.findFocus() == null) {
            FocusEngine.requestInitialFocus(if (state.blocked) b.detailDecline else b.detailAccept)
        }
    }

    override fun onOptions(): Boolean {
        MenuSheet.show(
            requireContext(),
            listOf(MenuItem(OPT_IGNORE, getString(R.string.invite_decline_ignore))),
        ) { viewModel.onAction(InviteDetailAction.DeclineAndIgnore) }
        return true
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val OPT_IGNORE = "ignore"
    }
}
