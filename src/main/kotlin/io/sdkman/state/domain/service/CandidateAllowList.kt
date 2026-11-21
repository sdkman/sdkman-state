package io.sdkman.state.domain.service

import arrow.core.Option

/**
 * The set of candidates that may be published to.
 *
 * [registered] answers from a copy the service already holds, so the registry check performs no
 * database read. A [None] result means the registry has never loaded successfully, which is
 * distinct from a registry that loaded and is genuinely empty.
 */
interface CandidateAllowList {
    fun registered(): Option<Set<String>>

    suspend fun refresh()
}
