package io.sdkman.state.domain.model

import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.none
import arrow.core.some
import arrow.core.toOption

/**
 * A release series: the set of rows that share a candidate, distribution, platform,
 * major line and variant. It is the unit within which exactly one version should be
 * advertised, so publishing into a series retires every other member of it.
 *
 * Build metadata is deliberately excluded from the key: it is what distinguishes the
 * members of a series from one another, and `26.0.2` versus `26.0.2+1.1` is precisely
 * the duplication that supersession collapses.
 */
data class SeriesKey(
    val candidate: String,
    val distribution: Option<Distribution>,
    val platform: Platform,
    val major: Int,
    val variant: Option<String>,
) {
    /**
     * The versions that belong to this series, as a regular expression over the version
     * string: the same grammar [of] parses, narrowed to this key's major line and variant.
     *
     * It is written in the syntax Kotlin and POSIX extended regular expressions share, so a
     * caller can push it down into SQL and let the database exclude the rows that cannot be
     * members. Membership itself is still decided by [of] — this only narrows what has to be
     * considered, and with it the rows a locking read has to hold.
     */
    fun versionPattern(): String = "^$major\\.(?:$NUMERIC)\\.(?:$NUMERIC)${variantPattern()}\$"

    private fun variantPattern(): String =
        variant
            .map { "(?:\\.$it|-$it(?:$BUILD)?)" }
            .getOrElse { "(?:$BUILD)?" }

    companion object {
        /**
         * Series membership is decided by this explicit grammar and not by the broader
         * legacy-to-semverish mapping, which would fold runtime targets (`25.0.4.r25`)
         * and rebuild counters (`11.0.14.1`) into the plain series and make them
         * retirable. Only four shapes take part in a series:
         *
         * ```
         * <major>.<minor>.<patch>                       plain
         * <major>.<minor>.<patch>.<variant>             legacy variant spelling
         * <major>.<minor>.<patch>-<variant>[+<build>]   semverish
         * <major>.<minor>.<patch>[+<build>]             semverish, no variant
         * ```
         *
         * Anything else is ineligible: it takes part in no series, so it is never
         * retired and never retires anything. That fails safe — an unrecognised
         * spelling stays advertised rather than being silently hidden.
         *
         * These constants are the one statement of that grammar for running code: both
         * the eligibility test here and the SQL prefilter of [versionPattern] are built
         * from them, so the query cannot drift from the parser. The `V18` backlog
         * migration mirrors the grammar by hand — a migration cannot call Kotlin, and an
         * applied one is frozen — so a later backlog-style migration must be written
         * against this grammar rather than copied from `V18`.
         *
         * They are also kept to the syntax Kotlin and POSIX extended regular expressions
         * share (`[0-9]` rather than `\d`), so [versionPattern] is valid in both.
         */
        private const val NUMERIC = "0|[1-9][0-9]*"
        private const val CORE = "($NUMERIC)\\.(?:$NUMERIC)\\.(?:$NUMERIC)"
        private const val VARIANT = "fx|crac"
        private const val IDENTIFIER = "[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?"
        private const val BUILD = "\\+$IDENTIFIER(?:\\.$IDENTIFIER)*"

        private val ELIGIBLE_PATTERN =
            Regex("^$CORE(?:\\.($VARIANT)|-($VARIANT)(?:$BUILD)?|$BUILD)?$")

        private const val MAJOR_GROUP = 1
        private const val LEGACY_VARIANT_GROUP = 2
        private const val SEMVERISH_VARIANT_GROUP = 3

        fun of(
            candidate: String,
            distribution: Option<Distribution>,
            platform: Platform,
            version: String,
        ): Option<SeriesKey> =
            ELIGIBLE_PATTERN
                .matchEntire(version)
                .toOption()
                .flatMap { match ->
                    match.groupValues[MAJOR_GROUP].toIntOrNull().toOption().map { major ->
                        SeriesKey(
                            candidate = candidate,
                            distribution = distribution,
                            platform = platform,
                            major = major,
                            variant = variantOf(match),
                        )
                    }
                }

        private fun variantOf(match: MatchResult): Option<String> =
            when {
                match.groupValues[LEGACY_VARIANT_GROUP].isNotEmpty() ->
                    match.groupValues[LEGACY_VARIANT_GROUP].some()

                match.groupValues[SEMVERISH_VARIANT_GROUP].isNotEmpty() ->
                    match.groupValues[SEMVERISH_VARIANT_GROUP].some()

                else -> none()
            }
    }
}
