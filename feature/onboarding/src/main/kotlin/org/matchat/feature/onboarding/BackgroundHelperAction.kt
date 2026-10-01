package org.matchat.feature.onboarding

sealed interface BackgroundHelperAction {
    /** CENTER on "Open Accessibility settings". */
    data object OpenSettings : BackgroundHelperAction

    /** The screen is visible again (e.g. back from system settings): re-read status. */
    data object Refresh : BackgroundHelperAction
}

/** One-shot navigation out of S27. */
sealed interface BackgroundHelperNav {
    data object AccessibilitySettings : BackgroundHelperNav
}
