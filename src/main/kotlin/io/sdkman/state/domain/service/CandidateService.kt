package io.sdkman.state.domain.service

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateRegistration

interface CandidateService {
    /** Lists the registry ascending by candidate. The order is part of the public contract. */
    suspend fun list(): Either<DomainError, List<Pair<Candidate, Option<String>>>>

    /** Upserts a candidate. The boolean reports whether the row was new, which is what tells a
     * `201` from a `200` without a preceding read. */
    suspend fun register(registration: CandidateRegistration): Either<DomainError, Pair<Candidate, Boolean>>

    /** Removes a candidate, answering the record as it was. Refuses with
     * [DomainError.CandidateHasVersions] while any version is published under it. */
    suspend fun delete(candidate: String): Either<DomainError, Candidate>
}
