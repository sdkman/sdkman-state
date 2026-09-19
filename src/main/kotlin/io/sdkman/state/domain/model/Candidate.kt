package io.sdkman.state.domain.model

import arrow.core.Option
import java.time.Instant

data class Candidate(
    val candidate: String,
    val name: String,
    val description: String,
    val websiteUrl: String,
    val createdAt: Instant,
    val lastUpdatedAt: Instant,
)

data class CandidateRegistration(
    val candidate: String,
    val name: String,
    val description: String,
    val websiteUrl: String,
)

data class CandidateDefault(
    val candidate: String,
    val platform: Platform,
    val version: String,
)

sealed interface CandidateDeletion {
    data class Deleted(
        val candidate: Candidate,
    ) : CandidateDeletion

    data object NotFound : CandidateDeletion

    data class HasVersions(
        val versionCount: Long,
    ) : CandidateDeletion
}

// R4: `default` is derived from version_tags on every read and never stored, so a listing is a
// candidate row plus the value resolved for it at that moment. R8 leaves it absent for `java`.
data class CandidateListing(
    val candidate: Candidate,
    val default: Option<String>,
)
