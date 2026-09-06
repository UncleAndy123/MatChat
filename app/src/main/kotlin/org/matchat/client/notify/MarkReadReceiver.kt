package org.matchat.client.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.RoomId
import javax.inject.Inject

/** Handles the notification "Mark as read" action. */
@AndroidEntryPoint
class MarkReadReceiver : BroadcastReceiver() {

    @Inject lateinit var session: MatrixSession

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // Hilt field injection happens here
        val roomValue = intent.getStringExtra(MessageNotifier.EXTRA_ROOM_ID) ?: return
        val notifId = intent.getIntExtra(MessageNotifier.EXTRA_NOTIF_ID, 0)
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
