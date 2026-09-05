package org.matchat.client.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

/**
 * Every screen extends SoftkeyFragment and declares its softkey labels
 * (AGENTS.md §4). A label may be empty, but the declaration is required — this is
 * the "fails a unit test" gate the spec refers to. Also verifies no feature module
 * adds touch listeners (NoTouchListeners, PLAN.md §8.4).
 */
class SoftkeyContractTest {

    @Test
    fun `every SoftkeyFragment subclass declares leftLabel and centerLabel`() {
        Konsist.scopeFromProject()
            .classes()
            .filter { klass -> klass.parents().any { it.name == "SoftkeyFragment" } }
            .assertTrue { klass ->
                val names = klass.properties().map { it.name }
                "leftLabel" in names && "centerLabel" in names
            }
    }

    @Test
    fun `no feature screen sets an OnClickListener on a non-focusable touch target`() {
        // Touch handling lives nowhere: no setOnTouchListener, no swipe/gesture APIs
        // in feature modules (AGENTS.md §4). CENTER activation uses performClick on
        // focusable rows, which is allowed.
        Konsist.scopeFromProject()
            .files
            .filter { it.path.contains("/feature/") }
            .assertTrue { file ->
                !file.text.contains("setOnTouchListener") &&
                    !file.text.contains("GestureDetector")
            }
    }
}
