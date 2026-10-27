package org.matchat.feature.timeline

import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.ui.contacts.PhoneContactIntents
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.SoftkeyFragment

/** S11 Message info: the sender (name + address), when it was sent, who
 *  reacted, and — merged in from the old profile screen — Send message,
 *  Save contact / Remove from contacts and Add to a phone contact…. All of it
 *  comes from [MessageInfoViewModel]'s state; this only adds the labels. */
@AndroidEntryPoint
class MessageInfoFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_room_info
    override val leftLabel: CharSequence get() = ""
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: MessageInfoViewModel by viewModels()
    private val navigator: Navigator get() = requireActivity() as Navigator
    private var list: RecyclerView? = null

    private val adapter = RoomInfoAdapter(
        onFieldActivated = {},
        onMemberActivated = {},
        onActionActivated = { action ->
            when (action.key) {
                ACTION_MESSAGE -> viewModel.sendMessage()
                ACTION_SAVE -> viewModel.saveContact()
                ACTION_UNSAVE -> viewModel.removeContact()
                ACTION_PHONE -> viewModel.addToPhoneContact()
            }
        },
    )

    /** "Add to a phone contact…" with the contacts permission: the phone's own
     *  contact picker; the ViewModel adds the Matrix address to the one picked. */
    private val pickPhoneContact = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickContact(),
    ) { uri -> viewModel.onPhoneContactPicked(uri?.toString()) }

    override fun onContentViewCreated(content: View) {
        setTitle(getString(R.string.msginfo_title))
        val rv = content.findViewById<RecyclerView>(R.id.roominfo_list)
        list = rv
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.navEvents.collect(::onNav) }
            }
        }
        FocusEngine.requestInitialFocus(rv)
    }

    private fun render(state: MessageInfoState) {
        adapter.submitList(
            buildList {
                add(RoomInfoRow.Info(getString(R.string.msginfo_sent_by), state.senderName))
                add(RoomInfoRow.Info(getString(R.string.msginfo_address), state.senderAddress))
                add(RoomInfoRow.Info(getString(R.string.msginfo_sent), state.sent))
                add(RoomInfoRow.Info(getString(R.string.msginfo_event_id), state.eventId))
                if (state.reactions.isNotEmpty()) {
                    add(RoomInfoRow.Section(getString(R.string.msginfo_reactions)))
                    state.reactions.forEach { r -> add(RoomInfoRow.Info(r.key, r.senderNames.joinToString(", "))) }
                }
                if (state.canMessage) {
                    add(RoomInfoRow.Action(ACTION_MESSAGE, getString(R.string.msginfo_send_message)))
                }
                if (state.showContactActions) {
                    add(
                        if (state.isSaved) {
                            RoomInfoRow.Action(ACTION_UNSAVE, getString(R.string.roominfo_remove_contact))
                        } else {
                            RoomInfoRow.Action(ACTION_SAVE, getString(R.string.roominfo_save_contact))
                        },
                    )
                    add(RoomInfoRow.Action(ACTION_PHONE, getString(R.string.roominfo_add_to_phone_contact)))
                }
            },
        )
    }

    private fun onNav(nav: MessageInfoNav) {
        when (nav) {
            is MessageInfoNav.OpenRoom -> navigator.toRoom(nav.roomId)
            is MessageInfoNav.Toast -> {
                val res = when (nav.key) {
                    ToastKey.CONTACT_SAVED -> R.string.roominfo_contact_saved
                    ToastKey.CONTACT_REMOVED -> R.string.roominfo_contact_removed
                    ToastKey.PHONE_MERGED -> R.string.roominfo_phone_merged
                    ToastKey.PHONE_MERGE_FAILED -> R.string.roominfo_phone_merge_failed
                    ToastKey.PHONE_CONTACTS_UNAVAILABLE -> R.string.roominfo_phone_contacts_unavailable
                    ToastKey.MESSAGE_FAILED -> R.string.msginfo_message_failed
                    // Room info's own edits; never sent from this screen.
                    ToastKey.BAD_ADDRESS, ToastKey.EDIT_FAILED, ToastKey.SOUNDS_NEED_ACCESS -> return
                }
                Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
            }
            MessageInfoNav.PickPhoneContact ->
                runCatching { pickPhoneContact.launch(null) }.onFailure { viewModel.onPhoneContactsUnavailable() }
            is MessageInfoNav.EditPhoneContact ->
                runCatching { startActivity(PhoneContactIntents.addToExisting(nav.address)) }
                    .onFailure { viewModel.onPhoneContactsUnavailable() }
        }
    }

    override fun onDestroyView() {
        list?.adapter = null
        list = null
        super.onDestroyView()
    }

    private companion object {
        const val ACTION_MESSAGE = "message"
        const val ACTION_SAVE = "save_contact"
        const val ACTION_UNSAVE = "remove_contact"
        const val ACTION_PHONE = "phone_contact"
    }
}
