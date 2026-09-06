package org.matchat.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.getSystemService
import dagger.hilt.android.HiltAndroidApp
import org.matchat.client.notify.MessageNotifier

/** Hilt graph root. The sync foreground service (not this class) owns the SDK
 *  client; the app just constructs the graph (ARCHITECTURE.md "Sync lifecycle"). */
@HiltAndroidApp
class MatChatApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createMessageChannel()
    }

    /** The incoming-message channel (the low-priority sync channel is created by
     *  the sync service). High importance so a message note actually alerts. */
    private fun createMessageChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(MessageNotifier.CHANNEL_MESSAGES) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                MessageNotifier.CHANNEL_MESSAGES,
                getString(R.string.messages_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { enableVibration(true) },
        )
    }
}
