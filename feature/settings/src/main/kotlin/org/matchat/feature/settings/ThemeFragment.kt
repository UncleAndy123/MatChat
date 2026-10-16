package org.matchat.feature.settings

import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.menu.MenuItem
import org.matchat.core.ui.menu.MenuSheet
import org.matchat.core.ui.prefs.AccentColor
import org.matchat.core.ui.prefs.ThemeMode
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.settings.databinding.FragmentThemeBinding

/** S24 Theme: light/dark stays two inline focusable rows; Accent color (4 ->
 *  16, Part 3 of the "12 more accent colors" round) is now a single row
 *  ("Accent color: <current> ›") that opens a [MenuSheet] listing all 16 on
 *  CENTER — the same scrollable-N-item-list-with-a-checkmark shape the
 *  reaction picker already uses, since 16 hand-built fixed-id rows would be
 *  unmanageable. render() is the only place that decides which Appearance
 *  row / which picker item carries the checkmark (AGENTS.md §3). */
@AndroidEntryPoint
class ThemeFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_theme
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    private val viewModel: ThemeViewModel by viewModels()
    private var binding: FragmentThemeBinding? = null
    private var state: ThemeState = ThemeState()

    override fun onContentViewCreated(content: View) {
        val b = FragmentThemeBinding.bind(content)
        binding = b
        setTitle(getString(R.string.theme_title))

        b.themeLight.setOnClickListener { viewModel.onAction(ThemeAction.SelectLight) }
        b.themeDark.setOnClickListener { viewModel.onAction(ThemeAction.SelectDark) }
        b.themeAccent.setOnClickListener { showAccentPicker() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        FocusEngine.requestInitialFocus(b.themeLight)
    }

    private fun render(newState: ThemeState) {
        state = newState
        val b = binding ?: return
        b.themeLight.text = labelFor(R.string.theme_light, newState.mode == ThemeMode.LIGHT)
        b.themeDark.text = labelFor(R.string.theme_dark, newState.mode == ThemeMode.DARK)
        b.themeAccent.text = getString(R.string.theme_accent_row_format, getString(labelResFor(newState.accent)))
    }

    private fun showAccentPicker() {
        val context = context ?: return
        val current = state.accent
        val items = AccentColor.entries.map { color ->
            MenuItem(id = color.name, label = labelFor(labelResFor(color), color == current))
        }
        MenuSheet.show(context, items) { item ->
            val selected = AccentColor.entries.first { it.name == item.id }
            viewModel.onAction(ThemeAction.SelectAccent(selected))
        }
    }

    private fun labelFor(labelRes: Int, selected: Boolean): CharSequence {
        val label = getString(labelRes)
        return if (selected) getString(R.string.theme_row_selected_format, label) else label
    }

    // A flat 1:1 accent -> string-res mapping; the branch count is the accent
    // count, not real complexity.
    @Suppress("CyclomaticComplexMethod")
    private fun labelResFor(color: AccentColor): Int = when (color) {
        AccentColor.GREEN -> R.string.theme_accent_green
        AccentColor.AMBER -> R.string.theme_accent_amber
        AccentColor.BLUE -> R.string.theme_accent_blue
        AccentColor.PLUM -> R.string.theme_accent_plum
        AccentColor.TEAL -> R.string.theme_accent_teal
        AccentColor.CYAN -> R.string.theme_accent_cyan
        AccentColor.INDIGO -> R.string.theme_accent_indigo
        AccentColor.VIOLET -> R.string.theme_accent_violet
        AccentColor.ORCHID -> R.string.theme_accent_orchid
        AccentColor.ROSE -> R.string.theme_accent_rose
        AccentColor.RUST -> R.string.theme_accent_rust
        AccentColor.OCHRE -> R.string.theme_accent_ochre
        AccentColor.OLIVE -> R.string.theme_accent_olive
        AccentColor.FOREST -> R.string.theme_accent_forest
        AccentColor.SLATE -> R.string.theme_accent_slate
        AccentColor.WINE -> R.string.theme_accent_wine
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
