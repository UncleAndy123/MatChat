package org.matchat.core.ui.menu

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import org.matchat.core.ui.R

/** One row of a [MenuSheet]. [enabled] false renders greyed and is not focusable. */
data class MenuItem(
    val id: String,
    val label: CharSequence,
    val enabled: Boolean = true,
)

/**
 * The only menu construct in the app (ARCHITECTURE.md, UX-SPEC S11): a
 * bottom-anchored list of focusable rows, max ~5, dismissed with RIGHT/BACK.
 * The menu *is* the options list, so the caller leaves its own LEFT softkey blank
 * while it is open. No touch-only dismiss — BACK always closes it (AGENTS.md §9).
 */
object MenuSheet {

    fun show(
        context: Context,
        items: List<MenuItem>,
        onSelect: (MenuItem) -> Unit,
    ): Dialog {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface_bright))
        }

        val dialog = AppCompatDialog(context, R.style.Theme_MatChat_Menu)
        items.forEach { item ->
            list.addView(rowFor(context, item) {
                onSelect(item)
                dialog.dismiss()
            })
        }
        dialog.setContentView(list)
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCancelable(true) // BACK dismisses; there is no touch-scrim dependence
        dialog.show()
        list.getChildAt(0)?.requestFocus()
        return dialog
    }

    private fun rowFor(context: Context, item: MenuItem, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = item.label
            textSize = MENU_ROW_TEXT_SP
            setTextColor(ContextCompat.getColor(context, R.color.text_on_focus))
            minHeight = context.resources.getDimensionPixelSize(R.dimen.row_min_height_compact)
            gravity = Gravity.CENTER_VERTICAL
            val pad = context.resources.getDimensionPixelSize(R.dimen.content_pad)
            setPadding(pad, pad, pad, pad)
            isEnabled = item.enabled
            isFocusable = item.enabled
            isFocusableInTouchMode = false
            setBackgroundResource(R.drawable.focus_selector)
            if (item.enabled) setOnClickListener { onClick() }
        }

    private const val MENU_ROW_TEXT_SP = 16f // body floor (PLAN.md G5)
}
