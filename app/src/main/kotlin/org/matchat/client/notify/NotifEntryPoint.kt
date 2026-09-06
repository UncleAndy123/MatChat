package org.matchat.client.notify

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.matchat.core.matrix.MatrixSession

/**
 * Hilt accessor for the notification action receivers. A BroadcastReceiver can't
 * use @AndroidEntryPoint field injection cleanly here (its onReceive is abstract,
 * so the generated super call won't compile), so the receivers resolve the
 * singleton [MatrixSession] through this entry point on the application context.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotifEntryPoint {
    fun matrixSession(): MatrixSession
}
