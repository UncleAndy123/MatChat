package org.matchat.core.matrix.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matchat.core.matrix.SessionVerification
import org.matchat.core.model.SasEmoji
import org.matchat.core.model.SasState
import org.matrix.rustcomponents.sdk.SessionVerificationController
import org.matrix.rustcomponents.sdk.SessionVerificationControllerDelegate
import org.matrix.rustcomponents.sdk.SessionVerificationData
import org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SDK-backed emoji (SAS) verification (S6). Wraps SessionVerificationController and
 * its delegate, mapping callbacks to [SasState]. Delegate callbacks arrive on SDK
 * threads; state is a MutableStateFlow so updates are safe, and suspend controller
 * calls are launched on [scope].
 *
 * FFI: client.getSessionVerificationController() and the controller/delegate names
 * are version-sensitive — confirm against the AAR on first compile.
 */
@Singleton
internal class RustSessionVerification @Inject constructor(
    private val holder: RustMatrixClientHolder,
) : SessionVerification {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<SasState>(SasState.Idle)
    override val state: StateFlow<SasState> = _state.asStateFlow()

    private var controller: SessionVerificationController? = null

    private val delegate = object : SessionVerificationControllerDelegate {
        override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) = Unit

        override fun didAcceptVerificationRequest() {
            // The other device accepted; move on to the SAS emoji exchange.
            scope.launch { runCatching { controller?.startSasVerification() } }
        }

        override fun didStartSasVerification() = Unit

        override fun didReceiveVerificationData(data: SessionVerificationData) {
            val emojis = (data as? SessionVerificationData.Emojis)?.emojis.orEmpty()
                .map { SasEmoji(symbol = it.symbol(), name = it.description()) }
            if (emojis.isNotEmpty()) _state.value = SasState.Comparing(emojis)
        }

        override fun didFinish() { _state.value = SasState.Success }
        override fun didFail() { _state.value = SasState.Cancelled }
        override fun didCancel() { _state.value = SasState.Cancelled }
    }

    override suspend fun start() {
        val c = holder.requireClient().getSessionVerificationController()
        controller = c
        c.setDelegate(delegate)
        _state.value = SasState.Requested
        c.requestDeviceVerification()
    }

    override suspend fun approve() {
        runCatching { controller?.approveVerification() }
    }

    override suspend fun decline() {
        runCatching { controller?.declineVerification() }
        _state.value = SasState.Cancelled
    }

    override suspend fun cancel() {
        runCatching { controller?.cancelVerification() }
        reset()
    }

    override fun reset() {
        controller?.setDelegate(null)
        controller = null
        _state.value = SasState.Idle
    }
}
