package org.matchat.core.matrix.internal

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.matchat.core.matrix.MatrixAuth
import org.matchat.core.model.ErrorText
import org.matchat.core.model.QrLoginStep
import org.matchat.core.policy.PolicyProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Password sign-in and session restore against the SDK. The homeserver comes
 * from the sign-in screen, unless policy pins one — never discovered (AGENTS.md §0).
 */
@Singleton
internal class RustMatrixAuth @Inject constructor(
    private val holder: RustMatrixClientHolder,
    private val policyProvider: PolicyProvider,
) : MatrixAuth {

    override suspend fun signIn(user: String, password: String, homeserver: String): Result<Unit> {
        val target = (policyProvider.policy.value.pinnedHomeserver ?: homeserver).trim()
        return runCatching {
            holder.buildClient(target)
            holder.requireClient().login(user, password, DEVICE_NAME, null)
            holder.persistSession()
            holder.startSync()
        }.onFailure { e ->
            // No credentials or tokens in logs (AGENTS.md §9); message only.
            Log.w(TAG, "sign-in failed for '$target': ${e.message}")
        }
    }

    // QR sign-in (S4) is a v1.1 candidate; not wired in M1.
    override fun signInWithQr(): Flow<QrLoginStep> =
        flowOf(QrLoginStep.Failed(ErrorText(ErrorText.Key.UNKNOWN)))

    override suspend fun restoreSession(): Result<Unit> =
        if (holder.restore()) Result.success(Unit) else Result.failure(IllegalStateException("no session"))

    private companion object {
        const val TAG = "MatrixAuth"
        const val DEVICE_NAME = "MatChat"
    }
}
