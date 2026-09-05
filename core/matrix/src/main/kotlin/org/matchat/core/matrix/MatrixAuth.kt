package org.matchat.core.matrix

import kotlinx.coroutines.flow.Flow
import org.matchat.core.model.QrLoginStep

/**
 * Sign-in and session restore (PLAN.md §5, §6.2). The homeserver is supplied by
 * :core:policy (pinnedHomeserver) or typed on S3; MatrixAuth never discovers one.
 */
interface MatrixAuth {
    /** [homeserver] may be a server name (example.org) or a full URL; well-known
     *  discovery resolves it. Ignored when policy pins the homeserver. */
    suspend fun signIn(user: String, password: String, homeserver: String): Result<Unit>

    /** QR sign-in (MSC4108); shipped behind policy.qrLoginEnabled. */
    fun signInWithQr(): Flow<QrLoginStep>

    /** Restores a persisted session on cold start; false Result → show S2. */
    suspend fun restoreSession(): Result<Unit>
}
