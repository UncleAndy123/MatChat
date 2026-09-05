package org.matchat.core.model

/** Trust state of this device's own encryption keys (drives the S5 flow). */
enum class DeviceTrust { UNVERIFIED, VERIFIED, RECOVERABLE }

/**
 * Progress of a QR (MSC4108) sign-in. Shipped behind `policy.qrLoginEnabled`;
 * the password path is always available as a fallback (PLAN.md §6.2).
 */
sealed interface QrLoginStep {
    data object WaitingForScan : QrLoginStep
    data object Establishing : QrLoginStep
    data object Done : QrLoginStep
    data class Failed(val error: ErrorText) : QrLoginStep
}
