package org.matchat.client.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

/**
 * The §5 dependency rules, enforced mechanically (PLAN.md §8.4). These are the
 * Konsist standins for the custom Detekt rules named in PLAN.md; they fail the
 * PR the same way. Run in CI as `:app:testDebugUnitTest --tests "*.arch.*"`.
 */
class ArchitectureTest {

    @Test
    fun `only core matrix imports the Rust SDK`() {
        Konsist.scopeFromProject()
            .files
            .filter { file -> file.imports.any { it.name.startsWith("org.matrix.rustcomponents") } }
            .assertTrue { it.path.contains("/core/matrix/") }
    }

    @Test
    fun `no SDK type name leaks into feature or other core modules`() {
        // Belt-and-braces for the import rule: catch fully-qualified references too.
        Konsist.scopeFromProject()
            .files
            .filter { !it.path.contains("/core/matrix/") }
            .filterNot { it.path.contains("/arch/") } // these rule files name the token
            .assertFalse { it.text.contains("org.matrix.rustcomponents") }
    }

    @Test
    fun `feature modules do not depend on each other`() {
        val featureOf = Regex("/feature/([^/]+)/")
        Konsist.scopeFromProject()
            .files
            .filter { it.path.contains("/feature/") }
            .assertFalse { file ->
                val own = featureOf.find(file.path)?.groupValues?.get(1)
                file.imports.any { imp ->
                    imp.name.startsWith("org.matchat.feature.") &&
                        own != null && !imp.name.startsWith("org.matchat.feature.$own.")
                }
            }
    }

    @Test
    fun `a ViewModel never imports android view, android widget, or core ui`() {
        Konsist.scopeFromProject()
            .classes()
            .filter { it.name.endsWith("ViewModel") }
            .assertFalse { klass ->
                klass.containingFile.imports.any {
                    it.name.startsWith("android.view") ||
                        it.name.startsWith("android.widget") ||
                        it.name.startsWith("org.matchat.core.ui")
                }
            }
    }

    @Test
    fun `a Fragment never imports core matrix`() {
        Konsist.scopeFromProject()
            .classes()
            .filter { it.name.endsWith("Fragment") }
            .assertFalse { klass ->
                klass.containingFile.imports.any { it.name.startsWith("org.matchat.core.matrix") }
            }
    }
}
