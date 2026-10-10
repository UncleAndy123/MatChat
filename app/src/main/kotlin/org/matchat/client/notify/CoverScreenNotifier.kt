package org.matchat.client.notify

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.matchat.client.R

/**
 * Posts a transient alert card to the Kyocera **cover (sub-LCD) screen** via the
 * OEM framework class `jp.kyocera.sublcd.SubLcdManager`, reached by reflection
 * on Android's public `Notification.Builder.extend()` API — the same extender
 * pattern wearable notifications use. This is the surface SystemUI's own PowerUI
 * uses for battery cards, and it is a *different* path from InfoSign →
 * `KeyguardStatusView`: it does not go through the notification-listener mirror
 * or the keyguard package allow-list that blocked a third-party app
 * (docs/COVER-DISPLAY.md), so an ordinary app can post to it. Approach verified
 * on-device by TurboText (github.com/Ben-Showalter/TurboText,
 * `OuterScreenNotifier`).
 *
 * Every card needs a duration and clears when it expires, so on top of the
 * single post this object:
 *  - **scrolls** text wider than the cover by reposting the same card id with
 *    a sliding window of the text (the OEM API has no marquee option — probe
 *    result, docs/COVER-DISPLAY.md);
 *  - **re-shows** the card every time the flip closes (`SCREEN_OFF` — the
 *    moment the cover becomes the visible screen) until [cancel] is called
 *    when the room is read, and stops it when the flip opens (`SCREEN_ON`).
 *
 * All state is confined to the main thread. Every OEM call is reflection
 * wrapped in `runCatching`, so off Kyocera hardware this is a silent no-op.
 */
internal object CoverScreenNotifier {

    private val TAG = MessageNotifier.COVER_TAG

    /** The sub-LCD rich-card template (`k_sublcd_template_5`) and
     *  `SubLcdManager.CATEGORY_INTERRUPT`, both confirmed by the probe. */
    private const val SUBLCD_LAYOUT_RICH_CARD = 0x010900af
    private const val SUBLCD_CATEGORY_MESSAGE = 2
    private const val SUBLCD_PRIORITY = 0

    /** How long a card stays up per showing. The sub-LCD holds the cover screen
     *  *on* for this whole span, so it is a battery/visibility trade-off. */
    private const val CARD_DURATION_MS = 30_000

    /** Characters visible on the cover at once — longer text scrolls. */
    private const val TICKER_WINDOW = 14
    private const val TICKER_STEP_MS = 400L

    /** Each scroll frame outlasts the step, so the card stays up between
     *  frames and clears shortly after the last one. */
    private const val TICKER_HOLD_MS = 1_500
    private const val TICKER_GAP = "   "

    private val main = Handler(Looper.getMainLooper())

    /** Cards still owed to the user (id → text), most recent last, until the
     *  room is read. Main thread only. */
    private val active = LinkedHashMap<Int, String>()
    private val tickers = HashMap<Int, Runnable>()
    private var screenReceiver: BroadcastReceiver? = null

    /** Shows (and keeps re-showing on flip close) a cover card for [id] with
     *  [text] until [cancel]. [id] matches the notification id. */
    fun notify(context: Context, id: Int, text: String) {
        val app = context.applicationContext
        main.post {
            active.remove(id)
            active[id] = text // move to most-recent
            ensureScreenReceiver(app)
            show(app, id, text)
        }
    }

    /** Clears the card for [id] and stops re-showing it (the room was read). */
    fun cancel(context: Context, id: Int) {
        val app = context.applicationContext
        main.post {
            active.remove(id)
            stopTicker(id)
            cancelCard(app, id)
            if (active.isEmpty()) releaseScreenReceiver(app)
        }
    }

