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
