package org.matchat.core.matrix

import kotlinx.coroutines.flow.Flow
import org.matchat.core.model.QrLoginStep

/**
 * Sign-in and session restore (PLAN.md §5, §6.2). The homeserver is supplied by
 * :core:policy (pinnedHomeserver) or typed on S3; MatrixAuth never discovers one.
 */
interface MatrixAuth {
    suspend fun signIn(user: String, password: String): Result<Unit>

    /** QR sign-in (MSC4108); shipped behind policy.qrLoginEnabled. */
    fun signInWithQr(): Flow<QrLoginStep>

    /** Restores a persisted session on cold start; false Result → show S2. */
    suspend fun restoreSession(): Result<Unit>
}
