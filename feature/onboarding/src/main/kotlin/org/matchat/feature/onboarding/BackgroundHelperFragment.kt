package org.matchat.feature.onboarding

import android.content.Intent
import android.provider.Settings
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.model.background.HelperStatus
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.onboarding.databinding.FragmentBackgroundHelperBinding

/** S27 "Hide the running notification?" (UX-SPEC §S27, docs/adr/0008). One
 *  focusable row opens system Accessibility settings, where only the user can
 *  turn the background helper on; RIGHT (Back) is "not now". */
@AndroidEntryPoint
class BackgroundHelperFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_background_helper
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_open)

    private val viewModel: BackgroundHelperViewModel by viewModels()
    private var binding: FragmentBackgroundHelperBinding? = null

    override fun onContentViewCreated(content: View) {
        val b = FragmentBackgroundHelperBinding.bind(content)
        binding = b
        setTitle(getString(R.string.background_helper_title))
        b.backgroundHelperOpen.setOnClickListener { viewModel.onAction(BackgroundHelperAction.OpenSettings) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.onAction(BackgroundHelperAction.Refresh)
                launch { viewModel.state.collect(::render) }
                launch { viewModel.navEvents.collect(::navigate) }
            }
        }
        FocusEngine.requestInitialFocus(b.backgroundHelperOpen)
    }

    private fun render(state: BackgroundHelperState) {
        val b = binding ?: return
        b.backgroundHelperStatus.setText(
            when (state.status) {
                HelperStatus.ON -> R.string.background_helper_status_on
                HelperStatus.NEEDS_BATTERY -> R.string.background_helper_status_needs_battery
                HelperStatus.OFF -> R.string.background_helper_status_off
            },
        )
    }

    private fun navigate(nav: BackgroundHelperNav) {
        when (nav) {
            BackgroundHelperNav.AccessibilitySettings ->
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
