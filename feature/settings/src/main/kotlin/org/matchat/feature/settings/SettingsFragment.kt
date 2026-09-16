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
        b.settingsTextSize.setOnClickListener { viewModel.onAction(SettingsAction.OpenTextSize) }
        b.settingsTheme.setOnClickListener { viewModel.onAction(SettingsAction.OpenTheme) }
        b.settingsAdvanced.setOnClickListener { viewModel.onAction(SettingsAction.OpenAdvanced) }
        b.settingsNotifications.setOnClickListener { viewModel.onAction(SettingsAction.OpenNotifications) }
        b.settingsPolicy.setOnClickListener { viewModel.onAction(SettingsAction.OpenPolicy) }
        b.settingsUpdate.setOnClickListener { viewModel.onAction(SettingsAction.OpenUpdate) }
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
        b.settingsUpdate.text = getString(
            if (state.updateAvailable) R.string.settings_update_available else R.string.settings_update,
        )
    }

    private fun navigate(nav: SettingsNav) {
        when (nav) {
            SettingsNav.Encryption -> navigator.toVerification()
            SettingsNav.TextSize -> navigator.toTextSize()
            SettingsNav.Theme -> navigator.toTheme()
            SettingsNav.Advanced -> navigator.toAdvanced()
            SettingsNav.Notifications -> navigator.toNotifications()
            SettingsNav.Policy -> navigator.toPolicy()
            SettingsNav.Update -> navigator.toUpdate()
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
