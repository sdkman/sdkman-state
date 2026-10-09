package io.sdkman.state.config

import io.ktor.server.application.*
import io.sdkman.state.domain.service.CandidateAllowList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
