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

        fun seedSeriesAndNeighbours() =
            insertVersions(
                javaVersion("26.0.1"),
                javaVersion("26.0.2"),
                javaVersion("26.0.2+1.1"),
                javaVersion("26.0.0", visible = false),
                javaVersion("26.0.1", platform = Platform.MAC_ARM64),
                javaVersion("26.0.1", distribution = Distribution.ZULU),
                javaVersion("26.0.2.fx"),
                javaVersion("21.0.1"),
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
                    seedSeriesAndNeighbours()

                    val retired =
                        repo
                            .retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1")
                            .shouldBeRight()

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
                    seedSeriesAndNeighbours()

                    repo.retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1").shouldBeRight()

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
                    seedSeriesAndNeighbours()

                    repo.retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1").shouldBeRight()

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
                    insertVersions(javaVersion("26.0.2.fx"), javaVersion("26.0.2-fx+1.1"), javaVersion("26.0.2"))

                    repo
                        .retireOtherVersionsInSeries(plainSeriesKey("26.0.2-fx+1.1"), "26.0.2-fx+1.1")
                        .shouldBeRight()

                    visibilityOf("26.0.2.fx") shouldBe false
                }
            }

            should("retire nothing when the series holds only the posted version") {
                withCleanDatabase {
                    insertVersions(javaVersion("26.0.2+1.1"))

                    val retired =
                        repo
                            .retireOtherVersionsInSeries(plainSeriesKey("26.0.2+1.1"), "26.0.2+1.1")
                            .shouldBeRight()

                    retired shouldBe emptyList()
                }
            }

            should("retire within a series that carries no distribution") {
                withCleanDatabase {
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

                    retired.map { it.distribution } shouldBe listOf(none())
                }
            }
        }
    })
