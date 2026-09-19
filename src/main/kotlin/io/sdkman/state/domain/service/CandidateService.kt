package io.sdkman.state.domain.service

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateRegistration

interface CandidateService {
    /**
     * Lists the registry ascending by candidate, each row paired with its derived `default`.
     * The default is never stored (business rule 4), so it is resolved on every read and is
     * [arrow.core.None] whenever no `lts` tag answers for that candidate.
     */
    suspend fun list(): Either<DomainError, List<Pair<Candidate, Option<String>>>>

    /**
     * Registers a candidate, keyed on its identifier. The boolean reports whether the row is
     * new, which is what tells a `201` from a `200` without a preceding read.
     */
    suspend fun register(registration: CandidateRegistration): Either<DomainError, Pair<Candidate, Boolean>>
}
