package org.matchat.client.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.matchat.client.MainActivity
import org.matchat.client.notify.CoverScreenNotifier
import org.matchat.client.sync.SyncHosts
import javax.inject.Inject

/**
 * **Third job — cover re-show (docs/COVER-DISPLAY.md), per explicit user
 * direction.** On Kyocera flips an outside button wakes the cover screen with
 * no signal an app can hear. This service already receives every key (that's
 * how the softkey job below works), so on each fresh key-down it nudges
 * [CoverScreenNotifier.onKeyPress], which re-shows an unread message's cover
 * card if the main screen is off. Observe only: the key is never consumed and
 * its code is never recorded or logged.
 *
 * **Second job — sync host (docs/adr/0008).** While enabled, the system keeps
 * this service bound, which keeps MatChat's process alive and restarts it after
 * a kill, the way TurboText's own accessibility service keeps TurboText alive.
 * When the battery exemption is also granted, [SyncHosts] lets this service host
 * the sync loop instead of the foreground service, so the "MatChat is running"
 * notification disappears. This service only lends its lifetime: it still reads
 * nothing but the right softkey, and sync logic stays in SyncOwner. Disabling it
 * hands sync straight back to the foreground service.
 *
 * **First job — softkey workaround.**
 * Optional, user-enabled workaround (Settings > Advanced > "Background helper",
 * which links to system Accessibility settings) for a confirmed device
 * conflict, diagnosed with the user via adb logcat: on some hardware, the
 * system's own predictive-text ("T9word") keyboard consumes
 * KEYCODE_SOFT_RIGHT before it ever reaches MainActivity.dispatchKeyEvent,
 * but ONLY while an EditText/IME is active — outside of composing, the
 * normal dispatch path already works fine.
 *
 * TurboText RULED OUT (read its own source, com.turbotext.app's
 * KeyButtonAccessibilityService, on the diagnosed device — same maker's
 * flip phones share this key-conflict-prone platform, and its author had
 * already written up the exact same class of AccessibilityService/hardware
 * quirks): that service only ever consumes KEYCODE_SOFT_RIGHT while the
 * *Kyocera home screen itself* is in the foreground (`foregroundClass ==
 * "jp.kyocera.kyocerahome.HomeScreenActivity"`), to fix a broken OEM
 * shortcut — every other foreground app, MatChat included, falls through to
 * `return false` unconditionally, untouched. Its `currentForeground()`
 * check still runs (and logs) on every SOFT_RIGHT press system-wide purely
 * for its own diagnostics, which is why its log lines showed up at all
 * while MatChat was in the foreground — but `foregroundClass` was never its
 * home-screen constant, so it never intercepts here. Confirmed innocent by
 * design, not just by the inconclusive on/off retest that preceded this.
 *
 * That same source turned up a real bug in this class, since fixed: it only
 * ever checked `event.action != ACTION_DOWN -> return false`, meaning a
 * consumed DOWN's matching UP was *never* consumed — left as an orphaned
 * event with no DOWN ever delivered anywhere, which TurboText's own doc
 * comment describes the platform mishandling ("Cancelling event due to no
 * window focus") on this exact hardware family. [onKeyEvent] now tracks and
 * consumes the matching UP the same way TurboText does.
 *
 * The Android 13+ "restricted settings" theory (a sideloaded app's
 * Accessibility toggle silently not taking effect) is RULED OUT too: an
 * on-device capture showed `onServiceConnected: flags=32` — the service
 * connects and is granted FLAG_REQUEST_FILTER_KEY_EVENTS (32) correctly.
 *
 * Config parity with TurboText, beyond the DOWN/UP fix above: comparing the
 * two services' manifest/XML declarations (not just their Kotlin) turned up
 * two more real differences, now matched — `android:exported="true"` on
 * the `<service>` entry (AndroidManifest.xml) and
 * `android:accessibilityFlags="flagRequestFilterKeyEvents"` declared
 * statically in `key_accessibility_service_config.xml` (that file's own
 * comment has the detail on what was and wasn't copied, and why).
 *
 * Still unconfirmed: whether any of this is what was actually blocking
 * Options, or whether something else is — the one on-device capture taken
 * so far was on Settings/room-list screens, where the key already worked
 * fine via normal dispatch; the actual originally-diagnosed conflict (the
 * T9 IME swallowing the key) only happens while composing on the
 * thread/timeline screen, not captured yet. That capture — using the
 * logging below and in MainActivity, SOFTKEY_LOG_TAG = "MatChatSoftkey" —
 * is the next real test.
 *
 * A service requesting FLAG_REQUEST_FILTER_KEY_EVENTS receives hardware key
 * events earlier in the platform's input pipeline than IME processing does
 * — that's the documented purpose of the flag — so it gets a chance to
 * claim the key before that IME can swallow it.
 *
 * Scope is deliberately as narrow as possible, per explicit direction: this
 * service claims ONLY the physical right softkey (KEYCODE_SOFT_RIGHT) — not
 * SOFT_LEFT, not MENU, not the dedicated BACK key. Every other key —
 * including all of T9's own digit/D-pad/CENTER input, and the left softkey
 * — returns false from [onKeyEvent] (after the cover nudge above, which
 * doesn't touch the event) and continues through the normal platform
 * pipeline exactly as if this service didn't exist.
 *
 * A claimed key is handed to [MainActivity.handleExternalSoftkey], which
 * runs it through the exact same [org.matchat.core.ui.key.KeyMap] +
 * LogicalKeyReceiver path `dispatchKeyEvent` already uses — no duplicated
 * or divergent key-handling logic.
 *
 * No canRetrieveWindowContent, no screen reading, ever
 * (key_accessibility_service_config.xml). Entirely inert unless the user
 * explicitly enables it in system Accessibility settings — nothing about
 * normal dispatchKeyEvent handling changes if they don't, on this device or
 * any other.
 */
