package io.sdkman.state.domain.model

import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.none
import arrow.core.some
import arrow.core.toOption

data class SeriesKey(
    val candidate: String,
    val distribution: Option<Distribution>,
    val platform: Platform,
    val major: Int,
    val variant: Option<String>,
) {
    fun versionPattern(): String = "^$major\\.(?:$NUMERIC)\\.(?:$NUMERIC)${variantPattern()}\$"

    private fun variantPattern(): String =
        variant
            .map { "(?:\\.$it|-$it(?:$BUILD)?)" }
            .getOrElse { "(?:$BUILD)?" }

    companion object {
        // The eligibility grammar of `specs/java-version-supersession.md`, in the syntax Kotlin
        // and POSIX regular expressions share so `versionPattern` is valid in both. `V18`
        // mirrors it by hand; a later backlog migration must be written against this, not V18.
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
