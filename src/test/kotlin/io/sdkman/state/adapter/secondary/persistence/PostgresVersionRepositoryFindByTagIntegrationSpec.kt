package io.sdkman.state.adapter.secondary.persistence

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.withCleanDatabase

@Tags("integration")
class PostgresVersionRepositoryFindByTagIntegrationSpec :
    ShouldSpec({

        context("findByTag") {
            should("resolve a tag to the version with its full tag list") {
                val repo = PostgresVersionRepository()
                val tagRepo = PostgresTagRepository()

                withCleanDatabase {
                    // given: a version carrying multiple tags
                    val version =
                        Version(
                            candidate = "java",
                            version = "27.0.2",
                            platform = Platform.LINUX_X64,
                            url = "https://java-27.0.2",
                            visible = true.some(),
                            distribution = Distribution.TEMURIN.some(),
                        )
                    val versionId = repo.createOrUpdate(version).shouldBeRight()
                    tagRepo.replaceTags(
                        versionId,
                        "java",
                        Distribution.TEMURIN.some(),
                        Platform.LINUX_X64,
                        listOf("latest", "lts"),
                    )

                    // when: resolving one of the tags
                    val resolved =
                        repo
                            .findByTag("java", "latest", Platform.LINUX_X64, Distribution.TEMURIN.some())
                            .shouldBeRight()

                    // then: returns the version with every tag attached
                    resolved.shouldBeSome()
                    resolved.onSome { v ->
                        v.candidate shouldBe "java"
                        v.version shouldBe "27.0.2"
                        v.tags.shouldBeSome()
                        v.tags.onSome { tags ->
                            tags shouldContain "latest"
                            tags shouldContain "lts"
                            tags shouldHaveSize 2
                        }
                    }
                }
            }

            should("resolve a tag on a version without distribution") {
                val repo = PostgresVersionRepository()
                val tagRepo = PostgresTagRepository()

                withCleanDatabase {
                    // given: a non-Java version with no distribution carrying a tag
                    val version =
                        Version(
                            candidate = "groovy",
                            version = "4.0.0",
                            platform = Platform.LINUX_X64,
                            url = "https://groovy-4.0.0",
                            visible = true.some(),
                            distribution = none(),
                        )
                    val versionId = repo.createOrUpdate(version).shouldBeRight()
                    tagRepo.replaceTags(versionId, "groovy", none(), Platform.LINUX_X64, listOf("latest"))

                    // when: resolving the tag with no distribution (matches the NULL row)
                    val resolved =
                        repo
                            .findByTag("groovy", "latest", Platform.LINUX_X64, none())
                            .shouldBeRight()

                    // then: the NULL-distribution version is returned
                    resolved.shouldBeSome()
                    resolved.onSome { v ->
                        v.version shouldBe "4.0.0"
                        v.distribution shouldBe none()
                    }
                }
            }

            should("return none() for a non-existent tag") {
                val repo = PostgresVersionRepository()

                withCleanDatabase {
                    // when: resolving a tag that does not exist
                    val resolved =
                        repo
                            .findByTag("java", "latest", Platform.LINUX_X64, Distribution.TEMURIN.some())
                            .shouldBeRight()

                    // then: none() returned
                    resolved shouldBe none()
                }
            }

            should("scope resolution to distribution") {
                val repo = PostgresVersionRepository()
                val tagRepo = PostgresTagRepository()

                withCleanDatabase {
                    // given: two versions of java tagged "lts" across distributions
                    val temurin =
                        Version(
                            candidate = "java",
                            version = "25.0.2",
                            platform = Platform.LINUX_X64,
                            url = "https://java-25.0.2-temurin",
                            visible = true.some(),
                            distribution = Distribution.TEMURIN.some(),
                        )
                    val corretto =
                        Version(
                            candidate = "java",
                            version = "25.0.2",
                            platform = Platform.LINUX_X64,
                            url = "https://java-25.0.2-corretto",
                            visible = true.some(),
                            distribution = Distribution.CORRETTO.some(),
                        )
                    val temurinId = repo.createOrUpdate(temurin).shouldBeRight()
                    val correttoId = repo.createOrUpdate(corretto).shouldBeRight()
                    tagRepo.replaceTags(temurinId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("lts"))
                    tagRepo.replaceTags(correttoId, "java", Distribution.CORRETTO.some(), Platform.LINUX_X64, listOf("lts"))

                    // when: resolving the tag for Corretto
                    val resolved =
                        repo
                            .findByTag("java", "lts", Platform.LINUX_X64, Distribution.CORRETTO.some())
                            .shouldBeRight()

                    // then: the Corretto version is returned, not Temurin
                    resolved.shouldBeSome()
                    resolved.onSome { v ->
                        v.distribution shouldBe Distribution.CORRETTO.some()
                        v.url shouldBe "https://java-25.0.2-corretto"
                    }
                }
            }

            should("scope resolution to platform") {
                val repo = PostgresVersionRepository()
                val tagRepo = PostgresTagRepository()

                withCleanDatabase {
                    // given: the same tag assigned to different platforms
                    val linux =
                        Version(
                            candidate = "java",
                            version = "25.0.2",
                            platform = Platform.LINUX_X64,
                            url = "https://java-25.0.2-linux",
                            visible = true.some(),
                            distribution = Distribution.TEMURIN.some(),
                        )
                    val mac =
                        Version(
                            candidate = "java",
                            version = "25.0.2",
                            platform = Platform.MAC_ARM64,
                            url = "https://java-25.0.2-mac",
                            visible = true.some(),
                            distribution = Distribution.TEMURIN.some(),
                        )
                    val linuxId = repo.createOrUpdate(linux).shouldBeRight()
                    val macId = repo.createOrUpdate(mac).shouldBeRight()
                    tagRepo.replaceTags(linuxId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("lts"))
                    tagRepo.replaceTags(macId, "java", Distribution.TEMURIN.some(), Platform.MAC_ARM64, listOf("lts"))

                    // when: resolving the tag for macOS
                    val resolved =
                        repo
                            .findByTag("java", "lts", Platform.MAC_ARM64, Distribution.TEMURIN.some())
                            .shouldBeRight()

                    // then: the mac version is returned, not the linux one
                    resolved.shouldBeSome()
                    resolved.onSome { v ->
                        v.platform shouldBe Platform.MAC_ARM64
                        v.url shouldBe "https://java-25.0.2-mac"
                    }
                }
            }
        }
    })
