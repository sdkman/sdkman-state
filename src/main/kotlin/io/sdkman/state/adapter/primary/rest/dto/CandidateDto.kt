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
 * Request body of `POST /admin/candidates`.
 *
 * Every field is required by the contract but modelled as [Option] so that a missing field
 * reaches [io.sdkman.state.application.validation.CandidateRequestValidator] as an accumulated
 * validation failure rather than a deserialisation exception, which would surface as a `500`.
 */
@Serializable
data class CreateCandidateRequest(
    val candidate: Option<String> = none(),
    val name: Option<String> = none(),
    val description: Option<String> = none(),
    @SerialName("website_url")
    val websiteUrl: Option<String> = none(),
)

/**
 * Public response body of `GET /candidates`.
 *
 * [defaultVersion] is derived per request from `version_tags` and is never stored, so it is
 * absent whenever no `lts` tag resolves, and always absent for `java`.
 */
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

/**
 * Response body of the admin write routes.
 *
 * It is [CandidateDto] without the derived default, plus the record timestamps. The JSON field
 * is `updated_at`, matching `VendorResponse`, while the column behind it is `last_updated_at`.
 */
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

/**
 * Response body of a `409` from `DELETE /admin/candidates/{candidate}`.
 *
 * It follows the structured shape of [TagConflictResponse] rather than interpolating the count
 * into prose, so a caller can act on [versionCount] without parsing the message.
 */
@Serializable
data class CandidateConflictResponse(
    val error: String,
    val message: String,
    @SerialName("version_count")
    val versionCount: Long,
)

/**
 * Maps a registry row onto the public response.
 *
 * The derived default is passed in rather than read off [Candidate], because business rule 4
 * keeps it out of the table: it is resolved from `version_tags` per request.
 */
fun Candidate.toDto(default: Option<String>): CandidateDto =
    CandidateDto(
        candidate = candidate,
        name = name,
        description = description,
        websiteUrl = websiteUrl,
        defaultVersion = default,
    )

/**
 * Maps a registry row onto the admin response.
 *
 * Both instants render through [ISO_FORMATTER] at UTC, the idiom `AdminRoutes` applies to
 * `VendorResponse`, so `TIMESTAMPTZ` becomes a zone-explicit ISO-8601 string.
 */
fun Candidate.toAdminDto(): CandidateAdminDto =
    CandidateAdminDto(
        candidate = candidate,
        name = name,
        description = description,
        websiteUrl = websiteUrl,
        createdAt = ISO_FORMATTER.format(createdAt.atOffset(ZoneOffset.UTC)),
        updatedAt = ISO_FORMATTER.format(lastUpdatedAt.atOffset(ZoneOffset.UTC)),
    )
