package org.matchat.feature.verification

import android.view.View
import android.view.ViewGroup
import dagger.hilt.android.AndroidEntryPoint
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.softkey.SoftkeyFragment

/**
 * S6 Emoji verification (SAS). RIGHT reads Cancel here (still Back semantics —
 * it returns without a different meaning). The match/no-match actions bind to the
 * SDK verification flow at M4.
 */
@AndroidEntryPoint
class VerificationFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_verification
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_options)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)
    override val rightLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_cancel)

    override fun onContentViewCreated(content: View) {
        setTitle(getString(R.string.verify_title))
        val match = (content as ViewGroup).findViewById<View>(R.id.verify_match)
        FocusEngine.requestInitialFocus(match)
    }
}
