package org.matchat.feature.settings

import android.Manifest
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
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
        b.settingsPhoneContacts.setOnClickListener { viewModel.onAction(SettingsAction.TogglePhoneContacts) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.onAction(SettingsAction.Refresh)
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
        b.settingsPhoneContacts.text = getString(
            if (state.phoneContactsOn) R.string.settings_phone_contacts_on else R.string.settings_phone_contacts_off,
        )
        b.settingsPhoneContactsCaption.isVisible = state.phoneContactsRefused
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
            SettingsNav.RequestContactsPermission ->
                contactsPermission.launch(CONTACTS_PERMISSIONS)
        }
    }

    /** "Save contacts to phone": one system dialog for the contacts group. */
    private val contactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.isNotEmpty() && result.values.all { it }
        viewModel.onAction(SettingsAction.PhoneContactsPermissionResult(granted))
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
        val CONTACTS_PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    }
}
