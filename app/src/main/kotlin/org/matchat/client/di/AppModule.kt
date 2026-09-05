package org.matchat.client.di

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.matchat.core.matrix.MatrixDevConfig
import org.matchat.core.model.MillisClock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideClock(): MillisClock = MillisClock.SYSTEM

    /**
     * TLS verification is disabled only when the app is debuggable, so a release
     * build can never ship with it on (see MatrixDevConfig). Debug testing then
     * works behind an SSL-inspecting proxy / around the rustls init requirement.
     */
    @Provides
    @Singleton
    fun provideMatrixDevConfig(@ApplicationContext context: Context): MatrixDevConfig {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return MatrixDevConfig(allowInsecureTls = debuggable)
    }
}
