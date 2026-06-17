package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * Pure, shape-only validator for the semverish version grammar.
 *
 * A semverish version has the shape `<major>.<minor>.<patch>[-<variant>][+<build-metadata>]`:
 *
 * - **Core**: three mandatory non-negative integer components separated by `.`, with no leading
 *   zeros (`0` is valid, `01` is not).
 * - **Variant** (optional, after a single `-`): one or more dot-separated identifiers.
 * - **Build metadata** (optional, after a single `+`): one or more dot-separated identifiers.
 *
 * Each identifier is non-empty and made of ASCII alphanumerics and `-`. An identifier must not
 * begin with `-`, which is what makes a duplicated separator such as `25.0.2--fx` invalid while
 * still permitting hyphens inside an identifier.
 *
 * This validator enforces the grammar only; it does not restrict the variant/metadata vocabulary
 * and does not implement ordering/precedence (both out of scope per the spec).
 */
object SemverishValidator {
    // Non-negative integer, no leading zeros.
    private const val NUMERIC = "(?:0|[1-9]\\d*)"

    // ASCII alphanumerics and '-', non-empty, not starting with '-'.
    private const val IDENTIFIER = "[0-9A-Za-z][0-9A-Za-z-]*"

    // One or more dot-separated identifiers.
    private const val IDENTIFIERS = "$IDENTIFIER(?:\\.$IDENTIFIER)*"

    private val SEMVERISH_PATTERN =
        Regex("^$NUMERIC\\.$NUMERIC\\.$NUMERIC(?:-$IDENTIFIERS)?(?:\\+$IDENTIFIERS)?$")

    fun validate(version: String): Either<ValidationError, Unit> =
        if (SEMVERISH_PATTERN.matches(version)) {
            Unit.right()
        } else {
            InvalidVersionFormatError(version = version).left()
        }
}
