package org.matchat.core.rtc

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.matchat.core.rtc.internal.HttpTokenService
import org.matchat.core.rtc.internal.LiveKitAudioTransport
import org.matchat.core.rtc.internal.MatrixRtcCallController
import javax.inject.Singleton

/**
 * Wires the call stack: MatrixRTC signalling + the real LiveKit media transport
 * and lk-jwt-service token fetch (docs/VOICE.md §3, ADR 0006). Whether audio
 * actually connects depends on [RtcConfig] being filled with the SFU endpoints
 * (provided by :app); blank config makes a call reach CONNECTED with audio
 * reported unavailable rather than failing.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RtcModule {

    @Binds
    @Singleton
    abstract fun bindCallController(impl: MatrixRtcCallController): CallController

    @Binds
    @Singleton
    abstract fun bindAudioTransport(impl: LiveKitAudioTransport): AudioTransport

    @Binds
    @Singleton
    abstract fun bindTokenService(impl: HttpTokenService): TokenService
}
