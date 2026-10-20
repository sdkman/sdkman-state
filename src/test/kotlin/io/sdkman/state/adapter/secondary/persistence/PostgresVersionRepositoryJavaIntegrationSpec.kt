package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Option
import arrow.core.none
import arrow.core.raise.either
import arrow.core.some
import arrow.core.toOption
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.insertTag
import io.sdkman.state.support.insertVersionWithId
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.selectVersion
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.withCleanDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

@Tags("integration")
class PostgresVersionRepositoryJavaIntegrationSpec :
    ShouldSpec({

        val repo = PostgresVersionRepository()
        val transactional = ExposedTransactional()

        fun javaVersion(
            version: String,
            distribution: Option<Distribution> = Distribution.TEMURIN.some(),
            platform: Platform = Platform.LINUX_X64,
            visible: Boolean = true,
        ): Version =
            Version(
                candidate = "java",
                version = version,
                platform = platform,
                url = "https://java/$version/${platform.name}",
                visible = visible.some(),
                distribution = distribution,
            )

        fun seedScopeAndNeighbours() =
            insertVersions(
                javaVersion("25.0.1"),
                javaVersion("25.0.0", visible = false),
                javaVersion("25.0.1", platform = Platform.MAC_ARM64),
                javaVersion("25.0.1", distribution = Distribution.ZULU.some()),
                javaVersion("25.0.2", distribution = none()),
                javaVersion("3.5.0", distribution = none()).copy(candidate = "scala"),
            )

        fun visibilityOf(
            version: String,
            distribution: Option<Distribution> = Distribution.TEMURIN.some(),
            platform: Platform = Platform.LINUX_X64,
        ): Boolean =
            selectVersion("java", version, distribution, platform)
                .shouldBeSome()
                .visible
                .shouldBeSome()

        suspend fun advisoryLocksHeldByThisBackend(): Long =
            dbQuery {
                TransactionManager
                    .current()
                    .exec("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid()") { result ->
                        result.next()
                        result.getLong(1)
                    }.toOption()
                    .shouldBeSome()
            }

        context("findJavaRows") {
            should("return the visible and hidden java rows of the distribution and platform") {
                withCleanDatabase {
                    seedScopeAndNeighbours()

                    val rows = repo.findJavaRows(Distribution.TEMURIN.some(), Platform.LINUX_X64).shouldBeRight()

                    rows.map { it.version } shouldContainExactlyInAnyOrder listOf("25.0.1", "25.0.0")
                }
            }

            should("read a hidden row as hidden") {
                withCleanDatabase {
                    seedScopeAndNeighbours()

                    val rows = repo.findJavaRows(Distribution.TEMURIN.some(), Platform.LINUX_X64).shouldBeRight()

                    rows.single { it.version == "25.0.0" }.visible shouldBe false.some()
                }
            }

            should("return only the java rows without a distribution for an absent distribution") {
                withCleanDatabase {
                    seedScopeAndNeighbours()

                    val rows = repo.findJavaRows(none(), Platform.LINUX_X64).shouldBeRight()

                    rows.map { it.version } shouldBe listOf("25.0.2")
                }
            }

            should("carry the tags of each row") {
                withCleanDatabase {
                    val featureHeadId = insertVersionWithId(javaVersion("25.0.1"))
                    insertTag("java", "25", Distribution.TEMURIN.some(), Platform.LINUX_X64, featureHeadId)
                    insertTag("java", "latest", Distribution.TEMURIN.some(), Platform.LINUX_X64, featureHeadId)
                    val ltsHeadId = insertVersionWithId(javaVersion("21.0.8"))
                    insertTag("java", "lts", Distribution.TEMURIN.some(), Platform.LINUX_X64, ltsHeadId)

                    val rows = repo.findJavaRows(Distribution.TEMURIN.some(), Platform.LINUX_X64).shouldBeRight()

                    rows.associate { it.version to it.tags.shouldBeSome().toSet() } shouldBe
                        mapOf("25.0.1" to setOf("25", "latest"), "21.0.8" to setOf("lts"))
                }
            }

            should("carry an empty tag list on an untagged row") {
                withCleanDatabase {
                    insertVersions(javaVersion("25.0.1"))

                    val rows = repo.findJavaRows(Distribution.TEMURIN.some(), Platform.LINUX_X64).shouldBeRight()

                    rows.single().tags shouldBe emptyList<String>().some()
                }
            }
        }

        context("hideJavaVersions") {
            should("hide a named row of the distribution and platform") {
                withCleanDatabase {
                    seedScopeAndNeighbours()

                    repo
                        .hideJavaVersions(Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("25.0.1"))
                        .shouldBeRight()

                    visibilityOf("25.0.1") shouldBe false
                }
            }

            should("leave the same version under another distribution or platform visible") {
                withCleanDatabase {
                    seedScopeAndNeighbours()

                    repo
                        .hideJavaVersions(Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("25.0.1"))
                        .shouldBeRight()

                    listOf(
                        visibilityOf("25.0.1", distribution = Distribution.ZULU.some()),
                        visibilityOf("25.0.1", platform = Platform.MAC_ARM64),
                    ) shouldBe listOf(true, true)
                }
            }

            should("count only the named rows found in the distribution and platform") {
                withCleanDatabase {
                    seedScopeAndNeighbours()

                    val hidden =
                        repo
                            .hideJavaVersions(Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("25.0.1", "25.0.2"))
                            .shouldBeRight()

                    hidden shouldBe 1
                }
            }

            should("hide nothing when no version is named") {
                withCleanDatabase {
                    seedScopeAndNeighbours()

                    repo
                        .hideJavaVersions(Distribution.TEMURIN.some(), Platform.LINUX_X64, emptyList())
                        .shouldBeRight()

                    visibilityOf("25.0.1") shouldBe true
                }
            }
        }

        context("lockJavaWrites") {
            should("keep its advisory lock for the rest of the enclosing transaction") {
                withCleanDatabase {
                    val locksHeld =
                        transactional
                            .inTransaction {
                                either {
                                    repo.lockJavaWrites(Distribution.TEMURIN.some(), Platform.LINUX_X64).bind()
                                    advisoryLocksHeldByThisBackend()
                                }
                            }.shouldBeRight()

                    locksHeld shouldBe 1L
                }
            }

            should("take one lock per distribution and platform") {
                withCleanDatabase {
                    val locksHeld =
                        transactional
                            .inTransaction {
                                either {
                                    repo.lockJavaWrites(Distribution.TEMURIN.some(), Platform.LINUX_X64).bind()
                                    repo.lockJavaWrites(Distribution.ZULU.some(), Platform.LINUX_X64).bind()
                                    repo.lockJavaWrites(Distribution.TEMURIN.some(), Platform.MAC_ARM64).bind()
                                    repo.lockJavaWrites(none(), Platform.LINUX_X64).bind()
                                    repo.lockJavaWrites(Distribution.TEMURIN.some(), Platform.LINUX_X64).bind()
                                    advisoryLocksHeldByThisBackend()
                                }
                            }.shouldBeRight()

                    locksHeld shouldBe 4L
                }
            }
        }
    })
