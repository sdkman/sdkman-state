package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.none
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.repository.CandidateRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant

private val FIXED_INSTANT: Instant = Instant.parse("2026-09-19T00:00:00Z")

private fun candidate(identifier: String) =
    Candidate(
        candidate = identifier,
        name = identifier.replaceFirstChar { it.uppercase() },
        description = "The $identifier candidate.",
        websiteUrl = "https://example.com/$identifier",
        createdAt = FIXED_INSTANT,
        lastUpdatedAt = FIXED_INSTANT,
    )

private fun queryFailure(message: String) = DatabaseFailure.QueryExecutionFailure(message, RuntimeException("timeout"))

class RefreshingCandidateAllowListUnitSpec :
    ShouldSpec({
        val candidatesRepo = mockk<CandidateRepository>()

        beforeEach { clearAllMocks() }

        should("answer none before any refresh") {
            // given: a holder that has never been refreshed
            val allowList = RefreshingCandidateAllowList(candidatesRepo)

            // when: reading the registered candidates
            val result = allowList.registered()

            // then: the holder is unready, which is distinct from an empty registry
            result shouldBe none()
        }

        should("hold the candidate names after a successful refresh") {
            // given: the registry holds two candidates
            coEvery { candidatesRepo.findAll() } returns Either.Right(listOf(candidate("groovy"), candidate("java")))
            val allowList = RefreshingCandidateAllowList(candidatesRepo)

            // when: refreshing the holder
            allowList.refresh()

            // then: the holder answers the candidate identifiers
            allowList.registered() shouldBe setOf("groovy", "java").some()
        }

        should("keep the last good copy when a later refresh fails") {
            // given: a holder loaded from a registry whose next read fails
            coEvery { candidatesRepo.findAll() } returns
                Either.Right(listOf(candidate("groovy"))) andThen
                Either.Left(queryFailure("connection lost"))
            val allowList = RefreshingCandidateAllowList(candidatesRepo)
            allowList.refresh()

            // when: the second refresh fails
            allowList.refresh()

            // then: the set from the first refresh keeps serving
            allowList.registered() shouldBe setOf("groovy").some()
        }

        should("stay unready when the first refresh fails") {
            // given: the very first registry read fails
            coEvery { candidatesRepo.findAll() } returns Either.Left(queryFailure("connection refused"))
            val allowList = RefreshingCandidateAllowList(candidatesRepo)

            // when: refreshing the holder
            allowList.refresh()

            // then: there is no copy to fall back to, so the holder reports never loaded
            allowList.registered() shouldBe none()
        }

        should("load the names on a later refresh after a failed first refresh") {
            // given: a cold load failure followed by a recovered database
            coEvery { candidatesRepo.findAll() } returns
                Either.Left(queryFailure("connection refused")) andThen
                Either.Right(listOf(candidate("scala")))
            val allowList = RefreshingCandidateAllowList(candidatesRepo)
            allowList.refresh()

            // when: the interval refresh retries the cold load
            allowList.refresh()

            // then: the holder becomes ready without a restart
            allowList.registered() shouldBe setOf("scala").some()
        }

        should("keep the newer copy when a slower refresh finishes last") {
            // given: a first registry read that stalls, and a second that sees a newly registered candidate
            val firstReadReleased = CompletableDeferred<Unit>()
            coEvery { candidatesRepo.findAll() } coAnswers {
                firstReadReleased.await()
                Either.Right(listOf(candidate("groovy")))
            } andThen Either.Right(listOf(candidate("groovy"), candidate("jpx")))
            val allowList = RefreshingCandidateAllowList(candidatesRepo)

            // when: the second refresh starts while the first is still reading, then the first read completes
            coroutineScope {
                val slowRefresh = async(start = CoroutineStart.UNDISPATCHED) { allowList.refresh() }
                val fastRefresh = async(start = CoroutineStart.UNDISPATCHED) { allowList.refresh() }
                firstReadReleased.complete(Unit)
                awaitAll(slowRefresh, fastRefresh)
            }

            // then: the stale read of the slower refresh has not overwritten the newer set
            allowList.registered() shouldBe setOf("groovy", "jpx").some()
        }
    })
