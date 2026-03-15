package io.sdkman.state.adapter.primary.rest.dto

import kotlinx.serialization.Serializable

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
