package org.matchat.core.model

/**
 * A Matrix OpenID token — proof of the user's Matrix identity that a third party
 * (here lk-jwt-service, MSC4195) can validate against the user's homeserver over
 * federation. Exchanged for a LiveKit JWT so the SFU will admit the caller
 * (docs/VOICE.md §3). A clean model so the SDK's own token type stays in
 * :core:matrix.
 */
data class MatrixOpenIdToken(
    val accessToken: String,
    val tokenType: String,
    val matrixServerName: String,
    val expiresInSeconds: Long,
)
