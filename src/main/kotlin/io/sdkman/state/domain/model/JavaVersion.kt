package io.sdkman.state.domain.model

import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.some
import arrow.core.toOption
import java.math.BigInteger

const val JAVA_CANDIDATE = "java"

data class JavaVersion(
    val spelling: String,
    val core: List<BigInteger>,
    val variant: Option<String>,
    val build: BuildMetadata,
) {
    fun compareWith(other: JavaVersion): Precedence =
        when (val byCore = compareCores(core, other.core)) {
            Precedence.EQUAL -> compareBuilds(build, other.build)
            else -> byCore
        }

    fun beats(other: JavaVersion): Boolean =
        when (compareWith(other)) {
            Precedence.HIGHER -> true
            Precedence.EQUAL -> spelling != other.spelling
            Precedence.LOWER, Precedence.UNORDERED -> false
        }

    companion object {
        private const val POSITIVE = "[1-9]\\d*"
        private const val NUMERIC = "(?:0|$POSITIVE)"
        private const val IDENTIFIER = "[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?"
        private const val VARIANT_VOCABULARY = "fx|crac"
        private val SEMVERISH_PATTERN =
            Regex(
                "^($NUMERIC\\.$NUMERIC\\.$NUMERIC\\.$NUMERIC(?:(?:\\.$NUMERIC)*\\.$POSITIVE)?)" +
                    "(?:-($VARIANT_VOCABULARY))?" +
                    "(?:\\+($IDENTIFIER(?:\\.$IDENTIFIER)*))?$",
            )
        private val LEGACY_DOT_VARIANT = Regex("^(\\d+(?:\\.\\d+)*)\\.($VARIANT_VOCABULARY)")
        private val THREE_COMPONENT_CORE = Regex("^(\\d+\\.\\d+\\.\\d+)(?=[-+]|$)")

        private const val CORE_GROUP = 1
        private const val VARIANT_GROUP = 2
        private const val BUILD_GROUP = 3

        private val KNOWN_BUILD_KINDS_LOWEST_FIRST =
            listOf(BuildMetadata.EarlyAccess::class, BuildMetadata.Absent::class, BuildMetadata.Numeric::class)

        fun parse(version: String): Option<JavaVersion> =
            SEMVERISH_PATTERN.matchEntire(version).toOption().map { match ->
                JavaVersion(
                    spelling = version,
                    core = match.groupValues[CORE_GROUP].split('.').map { it.toBigInteger() },
                    variant = match.optionalGroup(VARIANT_GROUP),
                    build = match.optionalGroup(BUILD_GROUP).map(BuildMetadata::of).getOrElse { BuildMetadata.Absent },
                )
            }

        fun read(stored: String): Option<JavaVersion> =
            parse(padThreeComponentCore(rewriteLegacyDotVariant(stored))).map { it.copy(spelling = stored) }

        private fun rewriteLegacyDotVariant(version: String): String = LEGACY_DOT_VARIANT.replaceFirst(version, "\$1-\$2")

        private fun padThreeComponentCore(version: String): String = THREE_COMPONENT_CORE.replaceFirst(version, "\$1.0")

        private fun MatchResult.optionalGroup(index: Int): Option<String> = groupValues[index].some().filter { it.isNotEmpty() }

        private fun compareCores(
            left: List<BigInteger>,
            right: List<BigInteger>,
        ): Precedence = Precedence.ofComparison(compareNumberSequences(left, right))

        private fun compareBuilds(
            left: BuildMetadata,
            right: BuildMetadata,
        ): Precedence =
            when {
                left == right -> Precedence.EQUAL
                left is BuildMetadata.Unknown || right is BuildMetadata.Unknown -> Precedence.UNORDERED
                left is BuildMetadata.EarlyAccess && right is BuildMetadata.EarlyAccess ->
                    Precedence.ofComparison(left.number.compareTo(right.number))
                left is BuildMetadata.Numeric && right is BuildMetadata.Numeric ->
                    Precedence.ofComparison(compareNumberSequences(left.numbers, right.numbers))
                else -> Precedence.ofComparison(knownBuildKindRank(left).compareTo(knownBuildKindRank(right)))
            }

        private fun knownBuildKindRank(build: BuildMetadata): Int = KNOWN_BUILD_KINDS_LOWEST_FIRST.indexOf(build::class)

        private fun compareNumberSequences(
            left: List<BigInteger>,
            right: List<BigInteger>,
        ): Int =
            left
                .zip(right) { leftNumber, rightNumber -> leftNumber.compareTo(rightNumber) }
                .firstOrNone { it != 0 }
                .getOrElse { left.size.compareTo(right.size) }
    }
}
