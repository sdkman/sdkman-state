package io.sdkman.state.application.service

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import io.sdkman.state.domain.repository.CandidateRepository
import io.sdkman.state.domain.service.CandidateAllowList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the candidate registry in memory and reloads it on demand.
 *
 * A failed reload keeps the last good copy, because a briefly stale allow-list beats rejecting
 * valid publishes. A failure before any successful load leaves the holder unready.
 *
 * Reloads are serialised, so a slow read cannot overwrite the result of a later one. The write
 * refresh and the periodic refresh can otherwise overlap and leave the older set serving.
 */
class RefreshingCandidateAllowList(
    private val candidateRepository: CandidateRepository,
) : CandidateAllowList {
    private val registeredCandidates = AtomicReference<Option<Set<String>>>(none())
    private val refreshLock = Mutex()

    override fun registered(): Option<Set<String>> = registeredCandidates.get()

    override suspend fun refresh() {
        refreshLock.withLock {
            candidateRepository
                .findAll()
                .onRight { candidates ->
                    registeredCandidates.set(candidates.map { it.candidate }.toSet().some())
                }
        }
    }
}
