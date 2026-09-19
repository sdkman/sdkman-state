package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.none
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.repository.CandidateRepository
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight
import java.time.Instant

private val FIXED_INSTANT: Instant = Instant.parse("2026-09-19T00:00:00Z")

private fun candidate(
    identifier: String,
    name: String = identifier.replaceFirstChar { it.uppercase() },
) = Candidate(
    candidate = identifier,
    name = name,
    description = "The $name candidate.",
    websiteUrl = "https://example.com/$identifier",
    createdAt = FIXED_INSTANT,
    lastUpdatedAt = FIXED_INSTANT,
)

private fun queryFailure(message: String) = DatabaseFailure.QueryExecutionFailure(message, RuntimeException("timeout"))

class CandidateServiceUnitSpec :
    ShouldSpec({
        val candidatesRepo = mockk<CandidateRepository>()
        val service = CandidateServiceImpl(candidatesRepo)

        beforeEach { clearAllMocks() }

        context("list") {

            should("keep java in the listing without a default") {
                // given: java carries an lts tag the derivation would otherwise answer with
                coEvery { candidatesRepo.findAll() } returns Either.Right(listOf(candidate("java")))
                coEvery { candidatesRepo.findLtsDefaults() } returns Either.Right(mapOf("java" to "21.0.8-tem"))

                // when: listing the registry
                val result = service.list()

                // then: java is listed with no default
                result.shouldBeRight() shouldBe listOf(candidate("java") to none())
            }

            should("pair every other candidate with its findLtsDefaults entry") {
                // given: two non-java candidates, only one of which resolves a default
                coEvery { candidatesRepo.findAll() } returns
                    Either.Right(listOf(candidate("groovy"), candidate("scala")))
                coEvery { candidatesRepo.findLtsDefaults() } returns Either.Right(mapOf("groovy" to "4.0.28"))

                // when: listing the registry
                val result = service.list()

                // then: the derived default follows the map, and an absent entry is none()
                result.shouldBeRight() shouldBe
                    listOf(
                        candidate("groovy") to "4.0.28".some(),
                        candidate("scala") to none(),
                    )
            }

            should("resolve the defaults in one query for the whole registry") {
                // given: a registry of three candidates
                coEvery { candidatesRepo.findAll() } returns
                    Either.Right(listOf(candidate("groovy"), candidate("java"), candidate("scala")))
                coEvery { candidatesRepo.findLtsDefaults() } returns Either.Right(emptyMap())

                // when: listing the registry
                service.list()

                // then: the default derivation runs once, not once per candidate
                coVerify(exactly = 1) { candidatesRepo.findLtsDefaults() }
            }

            should("return DatabaseError when the registry read fails") {
                // given: findAll fails
                val dbFailure = queryFailure("connection lost")
                coEvery { candidatesRepo.findAll() } returns Either.Left(dbFailure)
                coEvery { candidatesRepo.findLtsDefaults() } returns Either.Right(emptyMap())

                // when: listing the registry
                val result = service.list()

                // then: surfaces DatabaseError wrapping the failure
                result.shouldBeLeft() shouldBe DomainError.DatabaseError(dbFailure)
            }

            should("return DatabaseError when the default derivation fails") {
                // given: the registry reads but the lts derivation fails
                val dbFailure = queryFailure("connection reset")
                coEvery { candidatesRepo.findAll() } returns Either.Right(listOf(candidate("groovy")))
                coEvery { candidatesRepo.findLtsDefaults() } returns Either.Left(dbFailure)

                // when: listing the registry
                val result = service.list()

                // then: surfaces DatabaseError wrapping the failure
                result.shouldBeLeft() shouldBe DomainError.DatabaseError(dbFailure)
            }
        }

        context("register") {

            should("delegate to the repository upsert and report whether the row is new") {
                // given: the upsert creates the row
                val registration =
                    CandidateRegistration(
                        candidate = "groovy",
                        name = "Groovy",
                        description = "The Groovy candidate.",
                        websiteUrl = "https://example.com/groovy",
                    )
                coEvery { candidatesRepo.upsert(registration) } returns Either.Right(candidate("groovy") to true)

                // when: registering the candidate
                val result = service.register(registration)

                // then: answers the stored record and the created flag untouched
                result.shouldBeRight() shouldBe (candidate("groovy") to true)
            }

            should("return DatabaseError when the upsert fails") {
                // given: the upsert fails
                val registration =
                    CandidateRegistration(
                        candidate = "groovy",
                        name = "Groovy",
                        description = "The Groovy candidate.",
                        websiteUrl = "https://example.com/groovy",
                    )
                val dbFailure = queryFailure("constraint violation")
                coEvery { candidatesRepo.upsert(registration) } returns Either.Left(dbFailure)

                // when: registering the candidate
                val result = service.register(registration)

                // then: surfaces DatabaseError wrapping the failure
                result.shouldBeLeft() shouldBe DomainError.DatabaseError(dbFailure)
            }
        }

        context("delete") {

            should("remove the candidate and answer the record when no version is published under it") {
                // given: the candidate exists and carries no versions
                coEvery { candidatesRepo.find("groovy") } returns Either.Right(candidate("groovy").some())
                coEvery { candidatesRepo.countVersions("groovy") } returns Either.Right(0L)
                coEvery { candidatesRepo.delete("groovy") } returns Either.Right(candidate("groovy").some())

                // when: deleting the candidate
                val result = service.delete("groovy")

                // then: answers the removed record
                result.shouldBeRight() shouldBe candidate("groovy")
            }

            should("return CandidateNotFound when the candidate does not exist") {
                // given: the candidate is absent
                coEvery { candidatesRepo.find("groovy") } returns Either.Right(none())

                // when: deleting the candidate
                val result = service.delete("groovy")

                // then: returns CandidateNotFound without counting or deleting
                result.shouldBeLeft() shouldBe DomainError.CandidateNotFound("groovy")
                coVerify(exactly = 0) { candidatesRepo.countVersions(any()) }
                coVerify(exactly = 0) { candidatesRepo.delete(any()) }
            }

            should("return CandidateHasVersions when a version is published under the candidate") {
                // given: the candidate exists and one version names it
                coEvery { candidatesRepo.find("groovy") } returns Either.Right(candidate("groovy").some())
                coEvery { candidatesRepo.countVersions("groovy") } returns Either.Right(1L)

                // when: deleting the candidate
                val result = service.delete("groovy")

                // then: refuses with the count, and never deletes
                result.shouldBeLeft() shouldBe DomainError.CandidateHasVersions("groovy", 1L)
                coVerify(exactly = 0) { candidatesRepo.delete(any()) }
            }

            should("return CandidateNotFound when the row disappears between the count and the delete") {
                // given: the candidate is found and unused, but a concurrent delete wins the race
                coEvery { candidatesRepo.find("groovy") } returns Either.Right(candidate("groovy").some())
                coEvery { candidatesRepo.countVersions("groovy") } returns Either.Right(0L)
                coEvery { candidatesRepo.delete("groovy") } returns Either.Right(none())

                // when: deleting the candidate
                val result = service.delete("groovy")

                // then: the losing caller reads the same 404 as any absent candidate
                result.shouldBeLeft() shouldBe DomainError.CandidateNotFound("groovy")
            }

            should("return DatabaseError when the lookup fails") {
                // given: the lookup fails before the guard runs
                val dbFailure = queryFailure("connection lost")
                coEvery { candidatesRepo.find("groovy") } returns Either.Left(dbFailure)

                // when: deleting the candidate
                val result = service.delete("groovy")

                // then: surfaces DatabaseError and never deletes
                result.shouldBeLeft() shouldBe DomainError.DatabaseError(dbFailure)
                coVerify(exactly = 0) { candidatesRepo.delete(any()) }
            }

            should("return DatabaseError when the version count fails") {
                // given: the candidate exists but the guard query fails
                val dbFailure = queryFailure("connection reset")
                coEvery { candidatesRepo.find("groovy") } returns Either.Right(candidate("groovy").some())
                coEvery { candidatesRepo.countVersions("groovy") } returns Either.Left(dbFailure)

                // when: deleting the candidate
                val result = service.delete("groovy")

                // then: surfaces DatabaseError and never deletes
                result.shouldBeLeft() shouldBe DomainError.DatabaseError(dbFailure)
                coVerify(exactly = 0) { candidatesRepo.delete(any()) }
            }

            should("return DatabaseError when the removal fails") {
                // given: the candidate passes the guard but the removal itself fails
                val dbFailure = queryFailure("disk full")
                coEvery { candidatesRepo.find("groovy") } returns Either.Right(candidate("groovy").some())
                coEvery { candidatesRepo.countVersions("groovy") } returns Either.Right(0L)
                coEvery { candidatesRepo.delete("groovy") } returns Either.Left(dbFailure)

                // when: deleting the candidate
                val result = service.delete("groovy")

                // then: surfaces DatabaseError wrapping the failure
                result.shouldBeLeft() shouldBe DomainError.DatabaseError(dbFailure)
            }
        }
    })
