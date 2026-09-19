@file:UseSerializers(OptionSerializer::class)

package io.sdkman.state.adapter.primary.rest.dto

import arrow.core.Option
import arrow.core.none
import arrow.core.serialization.OptionSerializer
import io.sdkman.state.domain.model.Candidate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/**
 * Every field is required by the contract, yet modelled as [Option] so a missing one reaches
 * [io.sdkman.state.application.validation.CandidateRequestValidator] as an accumulated validation
 * failure rather than a deserialisation exception, which would surface as a `500`.
 */
@Serializable
data class CreateCandidateRequest(
    val candidate: Option<String> = none(),
    val name: Option<String> = none(),
    val description: Option<String> = none(),
    @SerialName("website_url")
    val websiteUrl: Option<String> = none(),
)

/** [defaultVersion] is derived per request from `version_tags` and never stored. */
@Serializable
data class CandidateDto(
    val candidate: String,
    val name: String,
    val description: String,
    @SerialName("website_url")
    val websiteUrl: String,
    @SerialName("default")
    val defaultVersion: Option<String> = none(),
)

/** The JSON field is `updated_at`, matching `VendorResponse`; the column behind it is
 * `last_updated_at`, matching `versions`. The two conventions disagree by design. */
@Serializable
data class CandidateAdminDto(
    val candidate: String,
    val name: String,
    val description: String,
    @SerialName("website_url")
    val websiteUrl: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

@Serializable
data class CandidateConflictResponse(
    val error: String,
    val message: String,
    @SerialName("version_count")
    val versionCount: Long,
)

/** The default is passed in rather than read off [Candidate]: it is not stored on the row. */
fun Candidate.toDto(default: Option<String>): CandidateDto =
    CandidateDto(
        candidate = candidate,
        name = name,
        description = description,
        websiteUrl = websiteUrl,
        defaultVersion = default,
    )

fun Candidate.toAdminDto(): CandidateAdminDto =
    CandidateAdminDto(
        candidate = candidate,
        name = name,
        description = description,
        websiteUrl = websiteUrl,
        createdAt = ISO_FORMATTER.format(createdAt.atOffset(ZoneOffset.UTC)),
        updatedAt = ISO_FORMATTER.format(lastUpdatedAt.atOffset(ZoneOffset.UTC)),
    )
