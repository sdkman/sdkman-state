package io.sdkman.state.adapter.primary.rest.dto

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
)
