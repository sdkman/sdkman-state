package io.sdkman.state.domain.repository

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateRegistration

interface CandidateRepository {
    suspend fun findAll(): Either<DatabaseFailure, List<Candidate>>

    suspend fun find(candidate: String): Either<DatabaseFailure, Option<Candidate>>

    suspend fun upsert(registration: CandidateRegistration): Either<DatabaseFailure, Pair<Candidate, Boolean>>

    suspend fun delete(candidate: String): Either<DatabaseFailure, Option<Candidate>>

    suspend fun countVersions(candidate: String): Either<DatabaseFailure, Long>

    suspend fun findLtsDefaults(): Either<DatabaseFailure, Map<String, String>>
}
