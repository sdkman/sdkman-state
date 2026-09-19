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

    /**
     * Counts every version published under [candidate], visible or not. This count is the
     * mechanism behind the delete guard (business rule 3), not a friendlier surface over a
     * database constraint.
     */
    suspend fun countVersions(candidate: String): Either<DatabaseFailure, Long>

    /**
     * Resolves the derived `default` for every candidate at once, keyed by candidate. The
     * default is never stored (business rule 4): it is read from the `lts` tag on each request.
     */
    suspend fun findLtsDefaults(): Either<DatabaseFailure, Map<String, String>>
}
