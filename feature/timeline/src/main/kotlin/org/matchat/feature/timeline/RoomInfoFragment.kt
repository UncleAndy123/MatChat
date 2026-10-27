package org.matchat.feature.timeline

import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
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
import org.matchat.core.ui.menu.MenuItem
import org.matchat.core.ui.menu.MenuSheet
import org.matchat.core.ui.menu.TextPromptSheet
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.theme.themeDimenPx

/** S12 Room Info + basic edits. Everything is in the focusable list (fields open a
 *  text prompt on CENTER, members open a remove menu, actions add/leave). */
@AndroidEntryPoint
class RoomInfoFragment : org.matchat.core.ui.softkey.SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_room_info
    override val leftLabel: CharSequence get() = ""
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: RoomInfoViewModel by viewModels()
    private val navigator: Navigator get() = requireActivity() as Navigator
    private var list: RecyclerView? = null

    private val adapter = RoomInfoAdapter(
        onFieldActivated = { editField(it) },
        onMemberActivated = { memberMenu(it) },
        onActionActivated = { onAction(it) },
        onSoundActivated = { viewModel.openSoundPicker() },
        onAvatarBind = { url, name, id, image -> loadAvatarInto(url, name, id, image) },
    )

    /** Avatars round: same shared AvatarBinder/AvatarCache path (core/ui)
     *  the room list and timeline use — this Fragment only supplies the
     *  byte fetch. name/id are the no-avatar-fallback's color+initial
     *  source (AvatarFallback round). */
    private fun loadAvatarInto(url: String?, name: String, id: String, image: android.widget.ImageView) {
        viewLifecycleOwner.lifecycleScope.launch {
            org.matchat.core.ui.media.AvatarBinder.bind(
                image,
                url,
                name,
                id,
                avatarMaxPx(),
            ) { viewModel.loadAvatar(it) }
        }
    }

    override fun onContentViewCreated(content: View) {
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

    private fun render(state: RoomInfoState) {
        setTitle(state.title.ifBlank { getString(R.string.roominfo_title) })
        adapter.submitList(state.rows)
    }

    private fun onNav(nav: RoomInfoNav) {
        when (nav) {
            RoomInfoNav.Left -> navigator.toRoomListRoot()
            is RoomInfoNav.Toast ->
                Toast.makeText(requireContext(), toastText(nav.key), Toast.LENGTH_SHORT).show()
            RoomInfoNav.RequestStoragePermission ->
                storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            is RoomInfoNav.OpenSoundPicker -> launchSoundPicker(nav.currentUri)
            RoomInfoNav.PickPhoneContact ->
                runCatching { pickPhoneContact.launch(null) }.onFailure { viewModel.onPhoneContactsUnavailable() }
            is RoomInfoNav.EditPhoneContact ->
                runCatching { startActivity(PhoneContactIntents.addToExisting(nav.address)) }
                    .onFailure { viewModel.onPhoneContactsUnavailable() }
        }
    }

    private fun toastText(key: ToastKey): Int = when (key) {
        ToastKey.BAD_ADDRESS -> R.string.roominfo_bad_address
        ToastKey.EDIT_FAILED -> R.string.roominfo_edit_failed
        ToastKey.SOUNDS_NEED_ACCESS -> R.string.roominfo_sounds_need_access
        ToastKey.CONTACT_SAVED -> R.string.roominfo_contact_saved
        ToastKey.CONTACT_REMOVED -> R.string.roominfo_contact_removed
        ToastKey.PHONE_MERGED -> R.string.roominfo_phone_merged
        ToastKey.PHONE_MERGE_FAILED -> R.string.roominfo_phone_merge_failed
        ToastKey.PHONE_CONTACTS_UNAVAILABLE -> R.string.roominfo_phone_contacts_unavailable
        ToastKey.MESSAGE_FAILED -> R.string.msginfo_message_failed
    }

    /** "Add to a phone contact…" with the contacts permission: the phone's own
     *  contact picker; the ViewModel adds the Matrix address to the one picked. */
    private val pickPhoneContact = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickContact(),
    ) { uri -> viewModel.onPhoneContactPicked(uri?.toString()) }

    /** Android 7–9 only: storage access to copy MatChat's bundled sounds out
     *  (docs/SOUNDS.md) before the picker opens. */
    private val storagePermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onStoragePermissionResult(granted) }

    private val pickSound = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        // Best-effort persistable grant, as in NotificationsFragment: most
        // MediaStore sound URIs need none, and notify() survives without it.
        picked?.let {
            runCatching {
                requireContext().contentResolver
                    .takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        viewModel.onSoundPicked(
            pickedUri = picked?.toString(),
            cancelled = result.resultCode != android.app.Activity.RESULT_OK,
            systemDefaultUri = Settings.System.DEFAULT_NOTIFICATION_URI.toString(),
        )
    }

    /** The system sound picker for this room. Its "Default" entry means the
     *  app's own sound (Settings > Notifications), not the phone's. */
    private fun launchSoundPicker(currentUri: String?) {
        val intent = android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.roominfo_sound_caption))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_NOTIFICATION_URI)
            val existing = when (currentUri) {
                null -> Settings.System.DEFAULT_NOTIFICATION_URI
                org.matchat.core.model.notify.SILENT_SOUND -> null
                else -> runCatching { Uri.parse(currentUri) }.getOrNull()
            }
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { pickSound.launch(intent) }
    }

    private fun editField(field: RoomInfoRow.Field) {
        TextPromptSheet.show(requireContext(), field.label, field.value) { text ->
            when (field.key) {
                RoomInfoViewModel.KEY_NAME -> viewModel.setName(text)
                RoomInfoViewModel.KEY_TOPIC -> viewModel.setTopic(text)
            }
        }
    }

    private fun onAction(action: RoomInfoRow.Action) {
        when (action.key) {
            RoomInfoViewModel.KEY_PINNED -> navigator.toPinnedMessages(viewModel.roomId())
            RoomInfoViewModel.KEY_ADD ->
                TextPromptSheet.show(requireContext(), getString(R.string.roominfo_add_member), "@") {
                    viewModel.addMember(it)
                }
            RoomInfoViewModel.KEY_LEAVE -> confirmLeave()
        }
    }

    private fun confirmLeave() {
        MenuSheet.show(
            requireContext(),
            listOf(MenuItem(RoomInfoViewModel.KEY_LEAVE, getString(R.string.roominfo_leave_confirm))),
        ) { viewModel.leave() }
    }

    /** S12 member menu: save / remove as a contact, add to a phone contact,
     *  remove from the room (UX-SPEC S12). */
    private fun memberMenu(member: RoomInfoRow.Member) {
        if (member.isSelf) return
        val contactItem = if (member.isSaved) {
            MenuItem(MEMBER_UNSAVE, getString(R.string.roominfo_remove_contact))
        } else {
            MenuItem(MEMBER_SAVE, getString(R.string.roominfo_save_contact))
        }
        MenuSheet.show(
            requireContext(),
            listOf(
                contactItem,
                MenuItem(MEMBER_PHONE, getString(R.string.roominfo_add_to_phone_contact)),
                MenuItem(MEMBER_REMOVE, getString(R.string.roominfo_remove_member)),
            ),
        ) { item ->
            when (item.id) {
                MEMBER_SAVE -> viewModel.saveContact(member)
                MEMBER_UNSAVE -> viewModel.removeContact(member.userId)
                MEMBER_PHONE -> viewModel.addToPhoneContact(member.userId)
                MEMBER_REMOVE -> viewModel.removeMember(member.userId)
            }
        }
    }

    override fun onDestroyView() {
        list?.adapter = null
        list = null
        super.onDestroyView()
    }

    /** Decode-quality cap, ~2x avatarSizeSender — a compile-time literal can't
     *  respond to the runtime Text size choice, so this is computed from the
     *  theme attr at bind time instead of a const. */
    private fun avatarMaxPx(): Int =
        (requireContext().themeDimenPx(org.matchat.core.ui.R.attr.avatarSizeSender) * 2).toInt()

    private companion object {
        const val MEMBER_REMOVE = "remove"
        const val MEMBER_SAVE = "save_contact"
        const val MEMBER_UNSAVE = "remove_contact"
        const val MEMBER_PHONE = "phone_contact"
    }
}
