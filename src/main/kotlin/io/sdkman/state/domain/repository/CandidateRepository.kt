package io.sdkman.state.domain.repository

import arrow.core.Either
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateDefault
import io.sdkman.state.domain.model.CandidateDeletion
import io.sdkman.state.domain.model.CandidateRegistration

interface CandidateRepository {
    suspend fun findAll(): Either<DatabaseFailure, List<Candidate>>

    suspend fun findDefaults(): Either<DatabaseFailure, List<CandidateDefault>>

    suspend fun upsert(registration: CandidateRegistration): Either<DatabaseFailure, Pair<Candidate, Boolean>>

    suspend fun delete(candidate: String): Either<DatabaseFailure, CandidateDeletion>
}
