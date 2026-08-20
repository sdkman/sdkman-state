package io.sdkman.state.adapter.secondary.persistence

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.SeriesKey
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.selectVersion
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.withCleanDatabase

/**
 * Boundaries of the retirement query. The series key is (candidate, distribution, platform,
 * major, variant), so retirement crosses no platform, no distribution, no major line and no
 * variant; ineligible spellings take part in no series at all and stay advertised.
 */
@Tags("integration")
class PostgresVersionRepositoryRetireIntegrationSpec :
    ShouldSpec({

        val repo = PostgresVersionRepository()

        fun javaVersion(
            version: String,
            distribution: Distribution = Distribution.TEMURIN,
            platform: Platform = Platform.LINUX_X64,
            visible: Boolean = true,
        ): Version =
            Version(
                candidate = "java",
                version = version,
                platform = platform,
                url = "https://java/$version/${distribution.name}/${platform.name}",
                visible = visible.some(),
                distribution = distribution.some(),
            )

        // One series under test — java/TEMURIN/LINUX_X64/major 26/no variant — surrounded by rows
        // that each sit just outside one axis of the key.
        fun seedSeriesAndNeighbours() =
            insertVersions(
                // the series being superseded
                javaVersion("26.0.1"),
                javaVersion("26.0.2"),
                javaVersion("26.0.2+1.1"),
                javaVersion("26.0.0", visible = false),
                // neighbours: one axis of the series key differs
                javaVersion("26.0.1", platform = Platform.MAC_ARM64),
                javaVersion("26.0.1", distribution = Distribution.ZULU),
                javaVersion("26.0.2.fx"),
                javaVersion("21.0.1"),
                // ineligible spellings: a rebuild counter and a runtime target
                javaVersion("26.0.1.1"),
                javaVersion("26.0.4.r25"),
            )

        fun plainSeriesKey(version: String): SeriesKey =
            SeriesKey
                .of("java", Distribution.TEMURIN.some(), Platform.LINUX_X64, version)
                .shouldBeSome()

        fun visibilityOf(
            version: String,
            distribution: Distribution = Distribution.TEMURIN,
            platform: Platform = Platform.LINUX_X64,
        ): Boolean =
            selectVersion("java", version, distribution.some(), platform)
                .shouldBeSome()
                .visible
                .shouldBeSome()

        context("retireOtherVersionsInSeries") {
            should("return every other visible member of the series") {
                withCleanDatabase {
                    // given: a series holding the posted version, two older visible rows and a hidden one
                    seedSeriesAndNeighbours()

                    // when: publishing into the series
                    val retired =
                        repo
                            .retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1")
                            .shouldBeRight()

                    // then: only the other visible members come back — an already hidden row is not
                    // retired a second time, and the posted version excludes itself
                    retired.map { it.version } shouldContainExactlyInAnyOrder listOf("26.0.1", "26.0.2")
                }
            }

            should("hide every other visible member of the series") {
                withCleanDatabase {
                    seedSeriesAndNeighbours()

                    repo.retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1").shouldBeRight()

                    visibilityOf("26.0.2") shouldBe false
                }
            }

            should("leave the posted version visible") {
                withCleanDatabase {
                    seedSeriesAndNeighbours()

                    repo.retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1").shouldBeRight()

                    visibilityOf("26.0.2+1.1") shouldBe true
                }
            }

            should("retire nothing on another platform") {
                withCleanDatabase {
                    // given: the same version published on a platform the release has not reached
                    seedSeriesAndNeighbours()

                    repo.retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1").shouldBeRight()

                    // then: a partially rolled-out release retires only where its replacement landed
                    visibilityOf("26.0.1", platform = Platform.MAC_ARM64) shouldBe true
                }
            }

            should("retire nothing under another distribution") {
                withCleanDatabase {
                    seedSeriesAndNeighbours()

                    repo.retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1").shouldBeRight()

                    visibilityOf("26.0.1", distribution = Distribution.ZULU) shouldBe true
                }
            }

            should("retire nothing in another variant") {
                withCleanDatabase {
                    // given: an fx build at the same release level — a distinct artefact, not a duplicate
                    seedSeriesAndNeighbours()

                    repo.retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1").shouldBeRight()

                    visibilityOf("26.0.2.fx") shouldBe true
                }
            }

            should("retire nothing in another major line") {
                withCleanDatabase {
                    seedSeriesAndNeighbours()

                    repo.retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1").shouldBeRight()

                    visibilityOf("21.0.1") shouldBe true
                }
            }

            should("retire no rebuild-counter row") {
                withCleanDatabase {
                    // given: a four-component version, which the grammar rules ineligible
                    seedSeriesAndNeighbours()

                    repo.retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1").shouldBeRight()

                    // then: an unrecognised spelling stays advertised rather than being silently hidden
                    visibilityOf("26.0.1.1") shouldBe true
                }
            }

            should("retire no runtime-target row") {
                withCleanDatabase {
                    seedSeriesAndNeighbours()

                    repo.retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1").shouldBeRight()

                    visibilityOf("26.0.4.r25") shouldBe true
                }
            }

            should("leave an already hidden member hidden") {
                withCleanDatabase {
                    seedSeriesAndNeighbours()

                    repo.retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1").shouldBeRight()

                    visibilityOf("26.0.0") shouldBe false
                }
            }

            should("retire the legacy variant row when a semverish variant build is published") {
                withCleanDatabase {
                    // given: the migrated `26.0.2.fx` row and its semverish replacement
                    insertVersions(javaVersion("26.0.2.fx"), javaVersion("26.0.2-fx+1.1"), javaVersion("26.0.2"))

                    repo
                        .retireOtherVersionsInSeries(plainSeriesKey("26.0.2-fx+1.1"), "26.0.2-fx+1.1")
                        .shouldBeRight()

                    // then: the two spellings of the fx variant share a series
                    visibilityOf("26.0.2.fx") shouldBe false
                }
            }

            should("retire nothing when the series holds only the posted version") {
                withCleanDatabase {
                    // given: a first publication into an empty series
                    insertVersions(javaVersion("26.0.2+1.1"))

                    val retired =
                        repo
                            .retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1")
                            .shouldBeRight()

                    // then: re-posting the current version is a no-op
                    retired shouldBe emptyList()
                }
            }

            should("retire within a series that carries no distribution") {
                withCleanDatabase {
                    // given: rows with a NULL distribution, which never share a series with a distributed row
                    insertVersions(
                        Version(
                            candidate = "java",
                            version = "26.0.1",
                            platform = Platform.LINUX_X64,
                            url = "https://java/26.0.1",
                            visible = true.some(),
                            distribution = none(),
                        ),
                    )
                    insertVersions(javaVersion("26.0.1"))

                    val key =
                        SeriesKey
                            .of("java", none(), Platform.LINUX_X64, "26.0.2+1.1")
                            .shouldBeSome()
                    val retired = repo.retireOtherVersionsInSeries(key, "26.0.2+1.1").shouldBeRight()

                    // then: only the NULL-distribution row is retired
                    retired.map { it.distribution } shouldBe listOf(none())
                }
            }
        }
    })
