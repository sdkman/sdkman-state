package io.sdkman.state.application.service

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import io.sdkman.state.domain.repository.CandidateRepository
import io.sdkman.state.domain.service.CandidateAllowList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

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
