package io.sdkman.state.domain.model

import arrow.core.none
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.sdkman.state.support.shouldBeNone
import io.sdkman.state.support.shouldBeSome

class SeriesKeySpec :
    ShouldSpec({

        val candidate = "java"
        val distribution = Distribution.LIBERICA.some()
        val platform = Platform.LINUX_X64

        fun seriesKeyOf(version: String) = SeriesKey.of(candidate, distribution, platform, version)

        context("eligible shapes") {

            listOf(
                Triple("26.0.2+1.1", 26, none<String>()),
                Triple("26.0.2-fx+1.1", 26, "fx".some()),
                Triple("17.0.20-crac+1.2", 17, "crac".some()),
                Triple("28.0.0+ea.11", 28, none()),
                Triple("26.0.2", 26, none()),
                Triple("26.0.2.fx", 26, "fx".some()),
                Triple("17.0.19.crac", 17, "crac".some()),
            ).forEach { (version, expectedMajor, expectedVariant) ->
                should("derive major $expectedMajor and variant $expectedVariant from $version") {
                    // given: a stored or submitted version in an eligible shape

                    // when: deriving its series key
                    val result = seriesKeyOf(version)

                    // then: the key carries the major line and the variant
                    result shouldBeSome
                        SeriesKey(
                            candidate = candidate,
                            distribution = distribution,
                            platform = platform,
                            major = expectedMajor,
                            variant = expectedVariant,
                        )
                }
            }

            should("accept a semverish variant with no build metadata") {
                // given: a semverish version carrying a variant but no build section
                val version = "26.0.2-fx"

                // when: deriving its series key
                val result = seriesKeyOf(version)

                // then: the variant is read from the semverish section
                result.shouldBeSome().variant shouldBeSome "fx"
            }
        }

        context("ineligible shapes") {

            listOf(
                "25.0.4.r25" to "r25 is a runtime target, not a variant",
                "11.0.14.1" to "four numeric components are a rebuild counter",
                "25.r25" to "two core components",
                "26.0" to "two numeric core components",
                "26" to "one core component",
                "26.0.2-lts+1.1" to "lts is outside the variant vocabulary",
                "26.0.2.lts" to "lts is outside the legacy variant vocabulary",
                "26.0.2.fx+1.1" to "the legacy variant spelling carries no build metadata",
                "26.0.2.fx.crac" to "a single variant only",
                "" to "an empty version",
            ).forEach { (version, reason) ->
                should("reject '$version' because $reason") {
                    // given: a version outside the eligibility grammar

                    // when: deriving its series key
                    val result = seriesKeyOf(version)

                    // then: it takes part in no series
                    result.shouldBeNone()
                }
            }
        }

        context("series version pattern") {

            listOf(
                "26.0.2",
                "26.0.2+1.1",
                "26.0.0",
                "26.0.2.fx",
                "26.0.2-fx+1.1",
                "26.0.1.1",
                "26.0.4.r25",
                "21.0.2",
                "260.0.2",
            ).forEach { version ->
                should("agree with series membership for $version") {
                    // given: the plain major 26 series
                    val series = seriesKeyOf("26.0.2").shouldBeSome()

                    // when: matching a version against the series pattern
                    val matches = Regex(series.versionPattern()).matches(version)

                    // then: the pattern admits exactly the members the parser derives
                    matches shouldBe (seriesKeyOf(version) == series.some())
                }
            }

            should("admit both spellings of its variant") {
                // given: the fx series of major 26
                val series = seriesKeyOf("26.0.2-fx+1.1").shouldBeSome()

                // when: matching the legacy spelling of a member
                val matches = Regex(series.versionPattern()).matches("26.0.2.fx")

                // then: legacy and semverish members share the one pattern
                matches shouldBe true
            }
        }

        context("series identity") {

            should("place a version and its rebuilt counterpart in the same series") {
                // given: the migrated row and the DISCO row of one release
                val migrated = seriesKeyOf("26.0.2")

                // when: deriving the key of the build that supersedes it
                val disco = seriesKeyOf("26.0.2+1.1")

                // then: build metadata does not separate the series
                disco shouldBe migrated
            }

            should("separate the plain series from the fx series") {
                // given: a plain publication
                val plain = seriesKeyOf("26.0.2+1.1")

                // when: comparing it with the fx build of the same release
                val fx = seriesKeyOf("26.0.2-fx+1.1")

                // then: the variant keeps them apart
                fx shouldNotBe plain
            }

            should("separate the legacy and semverish spellings of one variant") {
                // given: the legacy spelling of an fx build
                val legacy = seriesKeyOf("26.0.2.fx")

                // when: comparing it with the semverish spelling
                val semverish = seriesKeyOf("26.0.2-fx+1.1")

                // then: both spell the same variant, so they share a series
                semverish shouldBe legacy
            }

            should("separate major lines") {
                // given: a major 21 version
                val major21 = seriesKeyOf("21.0.2")

                // when: comparing it with a major 26 version
                val major26 = seriesKeyOf("26.0.2")

                // then: the major line keeps them apart
                major26 shouldNotBe major21
            }

            should("separate platforms") {
                // given: a version on LINUX_X64
                val linux = seriesKeyOf("26.0.2")

                // when: comparing it with the same version on MAC_ARM64
                val mac = SeriesKey.of(candidate, distribution, Platform.MAC_ARM64, "26.0.2")

                // then: retirement never crosses platforms
                mac shouldNotBe linux
            }

            should("separate distributions") {
                // given: a LIBERICA version
                val liberica = seriesKeyOf("26.0.2")

                // when: comparing it with the same version under ZULU
                val zulu = SeriesKey.of(candidate, Distribution.ZULU.some(), platform, "26.0.2")

                // then: a Temurin publication never retires a Zulu row
                zulu shouldNotBe liberica
            }

            should("separate a distributionless row from one carrying a distribution") {
                // given: a version with a distribution
                val withDistribution = seriesKeyOf("26.0.2")

                // when: comparing it with the same version with no distribution
                val withoutDistribution = SeriesKey.of(candidate, none(), platform, "26.0.2")

                // then: the two never share a series
                withoutDistribution shouldNotBe withDistribution
            }

            should("separate candidates") {
                // given: a java version
                val java = seriesKeyOf("26.0.2")

                // when: comparing it with the same version under another candidate
                val scala = SeriesKey.of("scala", distribution, platform, "26.0.2")

                // then: the candidate is part of the key
                scala shouldNotBe java
            }
        }
    })
