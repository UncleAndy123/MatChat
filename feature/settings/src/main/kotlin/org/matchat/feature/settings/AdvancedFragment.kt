package org.matchat.feature.settings

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
import org.matchat.feature.settings.databinding.FragmentAdvancedBinding

/** Settings > Advanced (S25; Phase 6, UI improvement plan; docs/adr/0007,
 *  0008). First row: the softkey swap, a narrow, explicit exception to
 *  "LEFT=Options/RIGHT=Back, always" for a device whose hardware softkeys are
 *  physically reversed — CENTER (or a tap) toggles it; render() is the only
 *  place that decides its checkmark (AGENTS.md §3), same shape as
 *  ThemeFragment. Then two rows that open system screens for settings only
 *  the user can grant: "Run in background" and the background helper, each
 *  with a status line rendered from state. */
@AndroidEntryPoint
class AdvancedFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_advanced
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: AdvancedViewModel by viewModels()
    private var binding: FragmentAdvancedBinding? = null

    override fun onContentViewCreated(content: View) {
        val b = FragmentAdvancedBinding.bind(content)
        binding = b
        setTitle(getString(R.string.advanced_title))

        b.advancedSwapSoftkeys.setOnClickListener {
            viewModel.onAction(AdvancedAction.ToggleSoftkeysSwapped)
        }
        b.advancedBatteryRow.setOnClickListener { openBatteryExemption() }
        b.advancedAccessibilityRow.setOnClickListener { openAccessibilitySettings() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.onAction(AdvancedAction.Refresh)
                viewModel.state.collect(::render)
            }
        }
        FocusEngine.requestInitialFocus(b.advancedSwapSoftkeys)
    }

    private fun render(state: AdvancedState) {
        val b = binding ?: return
        val label = getString(R.string.advanced_swap_softkeys)
        b.advancedSwapSoftkeys.text =
            if (state.softkeysSwapped) getString(R.string.theme_row_selected_format, label) else label
        b.advancedBatteryStatus.setText(
            if (state.batteryExempt) R.string.advanced_battery_allowed else R.string.advanced_battery_not_allowed,
        )
        b.advancedAccessibilityStatus.setText(
            when (state.helperStatus) {
                HelperStatus.ON -> R.string.advanced_helper_status_on
                HelperStatus.NEEDS_BATTERY -> R.string.advanced_helper_status_needs_battery
                HelperStatus.OFF -> R.string.advanced_helper_status_off
            },
        )
    }

    /** The system "let MatChat run in the background?" dialog, or its settings
     *  list on builds without the dialog. Like the helper, only the user can
     *  grant it; the status line refreshes when this screen is shown again. */
    @android.annotation.SuppressLint("BatteryLife") // sideloaded messenger with no push (docs/adr/0008)
    private fun openBatteryExemption() {
        val direct = android.content.Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${requireContext().packageName}"),
        )
        val list = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        listOf(direct, list).any { runCatching { startActivity(it) }.isSuccess }
    }

    /** The toggle above is our own preference; whether
     *  MatChatKeyAccessibilityService is enabled is a system setting, not
     *  something this app can read or set directly — only the OS's own
     *  Accessibility screen can grant it. */
    private fun openAccessibilitySettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        runCatching { startActivity(intent) }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
