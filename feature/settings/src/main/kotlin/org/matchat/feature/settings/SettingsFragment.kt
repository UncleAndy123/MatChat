package org.matchat.feature.settings

import android.view.View
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
import org.matchat.feature.settings.databinding.FragmentSettingsBinding

/** S13 Settings. */
@AndroidEntryPoint
class SettingsFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_settings
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: SettingsViewModel by viewModels()
    private var binding: FragmentSettingsBinding? = null
    private val navigator: Navigator get() = requireActivity() as Navigator

    override fun onContentViewCreated(content: View) {
        val b = FragmentSettingsBinding.bind(content)
        binding = b
        setTitle(getString(R.string.settings_title))

        b.settingsEncryption.setOnClickListener { viewModel.onAction(SettingsAction.OpenEncryption) }
        b.settingsPolicy.setOnClickListener { viewModel.onAction(SettingsAction.OpenPolicy) }
        b.settingsHelp.setOnClickListener { viewModel.onAction(SettingsAction.OpenHelp) }
        b.settingsSignOut.setOnClickListener { confirmSignOut() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.navEvents.collect(::navigate) }
            }
        }
        FocusEngine.requestInitialFocus(b.settingsNotifications)
    }

    private fun render(state: SettingsState) {
        val b = binding ?: return
        b.settingsPolicy.text = getString(
            if (state.isManaged) R.string.settings_policy_managed else R.string.settings_policy_unmanaged,
        )
    }

    private fun navigate(nav: SettingsNav) {
        when (nav) {
            SettingsNav.Encryption -> navigator.toVerification()
            SettingsNav.Policy -> navigator.toPolicy()
            SettingsNav.Help -> navigator.toHelp()
            SettingsNav.SignedOut -> navigator.toWelcomeRoot()
        }
    }

    private fun confirmSignOut() {
        MenuSheet.show(
            requireContext(),
            listOf(MenuItem(CONFIRM, getString(R.string.settings_sign_out_confirm))),
        ) { viewModel.onAction(SettingsAction.ConfirmSignOut) }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val CONFIRM = "confirm"
    }
}
