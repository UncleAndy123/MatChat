package org.matchat.core.ui.theme

import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.matchat.core.ui.R
import org.robolectric.RobolectricTestRunner

/** Mirrors [ThemeColorTest]'s pattern for [themeDimenPx] — the Text size
 *  setting round's dimension counterpart to [themeColor]. Normal is the
 *  larger, default set (the `_large` dimens); Small is the reduced option. */
@RunWith(RobolectricTestRunner::class)
class ThemeDimenTest {

    private fun themedContext(sizeStyleRes: Int) = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(),
        R.style.Theme_MatChat_Base_Light,
    ).apply {
        theme.applyStyle(R.style.Theme_MatChat_Accent_Green, true)
        theme.applyStyle(sizeStyleRes, true)
    }

    @Test
    fun `Normal size resolves textSizeBody to text_body_large`() {
        val context = themedContext(R.style.Theme_MatChat_Size_Normal)
        val resolved = context.themeDimenPx(R.attr.textSizeBody)
        assertEquals(context.resources.getDimension(R.dimen.text_body_large), resolved)
    }

    @Test
    fun `Small size resolves textSizeBody to text_body, not the Normal value`() {
        val context = themedContext(R.style.Theme_MatChat_Size_Small)
        val resolved = context.themeDimenPx(R.attr.textSizeBody)
        assertEquals(context.resources.getDimension(R.dimen.text_body), resolved)
    }

    @Test
    fun `Normal size resolves rowMinHeight to row_min_height_large`() {
        val context = themedContext(R.style.Theme_MatChat_Size_Normal)
        val resolved = context.themeDimenPx(R.attr.rowMinHeight)
        assertEquals(context.resources.getDimension(R.dimen.row_min_height_large), resolved)
    }

    @Test
    fun `Large size grows the softkey labels and both chrome bars`() {
        val context = themedContext(R.style.Theme_MatChat_Size_Large)
        val res = context.resources
        assertEquals(res.getDimension(R.dimen.text_softkey_label_xlarge), context.themeDimenPx(R.attr.textSizeSoftkey))
        assertEquals(res.getDimension(R.dimen.softkey_bar_height_xlarge), context.themeDimenPx(R.attr.softkeyBarHeight))
        assertEquals(res.getDimension(R.dimen.title_bar_height_xlarge), context.themeDimenPx(R.attr.titleBarHeight))
    }

    @Test
    fun `Small size shrinks the softkey labels`() {
        val context = themedContext(R.style.Theme_MatChat_Size_Small)
        val resolved = context.themeDimenPx(R.attr.textSizeSoftkey)
        assertEquals(context.resources.getDimension(R.dimen.text_softkey_label), resolved)
    }
}
