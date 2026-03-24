package io.sdkman.state.domain.error

import java.util.UUID

data class FieldError(
    val field: String,
    val message: String,
)

sealed interface DomainError {
    data class Unauthorized(
        val message: String,
    ) : DomainError

    data class Forbidden(
        val message: String,
    ) : DomainError

    data class VendorNotFound(
        val id: UUID,
    ) : DomainError

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
        val failures: List<FieldError>,
    ) : DomainError

    data class DatabaseError(
        val failure: DatabaseFailure,
    ) : DomainError
}
