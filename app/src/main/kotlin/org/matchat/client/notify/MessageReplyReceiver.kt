package org.matchat.client.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.RoomId
import javax.inject.Inject

/** Handles the notification inline-reply action: sends the typed text to the room
 *  and marks it read, then dismisses the notification. */
@AndroidEntryPoint
class MessageReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var session: MatrixSession

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // Hilt field injection happens here
        val roomValue = intent.getStringExtra(MessageNotifier.EXTRA_ROOM_ID) ?: return
        val notifId = intent.getIntExtra(MessageNotifier.EXTRA_NOTIF_ID, 0)
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(MessageNotifier.REPLY_KEY)?.toString()?.trim()
        if (text.isNullOrEmpty()) return

        val roomId = RoomId(roomValue)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                session.sendMessage(roomId, text)
                session.markRoomRead(roomId)
            } finally {
                MessageNotifier.cancelById(context, notifId)
                pending.finish()
            }
        }
    }
}
