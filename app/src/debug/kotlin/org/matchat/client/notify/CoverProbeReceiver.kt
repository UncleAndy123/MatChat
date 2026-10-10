package org.matchat.client.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import jp.kyocera.sublcd.ISubLcdCallback
import java.lang.reflect.Modifier

/**
 * DEBUG-ONLY. Answers two device-specific questions about the Kyocera cover
 * (sub-LCD) card, so the next step is a decision rather than a guess
 * (docs/COVER-DISPLAY.md):
 *
 *  1. **Scrolling** — what methods and constants does the OEM sub-LCD API
 *     actually expose (a marquee/ticker setter?), and which framework layouts
 *     sit next to the known rich-card template (a scrolling variant?).
 *  2. **Re-show on wake** — can an ordinary app *hear* the cover waking up
 *     (the cover exposed as an Android Display, or SCREEN_ON/OFF), so the card
 *     can be re-posted on every wake until the message is read — without the
 *     accessibility-service route AGENTS.md §4 restricts?
 *
 * Usage (PowerShell-safe):
 * ```
 * adb logcat -c
 * adb shell am broadcast -n org.matchat.client/.notify.CoverProbeReceiver --ez callback true
 * # close the flip; over the next ~2 min press a side key a few times to wake
 * # the cover, open/close the flip once
 * adb logcat -d > probe.txt
 * Select-String -Path probe.txt -Pattern "probe:","SubLcd"
 * ```
 * `callback` also registers a listener on Kyocera's cover service
 * (`ISubLcdCallback`, compiled against :stubs:kyocera-sublcd) that only logs
 * cover on/off and forwarded keys, answering every key "not handled".
 *
 * It never posts to the cover. (A tray-icon test that called
 * `notifyIconToAnnunciatorTray` was removed — it crashed SystemUI.)
 *
 * Results (scrolling: no native marquee, app-side ticker works;
 * wake: only main-display SCREEN_ON/OFF are visible) are in
 * docs/COVER-DISPLAY.md.
 */
class CoverProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // A BroadcastReceiver's own context can't register receivers; the app's can.
        val app = context.applicationContext
        dumpClass("jp.kyocera.sublcd.SubLcdManager")
        dumpClass("jp.kyocera.sublcd.SubLcdNotificationExtender")
        // The callback SubLcdManager.registerCallback takes — likely how
        // Kyocera reports cover-screen on/off (STATE_SCREEN_ON/OFF). Dump only:
        // its methods tell us what a listener would receive; nothing is
        // registered here.
        dumpClass("jp.kyocera.sublcd.ISubLcdCallback")
        dumpLayouts(app)
        dumpDisplays(app)
        watchWake(app)
        if (intent.getBooleanExtra("callback", false)) callbackTest(app)
    }

    /** Registers a listener on Kyocera's cover service for [WATCH_MS] and only
     *  *logs* what it reports — cover screen on/off and the keys it forwards.
     *  Every key method answers "not handled" (false), so no button changes
     *  behavior. Unregistered afterwards. */
    private fun callbackTest(context: Context) {
        runCatching {
            val managerClass = Class.forName("jp.kyocera.sublcd.SubLcdManager")
            val manager = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)
            val callbackType = Class.forName("jp.kyocera.sublcd.ISubLcdCallback")
            val callback = ProbeCallback()
            managerClass.getMethod("registerCallback", callbackType).invoke(manager, callback)
            Log.d(TAG, "probe: callback registered for ${WATCH_MS / 1000}s — open/close the flip, press outside buttons")
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { managerClass.getMethod("unregisterCallback", callbackType).invoke(manager, callback) }
                    .onFailure { Log.d(TAG, "probe: callback unregister failed: $it") }
                Log.d(TAG, "probe: callback unregistered")
            }, WATCH_MS)
        }.onFailure {
            Log.d(TAG, "probe: callback register failed: $it")
        }
    }

    /** Every declared method, constructor, static constant and nested class. */
    private fun dumpClass(name: String) {
        val cls = runCatching { Class.forName(name) }.getOrElse {
            Log.d(TAG, "probe: $name not found (${it.javaClass.simpleName})")
            return
        }
        Log.d(
            TAG,
            "probe: class $name extends ${cls.superclass?.name} " +
                "implements [${cls.interfaces.joinToString { it.name }}]",
        )
        cls.declaredConstructors.forEach { c ->
            Log.d(TAG, "probe:   ctor(${c.parameterTypes.joinToString { it.simpleName }})")
        }
        cls.declaredMethods.sortedBy { it.name }.forEach { m ->
            Log.d(
                TAG,
                "probe:   ${Modifier.toString(m.modifiers)} ${m.returnType.simpleName} " +
                    "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})",
            )
        }
        cls.declaredFields.filter { Modifier.isStatic(it.modifiers) }.forEach { f ->
            val value = runCatching {
                f.isAccessible = true
                f.get(null)
            }.getOrNull()
            val shown = if (value is Int) "$value (0x${Integer.toHexString(value)})" else value.toString()
            Log.d(TAG, "probe:   const ${f.name} = $shown")
        }
        cls.declaredClasses.forEach { dumpClass(it.name) }
    }

    /** Framework layout names around the known rich-card template, plus any
     *  whose name hints at the sub-LCD or at scrolling. */
    private fun dumpLayouts(context: Context) {
        val res = context.resources
        for (id in LAYOUT_SCAN_START..LAYOUT_SCAN_END) {
            val entry = runCatching { res.getResourceEntryName(id) }.getOrNull() ?: continue
            if (id in NEIGHBORHOOD || LAYOUT_HINT.containsMatchIn(entry)) {
                Log.d(TAG, "probe: layout 0x${Integer.toHexString(id)} = $entry")
            }
        }
    }

    private fun dumpDisplays(context: Context) {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return
        dm.displays.forEach { d ->
            Log.d(
                TAG,
                "probe: display id=${d.displayId} name=${d.name} state=${stateName(d.state)} " +
                    "flags=0x${Integer.toHexString(d.flags)}",
            )
        }
    }

    /** Logs every display-state change and SCREEN_ON/OFF for
     *  [WATCH_MS], so a cover wake shows up (or doesn't) as a named signal. */
    private fun watchWake(context: Context) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                Log.d(TAG, "probe: wake? display added id=$displayId")
            }
            override fun onDisplayRemoved(displayId: Int) {
                Log.d(TAG, "probe: wake? display removed id=$displayId")
            }
            override fun onDisplayChanged(displayId: Int) {
                val state = dm?.getDisplay(displayId)?.state?.let(::stateName)
                Log.d(TAG, "probe: wake? display changed id=$displayId state=$state")
            }
        }
        dm?.registerDisplayListener(displayListener, main)

        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                Log.d(TAG, "probe: wake? broadcast ${i.action}")
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        Log.d(TAG, "probe: watching wake signals for ${WATCH_MS / 1000}s — close the flip, press a side key")
        main.postDelayed({
            dm?.unregisterDisplayListener(displayListener)
            runCatching { context.unregisterReceiver(screenReceiver) }
            Log.d(TAG, "probe: wake watch ended")
        }, WATCH_MS)
    }

    private fun stateName(state: Int): String = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        else -> "UNKNOWN($state)"
    }

    private companion object {
        val TAG = MessageNotifier.COVER_TAG

        const val LAYOUT_SCAN_START = 0x01090000
        const val LAYOUT_SCAN_END = 0x010903ff
        val NEIGHBORHOOD = 0x01090090..0x010900d0 // around the known 0x010900af / 0x010900b1
        val LAYOUT_HINT = Regex("sub|lcd|marquee|ticker|scroll", RegexOption.IGNORE_CASE)

        const val WATCH_MS = 120_000L
    }
}

/** Logs everything Kyocera's cover service reports; handles nothing. Runs on
 *  binder threads, so it only logs. Debug probe only — key codes are fine to
 *  log here (they say which outside button is which), unlike in the app. */
private class ProbeCallback : ISubLcdCallback.Stub() {

    override fun onScreenStateChanged(state: Int) {
        val name = when (state) {
            1 -> "ON"
            0 -> "OFF"
            else -> "?"
        }
        Log.d(TAG, "probe: cb onScreenStateChanged state=$state ($name)")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = notHandled("onKeyDown key=$keyCode")

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = notHandled("onKeyUp key=$keyCode")

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean = notHandled("onKeyLongPress key=$keyCode")

    override fun onKeyMultiple(keyCode: Int, count: Int, event: KeyEvent?): Boolean =
        notHandled("onKeyMultiple key=$keyCode count=$count")

    override fun onNotificationCancel(tag: String?, id: Int) {
        Log.d(TAG, "probe: cb onNotificationCancel tag=$tag id=$id")
    }

    private fun notHandled(what: String): Boolean {
        Log.d(TAG, "probe: cb $what")
        return false
    }

    private companion object {
        val TAG = MessageNotifier.COVER_TAG
    }
}
