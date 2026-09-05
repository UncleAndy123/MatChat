package org.matchat.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.matchat.core.matrix.MatrixAuth
import org.matchat.core.model.QrLoginStep

/** A [MatrixAuth] whose results tests set directly. */
class FakeMatrixAuth(
    var signInResult: Result<Unit> = Result.success(Unit),
    var restoreResult: Result<Unit> = Result.failure(IllegalStateException("no session")),
    var qrSteps: List<QrLoginStep> = listOf(QrLoginStep.WaitingForScan, QrLoginStep.Done),
) : MatrixAuth {
    var lastUser: String? = null

    override suspend fun signIn(user: String, password: String): Result<Unit> {
        lastUser = user
        return signInResult
    }

    override fun signInWithQr(): Flow<QrLoginStep> = flowOf(*qrSteps.toTypedArray())

    override suspend fun restoreSession(): Result<Unit> = restoreResult
}
