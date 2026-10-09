package io.sdkman.state.support

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import io.sdkman.state.domain.service.CandidateAllowList

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
