package io.sdkman.state.domain.service

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateRegistration

interface CandidateService {
    suspend fun list(): Either<DomainError, List<Pair<Candidate, Option<String>>>>

    suspend fun register(registration: CandidateRegistration): Either<DomainError, Pair<Candidate, Boolean>>

    suspend fun delete(candidate: String): Either<DomainError, Candidate>
}
