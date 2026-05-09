package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.left
import arrow.core.right

object SemverishValidator {
    private const val IDENTIFIER = "[a-zA-Z0-9][a-zA-Z0-9-]*"
    private val SEMVERISH_PATTERN =
        Regex(
            "^(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)" +
                "(?:-$IDENTIFIER(?:\\.$IDENTIFIER)*)?" +
                "(?:\\+$IDENTIFIER(?:\\.$IDENTIFIER)*)?$",
        )

    fun validate(version: String): Either<ValidationError, String> =
        when {
            SEMVERISH_PATTERN.matches(version) -> version.right()
            else -> InvalidVersionFormatError(version = version).left()
        }
}
