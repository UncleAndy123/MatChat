package org.matchat.core.ui.softkey

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import org.matchat.core.ui.R
import org.matchat.core.ui.databinding.ViewSoftkeyBarBinding

/**
 * Renders the three softkey labels (UX-SPEC §1). A label may be empty (a screen
 * with no Options leaves LEFT blank) but the three cells always exist so the bar
 * never reflows. This view holds no logic — the hardware keys are handled by
 * [org.matchat.core.ui.softkey.SoftkeyFragment].
 */
class SoftkeyBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ViewSoftkeyBarBinding

    init {
        orientation = HORIZONTAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.surface_inverse))
        binding = ViewSoftkeyBarBinding.inflate(android.view.LayoutInflater.from(context), this)
        isFocusable = false
        isFocusableInTouchMode = false
    }

    fun render(left: CharSequence, center: CharSequence, right: CharSequence) {
        binding.softkeyLeft.text = left
        binding.softkeyCenter.text = center
        binding.softkeyRight.text = right
    }
}
