package org.matchat.core.ui.softkey

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.AttrRes
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.matchat.core.model.SyncState
import org.matchat.core.model.SyncStateSource
import org.matchat.core.ui.R
import org.matchat.core.ui.databinding.ViewChromeBinding
import org.matchat.core.ui.key.LogicalKey
import org.matchat.core.ui.prefs.UserPreferences
import org.matchat.core.ui.theme.themeColor
import javax.inject.Inject

/** SYNCING (connected) -> green; OFFLINE/ERROR (not connected) -> red; IDLE
 *  (no active session — before login or after logout) -> null, meaning
 *  "hide the dot" rather than pick a color for a state with nothing to show
 *  (Online indicator round). A top-level pure function, same reasoning as
 *  [mirroredLabels] below — testable without a Fragment/Robolectric. */
@AttrRes
internal fun connectionDotColorAttr(state: SyncState): Int? = when (state) {
    SyncState.IDLE -> null
    SyncState.SYNCING -> R.attr.colorEncrypted
    SyncState.OFFLINE, SyncState.ERROR -> R.attr.colorError
}

/** Swaps which screen position (left/right) shows which label when the
 *  physical keys are swapped (Settings > Advanced, Phase 6 of the UI
 *  improvement plan) — the RIGHT label becomes reachable via the LEFT
 *  hardware key (and vice versa), so the on-screen text must swap position
 *  too, or it would visually lie about which physical button does what.
 *  A top-level pure function (not a private Fragment method), so
 *  SoftkeyMirrorTest exercises it without a Fragment harness. */
internal fun mirroredLabels(
    left: CharSequence,
    center: CharSequence,
    right: CharSequence,
    swapped: Boolean,
): Triple<CharSequence, CharSequence, CharSequence> =
    if (swapped) Triple(right, center, left) else Triple(left, center, right)

/**
 * Every screen extends this (AGENTS.md §4). It owns the three chrome bands and
 * the softkey contract:
 *
 *  - LEFT softkey  = Options ([onOptions])
 *  - RIGHT softkey = Back    ([onBack])
 *  - CENTER        = activate the focused item ([onCenter])
 *
 * A subclass declares the three labels and its content layout; it never handles a
 * raw keycode and never reassigns a key to a different meaning. A subclass MUST
 * declare all three labels — [SoftkeyLabelsDeclaredTest] fails a screen that
 * leaves one unset. A label may be empty; the declaration may not be omitted.
 *
 * LEFT/RIGHT stay Options/Back *semantically* even with the swap preference on
 * (KeyMap already normalized which raw keycode produces which [LogicalKey] by
 * the time [onLogicalKey] sees it — this class's own key contract above never
 * changes); only [renderSoftkeys] mirrors which screen position shows which
 * label, to match.
 */
abstract class SoftkeyFragment : Fragment(), LogicalKeyReceiver {

    @Inject lateinit var userPreferences: UserPreferences

    /** Online indicator round: injected here (not read manually per-screen)
     *  so every screen shows correct, live connection state without each
     *  one wiring it — previously only RoomListFragment passed real state,
     *  TimelineFragment hardcoded IDLE, and ~15 other screens never called
     *  the old setSyncGlyph at all. */
    @Inject lateinit var syncStateSource: SyncStateSource

    private var chrome: ViewChromeBinding? = null

    /** The feature layout inflated into the content band. */
    @get:LayoutRes
    protected abstract val contentLayoutId: Int

