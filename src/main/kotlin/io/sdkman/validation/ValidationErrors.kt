package io.sdkman.validation

import kotlinx.serialization.Serializable

sealed class ValidationError {
    abstract val field: String
    abstract val message: String
}

data class EmptyFieldError(
    override val field: String,
) : ValidationError() {
    override val message: String = "$field cannot be empty"
}

data class InvalidCandidateError(
    override val field: String = "candidate",
    val candidate: String,
    val allowedCandidates: List<String>,
) : ValidationError() {
    override val message: String =
        "Candidate '$candidate' is not valid. Allowed values: ${allowedCandidates.joinToString(", ")}"
}

data class InvalidUrlError(
    override val field: String = "url",
    val url: String,
) : ValidationError() {
    override val message: String = "URL '$url' must be a valid HTTPS URL"
}

data class InvalidPlatformError(
    override val field: String = "platform",
    val platform: String,
) : ValidationError() {
    override val message: String = "Platform '$platform' is not valid"
}

data class InvalidDistributionError(
    override val field: String = "distribution",
    val distribution: String,
) : ValidationError() {
    override val message: String = "Distribution '$distribution' is not valid"
}

data class InvalidHashFormatError(
    override val field: String,
    val value: String,
    val expectedLength: Int,
) : ValidationError() {
    override val message: String =
        "$field must be a valid hexadecimal hash of $expectedLength characters, got: '$value'"
}

data class InvalidOptionalFieldError(
    override val field: String,
    val reason: String,
) : ValidationError() {
    override val message: String = "$field is invalid: $reason"
}

data class DeserializationError(
    override val field: String,
    override val message: String,
) : ValidationError()

data class InvalidRequestError(
    val details: String,
) : ValidationError() {
    override val field: String = "request"
    override val message: String = "Invalid request: $details"
}

@Serializable
data class ValidationFailure(
    val field: String,
    val message: String,
)

@Serializable
data class ValidationErrorResponse(
    val error: String,
    val failures: List<ValidationFailure>,
)
