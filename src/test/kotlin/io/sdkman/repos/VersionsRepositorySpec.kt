package io.sdkman.repos

import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import io.sdkman.support.selectLastUpdatedAt
import io.sdkman.support.withCleanDatabase
import kotlinx.coroutines.delay

class VersionsRepositorySpec :
    ShouldSpec({

        context("create") {
            should("insert a new version into the database") {
                val repo = VersionsRepository()
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
                    val result = repo.create(version)
                    result.isRight() shouldBe true

                    val retrieved =
                        repo.read(
                            candidate = version.candidate,
                            version = version.version,
                            platform = version.platform,
                            distribution = version.distribution,
                        )
                    retrieved shouldBe version.some()
                }
            }

            should("insert a version without distribution") {
                val repo = VersionsRepository()
                val version =
                    Version(
                        candidate = "scala",
                        version = "3.3.1",
                        platform = Platform.UNIVERSAL,
                        url = "https://scala-3.3.1",
                        visible = true.some(),
                        distribution = None,
                    )

                withCleanDatabase {
                    val result = repo.create(version)
                    result.isRight() shouldBe true

                    val retrieved =
                        repo.read(
                            candidate = version.candidate,
                            version = version.version,
                            platform = version.platform,
                            distribution = version.distribution,
                        )
                    retrieved shouldBe version.some()
                }
            }

            should("update an existing version (upsert)") {
                val repo = VersionsRepository()
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
                        md5sum = None,
                        sha256sum = "new-hash".some(),
                    )

                withCleanDatabase {
                    repo.create(originalVersion)
                    repo.create(updatedVersion)

                    val retrieved =
                        repo.read(
                            candidate = updatedVersion.candidate,
                            version = updatedVersion.version,
                            platform = updatedVersion.platform,
                            distribution = updatedVersion.distribution,
                        )
                    retrieved shouldBe updatedVersion.some()
                }
            }

            should("update last_updated_at timestamp when upserting a version") {
                val repo = VersionsRepository()
                val version =
                    Version(
                        candidate = "groovy",
                        version = "4.0.0",
                        platform = Platform.UNIVERSAL,
                        url = "https://groovy-4.0.0-original",
                        visible = true.some(),
                        distribution = None,
                    )

                withCleanDatabase {
                    // First insert
                    repo.create(version)
                    val firstTimestamp =
                        selectLastUpdatedAt(
                            candidate = version.candidate,
                            version = version.version,
                            distribution = version.distribution,
                            platform = version.platform,
                        )

                    firstTimestamp.isSome() shouldBe true

                    // Wait to ensure timestamp difference
                    delay(100)

                    // Upsert with different data
                    val updatedVersion = version.copy(url = "https://groovy-4.0.0-updated")
                    repo.create(updatedVersion)

                    val secondTimestamp =
                        selectLastUpdatedAt(
                            candidate = version.candidate,
                            version = version.version,
                            distribution = version.distribution,
                            platform = version.platform,
                        )

                    secondTimestamp.isSome() shouldBe true
                    secondTimestamp shouldNotBe firstTimestamp
                }
            }
        }

        context("read by candidate with filters") {
            should("retrieve all versions for a candidate") {
                val repo = VersionsRepository()
                val version1 =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-17.0.1",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
                    )
                val version2 =
                    Version(
                        candidate = "java",
                        version = "21.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-21.0.1",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
                    )

                withCleanDatabase {
                    repo.create(version1)
                    repo.create(version2)

                    val versions =
                        repo.read(
                            candidate = "java",
                            platform = None,
                            distribution = None,
                            visible = None,
                        )

                    versions shouldHaveSize 2
                    versions shouldContain version1
                    versions shouldContain version2
                }
            }

            should("filter versions by platform") {
                val repo = VersionsRepository()
                val linuxVersion =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-17.0.1-linux",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
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
                    repo.create(linuxVersion)
                    repo.create(macVersion)

                    val versions =
                        repo.read(
                            candidate = "java",
                            platform = Platform.LINUX_X64.some(),
                            distribution = None,
                            visible = None,
                        )

                    versions shouldHaveSize 1
                    versions.first() shouldBe linuxVersion
                }
            }

            should("filter versions by distribution") {
                val repo = VersionsRepository()
                val temurinVersion =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-17.0.1-temurin",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
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
                    repo.create(temurinVersion)
                    repo.create(zulu)

                    val versions =
                        repo.read(
                            candidate = "java",
                            platform = None,
                            distribution = Distribution.TEMURIN.some(),
                            visible = None,
                        )

                    versions shouldHaveSize 1
                    versions.first() shouldBe temurinVersion
                }
            }

            should("filter versions by visibility") {
                val repo = VersionsRepository()
                val visibleVersion =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-17.0.1",
                        visible = true.some(),
                        distribution = Distribution.TEMURIN.some(),
                    )
                val hiddenVersion =
                    Version(
                        candidate = "java",
                        version = "18.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://java-18.0.1",
                        visible = false.some(),
                        distribution = Distribution.TEMURIN.some(),
                    )

                withCleanDatabase {
                    repo.create(visibleVersion)
                    repo.create(hiddenVersion)

                    val visibleVersions =
                        repo.read(
                            candidate = "java",
                            platform = None,
                            distribution = None,
                            visible = true.some(),
                        )

                    visibleVersions shouldHaveSize 1
                    visibleVersions.first() shouldBe visibleVersion

                    val hiddenVersions =
                        repo.read(
                            candidate = "java",
                            platform = None,
                            distribution = None,
                            visible = false.some(),
                        )

                    hiddenVersions shouldHaveSize 1
                    hiddenVersions.first() shouldBe hiddenVersion
                }
            }

            should("return empty list when no versions match") {
                val repo = VersionsRepository()

                withCleanDatabase {
                    val versions =
                        repo.read(
                            candidate = "nonexistent",
                            platform = None,
                            distribution = None,
                            visible = None,
                        )

                    versions.shouldBeEmpty()
                }
            }
        }

        context("read specific version") {
            should("retrieve a specific version by candidate, version, platform, and distribution") {
                val repo = VersionsRepository()
                val version =
                    Version(
                        candidate = "kotlin",
                        version = "1.9.0",
                        platform = Platform.UNIVERSAL,
                        url = "https://kotlin-1.9.0",
                        visible = true.some(),
                        distribution = Distribution.JETBRAINS.some(),
                    )

                withCleanDatabase {
                    repo.create(version)

                    val retrieved =
                        repo.read(
                            candidate = "kotlin",
                            version = "1.9.0",
                            platform = Platform.UNIVERSAL,
                            distribution = Distribution.JETBRAINS.some(),
                        )

                    retrieved shouldBe version.some()
                }
            }

            should("retrieve a version without distribution") {
                val repo = VersionsRepository()
                val version =
                    Version(
                        candidate = "scala",
                        version = "3.3.1",
                        platform = Platform.UNIVERSAL,
                        url = "https://scala-3.3.1",
                        visible = true.some(),
                        distribution = None,
                    )

                withCleanDatabase {
                    repo.create(version)

                    val retrieved =
                        repo.read(
                            candidate = "scala",
                            version = "3.3.1",
                            platform = Platform.UNIVERSAL,
                            distribution = None,
                        )

                    retrieved shouldBe version.some()
                }
            }

            should("return None when version does not exist") {
                val repo = VersionsRepository()

                withCleanDatabase {
                    val retrieved =
                        repo.read(
                            candidate = "nonexistent",
                            version = "1.0.0",
                            platform = Platform.UNIVERSAL,
                            distribution = None,
                        )

                    retrieved shouldBe None
                }
            }
        }

        context("delete") {
            should("delete an existing version with distribution") {
                val repo = VersionsRepository()
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
                    repo.create(version)

                    val uniqueVersion =
                        UniqueVersion(
                            candidate = version.candidate,
                            version = version.version,
                            distribution = version.distribution,
                            platform = version.platform,
                        )

                    val deletedCount = repo.delete(uniqueVersion)
                    deletedCount shouldBe 1

                    val retrieved =
                        repo.read(
                            candidate = version.candidate,
                            version = version.version,
                            platform = version.platform,
                            distribution = version.distribution,
                        )
                    retrieved shouldBe None
                }
            }

            should("delete an existing version without distribution") {
                val repo = VersionsRepository()
                val version =
                    Version(
                        candidate = "scala",
                        version = "3.3.1",
                        platform = Platform.UNIVERSAL,
                        url = "https://scala-3.3.1",
                        visible = true.some(),
                        distribution = None,
                    )

                withCleanDatabase {
                    repo.create(version)

                    val uniqueVersion =
                        UniqueVersion(
                            candidate = version.candidate,
                            version = version.version,
                            distribution = version.distribution,
                            platform = version.platform,
                        )

                    val deletedCount = repo.delete(uniqueVersion)
                    deletedCount shouldBe 1

                    val retrieved =
                        repo.read(
                            candidate = version.candidate,
                            version = version.version,
                            platform = version.platform,
                            distribution = version.distribution,
                        )
                    retrieved shouldBe None
                }
            }

            should("return 0 when deleting non-existent version") {
                val repo = VersionsRepository()

                withCleanDatabase {
                    val uniqueVersion =
                        UniqueVersion(
                            candidate = "nonexistent",
                            version = "1.0.0",
                            distribution = None,
                            platform = Platform.UNIVERSAL,
                        )

                    val deletedCount = repo.delete(uniqueVersion)
                    deletedCount shouldBe 0
                }
            }
        }
    })
