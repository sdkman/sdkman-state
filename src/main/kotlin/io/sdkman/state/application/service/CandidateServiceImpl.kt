package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.none
import arrow.core.raise.either
import arrow.core.toOption
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.CandidateRegistrationResult
import io.sdkman.state.domain.model.ListedCandidate
import io.sdkman.state.domain.repository.CandidateRepository
import io.sdkman.state.domain.service.CandidateService

private const val JAVA_CANDIDATE = "java"

class CandidateServiceImpl(
    private val candidateRepository: CandidateRepository,
) : CandidateService {
    override suspend fun list(): Either<DomainError, List<ListedCandidate>> =
        either {
            val candidates =
                candidateRepository
                    .findAll()
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            val defaults =
                candidateRepository
                    .findLtsDefaults()
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            candidates.map { candidate ->
                ListedCandidate(
                    candidate = candidate,
                    defaultVersion =
                        when (candidate.candidate) {
                            JAVA_CANDIDATE -> none()
                            else -> defaults[candidate.candidate].toOption()
                        },
                )
            }
        }

    override suspend fun register(registration: CandidateRegistration): Either<DomainError, CandidateRegistrationResult> =
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
