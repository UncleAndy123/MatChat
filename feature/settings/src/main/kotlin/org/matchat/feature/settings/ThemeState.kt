package org.matchat.feature.settings

import org.matchat.core.ui.prefs.AccentColor
import org.matchat.core.ui.prefs.ThemeMode

/** S24 Theme. Everything the screen displays: the current light/dark mode
 *  and the current accent color, each rendered as a checkmark on one row
 *  of its list (UX-SPEC S24). */
data class ThemeState(
    val mode: ThemeMode = ThemeMode.LIGHT,
    val accent: AccentColor = AccentColor.MATCHAT,
)

sealed interface ThemeAction {
    data object SelectLight : ThemeAction
    data object SelectDark : ThemeAction
    data class SelectAccent(val color: AccentColor) : ThemeAction
}
