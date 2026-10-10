package org.matchat.client.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matchat.core.model.RoomId

/**
 * DEBUG-ONLY. Fires one real incoming-message notification on demand so the
 * Kyocera cover display (InfoSign, `jp.kyocera.kcinfosignprovider`) can be
 * tested without waiting for a live Matrix message.
 *
 * It posts through [MessageNotifier.show] unchanged, so what you see is exactly
 * the production notification: `CATEGORY_MESSAGE`, auto-cancel, not ongoing —
 * the shape InfoSign's NotificationListener filter is believed to mirror as
 * "N from <app>" on the front screen.
 *
 * Usage (reference device over adb):
 * ```
 * adb shell "logcat -c"
 * adb shell "logcat -v time -s kc_infosign" &      # watch the cover-screen tag
 * adb shell am broadcast -n org.matchat.client/.notify.TestNotificationReceiver
 * # optional extras:
 * #   --es text "see you at six"   --es room "Ann"   --ei count 1
 * ```
 * A pass looks like `onNotificationPosted(... pkg=org.matchat.client ...
 * category=msg ...)` followed by `sendBroadcast pkg=org.matchat.client count=1`.
 * If it posts but no `sendBroadcast` line follows, there is a package whitelist
 * and getting on-screen needs root (see the notifications branch notes).
 *
 * Registered only in `app/src/debug/AndroidManifest.xml`, so it ships in no
 * release build. Clear it afterwards with:
 * `adb shell am broadcast -n org.matchat.client/.notify.TestNotificationReceiver --ez clear true`
 */
class TestNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val roomId = RoomId(intent.getStringExtra("room_id") ?: TEST_ROOM_ID)

        if (intent.getBooleanExtra("clear", false)) {
            MessageNotifier.cancel(context, roomId)
            Log.i(TAG, "cleared test notification for $roomId")
            return
        }

        val content = NotificationContent(
            roomName = intent.getStringExtra("room") ?: "Test room",
            sender = intent.getStringExtra("sender") ?: "MatChat test",
            text = intent.getStringExtra("text") ?: "InfoSign cover-screen test",
            media = null,
            unread = intent.getIntExtra("count", 1).coerceAtLeast(1),
        )

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MessageNotifier.show(context, roomId, content)
                Log.i(TAG, "posted test notification (category=msg) for $roomId")
            } catch (e: Exception) {
                Log.e(TAG, "test notification failed to post", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MessageNotifier"
        const val TEST_ROOM_ID = "!matchat-infosign-test:local"
    }
}
