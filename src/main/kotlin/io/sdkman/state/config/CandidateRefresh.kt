package io.sdkman.state.config

import io.ktor.server.application.*
import io.sdkman.state.domain.service.CandidateAllowList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Loads the candidate registry at startup and reloads it every [intervalMs].
 *
 * The first load runs before the first delay, so an instance holds the allow-list from boot rather
 * than after one interval. A failed load cannot throw here, because [CandidateAllowList.refresh]
 * absorbs the database failure and keeps the last good copy, so the loop simply tries again at the
 * next interval: the interval doubles as the cold-load retry and a registry the service cannot read
 * never stops it booting.
 */
fun Application.scheduleCandidateRefresh(
    candidateAllowList: CandidateAllowList,
    intervalMs: Long,
) {
    launch {
        while (true) {
            candidateAllowList.refresh()
            delay(intervalMs)
        }
    }
}
