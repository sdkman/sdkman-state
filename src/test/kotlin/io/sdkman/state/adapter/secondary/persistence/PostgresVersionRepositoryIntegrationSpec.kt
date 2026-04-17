package io.sdkman.state.adapter.secondary.persistence

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.selectLastUpdatedAt
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.withCleanDatabase
import kotlinx.coroutines.delay

@Tags("integration")
class PostgresVersionRepositoryIntegrationSpec :
    ShouldSpec({

        context("create") {
            should("insert a new version into the database") {
                val repo = PostgresVersionRepository()
                val version =
                    Version(
                        candidate = "java",
                        version = "21.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-21.0.1",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
                        md5sum = "abc123".some(),
                    )

                withCleanDatabase {
                    val result = repo.createOrUpdate(version)
                    result.shouldBeRight()

                    val retrieved =
                        repo.findUnique(
                            candidate = version.candidate,
                            version = version.version,
                            platform = version.platform,
                            distribution = version.distribution,
                        )
                    retrieved.shouldBeRight()
                    retrieved.onRight { it shouldBe version.copy(tags = emptyList<String>().some()).some() }
                }
            }

            should("insert a version without distribution") {
                val repo = PostgresVersionRepository()
                val version =
                    Version(
                        candidate = "scala",
                        version = "3.3.1",
                        platform = Platform.UNIVERSAL,
                        url = "https://scala-3.3.1",
                        visible = true.some(),
                        distribution = none(),
                    )

                withCleanDatabase {
                    val result = repo.createOrUpdate(version)
                    result.shouldBeRight()

                    val retrieved =
                        repo.findUnique(
                            candidate = version.candidate,
                            version = version.version,
                            platform = version.platform,
                            distribution = version.distribution,
                        )
                    retrieved.shouldBeRight()
                    retrieved.onRight { it shouldBe version.copy(tags = emptyList<String>().some()).some() }
                }
            }

            should("update an existing version (upsert)") {
                val repo = PostgresVersionRepository()
                val originalVersion =
                    Version(
                        candidate = "kotlin",
                        version = "1.9.0",
                        platform = Platform.UNIVERSAL,
                        url = "https://kotlin-1.9.0-original",
                        visible = true.some(),
                        distribution = Distribution.JETBRAINS.some(),
                        md5sum = "original-hash".some(),
                    )

                val updatedVersion =
                    originalVersion.copy(
                        url = "https://kotlin-1.9.0-updated",
                        visible = false.some(),
                        md5sum = none(),
                        sha256sum = "new-hash".some(),
                    )

                withCleanDatabase {
                    repo.createOrUpdate(originalVersion)
                    repo.createOrUpdate(updatedVersion)

                    val retrieved =
                        repo.findUnique(
                            candidate = updatedVersion.candidate,
                            version = updatedVersion.version,
                            platform = updatedVersion.platform,
                            distribution = updatedVersion.distribution,
                        )
                    retrieved.shouldBeRight()
                    retrieved.onRight { it shouldBe updatedVersion.copy(tags = emptyList<String>().some()).some() }
                }
            }

            should("update last_updated_at timestamp when upserting a version") {
                val repo = PostgresVersionRepository()
                val version =
                    Version(
                        candidate = "groovy",
                        version = "4.0.0",
                        platform = Platform.UNIVERSAL,
                        url = "https://groovy-4.0.0-original",
                        visible = true.some(),
                        distribution = none(),
                    )

                withCleanDatabase {
                    // First insert
                    repo.createOrUpdate(version)
                    val firstTimestamp =
                        selectLastUpdatedAt(
                            candidate = version.candidate,
                            version = version.version,
                            distribution = version.distribution,
                            platform = version.platform,
                        )

                    firstTimestamp.shouldBeSome()

                    // Wait to ensure timestamp difference
                    delay(100)

                    // Upsert with different data
                    val updatedVersion = version.copy(url = "https://groovy-4.0.0-updated")
                    repo.createOrUpdate(updatedVersion)

                    val secondTimestamp =
                        selectLastUpdatedAt(
                            candidate = version.candidate,
                            version = version.version,
                            distribution = version.distribution,
                            platform = version.platform,
                        )

                    secondTimestamp.shouldBeSome()
                    secondTimestamp shouldNotBe firstTimestamp
                }
            }
        }

        context("read by candidate with filters") {
            should("retrieve all versions for a candidate") {
                val repo = PostgresVersionRepository()
                val version1 =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-17.0.1",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
                        tags = emptyList<String>().some(),
                    )
                val version2 =
                    Version(
                        candidate = "java",
                        version = "21.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-21.0.1",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
                        tags = emptyList<String>().some(),
                    )

                withCleanDatabase {
                    repo.createOrUpdate(version1)
                    repo.createOrUpdate(version2)

                    val result =
                        repo.findByCandidate(
                            candidate = "java",
                            platform = none(),
                            distribution = none(),
                            visible = none(),
                        )

                    val versions = result.shouldBeRight()
                    versions shouldHaveSize 2
                    versions shouldContain version1
                    versions shouldContain version2
                }
            }

            should("filter versions by platform") {
                val repo = PostgresVersionRepository()
                val linuxVersion =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-17.0.1-linux",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
                        tags = emptyList<String>().some(),
                    )
                val macVersion =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.MAC_ARM64,
                        url = "https://java-17.0.1-mac",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
                    )

                withCleanDatabase {
                    repo.createOrUpdate(linuxVersion)
                    repo.createOrUpdate(macVersion)

                    val result =
                        repo.findByCandidate(
                            candidate = "java",
                            platform = Platform.LINUX_X64.some(),
                            distribution = none(),
                            visible = none(),
                        )

                    val versions = result.shouldBeRight()
                    versions shouldHaveSize 1
                    versions.first() shouldBe linuxVersion
                }
            }

            should("filter versions by distribution") {
                val repo = PostgresVersionRepository()
                val temurinVersion =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-17.0.1-temurin",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
                        tags = emptyList<String>().some(),
                    )
                val zulu =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-17.0.1-zulu",
                        visible = true.some(),
                        distribution = Distribution.ZULU.some(),
                    )

                withCleanDatabase {
                    repo.createOrUpdate(temurinVersion)
                    repo.createOrUpdate(zulu)

                    val result =
                        repo.findByCandidate(
                            candidate = "java",
                            platform = none(),
                            distribution = Distribution.TEMURIN.some(),
                            visible = none(),
                        )

                    val versions = result.shouldBeRight()
                    versions shouldHaveSize 1
                    versions.first() shouldBe temurinVersion
                }
            }

            should("filter versions by visibility") {
                val repo = PostgresVersionRepository()
                val visibleVersion =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-17.0.1",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
                        tags = emptyList<String>().some(),
                    )
                val hiddenVersion =
                    Version(
                        candidate = "java",
                        version = "18.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-18.0.1",
                        visible = false.some(),
                        distribution = Distribution.TEMURIN.some(),
                        tags = emptyList<String>().some(),
                    )

                withCleanDatabase {
                    repo.createOrUpdate(visibleVersion)
                    repo.createOrUpdate(hiddenVersion)

                    val visibleResult =
                        repo.findByCandidate(
                            candidate = "java",
                            platform = none(),
                            distribution = none(),
                            visible = true.some(),
                        )

                    val visibleVersions = visibleResult.shouldBeRight()
                    visibleVersions shouldHaveSize 1
                    visibleVersions.first() shouldBe visibleVersion

                    val hiddenResult =
                        repo.findByCandidate(
                            candidate = "java",
                            platform = none(),
                            distribution = none(),
                            visible = false.some(),
                        )

                    val hiddenVersions = hiddenResult.shouldBeRight()
                    hiddenVersions shouldHaveSize 1
                    hiddenVersions.first() shouldBe hiddenVersion
                }
            }

            should("return empty list when no versions match") {
                val repo = PostgresVersionRepository()

                withCleanDatabase {
                    val result =
                        repo.findByCandidate(
                            candidate = "nonexistent",
                            platform = none(),
                            distribution = none(),
                            visible = none(),
                        )

                    result.shouldBeRight().shouldBeEmpty()
                }
            }
        }

        context("read specific version") {
            should("retrieve a specific version by candidate, version, platform, and distribution") {
                val repo = PostgresVersionRepository()
                val version =
                    Version(
                        candidate = "kotlin",
                        version = "1.9.0",
                        platform = Platform.UNIVERSAL,
                        url = "https://kotlin-1.9.0",
                        visible = true.some(),
                        distribution = Distribution.JETBRAINS.some(),
                        tags = emptyList<String>().some(),
                    )

                withCleanDatabase {
                    repo.createOrUpdate(version)

                    val retrieved =
                        repo.findUnique(
                            candidate = "kotlin",
                            version = "1.9.0",
                            platform = Platform.UNIVERSAL,
                            distribution = Distribution.JETBRAINS.some(),
                        )

                    retrieved.shouldBeRight()
                    retrieved.onRight { it shouldBe version.some() }
                }
            }

            should("retrieve a version without distribution") {
                val repo = PostgresVersionRepository()
                val version =
                    Version(
                        candidate = "scala",
                        version = "3.3.1",
                        platform = Platform.UNIVERSAL,
                        url = "https://scala-3.3.1",
                        visible = true.some(),
                        distribution = none(),
                        tags = emptyList<String>().some(),
                    )

                withCleanDatabase {
                    repo.createOrUpdate(version)

                    val retrieved =
                        repo.findUnique(
                            candidate = "scala",
                            version = "3.3.1",
                            platform = Platform.UNIVERSAL,
                            distribution = none(),
                        )

                    retrieved.shouldBeRight()
                    retrieved.onRight { it shouldBe version.some() }
                }
            }

            should("return none() when version does not exist") {
                val repo = PostgresVersionRepository()

                withCleanDatabase {
                    val retrieved =
                        repo.findUnique(
                            candidate = "nonexistent",
                            version = "1.0.0",
                            platform = Platform.UNIVERSAL,
                            distribution = none(),
                        )

                    retrieved.shouldBeRight()
                    retrieved.onRight { it shouldBe none() }
                }
            }
        }

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

        context("delete") {
            should("delete an existing version with distribution") {
                val repo = PostgresVersionRepository()
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-17.0.1",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
                    )

                withCleanDatabase {
                    repo.createOrUpdate(version)

                    val uniqueVersion =
                        UniqueVersion(
                            candidate = version.candidate,
                            version = version.version,
                            distribution = version.distribution,
                            platform = version.platform,
                        )

                    val deleteResult = repo.delete(uniqueVersion)
                    deleteResult.shouldBeRight()
                    deleteResult.onRight { it shouldBe 1 }

                    val retrieved =
                        repo.findUnique(
                            candidate = version.candidate,
                            version = version.version,
                            platform = version.platform,
                            distribution = version.distribution,
                        )
                    retrieved.shouldBeRight()
                    retrieved.onRight { it shouldBe none() }
                }
            }

            should("delete an existing version without distribution") {
                val repo = PostgresVersionRepository()
                val version =
                    Version(
                        candidate = "scala",
                        version = "3.3.1",
                        platform = Platform.UNIVERSAL,
                        url = "https://scala-3.3.1",
                        visible = true.some(),
                        distribution = none(),
                    )

                withCleanDatabase {
                    repo.createOrUpdate(version)

                    val uniqueVersion =
                        UniqueVersion(
                            candidate = version.candidate,
                            version = version.version,
                            distribution = version.distribution,
                            platform = version.platform,
                        )

                    val deleteResult = repo.delete(uniqueVersion)
                    deleteResult.shouldBeRight()
                    deleteResult.onRight { it shouldBe 1 }

                    val retrieved =
                        repo.findUnique(
                            candidate = version.candidate,
                            version = version.version,
                            platform = version.platform,
                            distribution = version.distribution,
                        )
                    retrieved.shouldBeRight()
                    retrieved.onRight { it shouldBe none() }
                }
            }

            should("return 0 when deleting non-existent version") {
                val repo = PostgresVersionRepository()

                withCleanDatabase {
                    val uniqueVersion =
                        UniqueVersion(
                            candidate = "nonexistent",
                            version = "1.0.0",
                            distribution = none(),
                            platform = Platform.UNIVERSAL,
                        )

                    val deleteResult = repo.delete(uniqueVersion)
                    deleteResult.shouldBeRight()
                    deleteResult.onRight { it shouldBe 0 }
                }
            }
        }
    })
