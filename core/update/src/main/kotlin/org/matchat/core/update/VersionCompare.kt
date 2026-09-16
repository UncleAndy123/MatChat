package org.matchat.core.update

/**
 * Compares two app version strings by their integer groups, left to right.
 *
 * The release tag is `v<versionName>` (see .github/workflows/release.yml) and
 * versionName looks like `0.1.0-M0`. We can't lean on versionCode — it's a
 * hardcoded constant the release flow doesn't bump — so we compare the version
 * *name* structurally: pull every run of digits out ("0.1.0-M0" -> [0,1,0,0])
 * and compare element by element, padding the shorter list with zeros. This
 * orders both the semver core (0.2.0 > 0.1.0) and the milestone suffix
 * (…-M1 > …-M0) the way a human would, without a full semver parser.
 *
 * A leading `v`/`V` and surrounding whitespace are ignored, so a raw tag and a
 * versionName compare equal when they name the same release.
 */
internal object VersionCompare {

    private val DIGITS = Regex("\\d+")

    /** Groups of consecutive digits, in order; empty when the string has none. */
    private fun parts(version: String): List<Long> =
        DIGITS.findAll(version.trim().removePrefix("v").removePrefix("V"))
            .map { it.value.toLong() }
            .toList()

    /** Negative if [a] < [b], zero if equal, positive if [a] > [b]. */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val cmp = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        return 0
    }

    /** True when [candidate] names a strictly newer release than [current]. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}
