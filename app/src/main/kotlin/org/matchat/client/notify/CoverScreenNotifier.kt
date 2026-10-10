package org.matchat.client.notify

import android.app.Notification
import android.content.Context
import android.util.Log
import org.matchat.client.R

/**
 * Posts a transient alert card to the Kyocera **cover (sub-LCD) screen** via the
 * OEM framework class `jp.kyocera.sublcd.SubLcdManager`, reached by reflection
 * on Android's public `Notification.Builder.extend()` API — the same extender
 * pattern wearable notifications use. This is the surface SystemUI's own PowerUI
 * uses for battery cards, and it is a *different* path from InfoSign →
 * `KeyguardStatusView`: it does not go through the notification-listener mirror
 * or the keyguard package allow-list that blocked a third-party app
 * (docs/COVER-DISPLAY.md), so an ordinary app can post to it.
 *
 * Approach and limitation both verified on-device by TurboText
 * (github.com/Ben-Showalter/TurboText, `OuterScreenNotifier`), which this
 * mirrors: every post needs a duration, and when it expires the card clears —
 * there is no setting that holds a badge on the idle cover indefinitely without
 * keeping the screen on. So this is a ~5 s "a message arrived" card, not a
 * persistent unread badge; a persistent idle badge still needs the signed
 * SystemUI build.
 *
 * Everything is wrapped in a single try/catch: on any non-Kyocera device the
 * `jp.kyocera.sublcd.*` classes don't exist, so this silently no-ops. It is
 * driven from [MessageNotifier] so it tracks the notification's own lifecycle —
 * posted when a message arrives, [cancel]led when the room is read.
 */
internal object CoverScreenNotifier {

    private val TAG = MessageNotifier.COVER_TAG

    /** The sub-LCD "rich card" template and message category, from the stock
     *  Messaging app's SubLcdUtils (TurboText's disassembly). Opaque OEM
     *  constants — kept named rather than inlined. */
    private const val SUBLCD_LAYOUT_RICH_CARD = 0x010900af
    private const val SUBLCD_CATEGORY_MESSAGE = 2
    private const val SUBLCD_PRIORITY = 0

    /** How long the card stays up. The sub-LCD holds the cover screen *on* for
     *  this whole span (TurboText's finding), so it is a battery/visibility
     *  trade-off — 30 s per the user's ask, long enough to notice on a glance
     *  without camping the screen on indefinitely. */
    private const val CARD_DURATION_MS = 30000

    /** Shows a cover-screen card for [id] with [text] (keep it short — the
     *  sub-LCD is tiny). [id] matches the notification id so [cancel] clears
     *  the right one. No-ops off Kyocera hardware. */
    fun notify(context: Context, id: Int, text: String, durationMs: Int = CARD_DURATION_MS) {
        post(context, id, text, durationMs)
    }

    @Suppress("DEPRECATION") // Notification.Builder(Context): this card is handed
    // to SubLcdManager, never posted through a NotificationManager channel.
    private fun post(context: Context, id: Int, text: String, durationMs: Int) {
        runCatching {
            val managerClass = Class.forName("jp.kyocera.sublcd.SubLcdManager")
            val extenderClass = Class.forName("jp.kyocera.sublcd.SubLcdNotificationExtender")

            val manager = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)

            val extender = extenderClass.getConstructor(Context::class.java).newInstance(context)
            extenderClass.getMethod("setCategory", Int::class.javaPrimitiveType)
                .invoke(extender, SUBLCD_CATEGORY_MESSAGE)
            extenderClass.getMethod("setLayout", Int::class.javaPrimitiveType)
                .invoke(extender, SUBLCD_LAYOUT_RICH_CARD)
            extenderClass.getMethod("setPriority", Int::class.javaPrimitiveType)
                .invoke(extender, SUBLCD_PRIORITY)
            extenderClass.getMethod("setIcon", Int::class.javaPrimitiveType)
                .invoke(extender, R.drawable.ic_stat_message)
            extenderClass.getMethod("setText", CharSequence::class.java).invoke(extender, text)
            extenderClass.getMethod("setDuration", Int::class.javaPrimitiveType)
                .invoke(extender, durationMs)

            val builder = Notification.Builder(context).setContentText(text)
            Notification.Builder::class.java
                .getMethod("extend", Notification.Extender::class.java)
                .invoke(builder, extender)

            managerClass.getMethod("notify", Int::class.javaPrimitiveType, Notification::class.java)
                .invoke(manager, id, builder.build())
        }.onSuccess {
            Log.d(TAG, "sub-LCD notify ok id=$id duration=$durationMs")
        }.onFailure {
            // Expected on any non-Kyocera device (classes absent) or if the OEM
            // API differs from the disassembly — either way, safe to no-op.
            Log.d(TAG, "sub-LCD notify unavailable id=$id: ${it.javaClass.simpleName}")
        }
    }

    /** Clears the cover-screen card for [id] (call when the room is read).
     *  No-ops off Kyocera hardware. */
    fun cancel(context: Context, id: Int) {
        runCatching {
            val managerClass = Class.forName("jp.kyocera.sublcd.SubLcdManager")
            val manager = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)
            managerClass.getMethod("cancel", Int::class.javaPrimitiveType).invoke(manager, id)
        }.onSuccess {
            Log.d(TAG, "sub-LCD cancel ok id=$id")
        }.onFailure {
            Log.d(TAG, "sub-LCD cancel unavailable id=$id: ${it.javaClass.simpleName}")
        }
    }
}
