package org.matchat.feature.onboarding

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
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.onboarding.databinding.FragmentContactsPromptBinding

/** S30 "Save contacts to this phone?" (UX-SPEC §S30). One focusable row asks
 *  Android for the contacts permission; RIGHT (Back) is "not now". Contacts
 *  are saved in MatChat either way — the permission only adds a phone copy. */
@AndroidEntryPoint
class ContactsPromptFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_contacts_prompt
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: ContactsPromptViewModel by viewModels()
    private var binding: FragmentContactsPromptBinding? = null

    private val contactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.isNotEmpty() && result.values.all { it }
        viewModel.onAction(ContactsPromptAction.PermissionResult(granted))
    }

    override fun onContentViewCreated(content: View) {
        val b = FragmentContactsPromptBinding.bind(content)
        binding = b
        setTitle(getString(R.string.contacts_prompt_title))
        b.contactsPromptSave.setOnClickListener { viewModel.onAction(ContactsPromptAction.SaveToPhone) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.navEvents.collect(::navigate) }
            }
        }
        FocusEngine.requestInitialFocus(b.contactsPromptSave)
    }

    private fun render(state: ContactsPromptState) {
        binding?.contactsPromptStatus?.isVisible = state.refused
    }

    private fun navigate(nav: ContactsPromptNav) {
        when (nav) {
            ContactsPromptNav.RequestPermission ->
                contactsPermission.launch(CONTACTS_PERMISSIONS)
            ContactsPromptNav.Done -> onBack()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private companion object {
        val CONTACTS_PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    }
}
