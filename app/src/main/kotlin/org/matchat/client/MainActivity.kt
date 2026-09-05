package org.matchat.client

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.client.sync.SyncForegroundService
import org.matchat.core.matrix.MatrixAuth
import org.matchat.core.matrix.MatrixSessionStore
import org.matchat.core.model.EventId
import org.matchat.core.model.RoomId
import org.matchat.core.ui.key.KeyMap
import org.matchat.core.ui.key.LogicalKey
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.LogicalKeyReceiver
import javax.inject.Inject

/**
 * The single Activity (ARCHITECTURE.md). It owns the nav host, the one global key
 * dispatcher, and the [Navigator] implementation. Raw key events are translated
 * once, here, via [KeyMap] and handed to the current screen as [LogicalKey]s —
 * no feature ever sees a keycode (AGENTS.md §4).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity(), Navigator {

    private lateinit var navController: NavController

    @Inject lateinit var auth: MatrixAuth
    @Inject lateinit var sessionStore: MatrixSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val host = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        navController = host.navController
        restoreSessionIfPresent()
    }

    /** Cold start with a saved session: restore it, start sync, jump to the room
     *  list (S1 → S8). Otherwise stay on Welcome (S2). */
    private fun restoreSessionIfPresent() {
        if (!sessionStore.hasSession()) return
        lifecycleScope.launch {
            if (auth.restoreSession().isSuccess) {
                SyncForegroundService.start(this@MainActivity)
                toRoomListRoot()
            }
        }
    }

    // --- Global key dispatch ------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Long-press # / * are power-user shortcuts routed as hold keys.
        if (event.action == KeyEvent.ACTION_DOWN && event.isLongPress) {
            KeyMap.holdKey(event.keyCode)?.let { return receiver()?.onLogicalKey(it) ?: false }
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val logical = KeyMap.map(event) ?: return super.dispatchKeyEvent(event)
        // Directional keys stay with the platform focus search (XML order); only
        // the softkeys, CENTER and digits are offered to the screen first.
        if (logical == LogicalKey.UP || logical == LogicalKey.DOWN ||
            logical == LogicalKey.LEFT || logical == LogicalKey.RIGHT
        ) {
            return super.dispatchKeyEvent(event)
        }
        return receiver()?.onLogicalKey(logical) ?: super.dispatchKeyEvent(event)
    }

    private fun receiver(): LogicalKeyReceiver? {
        val host = supportFragmentManager.findFragmentById(R.id.nav_host)
        return host?.childFragmentManager?.primaryNavigationFragment as? LogicalKeyReceiver
    }

    // --- Navigator ----------------------------------------------------------

    override fun toSignIn() = navController.navigate(R.id.signInFragment)

    override fun toRoomListRoot() {
        // A successful sign-in means a live session; own sync from here on.
        SyncForegroundService.start(this)
        val options = androidx.navigation.navOptions {
            popUpTo(R.id.welcomeFragment) { inclusive = true }
        }
        navController.navigate(R.id.roomListFragment, null, options)
    }

    override fun toWelcomeRoot() {
        val options = androidx.navigation.navOptions {
            popUpTo(R.id.nav_graph) { inclusive = true }
        }
        navController.navigate(R.id.welcomeFragment, null, options)
    }

    override fun toRoom(roomId: RoomId) =
        navController.navigate(R.id.timelineFragment, bundleOf(ARG_ROOM_ID to roomId.value))

    override fun toImageViewer(eventId: EventId) =
        navController.navigate(R.id.imageViewerFragment, bundleOf(ARG_EVENT_ID to eventId.value))

    override fun toInvites() = navController.navigate(R.id.invitesFragment)
    override fun toInvite(roomId: RoomId) =
        navController.navigate(R.id.inviteDetailFragment, bundleOf(ARG_ROOM_ID to roomId.value))

    override fun toNewChat() = navController.navigate(R.id.newChatFragment)
    override fun toTypeAddress() = navController.navigate(R.id.typeAddressFragment)
    override fun toVerification() = navController.navigate(R.id.verificationFragment)
    override fun toSettings() = navController.navigate(R.id.settingsFragment)
    override fun toPolicy() = navController.navigate(R.id.policyFragment)
    override fun toHelp() = navController.navigate(R.id.helpFragment)
    override fun back() { navController.navigateUp() }

    companion object {
        const val ARG_ROOM_ID = "roomId"
        const val ARG_EVENT_ID = "eventId"
    }
}
