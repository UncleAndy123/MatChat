package org.matchat.feature.onboarding

import android.view.View
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
import org.matchat.feature.onboarding.databinding.FragmentWelcomeBinding

/** S2 Welcome. Top-level onboarding screen: RIGHT is Exit. */
@AndroidEntryPoint
class WelcomeFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_welcome
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_options)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)
    override val rightLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_exit)

    private val viewModel: WelcomeViewModel by viewModels()
    private var binding: FragmentWelcomeBinding? = null
    private val navigator: Navigator get() = requireActivity() as Navigator

    override fun onContentViewCreated(content: View) {
        val b = FragmentWelcomeBinding.bind(content)
        binding = b
        setTitle(getString(org.matchat.core.ui.R.string.softkey_blank))
        b.welcomeQr.setOnClickListener { viewModel.onAction(WelcomeAction.SignInWithQr) }
        b.welcomePassword.setOnClickListener { viewModel.onAction(WelcomeAction.SignInWithPassword) }
        b.welcomeHelp.setOnClickListener { viewModel.onAction(WelcomeAction.Help) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.navEvents.collect(::navigate) }
            }
        }
    }

    private fun render(state: WelcomeState) {
        val b = binding ?: return
        b.welcomeQr.isVisible = state.qrEnabled
        if (b.root.findFocus() == null) {
            FocusEngine.requestInitialFocus(if (state.qrEnabled) b.welcomeQr else b.welcomePassword)
        }
    }

    private fun navigate(nav: WelcomeNav) {
        when (nav) {
            // QR (S4) is a v1.1 candidate; both routes go to password sign-in in v1.
            WelcomeNav.Password, WelcomeNav.Qr -> navigator.toSignIn()
            WelcomeNav.Help -> navigator.toHelp()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
