package org.matchat.client.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Test

/**
 * G3 as code: no directory or user-search API is reachable from any code path
 * (PLAN.md §8.4 NoDiscoveryApis). "Knowing an address is allowed; finding one is
 * not" (AGENTS.md §0). This scans source text for the endpoints and shapes that
 * would reintroduce discovery.
 */
class DiscoveryApiTest {

    private val bannedTokens = listOf(
        "user_directory/search",
        "publicRooms",
        "public_rooms",
        "searchUserDirectory",
        "roomDirectorySearch",
        "/directory/",
    )

    @Test
    fun `no discovery or search endpoints appear anywhere in source`() {
        Konsist.scopeFromProject()
            .files
            .filter { it.path.endsWith(".kt") }
            .filterNot { it.path.contains("/arch/") } // this test names the tokens
            .assertFalse { file -> bannedTokens.any { file.text.contains(it) } }
    }
}
