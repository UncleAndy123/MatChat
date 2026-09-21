package org.matchat.feature.settings

import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.settings.databinding.FragmentUpdateBinding

/** Settings > Software update (in-app updater over GitHub Releases). Same
 *  SoftkeyFragment shape as AdvancedFragment: one focusable action row, CENTER
 *  (or a tap) runs it; render() is the only place that decides its label and
 *  the surrounding text (AGENTS.md §3). The action's meaning follows the
 *  phase — Download when one is available, Install once downloaded, otherwise
 *  Check again. */
@AndroidEntryPoint
class UpdateFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_update
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: UpdateViewModel by viewModels()
    private var binding: FragmentUpdateBinding? = null

    override fun onContentViewCreated(content: View) {
        val b = FragmentUpdateBinding.bind(content)
        binding = b
        setTitle(getString(R.string.update_title))

        b.updateAction.setOnClickListener {
            when (viewModel.state.value.phase) {
                UpdatePhase.AVAILABLE -> viewModel.onAction(UpdateAction.Download)
                UpdatePhase.READY -> viewModel.onAction(UpdateAction.Install)
                UpdatePhase.UP_TO_DATE, UpdatePhase.FAILED -> viewModel.onAction(UpdateAction.Check)
                UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING -> Unit // busy; ignore
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        FocusEngine.requestInitialFocus(b.updateAction)
    }

    private fun render(state: UpdateState) {
        val b = binding ?: return
        b.updateStatus.text = when (state.phase) {
            UpdatePhase.CHECKING -> getString(R.string.update_checking)
            UpdatePhase.UP_TO_DATE -> getString(R.string.update_up_to_date)
            UpdatePhase.AVAILABLE -> getString(R.string.update_available)
            UpdatePhase.DOWNLOADING ->
                if (state.percent < 0) {
                    getString(R.string.update_downloading)
                } else {
                    getString(R.string.update_downloading_pct, state.percent)
                }
            UpdatePhase.READY -> getString(R.string.update_ready)
            UpdatePhase.FAILED -> getString(failureMessage(state.error))
        }

        b.updateVersions.text = if (state.latestVersion.isNotBlank()) {
            getString(R.string.update_versions_format, state.currentVersion, state.latestVersion)
        } else {
            getString(R.string.update_version_current_format, state.currentVersion)
        }

        b.updateNotes.text = state.notes
        b.updateNotes.visibility =
            if (state.notes.isNotBlank() &&
                (
                    state.phase == UpdatePhase.AVAILABLE ||
                        state.phase == UpdatePhase.DOWNLOADING ||
                        state.phase == UpdatePhase.READY
                    )
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val actionLabel = when (state.phase) {
            UpdatePhase.AVAILABLE -> getString(R.string.update_action_download)
            UpdatePhase.READY -> getString(R.string.update_action_install)
            UpdatePhase.DOWNLOADING -> getString(R.string.update_action_downloading)
            UpdatePhase.CHECKING -> getString(R.string.update_action_checking)
            UpdatePhase.UP_TO_DATE, UpdatePhase.FAILED -> getString(R.string.update_action_check)
        }
        b.updateAction.text = actionLabel
        val busy = state.phase == UpdatePhase.CHECKING || state.phase == UpdatePhase.DOWNLOADING
        b.updateAction.isEnabled = !busy
        b.updateAction.isFocusable = !busy
    }

    private fun failureMessage(error: org.matchat.core.update.UpdateError?): Int = when (error) {
        org.matchat.core.update.UpdateError.NO_RELEASE -> R.string.update_failed_no_release
        org.matchat.core.update.UpdateError.NO_ASSET -> R.string.update_failed_no_asset
        org.matchat.core.update.UpdateError.INSTALL -> R.string.update_failed_install
        org.matchat.core.update.UpdateError.DOWNLOAD -> R.string.update_failed_download
        org.matchat.core.update.UpdateError.NETWORK, null -> R.string.update_failed_network
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
