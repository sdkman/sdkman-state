@file:UseSerializers(OptionSerializer::class)

package io.sdkman.state.adapter.primary.rest.dto

import arrow.core.Option
import arrow.core.none
import arrow.core.serialization.OptionSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val token: String,
)

@Serializable
data class CreateVendorRequest(
    val email: String,
    val candidates: Option<List<String>> = none(),
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
    val deletedAt: Option<String> = none(),
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
