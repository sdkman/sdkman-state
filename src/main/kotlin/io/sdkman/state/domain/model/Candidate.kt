package io.sdkman.state.domain.model

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
