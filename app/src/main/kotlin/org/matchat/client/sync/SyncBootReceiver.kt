package org.matchat.client.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Restarts sync after a reboot or an app update (docs/adr/0004, "always-on"
 * amendment) — before this, nothing synced until the user opened MatChat.
 * TurboText gets the same effect for free because the OS wakes the default SMS
 * app for every text; a Matrix client has no such wake-up without FCM, so it
 * starts its own host.
 *
 * If the background helper is enabled the system re-binds it after boot on its
 * own, and it takes over from the foreground service when it connects. On API
 * 35 a BOOT_COMPLETED receiver may not start a dataSync foreground service; the
 * refusal is caught in [SyncForegroundService], and the watchdog scheduled here
 * runs catch-ups instead until the app is opened.
 */
@AndroidEntryPoint
class SyncBootReceiver : BroadcastReceiver() {

    @Inject lateinit var hosts: SyncHosts

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> hosts.ensureRunning()
        }
    }
}
