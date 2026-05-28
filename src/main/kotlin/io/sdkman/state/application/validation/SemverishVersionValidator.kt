package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * Pure parser/validator for the SDKMAN *semverish* version grammar.
 *
 * Semverish (see `specs/semverish-version-validation.md`) borrows SemVer 2.0.0's
 * surface syntax — `<major>.<minor>.<patch>[-<variant>][+<build-metadata>]` —
 * but reinterprets the optional sections:
 *   - the `-...` section denotes a release **variant** (e.g. `-fx`, `-crac`), not a pre-release;
 *   - build metadata (`+...`) participates in precedence.
 *
 * This object is intentionally stateless and knows nothing about candidates,
 * HTTP, or any opt-in configuration. It enforces the grammar only. Callers
 * decide *when* to invoke it (the per-candidate opt-in is layered on top).
 *
 * Grammar (from the spec):
 * ```
 *   core       = numeric "." numeric "." numeric
 *   numeric    = "0" | non-zero-digit *digit          ; no leading zeros
 *   variant    = identifier *( "." identifier )       ; introduced by "-"
 *   build      = identifier *( "." identifier )       ; introduced by "+"
 *   identifier = alphanumeric *( alphanumeric | "-" ) ; non-empty, must start
 *                                                     ; with an alphanumeric so
 *                                                     ; "--fx" is rejected
 * ```
 */
object SemverishVersionValidator {
    private const val CORE_NUMERIC = "(?:0|[1-9][0-9]*)"

    // Identifiers must start with an alphanumeric. This intentionally excludes
    // identifiers that begin with `-`, which makes `25.0.2--fx` (listed as
    // invalid in the spec) parse-fail as expected.
    private const val IDENTIFIER = "[A-Za-z0-9][A-Za-z0-9-]*"
    private const val DOT_SEPARATED_IDENTIFIERS = "$IDENTIFIER(?:\\.$IDENTIFIER)*"

    private val SEMVERISH_PATTERN =
        Regex(
            "^" +
                "$CORE_NUMERIC\\.$CORE_NUMERIC\\.$CORE_NUMERIC" +
                "(?:-$DOT_SEPARATED_IDENTIFIERS)?" +
                "(?:\\+$DOT_SEPARATED_IDENTIFIERS)?" +
                "$",
        )

    /**
     * Validate that [version] conforms to the semverish grammar.
     *
     * Returns the input string unchanged on success so callers can use the
     * result in `either { }` blocks without re-binding the original value.
     * On failure returns [InvalidSemverishVersionError] on the `version` field;
     * the offending input is carried on the error so the validation-error
     * payload can quote it back to the client.
     */
    fun validate(version: String): Either<ValidationError, String> =
        if (SEMVERISH_PATTERN.matches(version)) {
            version.right()
        } else {
            InvalidSemverishVersionError(version = version).left()
        }
}
