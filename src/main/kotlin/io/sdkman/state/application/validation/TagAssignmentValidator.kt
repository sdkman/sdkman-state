package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNone
import io.sdkman.state.domain.model.TagAssignment

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
