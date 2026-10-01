package org.matchat.feature.onboarding

import org.matchat.core.model.background.HelperStatus

/**
 * S27 "Hide the running notification?" (UX-SPEC §S27, docs/adr/0008): shown
 * once after sign-in when the background helper isn't on. [status] drives the
 * one status line under the explanation.
 */
data class BackgroundHelperState(
    val status: HelperStatus = HelperStatus.OFF,
)
