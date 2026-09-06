package org.matchat.client.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matchat.core.model.RoomId

/** Handles the notification "Mark as read" action. */
class MarkReadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val roomValue = intent.getStringExtra(MessageNotifier.EXTRA_ROOM_ID) ?: return
        val notifId = intent.getIntExtra(MessageNotifier.EXTRA_NOTIF_ID, 0)
        val session = EntryPointAccessors
            .fromApplication(context.applicationContext, NotifEntryPoint::class.java)
            .matrixSession()
        val roomId = RoomId(roomValue)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                session.markRoomRead(roomId)
            } finally {
                MessageNotifier.cancelById(context, notifId)
                pending.finish()
            }
        }
    }
}
