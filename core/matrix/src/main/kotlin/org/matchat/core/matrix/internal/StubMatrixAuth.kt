package org.matchat.core.matrix.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.matchat.core.matrix.MatrixAuth
import org.matchat.core.model.QrLoginStep
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M0 placeholder. `restoreSession` reports no session, so the app routes to S2
 * Welcome. Replaced in M1 by the SDK login/restore flow.
 */
@Singleton
internal class StubMatrixAuth @Inject constructor() : MatrixAuth {
    override suspend fun signIn(user: String, password: String): Result<Unit> = Result.success(Unit)

    override fun signInWithQr(): Flow<QrLoginStep> = flowOf(QrLoginStep.WaitingForScan)

    override suspend fun restoreSession(): Result<Unit> =
        Result.failure(IllegalStateException("no session (M0 stub)"))
}
