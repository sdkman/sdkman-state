package io.sdkman.domain

import io.sdkman.validation.ValidationFailure

sealed interface DomainError {
    data class VersionNotFound(
        val candidate: String,
        val version: String,
    ) : DomainError

    data class TagConflict(
        val tags: List<String>,
    ) : DomainError

    data class TagNotFound(
        val tagName: String,
    ) : DomainError

    data class ValidationFailed(
        val message: String,
    ) : DomainError

    data class ValidationFailures(
        val failures: List<ValidationFailure>,
    ) : DomainError

    data class DatabaseError(
        val failure: DatabaseFailure,
    ) : DomainError
}
