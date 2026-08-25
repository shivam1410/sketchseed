package com.shivam.sketchseed.update

/**
 * A dotted numeric version, as it appears on a git tag.
 *
 * This exists because the two sides being compared are written by different
 * hands. The installed version comes from the build file; the candidate comes
 * from whatever string was typed as a tag — `v1.1` and `v1.1.1` are both real
 * tags in this project's history. So the comparison has to cope with a leading
 * `v`, with the two sides having different numbers of components, and with a
 * tag it cannot read at all.
 */
data class ReleaseVersion(val parts: List<Int>) : Comparable<ReleaseVersion> {

    /** Pads the shorter side with zeros, so `1.2` and `1.2.0` are the same version. */
    override fun compareTo(other: ReleaseVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val mine = parts.getOrElse(i) { 0 }
            val theirs = other.parts.getOrElse(i) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return 0
    }

    companion object {

        /**
         * Reads [raw], or null if it is not a plain dotted number.
         *
         * A trailing suffix — `-rc1`, `+build3` — is dropped rather than ranked.
         * Ordering pre-releases correctly is a specification of its own, and this
         * project has never published one; ignoring the suffix means the worst
         * case is that a pre-release of an already-installed version is treated
         * as the same version and nothing is offered. Staying put is the right
         * failure.
         */
        fun parse(raw: String): ReleaseVersion? {
            val numeric = raw.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')

            if (numeric.isEmpty()) return null

            val parts = numeric.split('.').map { component ->
                // toIntOrNull would accept a leading "+" or "-"; a version
                // component is digits and nothing else.
                if (component.isEmpty() || !component.all(Char::isDigit)) return null
                component.toIntOrNull() ?: return null
            }

            return ReleaseVersion(parts)
        }

        /**
         * Whether [candidate] is worth offering to someone running [installed].
         *
         * False whenever either side cannot be read. An update prompt is a
         * request to replace a working app, so "I could not tell" has to resolve
         * to silence rather than to a download.
         */
        fun isUpgrade(installed: String, candidate: String): Boolean {
            val here = parse(installed) ?: return false
            val there = parse(candidate) ?: return false
            return there > here
        }
    }
}
