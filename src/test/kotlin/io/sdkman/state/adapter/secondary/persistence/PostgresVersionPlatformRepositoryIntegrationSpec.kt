package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.withCleanDatabase

@Tags("integration")
class PostgresVersionPlatformRepositoryIntegrationSpec :
    ShouldSpec({

        val versions = PostgresVersionRepository()
        val repo = PostgresVersionPlatformRepository()

        fun jpx(
            version: String,
            platform: Platform,
            distribution: Option<Distribution> = none(),
        ): Version =
            Version(
                candidate = "jpx",
                version = version,
                platform = platform,
                url = "https://jpx-$version-${platform.name}",
                visible = true.some(),
                distribution = distribution,
            )

        context("findPlatforms") {
            should("return every platform a version is stored on") {
                withCleanDatabase {
                    // given: one version stored on two platforms, and another version
                    versions.createOrUpdate(jpx("1.2.0", Platform.LINUX_X64)).shouldBeRight()
                    versions.createOrUpdate(jpx("1.2.0", Platform.MAC_ARM64)).shouldBeRight()
                    versions.createOrUpdate(jpx("1.1.0", Platform.UNIVERSAL)).shouldBeRight()

                    // when: reading the platforms of the two-row version
                    val platforms = repo.findPlatforms("jpx", "1.2.0", none()).shouldBeRight()

                    // then: only its own two platforms come back
                    platforms shouldContainExactlyInAnyOrder listOf(Platform.LINUX_X64, Platform.MAC_ARM64)
                }
            }

            should("return no platforms for an unknown version") {
                withCleanDatabase {
                    // given: a stored version
                    versions.createOrUpdate(jpx("1.2.0", Platform.LINUX_X64)).shouldBeRight()

                    // when: reading the platforms of a version never stored
                    val platforms = repo.findPlatforms("jpx", "9.9.9", none()).shouldBeRight()

                    // then: nothing comes back
                    platforms.shouldBeEmpty()
                }
            }

            should("return only the platforms of the requested distribution") {
                withCleanDatabase {
                    // given: one version under two distributions on different platforms
                    versions.createOrUpdate(jpx("1.2.0", Platform.LINUX_X64, Distribution.TEMURIN.some())).shouldBeRight()
                    versions.createOrUpdate(jpx("1.2.0", Platform.MAC_ARM64, Distribution.ZULU.some())).shouldBeRight()
                    versions.createOrUpdate(jpx("1.2.0", Platform.UNIVERSAL)).shouldBeRight()

                    // when: reading the platforms of the temurin distribution
                    val platforms = repo.findPlatforms("jpx", "1.2.0", Distribution.TEMURIN.some()).shouldBeRight()

                    // then: only the temurin platform comes back
                    platforms shouldContainExactly listOf(Platform.LINUX_X64)
                }
            }
        }
    })
