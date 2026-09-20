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
                // given: one candidate tagged lts on both resolvable platforms
                seedLtsVersion("gradle", "9.0.0", Platform.UNIVERSAL)
                seedLtsVersion("gradle", "8.0.0", Platform.LINUX_X64)

                // when: the defaults are derived
                val defaults = repo.findLtsDefaults().shouldBeRight()

                // then: UNIVERSAL wins — the platforms are a resolution order, not a filter
                defaults shouldContain ("gradle" to "9.0.0")
            }
        }

        should("fall back to the LINUX_X64 row when no UNIVERSAL row is tagged lts") {
            withCleanDatabase {
                // given: the candidate carries lts only on LINUX_X64, plus an untagged UNIVERSAL row
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

                // when: the defaults are derived
                val defaults = repo.findLtsDefaults().shouldBeRight()

                // then: an untagged UNIVERSAL row does not block the fallback
                defaults shouldContain ("gradle" to "8.0.0")
            }
        }

        should("omit a candidate whose only lts tag sits on MAC_ARM64") {
            withCleanDatabase {
                // given: the candidate is tagged lts on a platform outside the resolution order
                seedLtsVersion("scala", "3.5.0", Platform.MAC_ARM64)

                // when: the defaults are derived
                val defaults = repo.findLtsDefaults().shouldBeRight()

                // then: no default at all — MAC_ARM64 is never consulted
                defaults.shouldNotContainKey("scala")
            }
        }

        should("include a candidate whose lts row is not visible") {
            withCleanDatabase {
                // given: the lts tag points at a retired row, as between supersession and the next DISCO pass
                seedLtsVersion("groovy", "4.0.0", Platform.UNIVERSAL, visible = false)

                // when: the defaults are derived
                val defaults = repo.findLtsDefaults().shouldBeRight()

                // then: visibility goes unfiltered, so this agrees with GET /versions/{c}/tags/lts
                defaults shouldContain ("groovy" to "4.0.0")
            }
        }

        should("omit a version row that carries a distribution") {
            withCleanDatabase {
                // given: a distribution on the *version* row while the tag row carries none —
                // reading version_tags.distribution instead would wrongly include it
                seedLtsVersion("java", "25.0.2", Platform.UNIVERSAL, Distribution.TEMURIN.some(), tagDistribution = none())

                // when: the defaults are derived
                val defaults = repo.findLtsDefaults().shouldBeRight()

                // then: the filter reads versions.distribution, so the row is omitted
                defaults.shouldNotContainKey("java")
            }
        }

        should("return one entry per candidate") {
            withCleanDatabase {
                // given: three candidates, one of them tagged lts on both resolvable platforms
                seedLtsVersion("gradle", "9.0.0", Platform.UNIVERSAL)
                seedLtsVersion("gradle", "8.0.0", Platform.LINUX_X64)
                seedLtsVersion("groovy", "4.0.0", Platform.UNIVERSAL)
                seedLtsVersion("kotlin", "2.2.0", Platform.LINUX_X64)

                // when: the defaults are derived
                val defaults = repo.findLtsDefaults().shouldBeRight()

                // then: the map is keyed by candidate, so the two gradle rows collapse into one entry
                defaults shouldHaveSize 3
            }
        }
    })