@AndroidEntryPoint
class MatChatKeyAccessibilityService : AccessibilityService() {

    @Inject lateinit var syncHosts: SyncHosts

    /** Lent to SyncOwner while this service hosts sync; lives as long as the
     *  service does. */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hostingRegistered = false

    // Tracks whether we consumed the DOWN half of the current SOFT_RIGHT
    // press, so the matching UP gets consumed too. Found by comparing this
    // class against a sibling app's own accessibility-based key service on
    // the same hardware family (TurboText's KeyButtonAccessibilityService,
    // which hit this exact issue): consuming only DOWN and always returning
    // false for UP leaves the UP orphaned — no DOWN was ever delivered to
    // whatever ends up with focus next, so the system keeps trying to
    // redeliver it, logged there as "Cancelling event due to no window
    // focus." This class had exactly that asymmetry (`if (event.action !=
    // ACTION_DOWN) return false` unconditionally covered UP too) until now.
    private var interceptedDown = false

    override fun onServiceConnected() {
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        // Diagnostic only (see this class's doc comment): proves the service
        // actually connected and was granted the flag — if this never logs on a
        // device where the user believes they enabled it, the OS never actually
        // started it (e.g. Android 13+'s "restricted settings" silently blocking
        // a sideloaded app's toggle).
        Log.d(LOG_TAG, "onServiceConnected: flags=${serviceInfo?.flags}")
        hostingRegistered = true
        syncHosts.onHelperConnected(syncScope)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        releaseSync()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        releaseSync()
        syncScope.cancel()
        super.onDestroy()
    }

    /** Hand sync back to the foreground service (once, whichever of
     *  onUnbind/onDestroy comes first). */
    private fun releaseSync() {
        if (!hostingRegistered) return
        hostingRegistered = false
        syncHosts.onHelperDisconnected()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Third job (cover re-show): any fresh key press is a cue that the
        // cover may have just woken. Observe only — never consumed, key code
        // never recorded; CoverScreenNotifier decides whether to act.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            CoverScreenNotifier.onKeyPress(this)
        }
        if (!isInterceptedSoftkey(event.keyCode)) return false
        if (event.action == KeyEvent.ACTION_UP) {
            if (!interceptedDown) return false
            interceptedDown = false
            Log.d(LOG_TAG, "onKeyEvent: consuming the UP matching a consumed DOWN")
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val consumed = MainActivity.handleExternalSoftkey(event)
        interceptedDown = consumed
        Log.d(LOG_TAG, "onKeyEvent: keyCode=${event.keyCode} consumed=$consumed")
        return consumed
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    private companion object {
        // Same tag MainActivity's dispatchKeyEvent/handleAccessibilityKeyEvent
        // log under, so one logcat filter shows the whole path in order.
        const val LOG_TAG = "MatChatSoftkey"
    }
}

/** True only for the physical right softkey. A top-level, pure function
 *  (not a private method on the service) so it's unit-testable without an
 *  Android runtime — a plain Int compare against a real [KeyEvent] keycode
 *  constant needs no Robolectric, unlike constructing/inspecting a live
 *  [KeyEvent] instance would. */
internal fun isInterceptedSoftkey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_SOFT_RIGHT
