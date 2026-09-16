package org.matchat.client.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the incoming-call notification's Decline action: hangs up the ringing
 *  call and clears the notification. */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CallNotifier.ACTION_DECLINE) return
        val calls = EntryPointAccessors
            .fromApplication(context.applicationContext, NotifEntryPoint::class.java)
            .callController()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                calls.hangup()
            } finally {
                CallNotifier.cancel(context)
                pending.finish()
            }
        }
    }
}
