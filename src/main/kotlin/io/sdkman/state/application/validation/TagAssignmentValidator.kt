package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNone
import io.sdkman.state.domain.model.TagAssignment

// Structural validation for a single tag assignment, following the accumulated-error
// pattern used by `UniqueTagValidator`: all failures are returned together in one
// `NonEmptyList`. This validator does strictly more than `UniqueTagValidator` — it
// also requires a non-blank `version` and validates the `tag` against the shared
// `TagNameRules`. Platform/distribution enum validity is enforced earlier, at DTO
// deserialization, so it is not re-checked here.
object TagAssignmentValidator {
    fun validate(assignment: TagAssignment): Either<NonEmptyList<ValidationError>, TagAssignment> {
        val errors =
            buildList {
                if (assignment.candidate.isBlank()) add(EmptyFieldError("candidate"))
                if (assignment.version.isBlank()) add(EmptyFieldError("version"))
                addAll(TagNameRules.validate("tag", assignment.tag))
            }
        return errors
            .toNonEmptyListOrNone()
            .fold(
                { assignment.right() },
                { nel -> nel.left() },
            )
    }
}
