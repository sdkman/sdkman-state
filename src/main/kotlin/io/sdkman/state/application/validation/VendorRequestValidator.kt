package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.sdkman.state.domain.error.DomainError

object VendorRequestValidator {
    private val EMAIL_REGEX =
        Regex(
            "^[a-zA-Z0-9.!#\$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9]" +
                "(?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" +
                "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\$",
        )

    fun validate(
        email: String,
        candidates: List<String>,
        adminEmail: String,
    ): Either<DomainError, Unit> =
        when {
            !EMAIL_REGEX.matches(email) -> DomainError.ValidationFailed("Invalid email format").left()
            email == adminEmail -> DomainError.ValidationFailed("Email must not match admin email").left()
            candidates.isEmpty() -> DomainError.ValidationFailed("Candidates list must not be empty").left()
            else -> Unit.right()
        }
}
