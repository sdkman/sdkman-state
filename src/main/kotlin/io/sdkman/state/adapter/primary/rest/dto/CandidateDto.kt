@file:UseSerializers(OptionSerializer::class)

package io.sdkman.state.adapter.primary.rest.dto

import arrow.core.Option
import arrow.core.none
import arrow.core.serialization.OptionSerializer
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateListing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Both admin responses render their instants as ISO-8601, the same way `AdminRoutes`
// renders a `Vendor`. The column is `TIMESTAMPTZ`, so the offset is converted
// explicitly rather than assumed to be the server's zone.
private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

// Public shape of a registered candidate, as `GET /candidates` renders it. The
// `default` is derived from `version_tags` on every read and never stored, so it is
// absent whenever no `lts` tag resolves, and always absent for `java`.
@Serializable
data class CandidateDto(
    val candidate: String,
    val name: String,
    val description: String,
    @SerialName("website_url")
    val websiteUrl: String,
    val default: Option<String> = none(),
)

// Admin shape of a registered candidate, returned by both `POST /admin/candidates`
// and `DELETE /admin/candidates/{candidate}`. It is `CandidateDto` without the derived
// `default` plus the two timestamps. The JSON name is `updated_at`, matching
// `VendorResponse`, while the column behind it is `last_updated_at`, matching
// `versions` and `version_tags`; each layer follows its own convention.
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

// Request shape of `POST /admin/candidates`. Every field is optional at the
// deserialization boundary so that a missing field becomes an accumulated validation
// error rather than a decoding failure that hides every other fault in the same body.
@Serializable
data class CreateCandidateRequest(
    val candidate: Option<String> = none(),
    val name: Option<String> = none(),
    val description: Option<String> = none(),
    @SerialName("website_url")
    val websiteUrl: Option<String> = none(),
)

// Structured `409` body for a delete refused because the candidate still owns
// versions, following `TagConflictResponse` rather than interpolating the count into
// prose.
@Serializable
data class CandidateConflictResponse(
    val error: String,
    val message: String,
    @SerialName("version_count")
    val versionCount: Long,
)

// `GET /candidates` renders a listing, not a bare candidate row: the `default` is
// resolved by the service on every read and carried alongside the row it belongs to.
fun CandidateListing.toDto(): CandidateDto =
    CandidateDto(
        candidate = candidate.candidate,
        name = candidate.name,
        description = candidate.description,
        websiteUrl = candidate.websiteUrl,
        default = default,
    )

// The admin responses carry the stored row and no derived `default`. The domain field
// is `lastUpdatedAt` and the JSON field is `updated_at`; the rename happens here and
// nowhere else.
fun Candidate.toAdminDto(): CandidateAdminDto =
    CandidateAdminDto(
        candidate = candidate,
        name = name,
        description = description,
        websiteUrl = websiteUrl,
        createdAt = ISO_FORMATTER.format(createdAt.atOffset(ZoneOffset.UTC)),
        updatedAt = ISO_FORMATTER.format(lastUpdatedAt.atOffset(ZoneOffset.UTC)),
    )
