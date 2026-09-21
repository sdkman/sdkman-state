package io.sdkman.state.support

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import io.sdkman.state.domain.service.CandidateAllowList

/**
 * Fixed [CandidateAllowList] doubles for tests that need a known registry without a database.
 *
 * [allowList] stands in for a registry that has loaded, and [unreadyAllowList] for one that never
 * has. That second state is not an empty registry: it is the case rule 5 turns into a `500` rather
 * than a `400`, so it needs a double of its own.
 *
 * Both hold their answer fixed, so [CandidateAllowList.refresh] does nothing.
 */

fun allowList(vararg candidates: String): CandidateAllowList =
    object : CandidateAllowList {
        override fun registered(): Option<Set<String>> = candidates.toSet().some()

        override suspend fun refresh() = Unit
    }

fun unreadyAllowList(): CandidateAllowList =
    object : CandidateAllowList {
        override fun registered(): Option<Set<String>> = none()

        override suspend fun refresh() = Unit
    }
