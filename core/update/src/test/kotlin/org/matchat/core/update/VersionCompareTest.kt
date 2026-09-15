package org.matchat.core.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionCompareTest {

    @Test
    fun `newer semver core is newer`() {
        assertTrue(VersionCompare.isNewer("0.2.0-M0", "0.1.0-M0"))
        assertTrue(VersionCompare.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `newer milestone suffix at same core is newer`() {
        assertTrue(VersionCompare.isNewer("0.1.0-M1", "0.1.0-M0"))
    }

    @Test
    fun `leading v and equality are handled`() {
        assertTrue(VersionCompare.compare("v0.1.0-M0", "0.1.0-M0") == 0)
        assertFalse(VersionCompare.isNewer("v0.1.0-M0", "0.1.0-M0"))
    }

    @Test
    fun `older is not newer`() {
        assertFalse(VersionCompare.isNewer("0.1.0-M0", "0.2.0-M0"))
        assertFalse(VersionCompare.isNewer("0.1.0-M0", "0.1.0-M1"))
    }

    @Test
    fun `missing groups pad with zero`() {
        assertTrue(VersionCompare.isNewer("0.1.1", "0.1"))
        assertFalse(VersionCompare.isNewer("0.1", "0.1.0"))
    }
}
