package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import arrow.core.Option
import arrow.core.raise.either
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.selectAllTags
import io.sdkman.state.support.selectVersion
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeNone
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.withCleanDatabase

@Tags("integration")
class ExposedTransactionalIntegrationSpec :
    ShouldSpec({

        val versionRepo = PostgresVersionRepository()
        val tagRepo = PostgresTagRepository()
        val transactional = ExposedTransactional()

        val javaVersion =
            Version(
                candidate = "java",
                version = "25.0.1",
                platform = Platform.LINUX_X64,
                url = "https://java/25.0.1/LINUX_X64",
                visible = true.some(),
                distribution = Distribution.TEMURIN.some(),
            )

        suspend fun storeVersionAndTagThen(outcome: (versionId: Int) -> Either<DomainError, Int>): Either<DomainError, Int> =
            transactional.inTransaction {
                either {
                    val versionId = versionRepo.createOrUpdate(javaVersion).mapLeft(DomainError::DatabaseError).bind()
                    tagRepo
                        .assignTag(versionId, "java", javaVersion.distribution, javaVersion.platform, "lts")
                        .mapLeft(DomainError::DatabaseError)
                        .bind()
                    outcome(versionId).bind()
                }
            }

        fun storedJavaVersion(): Option<Version> =
            selectVersion("java", javaVersion.version, javaVersion.distribution, javaVersion.platform)

        should("roll back the nested version and tag writes of a block that answers a failure") {
            withCleanDatabase {
                // given: a block that stores a java row and its tag, then answers a database error
                val failure = DomainError.DatabaseError(DatabaseFailure.QueryExecutionFailure("write failed", RuntimeException("deadlock")))

                // when: the block runs in a transaction
                storeVersionAndTagThen { Either.Left(failure) }.shouldBeLeft(failure)

                // then: neither the row nor its tag is stored
                storedJavaVersion().shouldBeNone()
                selectAllTags().shouldBeEmpty()
            }
        }

        should("commit the nested version and tag writes of a block that answers a success") {
            withCleanDatabase {
                // given: a block that stores a java row and its tag, then answers the row id
                // when: the block runs in a transaction
                val versionId = storeVersionAndTagThen { versionId -> Either.Right(versionId) }.shouldBeRight()

                // then: the row and its tag are stored
                storedJavaVersion().shouldBeSome()
                selectAllTags() shouldBe listOf(versionId to "lts")
            }
        }
    })
