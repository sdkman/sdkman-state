package io.sdkman.state.adapter.primary.rest.dto

import kotlinx.serialization.Serializable

@Serializable
data class TagConflictResponse(
    val error: String,
    val message: String,
    val tags: List<String>,
)
