package org.matchat.core.matrix.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.matchat.core.matrix.MatrixAuth
import org.matchat.core.model.ErrorText
import org.matchat.core.model.QrLoginStep
import org.matchat.core.policy.PolicyProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Password sign-in and session restore against the SDK. The homeserver is taken
 * from policy (pinnedHomeserver) or a default — never discovered (AGENTS.md §0).
 */
@Singleton
internal class RustMatrixAuth @Inject constructor(
    private val holder: RustMatrixClientHolder,
    private val policyProvider: PolicyProvider,
) : MatrixAuth {

    override suspend fun signIn(user: String, password: String): Result<Unit> = runCatching {
        val homeserver = normalize(policyProvider.policy.value.pinnedHomeserver ?: DEFAULT_HOMESERVER)
        val client = holder.buildClient(homeserver)
        client.login(user, password, DEVICE_NAME, null)
        holder.persistSession()
        holder.startSync()
    }

    // QR sign-in (S4) is a v1.1 candidate; not wired in M1.
    override fun signInWithQr(): Flow<QrLoginStep> =
        flowOf(QrLoginStep.Failed(ErrorText(ErrorText.Key.UNKNOWN)))

    override suspend fun restoreSession(): Result<Unit> =
        if (holder.restore()) Result.success(Unit) else Result.failure(IllegalStateException("no session"))

    private fun normalize(homeserver: String): String =
        if (homeserver.startsWith("http")) homeserver else "https://$homeserver"

    private companion object {
        const val DEVICE_NAME = "MatChat"
        // Assumed homeserver (PLAN.md §12 open question 1); overridden by policy.
        const val DEFAULT_HOMESERVER = "chats.carpathianserver.org"
    }
}