    /** Softkey labels. RIGHT is Back on every screen without exception. */
    abstract val leftLabel: CharSequence
    abstract val centerLabel: CharSequence
    open val rightLabel: CharSequence get() = getString(R.string.softkey_back)

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = ViewChromeBinding.inflate(inflater, container, false)
        chrome = binding
        inflater.inflate(contentLayoutId, binding.chromeContent, true)
        renderSoftkeys()
        // Tapping a softkey label behaves exactly like its hardware key (useful on
        // an emulator / touch device; a no-op on a real feature phone).
        binding.chromeSoftkeys.onKey = { key -> onLogicalKey(key) }
        onContentViewCreated(binding.chromeContent.getChildAt(0))
        observeSoftkeySwap()
        observeSyncState()
        return binding.root
    }

    override fun onDestroyView() {
        chrome = null
        super.onDestroyView()
    }

    /** Bind the feature layout here (equivalent of a normal onViewCreated). */
    protected abstract fun onContentViewCreated(content: View)

    /** Re-render the softkey labels after a state change (e.g. compose → Send). */
    protected fun refreshSoftkeys() {
        renderSoftkeys()
    }

    private fun renderSoftkeys() {
        val swapped = userPreferences.softkeysSwapped.value
        val (left, center, right) = mirroredLabels(leftLabel, centerLabel, rightLabel, swapped)
        chrome?.chromeSoftkeys?.render(left, center, right, swapped)
    }

    /** Re-renders if the swap preference changes while this screen is visible
     *  (e.g. the user backs out of Settings > Advanced into a room list that
     *  was already on the back stack). The very first render above already
     *  reads the current value directly (StateFlow.value), so there's no
     *  flash of the wrong labels before this collector's first emission. */
    private fun observeSoftkeySwap() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                userPreferences.softkeysSwapped.collect { renderSoftkeys() }
            }
        }
    }

    protected fun setTitle(title: CharSequence) {
        chrome?.chromeTitle?.text = title
    }

    /** The optional avatar just left of the title (hidden unless a screen
     *  shows it — today only the conversation screen does, S9). Null before
     *  the view exists / after it's destroyed. */
    protected val titleAvatar: ImageView? get() = chrome?.chromeTitleAvatar

    /** Live on every screen (Online indicator round) — no per-screen call
     *  needed, unlike the old setSyncGlyph this replaces. The very first
     *  emission lands before the first frame draws (StateFlow.value read
     *  immediately on collect), so there's no flash of stale state. */
    private fun observeSyncState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                syncStateSource.syncState.collect(::renderSyncState)
            }
        }
    }

    /** Title-bar sync glyph (⟳ syncing, ! offline, nothing otherwise — UX-SPEC
     *  §1) plus the small connection dot beside it (Online indicator round).
     *  The glyph stays the primary cue — color is never the only signal
     *  (AGENTS.md) — the dot is a secondary at-a-glance addition. */
    private fun renderSyncState(state: SyncState) {
        chrome?.chromeSync?.text = when (state) {
            SyncState.SYNCING -> getString(R.string.glyph_syncing)
            SyncState.OFFLINE, SyncState.ERROR -> getString(R.string.glyph_offline)
            SyncState.IDLE -> ""
        }
        val dot = chrome?.chromeConnectionDot ?: return
        val attr = connectionDotColorAttr(state)
        dot.isVisible = attr != null
        if (attr == null) return
        val color = dot.context.themeColor(attr)
        val drawable = dot.background as? GradientDrawable ?: GradientDrawable().also { dot.background = it }
        drawable.shape = GradientDrawable.OVAL
        drawable.setColor(color)
    }

    // --- Key contract ------------------------------------------------------

    final override fun onLogicalKey(key: LogicalKey): Boolean = when (key) {
        LogicalKey.SOFT_LEFT -> onOptions()
        LogicalKey.SOFT_RIGHT -> onBack()
        LogicalKey.CENTER -> onCenter()
        // UP/DOWN/LEFT/RIGHT never arrive here (the host sends them to the platform
        // focus search); digits and holds do — a screen opts in via [onOtherKey].
        else -> onOtherKey(key)
    }

    /** Non-softkey, non-CENTER keys (digits, # / * holds). Default: ignored. A
     *  screen overrides this to use the keypad for its own actions (e.g. the image
     *  viewer pans on 2/4/6/8 and resets on 0). */
    protected open fun onOtherKey(key: LogicalKey): Boolean = false

    /** LEFT softkey. Default: no options. Override to open the screen's menu. */
    protected open fun onOptions(): Boolean = false

    /** RIGHT softkey. Default: pop the back stack via the host activity. */
    protected open fun onBack(): Boolean {
        requireActivity().onBackPressedDispatcher.onBackPressed()
        return true
    }

    /** CENTER. Default: activate the focused view (rows are the click target). */
    protected open fun onCenter(): Boolean {
        val focused = chrome?.root?.findFocus() ?: return false
        return focused.performClick()
    }
}
