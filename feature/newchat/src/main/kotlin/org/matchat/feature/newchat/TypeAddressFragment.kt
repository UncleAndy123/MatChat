package org.matchat.feature.newchat

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
import org.matchat.feature.newchat.databinding.FragmentTypeAddressBinding

/** S21 Type an address, with the confirm step shown in place of the entry group. */
@AndroidEntryPoint
class TypeAddressFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_type_address
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_options)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: TypeAddressViewModel by viewModels()
    private var binding: FragmentTypeAddressBinding? = null
    private val navigator: Navigator get() = requireActivity() as Navigator

    override fun onContentViewCreated(content: View) {
        val b = FragmentTypeAddressBinding.bind(content)
        binding = b
        setTitle(getString(R.string.type_address_title))
        b.addressField.setText(getString(R.string.type_address_prefill))

        b.continueButton.setOnClickListener {
            viewModel.onAction(TypeAddressAction.Continue(b.addressField.text.toString()))
        }
        b.confirmStart.setOnClickListener { viewModel.onAction(TypeAddressAction.Confirm) }
        b.confirmChange.setOnClickListener { viewModel.onAction(TypeAddressAction.Change) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.navEvents.collect { nav ->
                        when (nav) {
                            is TypeAddressNav.OpenRoom -> navigator.toRoom(nav.roomId)
                        }
                    }
                }
            }
        }
        FocusEngine.requestInitialFocus(b.addressField)
    }

    private fun render(state: TypeAddressState) {
        val b = binding ?: return
        val confirming = state.confirm != null
        b.entryGroup.isVisible = !confirming
        b.confirmGroup.isVisible = confirming

        b.typeError.isVisible = state.error != null
        state.error?.let { b.typeError.text = messageFor(it) }

        b.typeBlocked.isVisible = state.blockedDomain != null
        state.blockedDomain?.let {
            b.typeBlocked.text = getString(R.string.type_address_blocked, it)
        }

        state.confirm?.let { target ->
            b.confirmPrompt.text = getString(R.string.type_address_send_to, target.name)
            b.confirmAddress.text = target.address.value
            b.confirmStart.text = getString(
                if (target.unresolved) R.string.type_address_start_anyway else R.string.type_address_start,
            )
            if (b.root.findFocus() == null || !b.confirmStart.isFocused) {
                FocusEngine.requestInitialFocus(b.confirmStart)
            }
        }
    }

    private fun messageFor(error: ErrorText): CharSequence = when (error.key) {
        ErrorText.Key.MALFORMED_ADDRESS -> getString(R.string.type_address_error_malformed)
        ErrorText.Key.ADDRESS_NOT_FOUND -> getString(R.string.type_address_error_not_found)
        else -> getString(R.string.type_address_error_unreachable)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
