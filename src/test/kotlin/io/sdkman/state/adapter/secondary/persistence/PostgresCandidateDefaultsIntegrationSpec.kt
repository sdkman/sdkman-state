package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.maps.shouldNotContainKey
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.insertTag
import io.sdkman.state.support.insertVersionWithId
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.withCleanDatabase

@Tags("integration")
class PostgresCandidateDefaultsIntegrationSpec :
    ShouldSpec({
        val repo = PostgresCandidateRepository()

        fun seedLtsVersion(
            candidate: String,
            version: String,
            platform: Platform,
            distribution: Option<Distribution> = none(),
            tagDistribution: Option<Distribution> = distribution,
            visible: Boolean = true,
        ) {
            val versionId =
                insertVersionWithId(
                    Version(
                        candidate = candidate,
                        version = version,
                        platform = platform,
                        url = "https://$candidate-$version",
                        visible = visible.some(),
                        distribution = distribution,
                    ),
                )
            insertTag(candidate, "lts", tagDistribution, platform, versionId)
        }

        should("prefer the UNIVERSAL row over the LINUX_X64 row") {
            withCleanDatabase {
                seedLtsVersion("gradle", "9.0.0", Platform.UNIVERSAL)
                seedLtsVersion("gradle", "8.0.0", Platform.LINUX_X64)

                val defaults = repo.findLtsDefaults().shouldBeRight()

                defaults shouldContain ("gradle" to "9.0.0")
            }
        }

        should("fall back to the LINUX_X64 row when no UNIVERSAL row is tagged lts") {
            withCleanDatabase {
                insertVersionWithId(
                    Version(
                        candidate = "gradle",
                        version = "9.0.0",
                        platform = Platform.UNIVERSAL,
                        url = "https://gradle-9.0.0",
                        visible = true.some(),
                    ),
                )
                seedLtsVersion("gradle", "8.0.0", Platform.LINUX_X64)

                val defaults = repo.findLtsDefaults().shouldBeRight()

                defaults shouldContain ("gradle" to "8.0.0")
            }
        }

        should("omit a candidate whose only lts tag sits on MAC_ARM64") {
            withCleanDatabase {
                seedLtsVersion("scala", "3.5.0", Platform.MAC_ARM64)

                val defaults = repo.findLtsDefaults().shouldBeRight()

                defaults.shouldNotContainKey("scala")
            }
        }

        should("include a candidate whose lts row is not visible") {
            withCleanDatabase {
                seedLtsVersion("groovy", "4.0.0", Platform.UNIVERSAL, visible = false)

                val defaults = repo.findLtsDefaults().shouldBeRight()

                defaults shouldContain ("groovy" to "4.0.0")
            }
        }

        should("omit a version row that carries a distribution") {
            withCleanDatabase {
                seedLtsVersion("java", "25.0.2", Platform.UNIVERSAL, Distribution.TEMURIN.some(), tagDistribution = none())

                val defaults = repo.findLtsDefaults().shouldBeRight()

                defaults.shouldNotContainKey("java")
            }
        }

        should("return one entry per candidate") {
            withCleanDatabase {
                seedLtsVersion("gradle", "9.0.0", Platform.UNIVERSAL)
                seedLtsVersion("gradle", "8.0.0", Platform.LINUX_X64)
                seedLtsVersion("groovy", "4.0.0", Platform.UNIVERSAL)
                seedLtsVersion("kotlin", "2.2.0", Platform.LINUX_X64)

                val defaults = repo.findLtsDefaults().shouldBeRight()

                defaults shouldHaveSize 3
            }
        }
    })
