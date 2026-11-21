package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
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

        fun seedTaggedVersion(
            candidate: String,
            version: String,
            platform: Platform,
            distribution: Option<Distribution> = none(),
            tagDistribution: Option<Distribution> = distribution,
            visible: Boolean = true,
            tag: String = "stable",
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
            insertTag(candidate, tag, tagDistribution, platform, versionId)
        }

        should("prefer the UNIVERSAL row over the LINUX_X64 row") {
            withCleanDatabase {
                // given: one candidate tagged stable on both resolvable platforms
                seedTaggedVersion("gradle", "9.0.0", Platform.UNIVERSAL)
                seedTaggedVersion("gradle", "8.0.0", Platform.LINUX_X64)

                // when: the defaults are derived
                val defaults = repo.findDefaults().shouldBeRight()

                // then: UNIVERSAL wins — the platforms are a resolution order, not a filter
                defaults shouldContain ("gradle" to "9.0.0")
            }
        }

        should("fall back to the LINUX_X64 row when no UNIVERSAL row is tagged stable") {
            withCleanDatabase {
                // given: the candidate carries stable only on LINUX_X64, plus an untagged UNIVERSAL row
                insertVersionWithId(
                    Version(
                        candidate = "gradle",
                        version = "9.0.0",
                        platform = Platform.UNIVERSAL,
                        url = "https://gradle-9.0.0",
                        visible = true.some(),
                    ),
                )
                seedTaggedVersion("gradle", "8.0.0", Platform.LINUX_X64)

                // when: the defaults are derived
                val defaults = repo.findDefaults().shouldBeRight()

                // then: an untagged UNIVERSAL row does not block the fallback
                defaults shouldContain ("gradle" to "8.0.0")
            }
        }

        should("omit a candidate whose only stable tag sits on MAC_ARM64") {
            withCleanDatabase {
                // given: the candidate is tagged stable on a platform outside the resolution order
                seedTaggedVersion("scala", "3.5.0", Platform.MAC_ARM64)

                // when: the defaults are derived
                val defaults = repo.findDefaults().shouldBeRight()

                // then: no default at all — MAC_ARM64 is never consulted
                defaults.shouldNotContainKey("scala")
            }
        }

        should("include a candidate whose stable row is not visible") {
            withCleanDatabase {
                // given: the stable tag points at a retired row
                seedTaggedVersion("groovy", "4.0.0", Platform.UNIVERSAL, visible = false)

                // when: the defaults are derived
                val defaults = repo.findDefaults().shouldBeRight()

                // then: visibility goes unfiltered, so this agrees with GET /versions/{c}/tags/stable
                defaults shouldContain ("groovy" to "4.0.0")
            }
        }

        should("omit a version row that carries a distribution") {
            withCleanDatabase {
                // given: a distribution on the *version* row while the tag row carries none —
                // reading version_tags.distribution instead would wrongly include it
                seedTaggedVersion("java", "25.0.2", Platform.UNIVERSAL, Distribution.TEMURIN.some(), tagDistribution = none())

                // when: the defaults are derived
                val defaults = repo.findDefaults().shouldBeRight()

                // then: the filter reads versions.distribution, so the row is omitted
                defaults.shouldNotContainKey("java")
            }
        }

        should("return one entry per candidate") {
            withCleanDatabase {
                // given: three candidates, one of them tagged stable on both resolvable platforms
                seedTaggedVersion("gradle", "9.0.0", Platform.UNIVERSAL)
                seedTaggedVersion("gradle", "8.0.0", Platform.LINUX_X64)
                seedTaggedVersion("groovy", "4.0.0", Platform.UNIVERSAL)
                seedTaggedVersion("kotlin", "2.2.0", Platform.LINUX_X64)

                // when: the defaults are derived
                val defaults = repo.findDefaults().shouldBeRight()

                // then: the map is keyed by candidate, so the two gradle rows collapse into one entry
                defaults shouldHaveSize 3
            }
        }

        should("ignore lts on UNIVERSAL and resolve stable on LINUX_X64") {
            withCleanDatabase {
                // given: stable only on LINUX_X64 and lts on UNIVERSAL
                seedTaggedVersion("kuml", "0.20.5", Platform.LINUX_X64)
                seedTaggedVersion("kuml", "0.21.0", Platform.UNIVERSAL, tag = "lts")

                // when: the defaults are derived
                val defaults = repo.findDefaults().shouldBeRight()

                // then: lts is not read, even on the preferred platform
                defaults shouldContain ("kuml" to "0.20.5")
            }
        }

        should("omit a candidate tagged only lts") {
            withCleanDatabase {
                // given: gradle carries only stable and scala carries only lts
                seedTaggedVersion("gradle", "8.14", Platform.UNIVERSAL)
                seedTaggedVersion("scala", "3.4.3", Platform.UNIVERSAL, tag = "lts")

                // when: the defaults are derived
                val defaults = repo.findDefaults().shouldBeRight()

                // then: only stable produces a default
                defaults shouldBe mapOf("gradle" to "8.14")
            }
        }

        should("ignore stable on MAC_ARM64 and resolve stable on LINUX_X64") {
            withCleanDatabase {
                // given: stable on a platform outside the resolution order and on LINUX_X64
                seedTaggedVersion("kuml", "0.21.0", Platform.MAC_ARM64)
                seedTaggedVersion("kuml", "0.20.5", Platform.LINUX_X64)

                // when: the defaults are derived
                val defaults = repo.findDefaults().shouldBeRight()

                // then: a stable tag outside the four combinations is inert
                defaults shouldContain ("kuml" to "0.20.5")
            }
        }

        should("match tag names exactly so Stable never counts as stable") {
            withCleanDatabase {
                // given: a capitalised Stable tag on UNIVERSAL and a stable tag on LINUX_X64
                seedTaggedVersion("kuml", "0.21.0", Platform.UNIVERSAL, tag = "Stable")
                seedTaggedVersion("kuml", "0.20.5", Platform.LINUX_X64)

                // when: the defaults are derived
                val defaults = repo.findDefaults().shouldBeRight()

                // then: Stable is a different tag, so the LINUX_X64 stable resolves
                defaults shouldContain ("kuml" to "0.20.5")
            }
        }

        should("omit a candidate tagged only latest") {
            withCleanDatabase {
                // given: the candidate carries latest on UNIVERSAL and no stable
                seedTaggedVersion("jpx", "0.15.5", Platform.UNIVERSAL, tag = "latest")

                // when: the defaults are derived
                val defaults = repo.findDefaults().shouldBeRight()

                // then: latest never produces a default
                defaults.shouldNotContainKey("jpx")
            }
        }

        should("ignore stable on a version row with a distribution and fall back to LINUX_X64") {
            withCleanDatabase {
                // given: stable points at a TEMURIN version row through a tag row with no distribution
                seedTaggedVersion(
                    "kuml",
                    "0.21.0",
                    Platform.UNIVERSAL,
                    Distribution.TEMURIN.some(),
                    tagDistribution = none(),
                )
                seedTaggedVersion("kuml", "0.20.5", Platform.LINUX_X64)

                // when: the defaults are derived
                val defaults = repo.findDefaults().shouldBeRight()

                // then: the distribution filter drops stable before the cascade ranks it
                defaults shouldContain ("kuml" to "0.20.5")
            }
        }
    })
