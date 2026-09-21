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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

private const val SLOW_READ_MILLIS = 300L
private const val STAGGER_MILLIS = 50L

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

        should("keep the newer set when two refreshes overlap") {
            // given: a slow first read of an older registry and a fast second read of a newer one
            coEvery { candidatesRepo.findAll() } coAnswers {
                delay(SLOW_READ_MILLIS)
                Either.Right(listOf(candidate("groovy")))
            } andThen Either.Right(listOf(candidate("groovy"), candidate("jbang")))
            val allowList = RefreshingCandidateAllowList(candidatesRepo)

            // when: a write refresh and an interval refresh run concurrently
            coroutineScope {
                val slowRefresh = async { allowList.refresh() }
                delay(STAGGER_MILLIS)
                val fastRefresh = async { allowList.refresh() }
                listOf(slowRefresh, fastRefresh).awaitAll()
            }

            // then: the serialised refreshes leave the newer set serving, not the slower older one
            allowList.registered() shouldBe setOf("groovy", "jbang").some()
        }
    })
