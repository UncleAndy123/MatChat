package org.matchat.feature.newchat

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.ui.contacts.PhoneContactIntents
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.menu.MenuItem
import org.matchat.core.ui.menu.MenuSheet
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.core.ui.theme.themeColor
import org.matchat.core.ui.theme.themeDimenPx
import org.matchat.feature.newchat.databinding.FragmentNewChatBinding
import org.matchat.core.ui.R as UiR

/** S20 Contacts. Each row is a focus stop; CENTER on a person opens their
 *  menu (Send message, …), CENTER on "Type an address" messages someone new.
 *  LEFT is blank — the menu is on CENTER (UX-SPEC S20). */
@AndroidEntryPoint
class NewChatFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_new_chat
    override val leftLabel: CharSequence get() = getString(UiR.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: NewChatViewModel by viewModels()
    private var binding: FragmentNewChatBinding? = null
    private val navigator: Navigator get() = requireActivity() as Navigator
    private var state = NewChatState()

    // The state last drawn: rows are rebuilt only when it changes, so an
    // unrelated re-emission never moves focus back to the first row.
    private var drawnState: NewChatState? = null

    /** "Add to a phone contact…" with the contacts permission: the phone's own
     *  contact picker; the ViewModel adds the Matrix address to the one picked. */
    private val pickPhoneContact = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickContact(),
    ) { uri -> viewModel.onAction(NewChatAction.PhoneContactPicked(uri?.toString())) }

    override fun onContentViewCreated(content: View) {
        val b = FragmentNewChatBinding.bind(content)
        binding = b
        setTitle(getString(R.string.newchat_title))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.navEvents.collect(::navigate) }
            }
        }
    }

    private fun render(newState: NewChatState) {
        state = newState
        val container = binding?.newchatContainer ?: return
        if (newState == drawnState) return
        drawnState = newState
        container.removeAllViews()

        if (newState.contacts.isNotEmpty()) {
            container.addView(header(getString(R.string.newchat_section_contacts)))
            newState.contacts.forEach { container.addView(contactRow(it)) }
        }
        if (newState.recents.isNotEmpty()) {
            container.addView(header(getString(R.string.newchat_section_recent)))
            newState.recents.forEach { container.addView(contactRow(it)) }
        }
        if (!newState.isLoading && !newState.hasSavedContacts) {
            container.addView(header(getString(R.string.newchat_empty)))
        }
        // Messaging someone new is hidden when policy turns direct chat off.
        val typeRow = if (newState.canMessage) {
            actionRow(getString(R.string.newchat_type_address)) { viewModel.onAction(NewChatAction.TypeAnAddress) }
                .also { container.addView(it) }
        } else {
            null
        }

        if (container.findFocus() == null) {
            val first = (0 until container.childCount).map(container::getChildAt).firstOrNull { it.isFocusable }
            (first ?: typeRow)?.let { FocusEngine.requestInitialFocus(it) }
        }
    }

    private fun navigate(nav: NewChatNav) {
        when (nav) {
            is NewChatNav.OpenRoom -> navigator.toRoom(nav.roomId)
            NewChatNav.TypeAddress -> navigator.toTypeAddress()
            NewChatNav.PickPhoneContact ->
                runCatching { pickPhoneContact.launch(null) }
                    .onFailure { viewModel.onAction(NewChatAction.PhoneContactsUnavailable) }
            is NewChatNav.EditPhoneContact ->
                runCatching { startActivity(PhoneContactIntents.addToExisting(nav.address)) }
                    .onFailure { viewModel.onAction(NewChatAction.PhoneContactsUnavailable) }
            is NewChatNav.Message -> Toast.makeText(requireContext(), messageRes(nav.key), Toast.LENGTH_SHORT).show()
        }
    }

    private fun messageRes(key: NewChatMessage): Int = when (key) {
        NewChatMessage.CONTACT_SAVED -> R.string.newchat_contact_saved
        NewChatMessage.CONTACT_REMOVED -> R.string.newchat_contact_removed
        NewChatMessage.MESSAGE_FAILED -> R.string.newchat_message_failed
        NewChatMessage.PHONE_MERGED -> R.string.newchat_phone_merged
        NewChatMessage.PHONE_MERGE_FAILED -> R.string.newchat_phone_merge_failed
        NewChatMessage.PHONE_CONTACTS_UNAVAILABLE -> R.string.newchat_phone_contacts_unavailable
    }

    /** A person's menu (UX-SPEC S20): Send message (when policy allows), then
     *  for a contact Add to a phone contact… and Remove contact (saved ones
     *  only, not admin-pushed), or for a recent Save contact. */
    private fun personMenu(row: ContactRow) {
        val items = buildList {
            if (state.canMessage) add(MenuItem(OPTION_MESSAGE, getString(R.string.newchat_send_message)))
            if (row.isSaved) {
                add(MenuItem(OPTION_PHONE, getString(R.string.newchat_add_to_phone_contact)))
                if (row.isRemovable) add(MenuItem(OPTION_REMOVE, getString(R.string.newchat_remove_contact)))
            } else {
                add(MenuItem(OPTION_SAVE, getString(R.string.newchat_save_contact)))
            }
        }
        MenuSheet.show(requireContext(), items) { item ->
            when (item.id) {
                OPTION_MESSAGE -> viewModel.onAction(NewChatAction.Message(row.address))
                OPTION_SAVE -> viewModel.onAction(NewChatAction.SaveContact(row.address))
                OPTION_PHONE -> viewModel.onAction(NewChatAction.AddToPhoneContact(row.address))
                OPTION_REMOVE -> viewModel.onAction(NewChatAction.RemoveContact(row.address))
            }
        }
    }

    private fun header(text: String): TextView = TextView(requireContext()).apply {
        this.text = text.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_PX, requireContext().themeDimenPx(UiR.attr.textSizeMeta))
        setTextColor(requireContext().themeColor(UiR.attr.colorTextSecondary))
        isFocusable = false
        val pad = resources.getDimensionPixelSize(UiR.dimen.content_pad)
        setPadding(0, pad, 0, 2)
    }

    private fun contactRow(row: ContactRow): View = actionRow(row.primary, row.secondary) { personMenu(row) }

    private fun actionRow(primary: String, secondary: String = "", onClick: () -> Unit): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = false
            minimumHeight = resources.getDimensionPixelSize(UiR.dimen.row_min_height_compact)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(UiR.drawable.focus_selector)
            setOnClickListener { onClick() }
        }
        layout.addView(
            TextView(requireContext()).apply {
                text = primary
                setTextSize(TypedValue.COMPLEX_UNIT_PX, requireContext().themeDimenPx(UiR.attr.textSizeBody))
                isDuplicateParentStateEnabled = true // inherit row focus → text flips
                setTextColor(requireContext().themeColor(UiR.attr.colorTextOnFocus))
            },
        )
        if (secondary.isNotEmpty()) {
            layout.addView(
                TextView(requireContext()).apply {
                    text = secondary
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, requireContext().themeDimenPx(UiR.attr.textSizeMeta))
                    isDuplicateParentStateEnabled = true
                    setTextColor(requireContext().themeColor(UiR.attr.colorTextMetaOnFocus))
                },
            )
        }
        return layout
    }

    override fun onDestroyView() {
        binding = null
        drawnState = null
        super.onDestroyView()
    }

    private companion object {
        const val OPTION_MESSAGE = "message"
        const val OPTION_SAVE = "save_contact"
        const val OPTION_PHONE = "phone_contact"
        const val OPTION_REMOVE = "remove_contact"
    }
}
