package org.matchat.feature.settings

import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.settings.databinding.FragmentPolicyBinding

/** S23 Policy — read-only. No actionable focus; LEFT and CENTER are blank. */
@AndroidEntryPoint
class PolicyFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_policy
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)

    private val viewModel: PolicyViewModel by viewModels()
    private var binding: FragmentPolicyBinding? = null

    override fun onContentViewCreated(content: View) {
        val b = FragmentPolicyBinding.bind(content)
        binding = b
        setTitle(getString(R.string.policy_title))
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: PolicyState) {
        val b = binding ?: return
        b.policyState.text = getString(
            if (state.isManaged) R.string.policy_managed else R.string.policy_unmanaged,
        )
        b.policyStateSub.text = getString(
            if (state.isManaged) R.string.policy_managed_sub else R.string.policy_unmanaged_sub,
        )
        b.policyHomeserver.text = state.homeserver.ifEmpty { getString(R.string.policy_homeserver_none) }
        b.policyAllowed.text = state.allowedServers
            ?.joinToString("\n")
            ?: getString(R.string.policy_all_allowed)
        b.policyDirect.text = getString(
            if (state.directChatAllowed) R.string.policy_direct_allowed else R.string.policy_direct_blocked,
        )
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
