package org.matchat.feature.verification

import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.verification.databinding.FragmentVerificationBinding

/** S7 recovery-key entry. RIGHT reads Cancel (still Back semantics). */
@AndroidEntryPoint
class VerificationFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_verification
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)
    override val rightLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_cancel)

    private val viewModel: VerificationViewModel by viewModels()
    private var binding: FragmentVerificationBinding? = null
    private val navigator: Navigator get() = requireActivity() as Navigator

    override fun onContentViewCreated(content: View) {
        val b = FragmentVerificationBinding.bind(content)
        binding = b
        setTitle(getString(R.string.verify_title))
        b.verifyButton.setOnClickListener {
            viewModel.onAction(VerificationAction.Submit(b.recoveryKey.text.toString()))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.navEvents.collect {
                        Toast.makeText(requireContext(), R.string.verify_done, Toast.LENGTH_SHORT).show()
                        navigator.back()
                    }
                }
            }
        }
        FocusEngine.requestInitialFocus(b.recoveryKey)
    }

    private fun render(state: VerificationState) {
        val b = binding ?: return
        b.verifyButton.isEnabled = !state.isSubmitting
        b.verifyError.isVisible = state.error != null
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
