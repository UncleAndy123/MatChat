package org.matchat.client

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.matchat.client.di.UserPreferencesEntryPoint
import org.matchat.client.sync.BackgroundSyncStatusProvider
import org.matchat.client.sync.SyncHosts
import org.matchat.core.matrix.MatrixAuth
import org.matchat.core.matrix.MatrixSessionStore
import org.matchat.core.model.EventId
import org.matchat.core.model.RoomId
import org.matchat.core.model.UserId
import org.matchat.core.ui.key.KeyMap
import org.matchat.core.ui.key.LogicalKey
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.prefs.AccentColor
import org.matchat.core.ui.prefs.TextSizePreference
import org.matchat.core.ui.prefs.ThemeMode
import org.matchat.core.ui.prefs.UserPreferences
import org.matchat.core.ui.softkey.DirectionalKeyReceiver
import org.matchat.core.ui.softkey.LogicalKeyReceiver
import org.matchat.core.ui.theme.SystemBars
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

    @Inject lateinit var session: org.matchat.core.matrix.MatrixSession

    @Inject lateinit var updateManager: org.matchat.core.update.UpdateManager

    @Inject lateinit var syncHosts: SyncHosts

    @Inject lateinit var backgroundStatus: BackgroundSyncStatusProvider

    @Inject lateinit var contacts: org.matchat.core.contacts.ContactsRepository

    // Read via an EntryPoint, not @Inject: Hilt's own field injection runs
    // inside super.onCreate(), too late to setTheme() before it.
    private lateinit var userPreferences: UserPreferences

    // A room to open once the session is live (from a tapped notification on a
    // cold start). Consumed after restore routes to the room list.
    private var pendingRoomId: String? = null

    // An incoming call to open once the session is live (ring tapped from a cold
    // start). Cleared after it's shown.
    private var pendingCall: Triple<String, String, Boolean>? = null

    // "Opening the app lands on the room list unless from a notification"
    // (shouldGoHome, LaunchRouting.kt). Gathered across leaving and coming back.
    private var userLeft = false // onUserLeaveHint: Home/End, or our own launch
    private var launchedExternal = false // a picker/settings page we opened
    private var screenOnWhenStopped = false // false: the flip closed (screen off)
    private var stoppedForConfigChange = false
    private var openedFromNotification = false // onNewIntent with a room/call

    override fun onCreate(savedInstanceState: Bundle?) {
        userPreferences = EntryPointAccessors.fromApplication(
            applicationContext,
            UserPreferencesEntryPoint::class.java,
        ).userPreferences()
        setTheme(baseStyleFor(userPreferences.themeMode.value))
        theme.applyStyle(accentStyleFor(userPreferences.accentColor.value), true)
        theme.applyStyle(sizeStyleFor(userPreferences.textSize.value), true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val host = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        navController = host.navController
        // Only a genuine launch carries a notification to act on. With saved
        // state, `intent` is the one this activity was first started with — a
        // notification tapped long ago must not reopen its room.
        if (savedInstanceState == null) {
            pendingRoomId = intent?.getStringExtra(org.matchat.client.notify.MessageNotifier.EXTRA_ROOM_ID)
            intent?.let { stashCallIntent(it) }
        }
        requestNotificationsIfNeeded()
        // A recreate within this process (theme or text-size change) keeps its
        // screen. A cold start — or a restart after the process died, whose
        // restored back stack is dropped — lands on the room list.
        restoreSessionIfPresent(goHome = savedInstanceState == null || !navStartedThisProcess)
        navStartedThisProcess = true
        observeThemeChanges()
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(barFocusListener)
        checkForUpdates()
    }

    /** Auto-check for a newer GitHub Release on launch (throttled to once every
     *  few hours inside UpdateManager). Best-effort and silent: a hit just flags
     *  the Settings > Software update row; the user opens that screen to act. */
    private fun checkForUpdates() {
        lifecycleScope.launch { updateManager.checkForUpdate(force = false) }
    }

    /** A change made on the Theme settings screen only takes effect on a
     *  recreate — attrs already resolved into inflated Views don't update
     *  live. Skips the current (already-applied) value on each (re)subscribe,
     *  recreating only on a genuine subsequent change. */
    private fun observeThemeChanges() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    userPreferences.themeMode,
                    userPreferences.accentColor,
                    userPreferences.textSize,
                    ::Triple,
                )
                    .drop(1)
                    .collect { recreate() }
            }
        }
    }

    /** The base carries every role that doesn't depend on accent or size —
     *  see themes.xml's file header. */
    private fun baseStyleFor(mode: ThemeMode): Int = when (mode) {
        ThemeMode.LIGHT -> org.matchat.core.ui.R.style.Theme_MatChat_Base_Light
        ThemeMode.DARK -> org.matchat.core.ui.R.style.Theme_MatChat_Base_Dark
    }

    /** Layered onto the base via theme.applyStyle(_, force = true) — a flat
     *  lookup, one entry per accent, rather than a nested when (the old
     *  8-branch mode x accent shape this replaced). */
    private fun accentStyleFor(accent: AccentColor): Int = accentStyles.getValue(accent)

    private val accentStyles: Map<AccentColor, Int> = mapOf(
        AccentColor.MATCHAT to org.matchat.core.ui.R.style.Theme_MatChat_Accent_MatChat,
        AccentColor.GREEN to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Green,
        AccentColor.AMBER to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Amber,
        AccentColor.BLUE to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Blue,
        AccentColor.PLUM to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Plum,
        AccentColor.TEAL to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Teal,
        AccentColor.CYAN to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Cyan,
        AccentColor.INDIGO to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Indigo,
        AccentColor.VIOLET to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Violet,
        AccentColor.ORCHID to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Orchid,
        AccentColor.ROSE to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Rose,
        AccentColor.RUST to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Rust,
        AccentColor.OCHRE to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Ochre,
        AccentColor.OLIVE to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Olive,
        AccentColor.FOREST to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Forest,
        AccentColor.SLATE to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Slate,
        AccentColor.WINE to org.matchat.core.ui.R.style.Theme_MatChat_Accent_Wine,
    )

    /** Layered on top of the accent overlay (Settings > Text size, UX-SPEC
     *  §S16) — also applied via theme.applyStyle(_, force = true). */
    private fun sizeStyleFor(size: TextSizePreference): Int = when (size) {
        TextSizePreference.NORMAL -> org.matchat.core.ui.R.style.Theme_MatChat_Size_Normal
        TextSizePreference.SMALL -> org.matchat.core.ui.R.style.Theme_MatChat_Size_Small
        TextSizePreference.LARGE -> org.matchat.core.ui.R.style.Theme_MatChat_Size_Large
    }

    /** Settings > Text size's own rows set the same preference directly;
     *  this is also the "Hold * to change text size" shortcut Help promises
     *  (S14's help_text_size string) — a 3-way cycle rather than Normal's
     *  old binary toggle, now that Large exists too. Deliberately global
     *  (any screen), unlike Pinned messages' RIGHT shortcut (TimelineFragment's
     *  narrow, explicit DirectionalKeyReceiver exception) — Text size isn't
     *  scoped to one screen, so it's handled here rather than delegated to
     *  the current screen's LogicalKeyReceiver. */
    private fun toggleTextSize() {
        val next = when (userPreferences.textSize.value) {
            TextSizePreference.SMALL -> TextSizePreference.NORMAL
            TextSizePreference.NORMAL -> TextSizePreference.LARGE
            TextSizePreference.LARGE -> TextSizePreference.SMALL
        }
        lifecycleScope.launch { userPreferences.setTextSize(next) }
    }

    private val notificationPermission =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { /* best-effort; notifications simply stay silent if denied */ }

    private fun requestNotificationsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(perm)
        }
    }

    // One-time background setup after sign-in (UX-SPEC §S27, docs/adr/0008):
    // the system "run in background" (battery optimization) dialog, then — if
    // the background helper isn't on yet — the S27 prompt. Returning from the
    // dialog continues the sequence whatever the user chose.
    private val batteryExemptionRequest =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { continueBackgroundSetup() }

    /** Offered once per install (existing installs see it once after updating). */
    private fun offerBackgroundSetupIfNeeded() {
        if (backgroundStatus.setupOffered) {
            // Existing installs: S30 once after updating (UX-SPEC §S30).
            offerContactsPromptIfNeeded()
            return
        }
        backgroundStatus.markSetupOffered()
        backgroundStatus.refresh()
        if (backgroundStatus.status.value.batteryExempt || !requestBatteryExemption()) {
            continueBackgroundSetup()
        }
    }

    /** Launches the system dialog, or its settings list on builds that lack the
     *  dialog. False if neither exists; S27 then names the missing permission. */
    @android.annotation.SuppressLint("BatteryLife") // sideloaded messenger with no push: the documented exemption case
    private fun requestBatteryExemption(): Boolean {
        val direct = Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:$packageName"),
        )
        val list = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        // Try-and-catch rather than resolveActivity(): package visibility on API
        // 30+ can hide the Settings activity from resolveActivity itself.
        return listOf(direct, list).any { intent ->
            runCatching { batteryExemptionRequest.launch(intent) }.isSuccess
        }
    }

    private fun continueBackgroundSetup() {
        backgroundStatus.refresh()
        syncHosts.ensureRunning()
        // S30 goes on the back stack first, under S27, so leaving S27 (Back)
        // lands on it — after S27 when S27 shows, otherwise on its own.
        offerContactsPromptIfNeeded()
        if (!backgroundStatus.status.value.helperConnected) toBackgroundHelper()
    }

    /** S30, once per install (UX-SPEC §S30). Already allowed (e.g. granted
     *  earlier in system settings): just turn the phone copy on, no prompt. */
    private fun offerContactsPromptIfNeeded() {
        if (contacts.isPhonePromptOffered()) return
        contacts.markPhonePromptOffered()
        if (contacts.canUsePhoneContacts()) {
            lifecycleScope.launch { contacts.turnOnPhoneSync() }
        } else {
            toContactsPrompt()
        }
    }

    // Own presence: online while the app is foregrounded, unavailable when it
    // leaves. (The SDK exposes no way to read *other* users' presence, so this is
    // outbound only — there are no peer presence dots.)
    override fun onResume() {
        super.onResume()
        activeInstance = this
        val goHome = !stoppedForConfigChange && shouldGoHome(
            userLeft = userLeft,
            launchedExternal = launchedExternal,
            screenOnWhenStopped = screenOnWhenStopped,
            fromNotification = openedFromNotification,
            inCall = navController.currentDestination?.id == R.id.callFragment,
            hasSession = sessionStore.hasSession(),
        )
        if (goHome) toRoomListHome()
        screenOnWhenStopped = false
        stoppedForConfigChange = false
        openedFromNotification = false
        userLeft = false
        launchedExternal = false
        SystemBars.hideNavigation(this) // the white system soft-key bar (UX-SPEC §1)
        if (sessionStore.hasSession()) {
            // Make sure a sync host is running on every foreground (docs/adr/0008).
            // With the FGS as host, being in the foreground resets the Android 15
            // dataSync runtime budget, so this also recovers from a catch-up
            // handoff (ADR 0004). Also re-checks the battery exemption, so the
            // background helper takes over (or hands back) after the user changed
            // it in system settings. Idempotent when the host is already live.
            syncHosts.ensureRunning()
            lifecycleScope.launch { session.setPresence(online = true) }
        }
    }

    /** The white system bar shows while a text field has focus (its T9
     *  keyboard's word labels live there) and hides again once focus leaves
     *  it (SystemBars, UX-SPEC §1). */
    private val barFocusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus is android.widget.EditText) SystemBars.showNavigation(this) else SystemBars.hideNavigation(this)
    }

    override fun onDestroy() {
        runCatching { window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(barFocusListener) }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs bring the system bar back; hide it again (not while typing —
        // hideNavigation leaves it for a focused text field's keyboard).
        if (hasFocus) SystemBars.hideNavigation(this)
    }

    override fun onPause() {
        if (activeInstance === this) activeInstance = null
        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        userLeft = true
    }

    /** Every in-app launch of another app's screen (pickers, settings pages,
     *  share sheets — Activity, Fragment and ActivityResult launches all funnel
     *  here), so returning from it isn't mistaken for opening the app. */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        launchedExternal = true
        try {
            super.startActivityForResult(intent, requestCode, options)
        } catch (e: RuntimeException) {
            launchedExternal = false // nothing opened (e.g. ActivityNotFoundException)
            throw e
        }
    }

    override fun onStop() {
        super.onStop()
        screenOnWhenStopped = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        stoppedForConfigChange = isChangingConfigurations
        if (sessionStore.hasSession()) lifecycleScope.launch { session.setPresence(online = false) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openedFromNotification = intent.hasExtra(org.matchat.client.notify.CallNotifier.EXTRA_CALL_ROOM) ||
            intent.hasExtra(org.matchat.client.notify.MessageNotifier.EXTRA_ROOM_ID)
        if (intent.hasExtra(org.matchat.client.notify.CallNotifier.EXTRA_CALL_ROOM)) {
            stashCallIntent(intent)
            if (sessionStore.hasSession()) openPendingCall()
            return
        }
        val roomValue = intent.getStringExtra(org.matchat.client.notify.MessageNotifier.EXTRA_ROOM_ID) ?: return
        if (sessionStore.hasSession()) toRoom(RoomId(roomValue)) else pendingRoomId = roomValue
    }

    private fun stashCallIntent(intent: Intent) {
        val room = intent.getStringExtra(org.matchat.client.notify.CallNotifier.EXTRA_CALL_ROOM) ?: return
        val caller = intent.getStringExtra(org.matchat.client.notify.CallNotifier.EXTRA_CALL_CALLER).orEmpty()
        val answer = intent.getBooleanExtra(org.matchat.client.notify.CallNotifier.EXTRA_CALL_ANSWER, false)
        pendingCall = Triple(room, caller, answer)
    }

    private fun openPendingCall() {
        val (room, caller, answer) = pendingCall ?: return
        pendingCall = null
        navController.navigate(
            R.id.callFragment,
            bundleOf(
                ARG_ROOM_ID to room,
                "peerName" to caller,
                "incoming" to true,
                "answer" to answer,
            ),
        )
    }

    /** Cold start with a saved session: show the room list immediately and restore
     *  the SDK client in the background (S1 → S8). Restoring can take a few seconds
     *  on low-end hardware, so we must NOT sit on Welcome/Sign-in while it runs —
     *  that produced a sign-in flash on every launch. Only a genuine restore
     *  failure falls back to Welcome. Otherwise (no session) stay on Welcome (S2). */
    private fun restoreSessionIfPresent(goHome: Boolean) {
        if (!sessionStore.hasSession()) return
        if (goHome) toRoomListRoot() // replaces Welcome up front; the list shows until sync warms
        lifecycleScope.launch {
            if (auth.restoreSession().isSuccess) {
                pendingRoomId?.let {
                    pendingRoomId = null
                    toRoom(RoomId(it))
                }
                if (pendingCall != null) openPendingCall()
            } else {
                toWelcomeRoot()
            }
        }
    }

    // --- Global key dispatch ------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Long-press # / * are power-user shortcuts routed as hold keys.
        if (event.action == KeyEvent.ACTION_DOWN && event.isLongPress) {
            val hold = KeyMap.holdKey(event.keyCode)
            // Deliberately global (any screen), not delegated to the current
            // screen's LogicalKeyReceiver — see toggleTextSize()'s doc comment.
            if (hold == LogicalKey.STAR_HOLD) {
                toggleTextSize()
                return true
            }
            hold?.let { return receiver()?.onLogicalKey(it) ?: false }
        }
        // MENU is ours for the whole press (KeyMap.isMenuKey): its UP — and a
        // held key's repeats — must not reach AppCompat, which would open its
        // own options panel over the Options menu the DOWN just opened.
        if (KeyMap.isMenuKey(event.keyCode) && (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0)) {
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        // Diagnostic only (on-device report: right softkey doesn't open Options
        // even with the accessibility-service workaround on, and a competing
        // app's own key-intercepting accessibility service was ruled out as the
        // cause by disabling it and retesting) — this line proves whether normal
        // dispatch ever saw the raw key at all, distinct from the accessibility
        // path logged in handleAccessibilityKeyEvent below. Left in permanently;
        // one key event is not meaningful log volume.
        if (event.keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            Log.d(SOFTKEY_LOG_TAG, "dispatchKeyEvent: normal dispatch saw raw SOFT_RIGHT")
        }

        val logical = KeyMap.map(event, softkeysSwappedAfter(event))
            ?: return super.dispatchKeyEvent(event)
        // Directional keys stay with the platform focus search (XML order); only
        // the softkeys, CENTER and digits are offered to the screen first.
        // Narrow, explicit exception (Pinned messages quick-access round, mirrors
        // docs/adr/0007's softkey-swap exception): RIGHT specifically is offered
        // to the current screen first, ONLY if it opts in via
        // DirectionalKeyReceiver — every screen that doesn't implement it (i.e.
        // everything except TimelineFragment today) behaves exactly as before.
        if (logical == LogicalKey.RIGHT) {
            val consumed = (receiver() as? DirectionalKeyReceiver)?.onDirectionalKey(logical) ?: false
            if (consumed) return true
        }
        if (logical == LogicalKey.UP || logical == LogicalKey.DOWN ||
            logical == LogicalKey.LEFT || logical == LogicalKey.RIGHT
        ) {
            return super.dispatchKeyEvent(event)
        }
        val handled = receiver()?.onLogicalKey(logical) ?: false
        if (logical == LogicalKey.SOFT_LEFT || logical == LogicalKey.SOFT_RIGHT) {
            Log.d(
                SOFTKEY_LOG_TAG,
                "dispatchKeyEvent: logical=$logical handled=$handled receiver=${receiver()?.javaClass?.simpleName}",
            )
        }
        // Unhandled hardware call keys must reach the system (so the CALL key still
        // opens the dialer when no call screen consumes it — docs/VOICE.md §6).
        // Other unhandled keys stay swallowed, as before.
        if (!handled && (logical == LogicalKey.CALL || logical == LogicalKey.END)) {
            return super.dispatchKeyEvent(event)
        }
        return handled || receiver() == null && super.dispatchKeyEvent(event)
    }

    private fun receiver(): LogicalKeyReceiver? {
        val host = supportFragmentManager.findFragmentById(R.id.nav_host)
        return host?.childFragmentManager?.primaryNavigationFragment as? LogicalKeyReceiver
    }

    /** Entry point for
     *  [org.matchat.client.accessibility.MatChatKeyAccessibilityService] — see
     *  that class's own doc comment for the full story (a confirmed device
     *  conflict: the system's predictive-text IME consumes the right softkey
     *  before dispatchKeyEvent above ever sees it, only while composing).
     *  Simpler than dispatchKeyEvent: there's no Android dispatch chain to
     *  fall back to here (returning false just tells the service "didn't
     *  consume it, let it continue as normal" — the IME still gets a chance
     *  after that), and only the right softkey ever reaches this (the
     *  service filters to just that code before calling in; digits/D-pad/
     *  CENTER/holds/the left softkey never do, so T9 text entry — and every
     *  other key — is unaffected whether or not the service is enabled). */
    /** The softkey layout to map [event] with, after letting it set the layout
     *  automatically (KeyMap.detectSoftkeys: a MENU key puts Options on the
     *  left, a right softkey on the right). A change is saved and the bar
     *  re-renders through the softkeysSwapped flow; this key already uses the
     *  new layout, so the very first press does what its new label says. */
    private fun softkeysSwappedAfter(event: KeyEvent): Boolean {
        val detected = KeyMap.detectSoftkeys(event.keyCode, userPreferences.softkeyKindSeen.value)
            ?: return userPreferences.softkeysSwapped.value
        Log.d(SOFTKEY_LOG_TAG, "softkey layout detected: ${detected.kind} swapped=${detected.swapped}")
        lifecycleScope.launch {
            userPreferences.setSoftkeyKindSeen(detected.kind)
            userPreferences.setSoftkeysSwapped(detected.swapped)
        }
        return detected.swapped
    }

    private fun handleAccessibilityKeyEvent(event: KeyEvent): Boolean {
        val logical = KeyMap.map(event, softkeysSwappedAfter(event)) ?: return false
        val handled = receiver()?.onLogicalKey(logical) ?: false
        // Diagnostic only — see the matching log line in dispatchKeyEvent. This
        // one firing at all proves MatChatKeyAccessibilityService.onKeyEvent was
        // invoked and returned true for the key (it only forwards a raw
        // SOFT_RIGHT down here to begin with); if it never fires on a device
        // where the service is enabled, the key never reached MatChat through
        // either path — a strictly earlier, OS/other-app-level swallow.
        Log.d(SOFTKEY_LOG_TAG, "handleAccessibilityKeyEvent: logical=$logical handled=$handled")
        return handled
    }

    // --- Navigator ----------------------------------------------------------

    override fun toSignIn() = navController.navigate(R.id.signInFragment)

    override fun toRoomListRoot() {
        // A successful sign-in means a live session; own sync from here on.
        syncHosts.ensureRunning()
        toRoomListHome()
        offerBackgroundSetupIfNeeded()
    }

    /** The room list as the only screen. Pops the whole graph, not just
     *  Welcome, so a back stack restored after process death can't sit under
     *  it (opening the app always lands here — LaunchRouting.kt). */
    private fun toRoomListHome() {
        val alreadyHome = navController.currentDestination?.id == R.id.roomListFragment &&
            navController.previousBackStackEntry == null
        if (alreadyHome) return // keep the list (and its scroll) as it is
        val options = androidx.navigation.navOptions {
            popUpTo(R.id.nav_graph) { inclusive = true }
        }
        navController.navigate(R.id.roomListFragment, null, options)
    }

    override fun toWelcomeRoot() {
        // Signed out (not a failed restore, which keeps the stored session):
        // stop every sync host, the watchdog and the running notification.
        if (!sessionStore.hasSession()) syncHosts.stopAll()
        val options = androidx.navigation.navOptions {
            popUpTo(R.id.nav_graph) { inclusive = true }
        }
        navController.navigate(R.id.welcomeFragment, null, options)
    }

    override fun toRoom(roomId: RoomId) =
        navController.navigate(R.id.timelineFragment, bundleOf(ARG_ROOM_ID to roomId.value))

    override fun toImageViewer(eventId: EventId) =
        navController.navigate(R.id.imageViewerFragment, bundleOf(ARG_EVENT_ID to eventId.value))

    override fun toRoomInfo(roomId: RoomId) =
        navController.navigate(R.id.roomInfoFragment, bundleOf(ARG_ROOM_ID to roomId.value))

    override fun toPinnedMessages(roomId: RoomId) =
        navController.navigate(R.id.pinnedMessagesFragment, bundleOf(ARG_ROOM_ID to roomId.value))

    override fun toMessageInfo(roomId: RoomId, eventId: EventId, senderId: UserId, timestampEpochMs: Long) =
        navController.navigate(
            R.id.messageInfoFragment,
            bundleOf(
                ARG_ROOM_ID to roomId.value,
                ARG_EVENT_ID to eventId.value,
                ARG_SENDER_ID to senderId.value,
                ARG_TIMESTAMP to timestampEpochMs,
            ),
        )

    override fun toCall(roomId: RoomId, peerName: String?, incoming: Boolean) = navController.navigate(
        R.id.callFragment,
        bundleOf(
            ARG_ROOM_ID to roomId.value,
            "peerName" to peerName.orEmpty(),
            "incoming" to incoming,
        ),
    )

    override fun toInvites() = navController.navigate(R.id.invitesFragment)
    override fun toInvite(roomId: RoomId) =
        navController.navigate(R.id.inviteDetailFragment, bundleOf(ARG_ROOM_ID to roomId.value))

    override fun toSpace(spaceId: RoomId) =
        navController.navigate(R.id.spaceFragment, bundleOf(ARG_SPACE_ID to spaceId.value))

    override fun toJoinRoom(link: String) = navController.navigate(R.id.joinRoomFragment, bundleOf(ARG_LINK to link))

    override fun toNewChat() = navController.navigate(R.id.newChatFragment)
    override fun toTypeAddress() = navController.navigate(R.id.typeAddressFragment)
    override fun toVerification() = navController.navigate(R.id.verificationFragment)
    override fun toSettings() = navController.navigate(R.id.settingsFragment)
    override fun toTheme() = navController.navigate(R.id.themeFragment)
    override fun toTextSize() = navController.navigate(R.id.textSizeFragment)
    override fun toAdvanced() = navController.navigate(R.id.advancedFragment)
    override fun toBackgroundHelper() = navController.navigate(R.id.backgroundHelperFragment)

    override fun toContactsPrompt() = navController.navigate(R.id.contactsPromptFragment)
    override fun toNotifications() = navController.navigate(R.id.notificationsFragment)
    override fun toPolicy() = navController.navigate(R.id.policyFragment)
    override fun toUpdate() = navController.navigate(R.id.updateFragment)
    override fun toHelp() = navController.navigate(R.id.helpFragment)
    override fun back() {
        navController.navigateUp()
    }

    companion object {
        const val ARG_ROOM_ID = "roomId"
        const val ARG_EVENT_ID = "eventId"
        const val ARG_SENDER_ID = "senderId"
        const val ARG_TIMESTAMP = "timestamp"
        const val ARG_LINK = "link"
        const val ARG_SPACE_ID = "spaceId"

        // Diagnostic logging for the right-softkey/Options on-device report —
        // see the log call sites (dispatchKeyEvent, handleAccessibilityKeyEvent)
        // and MatChatKeyAccessibilityService's own doc comment for the full story.
        private const val SOFTKEY_LOG_TAG = "MatChatSoftkey"

        // Set/cleared in onResume/onPause — same process as
        // MatChatKeyAccessibilityService (no separate android:process declared
        // for it), so a plain reference is enough; no Binder/IPC needed. Null
        // whenever this Activity isn't the interactive foreground (matches
        // "is dispatchKeyEvent even reachable right now" as closely as a
        // service running independently of the Activity lifecycle can).
        @Volatile private var activeInstance: MainActivity? = null

        /** Set once a MainActivity has routed in this process, to tell an
         *  in-process recreate (keep the screen) from a restart after process
         *  death (land on the room list) — both arrive with saved state. */
        @Volatile private var navStartedThisProcess = false

        /** Called by the accessibility service when it intercepts the right
         *  softkey. Returns false (don't consume) if there's no foreground
         *  MainActivity to hand it to — the key then continues through the
         *  normal platform pipeline exactly as if this service didn't exist. */
        fun handleExternalSoftkey(event: KeyEvent): Boolean =
            activeInstance?.handleAccessibilityKeyEvent(event) ?: false
    }
}
