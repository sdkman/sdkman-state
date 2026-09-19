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

    /** Counts every version under [candidate], visible or not. No foreign key stands behind
     * the delete guard, so this count *is* the guard. */
    suspend fun countVersions(candidate: String): Either<DatabaseFailure, Long>

    /** Resolves every candidate's `lts` default in one query, keyed by candidate. */
    suspend fun findLtsDefaults(): Either<DatabaseFailure, Map<String, String>>
}
