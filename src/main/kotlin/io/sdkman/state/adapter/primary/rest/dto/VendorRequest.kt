package io.sdkman.state.adapter.primary.rest.dto

import arrow.core.Option
import arrow.core.serialization.OptionSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VendorRequest(
    val email: String,
    val candidates:
        @Serializable(with = OptionSerializer::class)
        Option<List<String>> = Option.fromNullable(null),
)

@Serializable
data class VendorResponse(
    val id: String,
    val email: String,
    val candidates: List<String>,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("deleted_at")
    val deletedAt:
        @Serializable(with = OptionSerializer::class)
        Option<String> = Option.fromNullable(null),
)

@Serializable
data class VendorWithPasswordResponse(
    val id: String,
    val email: String,
    val password: String,
    val candidates: List<String>,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)
