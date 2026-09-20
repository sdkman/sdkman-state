package io.sdkman.state.domain.service

import arrow.core.Either
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.CandidateRegistrationResult
import io.sdkman.state.domain.model.ListedCandidate

interface CandidateService {
    suspend fun list(): Either<DomainError, List<ListedCandidate>>

    suspend fun register(registration: CandidateRegistration): Either<DomainError, CandidateRegistrationResult>

    suspend fun delete(candidate: String): Either<DomainError, Candidate>
}
