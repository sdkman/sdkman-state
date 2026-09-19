package io.sdkman.state.domain.service

import arrow.core.Either
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateDeletion
import io.sdkman.state.domain.model.CandidateListing
import io.sdkman.state.domain.model.CandidateRegistration

interface CandidateService {
    suspend fun findAll(): Either<DomainError, List<CandidateListing>>

    // The Boolean is the created/updated answer the upsert itself reports, which the route turns
    // into 201 or 200. It is never derived from a preceding read.
    suspend fun register(registration: CandidateRegistration): Either<DomainError, Pair<Candidate, Boolean>>

    suspend fun delete(candidate: String): Either<DomainError, CandidateDeletion>
}