    /** Only one card is on screen at a time: stop any other scroll, then show
     *  [text] once (short) or as a ticker (longer than [TICKER_WINDOW]). */
    private fun show(context: Context, id: Int, text: String) {
        tickers.keys.toList().forEach(::stopTicker)
        if (text.length <= TICKER_WINDOW) {
            post(context, id, text, CARD_DURATION_MS)
            Log.d(TAG, "cover card shown id=$id scrolling=false")
            return
        }
        val loop = text + TICKER_GAP
        val doubled = loop + loop
        val frames = (CARD_DURATION_MS / TICKER_STEP_MS).toInt()
        val ticker = object : Runnable {
            private var frame = 0
            override fun run() {
                val start = frame % loop.length
                post(context, id, doubled.substring(start, start + TICKER_WINDOW), TICKER_HOLD_MS)
                frame++
                if (frame < frames) main.postDelayed(this, TICKER_STEP_MS) else tickers.remove(id)
            }
        }
        tickers[id] = ticker
        ticker.run()
        Log.d(TAG, "cover card shown id=$id scrolling=true")
    }

    private fun stopTicker(id: Int) {
        tickers.remove(id)?.let(main::removeCallbacks)
    }

    /** Flip closed → re-show the most recent card; flip opened → stop it (the
     *  main screen shows the notification itself). */
    private fun ensureScreenReceiver(context: Context) {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> active.entries.lastOrNull()?.let { (id, text) ->
                        Log.d(TAG, "screen off (flip closed?) — re-showing id=$id")
                        show(context, id, text)
                    }
                    Intent.ACTION_SCREEN_ON -> active.keys.forEach { id ->
                        stopTicker(id)
                        cancelCard(context, id)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver
    }

    private fun releaseScreenReceiver(context: Context) {
        screenReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenReceiver = null
    }

    /** One post of one card. Logs only failures — a scrolling card posts every
     *  [TICKER_STEP_MS], so success lines would flood logcat. */
    @Suppress("DEPRECATION") // Notification.Builder(Context): this card is handed
    // to SubLcdManager, never posted through a NotificationManager channel.
    internal fun post(context: Context, id: Int, text: String, durationMs: Int) {
        runCatching {
            val managerClass = Class.forName("jp.kyocera.sublcd.SubLcdManager")
            val manager = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)
            managerClass.getMethod("notify", Int::class.javaPrimitiveType, Notification::class.java)
                .invoke(manager, id, buildCard(context, text, durationMs))
        }.onFailure {
            // Expected on any non-Kyocera device (classes absent) or if the OEM
            // API differs — either way, safe to no-op.
            Log.d(TAG, "sub-LCD notify unavailable id=$id: ${it.javaClass.simpleName}")
        }
    }

    /** A Notification carrying the sub-LCD extender (icon, text, template,
     *  duration). Also used by the debug probe's tray test. */
    @Suppress("DEPRECATION")
    internal fun buildCard(context: Context, text: String, durationMs: Int): Notification {
        val extenderClass = Class.forName("jp.kyocera.sublcd.SubLcdNotificationExtender")
        val extender = extenderClass.getConstructor(Context::class.java).newInstance(context)
        extenderClass.getMethod("setCategory", Int::class.javaPrimitiveType).invoke(extender, SUBLCD_CATEGORY_MESSAGE)
        extenderClass.getMethod("setLayout", Int::class.javaPrimitiveType).invoke(extender, SUBLCD_LAYOUT_RICH_CARD)
        extenderClass.getMethod("setPriority", Int::class.javaPrimitiveType).invoke(extender, SUBLCD_PRIORITY)
        extenderClass.getMethod("setIcon", Int::class.javaPrimitiveType).invoke(extender, R.drawable.ic_stat_message)
        extenderClass.getMethod("setText", CharSequence::class.java).invoke(extender, text)
        extenderClass.getMethod("setDuration", Int::class.javaPrimitiveType).invoke(extender, durationMs)

        val builder = Notification.Builder(context)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentText(text)
        Notification.Builder::class.java
            .getMethod("extend", Notification.Extender::class.java)
            .invoke(builder, extender)
        return builder.build()
    }

    private fun cancelCard(context: Context, id: Int) {
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
