package org.matchat.core.ui.softkey

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import org.matchat.core.model.SyncState
import org.matchat.core.ui.R
import org.matchat.core.ui.databinding.ViewChromeBinding
import org.matchat.core.ui.key.LogicalKey

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
 */
abstract class SoftkeyFragment : Fragment(), LogicalKeyReceiver {

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
        binding.chromeSoftkeys.render(leftLabel, centerLabel, rightLabel)
        // Tapping a softkey label behaves exactly like its hardware key (useful on
        // an emulator / touch device; a no-op on a real feature phone).
        binding.chromeSoftkeys.onKey = { key -> onLogicalKey(key) }
        onContentViewCreated(binding.chromeContent.getChildAt(0))
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
        chrome?.chromeSoftkeys?.render(leftLabel, centerLabel, rightLabel)
    }

    protected fun setTitle(title: CharSequence) {
        chrome?.chromeTitle?.text = title
    }

    /** Title-bar sync glyph: ⟳ syncing, ! offline, nothing otherwise (UX-SPEC §1). */
    protected fun setSyncGlyph(state: SyncState) {
        chrome?.chromeSync?.text = when (state) {
            SyncState.SYNCING -> getString(R.string.glyph_syncing)
            SyncState.OFFLINE, SyncState.ERROR -> getString(R.string.glyph_offline)
            SyncState.IDLE -> ""
        }
    }

    // --- Key contract ------------------------------------------------------

    final override fun onLogicalKey(key: LogicalKey): Boolean = when (key) {
        LogicalKey.SOFT_LEFT -> onOptions()
        LogicalKey.SOFT_RIGHT -> onBack()
        LogicalKey.CENTER -> onCenter()
        // UP/DOWN fall through to the platform focus search (XML order).
        else -> false
    }

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
