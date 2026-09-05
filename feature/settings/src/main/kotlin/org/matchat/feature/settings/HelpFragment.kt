package org.matchat.feature.settings

import android.view.View
import android.view.ViewGroup
import dagger.hilt.android.AndroidEntryPoint
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.softkey.SoftkeyFragment

/** S14 Help — static key hints. LEFT is blank; the list is the whole screen. */
@AndroidEntryPoint
class HelpFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_help
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_select)

    override fun onContentViewCreated(content: View) {
        setTitle(getString(R.string.help_title))
        val list = (content as ViewGroup).getChildAt(0) as ViewGroup
        FocusEngine.requestInitialFocus(list.getChildAt(0))
    }
}
