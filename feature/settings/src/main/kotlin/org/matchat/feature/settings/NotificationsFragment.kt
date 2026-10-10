package org.matchat.feature.settings

import android.app.Activity
import android.media.RingtoneManager
import android.net.Uri
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
import org.matchat.feature.settings.databinding.FragmentNotificationsBinding

/** Settings > Notifications: a toggle for the incoming-message notification,
 *  and a Sound row that launches the system ringtone picker (same shape as
 *  AdvancedFragment's single toggle row, plus one picker-launching row). */
@AndroidEntryPoint
class NotificationsFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_notifications
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: NotificationsViewModel by viewModels()
    private var binding: FragmentNotificationsBinding? = null

    private val pickRingtone = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pickedUri = result.data
            ?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        // Crash fix, defense-in-depth: a custom sound's content:// URI can
        // outlive whatever transient read grant the picker handed us (the
        // on-device crash this fixes was exactly that, at notify() time,
        // later). Asking for a persistable grant now is best-effort — most
        // ringtone/notification MediaStore URIs don't need or support one,
        // so a failure here is expected and harmless; MessageNotifier.show()
        // no longer crashes even if this doesn't help for a given URI.
        pickedUri?.let {
            runCatching {
                requireContext().contentResolver
                    .takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val uri = viewModel.ringtonePickResultToUri(
            pickedUri,
            wasCancelled = result.resultCode != Activity.RESULT_OK,
        )
        viewModel.onAction(NotificationsAction.SelectSound(uri))
    }

    override fun onContentViewCreated(content: View) {
        val b = FragmentNotificationsBinding.bind(content)
        binding = b
        setTitle(getString(R.string.notifications_title))

        b.notificationsEnabled.setOnClickListener {
            viewModel.onAction(NotificationsAction.ToggleEnabled)
        }
        b.notificationsSound.setOnClickListener { viewModel.onAction(NotificationsAction.OpenSoundPicker) }
        b.notificationsCoverHidden.setOnClickListener {
            viewModel.onAction(NotificationsAction.ToggleCoverMessageHidden)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.navEvents.collect(::navigate) }
            }
        }
        FocusEngine.requestInitialFocus(b.notificationsEnabled)
    }

    /** Android 7–9 only: storage access to copy MatChat's bundled sounds into
     *  the phone's Notifications folder before the picker opens. */
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onAction(NotificationsAction.StoragePermissionResult(granted)) }

    private fun navigate(nav: NotificationsNav) {
        when (nav) {
            NotificationsNav.RequestStoragePermission ->
                storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            NotificationsNav.OpenPicker -> launchPicker()
        }
    }

    private fun launchPicker() {
        val intent = android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            )
            val current = viewModel.currentSoundUri()
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                current?.let { runCatching { Uri.parse(it) }.getOrNull() },
            )
            // Best-effort: ask for a grant on whatever URI comes back (see the
            // pickRingtone callback's takePersistableUriPermission attempt).
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pickRingtone.launch(intent)
    }

    private fun render(state: NotificationsState) {
        val b = binding ?: return
        val label = getString(R.string.notifications_enabled)
        b.notificationsEnabled.text =
            if (state.enabled) getString(R.string.theme_row_selected_format, label) else label
        b.notificationsSoundSub.text = soundLabel(state.sound)
        b.notificationsBundledAccess.isVisible = state.bundledSoundsNeedAccess
        val coverLabel = getString(R.string.notifications_cover_hidden)
        b.notificationsCoverHidden.text =
            if (state.coverMessageHidden) getString(R.string.theme_row_selected_format, coverLabel) else coverLabel
    }

    /** Resolving a Custom choice's display name needs RingtoneManager +
     *  Context, which is why this lives here and not in the ViewModel (this
     *  codebase keeps ViewModels Android-API-free; see render()'s doc
     *  convention on every other settings screen). */
    private fun soundLabel(sound: SoundChoice): String = when (sound) {
        SoundChoice.Default -> getString(R.string.notifications_sound_default)
        SoundChoice.Silent -> getString(R.string.notifications_sound_silent)
        is SoundChoice.Custom -> runCatching {
            RingtoneManager.getRingtone(requireContext(), Uri.parse(sound.uri))?.getTitle(requireContext())
        }.getOrNull() ?: getString(R.string.notifications_sound_default)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
