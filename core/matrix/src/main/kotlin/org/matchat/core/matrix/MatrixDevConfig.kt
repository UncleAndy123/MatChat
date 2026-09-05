package org.matchat.core.matrix

/**
 * Development-only knobs for the SDK, provided by :app.
 *
 * [allowInsecureTls] disables TLS certificate verification in the SDK's HTTP
 * stack. It exists so on-device testing works behind an SSL-inspecting corporate
 * proxy (PLAN.md §11) and around the rustls-platform-verifier init requirement.
 * :app sets it to the app's debuggable flag, so a release build can NEVER turn it
 * on. Do not use it as a substitute for the server-side fix (exempt the homeserver
 * from inspection, or install the proxy CA on the device).
 */
data class MatrixDevConfig(
    val allowInsecureTls: Boolean = false,
)
