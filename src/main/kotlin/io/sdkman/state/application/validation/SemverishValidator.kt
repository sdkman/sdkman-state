package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.sdkman.state.domain.model.SeriesKey

object SemverishValidator {
    private const val NUMERIC = "(?:0|[1-9]\\d*)"
    private const val IDENTIFIER = "[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?"
    private val SEMVERISH_PATTERN =
        Regex(
            "^$NUMERIC\\.$NUMERIC\\.$NUMERIC\\.$NUMERIC" +
                "(?:-(?:${SeriesKey.VARIANT_VOCABULARY}))?" +
                "(?:\\+$IDENTIFIER(?:\\.$IDENTIFIER)*)?$",
        )

    fun validate(version: String): Either<ValidationError, String> =
        when {
            SEMVERISH_PATTERN.matches(version) -> version.right()
            else -> InvalidVersionFormatError(version = version).left()
        }
}
