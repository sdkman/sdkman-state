package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.Option
import arrow.core.none
import arrow.core.raise.either
import arrow.core.toOption
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateDefault
import io.sdkman.state.domain.model.CandidateDeletion
import io.sdkman.state.domain.model.CandidateListing
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.repository.CandidateRepository
import io.sdkman.state.domain.service.CandidateService

// R8: java never carries a default. Its `lts` tag exists once per distribution, so a single value
// would be arbitrary. This is the one candidate-specific rule in the service.
private const val JAVA_CANDIDATE = "java"

// R5: UNIVERSAL first, then LINUX_X64, and no other platform. The repository restricts the query
// to these two; this list states which of the two wins when a candidate is tagged on both.
private val PLATFORM_PREFERENCE = listOf(Platform.UNIVERSAL, Platform.LINUX_X64)

class CandidateServiceImpl(
    private val candidatesRepo: CandidateRepository,
) : CandidateService {
    override suspend fun findAll(): Either<DomainError, List<CandidateListing>> =
        either {
            val candidates =
                candidatesRepo
                    .findAll()
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            val defaults =
                candidatesRepo
                    .findDefaults()
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()
            val defaultsByCandidate = defaults.indexByCandidate()
            candidates.map { candidate ->
                CandidateListing(
                    candidate = candidate,
                    default = resolveDefault(candidate.candidate, defaultsByCandidate),
                )
            }
        }

    override suspend fun register(registration: CandidateRegistration): Either<DomainError, Pair<Candidate, Boolean>> =
        candidatesRepo
            .upsert(registration)
            .mapLeft { DomainError.DatabaseError(it) }

    override suspend fun delete(candidate: String): Either<DomainError, CandidateDeletion> =
        candidatesRepo
            .delete(candidate)
            .mapLeft { DomainError.DatabaseError(it) }

    // Both reads happen once per request, so the listing costs two queries whatever the registry
    // size. The defaults are indexed in memory rather than joined, because a candidate with no
    // `lts` tag at either platform must still appear in the listing.
    private fun List<CandidateDefault>.indexByCandidate(): Map<String, String> =
        groupBy { it.candidate }
            .mapValues { (_, rows) ->
                rows.minBy { PLATFORM_PREFERENCE.indexOf(it.platform) }.version
            }

    private fun resolveDefault(
        candidate: String,
        defaults: Map<String, String>,
    ): Option<String> =
        if (candidate == JAVA_CANDIDATE) {
            none()
        } else {
            defaults[candidate].toOption()
        }
}
