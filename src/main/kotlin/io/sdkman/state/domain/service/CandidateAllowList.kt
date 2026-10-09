package io.sdkman.state.domain.service

import arrow.core.Option

interface CandidateAllowList {
    fun registered(): Option<Set<String>>

    suspend fun refresh()
}
