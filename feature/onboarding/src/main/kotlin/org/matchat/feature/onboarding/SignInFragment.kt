package org.matchat.feature.onboarding

import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.model.ErrorText
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.onboarding.databinding.FragmentSignInBinding

/** S3 Sign in (password). */
@AndroidEntryPoint
class SignInFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_sign_in
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_options)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: SignInViewModel by viewModels()
    private var binding: FragmentSignInBinding? = null
    private val navigator: Navigator get() = requireActivity() as Navigator

    override fun onContentViewCreated(content: View) {
        val b = FragmentSignInBinding.bind(content)
        binding = b
        setTitle(getString(R.string.signin_title))
        b.signInButton.setOnClickListener { submit() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.navEvents.collect { navigator.toRoomListRoot() } }
            }
        }
        FocusEngine.requestInitialFocus(b.username)
    }

    private fun submit() {
        val b = binding ?: return
        viewModel.onAction(
            SignInAction.Submit(b.username.text.toString(), b.password.text.toString()),
        )
    }

    private fun render(state: SignInState) {
        val b = binding ?: return
        val lock = if (state.homeserverPinned) "🔒 " else ""
        b.homeserverRow.text = getString(R.string.signin_homeserver, lock + state.homeserver)
        b.signInButton.isEnabled = !state.isSubmitting
        b.error.isVisible = state.error != null
        state.error?.let { b.error.text = messageFor(it) }
    }

    private fun messageFor(error: ErrorText): CharSequence = when (error.key) {
        ErrorText.Key.NETWORK -> getString(R.string.signin_error_network)
        else -> getString(R.string.signin_error_credentials)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
