package org.matchat.core.ui.softkey

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import org.matchat.core.ui.R
import org.matchat.core.ui.databinding.ViewSoftkeyBarBinding
import org.matchat.core.ui.key.LogicalKey

/**
 * Renders the three softkey labels (UX-SPEC §1). A label may be empty (a screen
 * with no Options leaves LEFT blank) but the three cells always exist so the bar
 * never reflows.
 *
 * The cells are also tappable, dispatching the same [LogicalKey] as the matching
 * hardware key (LEFT=Options, CENTRE=activate, RIGHT=Back). Real feature phones
 * have no touchscreen, so the taps are a no-op there and cost nothing; on an
 * emulator or a touch device they make the bar usable without a D-pad. A blank
 * cell is not tappable.
 */
class SoftkeyBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ViewSoftkeyBarBinding

    /** Set by [SoftkeyFragment] to receive taps as logical keys. */
    var onKey: ((LogicalKey) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.surface_inverse))
        binding = ViewSoftkeyBarBinding.inflate(android.view.LayoutInflater.from(context), this)
        isFocusable = false
        isFocusableInTouchMode = false
        binding.softkeyLeft.setOnClickListener { onKey?.invoke(LogicalKey.SOFT_LEFT) }
        binding.softkeyCenter.setOnClickListener { onKey?.invoke(LogicalKey.CENTER) }
        binding.softkeyRight.setOnClickListener { onKey?.invoke(LogicalKey.SOFT_RIGHT) }
    }

    fun render(left: CharSequence, center: CharSequence, right: CharSequence) {
        binding.softkeyLeft.text = left
        binding.softkeyCenter.text = center
        binding.softkeyRight.text = right
        // Only offer a tap target where there is a label.
        binding.softkeyLeft.isClickable = left.isNotEmpty()
        binding.softkeyCenter.isClickable = center.isNotEmpty()
        binding.softkeyRight.isClickable = right.isNotEmpty()
    }
}
