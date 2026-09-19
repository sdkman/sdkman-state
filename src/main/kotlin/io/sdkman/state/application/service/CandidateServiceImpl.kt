package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.none
import arrow.core.raise.either
import arrow.core.toOption
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.repository.CandidateRepository
import io.sdkman.state.domain.service.CandidateService

// `java` is the one candidate excluded from `default` (business rule 8): its `lts` tag exists
// once per distribution, so a single value would be arbitrary. It still appears in the listing.
private const val JAVA_CANDIDATE = "java"

class CandidateServiceImpl(
    private val candidateRepository: CandidateRepository,
) : CandidateService {
    override suspend fun list(): Either<DomainError, List<Pair<Candidate, Option<String>>>> =
        either {
            val candidates =
                candidateRepository
                    .findAll()
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            // One query for the whole registry, not one per candidate, so the listing stays
            // two round trips however many candidates are registered.
            val defaults =
                candidateRepository
                    .findLtsDefaults()
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            candidates.map { candidate ->
                candidate to
                    when (candidate.candidate) {
                        JAVA_CANDIDATE -> none()
                        else -> defaults[candidate.candidate].toOption()
                    }
            }
        }

    override suspend fun register(registration: CandidateRegistration): Either<DomainError, Pair<Candidate, Boolean>> =
        candidateRepository
            .upsert(registration)
            .mapLeft { DomainError.DatabaseError(it) }

    override suspend fun delete(candidate: String): Either<DomainError, Candidate> =
        either {
            candidateRepository
                .find(candidate)
                .mapLeft { DomainError.DatabaseError(it) }
                .bind()
                .getOrElse { raise(DomainError.CandidateNotFound(candidate)) }
            // The count is the whole guard: no foreign key stands behind it, so a delete that
            // skipped this would strand every version row published under the candidate.
            val versionCount =
                candidateRepository
                    .countVersions(candidate)
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            if (versionCount > 0) raise(DomainError.CandidateHasVersions(candidate, versionCount))
            candidateRepository
                .delete(candidate)
                .mapLeft { DomainError.DatabaseError(it) }
                .bind()
                .getOrElse { raise(DomainError.CandidateNotFound(candidate)) }
        }
}
