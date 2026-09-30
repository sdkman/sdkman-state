package io.sdkman.state.application.service

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import io.sdkman.state.domain.repository.CandidateRepository
import io.sdkman.state.domain.service.CandidateAllowList
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the candidate registry in memory and reloads it on demand.
 *
 * A failed reload keeps the last good copy, because a briefly stale allow-list beats rejecting
 * valid publishes. A failure before any successful load leaves the holder unready.
 */
class RefreshingCandidateAllowList(
    private val candidateRepository: CandidateRepository,
) : CandidateAllowList {
    private val registeredCandidates = AtomicReference<Option<Set<String>>>(none())

    override fun registered(): Option<Set<String>> = registeredCandidates.get()

    override suspend fun refresh() {
        candidateRepository
            .findAll()
            .onRight { candidates ->
                registeredCandidates.set(candidates.map { it.candidate }.toSet().some())
            }
    }
}
