package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.support.deserializeVersionData
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.postJavaVersion
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.selectAuditRecordsByOperation
import io.sdkman.state.support.tagsOf
import io.sdkman.state.support.visibilityOf
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class JavaSupersessionSeriesAcceptanceSpec :
    ShouldSpec({

        should("let an fx post supersede its own series without tagging it or touching the plain series") {
            val fxHead = javaVersion(Distribution.LIBERICA, "21.0.12-fx+1.1")
            val plainHead = javaVersion(Distribution.LIBERICA, "21.0.12+1.1")
            val newerFxBuild = javaVersion(Distribution.LIBERICA, "21.0.13.0-fx+1")

            withCleanDatabase {
                // given: an fx head and a plain head of the same feature are visible
                insertVersions(fxHead, plainHead)

                // when: DISCO posts a newer fx build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerFxBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the fx post is the visible, untagged head; only the old fx head is hidden
                visibilityOf(newerFxBuild) shouldBe true.some()
                tagsOf(newerFxBuild).shouldBeEmpty()
                visibilityOf(fxHead) shouldBe false.some()
                visibilityOf(plainHead) shouldBe true.some()
            }
        }

        should("let a crac post hide only its own series and leave the plain and fx series visible") {
            val cracHead = javaVersion(Distribution.LIBERICA, "17.0.20-crac+1.2")
            val fxHead = javaVersion(Distribution.LIBERICA, "17.0.20-fx+1.1")
            val plainHead = javaVersion(Distribution.LIBERICA, "17.0.20+1.1")
            val newerCracBuild = javaVersion(Distribution.LIBERICA, "17.0.21.0-crac+1")

            withCleanDatabase {
                // given: a crac, an fx and a plain head of the same feature are visible
                insertVersions(cracHead, fxHead, plainHead)

                // when: DISCO posts a newer crac build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerCracBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the old crac head is hidden, the fx and plain heads stay visible
                visibilityOf(cracHead) shouldBe false.some()
                visibilityOf(fxHead) shouldBe true.some()
                visibilityOf(plainHead) shouldBe true.some()
            }
        }

        should("collapse leftover visible rows of a series into the next post and retire each of them") {
            val leftovers =
                listOf("8.0.504", "8.0.472", "8.0.232").map { javaVersion(Distribution.CORRETTO, it) }
            val nextBuild = javaVersion(Distribution.CORRETTO, "8.0.512.0+1")

            withCleanDatabase {
                // given: three leftover rows of CORRETTO major 8 are visible
                insertVersions(*leftovers.toTypedArray())

                // when: DISCO posts the next build of the series
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(nextBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the post is the only visible CORRETTO row of major 8 and each leftover is retired
                (leftovers + nextBuild).filter { visibilityOf(it) == true.some() } shouldBe listOf(nextBuild)
                selectAuditRecordsByOperation(AuditOperation.RETIRE)
                    .map { deserializeVersionData(it.versionData).version } shouldContainExactlyInAnyOrder leftovers.map { it.version }
            }
        }

        should("leave the same version on another platform or distribution visible") {
            val otherPlatform = javaVersion(Distribution.TEMURIN, "25.0.4.1+1", Platform.MAC_ARM64)
            val otherDistribution = javaVersion(Distribution.ZULU, "25.0.4.1+1")
            val newerBuild = javaVersion(Distribution.TEMURIN, "25.0.5.0+1")

            withCleanDatabase {
                // given: the same build is visible for TEMURIN on MAC_ARM64 and for ZULU on LINUX_X64
                insertVersions(otherPlatform, otherDistribution)

                // when: DISCO posts a newer TEMURIN build on LINUX_X64
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: neither row outside the post's distribution and platform is hidden
                visibilityOf(otherPlatform) shouldBe true.some()
                visibilityOf(otherDistribution) shouldBe true.some()
            }
        }
    })
