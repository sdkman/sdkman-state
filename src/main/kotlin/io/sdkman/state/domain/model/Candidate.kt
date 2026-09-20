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

data class ListedCandidate(
    val candidate: Candidate,
    val defaultVersion: Option<String>,
)

sealed interface CandidateRegistrationResult {
    val candidate: Candidate

    data class Registered(
        override val candidate: Candidate,
    ) : CandidateRegistrationResult

    data class Updated(
        override val candidate: Candidate,
    ) : CandidateRegistrationResult
}
