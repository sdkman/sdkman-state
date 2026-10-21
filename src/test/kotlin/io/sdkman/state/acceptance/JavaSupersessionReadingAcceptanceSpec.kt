package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.support.insertTaggedVersion
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.postJavaVersion
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.tagsOf
import io.sdkman.state.support.visibilityOf
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class JavaSupersessionReadingAcceptanceSpec :
    ShouldSpec({

        should("hide a legacy spelling of the same build and move its tag onto the post") {
            val legacySpelling = javaVersion(Distribution.OPENJDK, "27.0.0+35")
            val semverishSpelling = javaVersion(Distribution.OPENJDK, "27.0.0.0+35")

            withCleanDatabase {
                // given: the legacy spelling of the build is visible and holds 27
                insertTaggedVersion(legacySpelling, "27")

                // when: DISCO posts the same build in its semverish spelling
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(semverishSpelling, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the semverish spelling is the visible head holding 27, the legacy spelling is hidden
                visibilityOf(semverishSpelling) shouldBe true.some()
                tagsOf(semverishSpelling) shouldContain "27"
                visibilityOf(legacySpelling) shouldBe false.some()
            }
        }

        should("read a three-component row as four components and hide it under a newer build") {
            val threeComponentRow = javaVersion(Distribution.TEMURIN, "17.0.20")
            val newerBuild = javaVersion(Distribution.TEMURIN, "17.0.20.0+8")

            withCleanDatabase {
                // given: a three-component row is visible
                insertVersions(threeComponentRow)

                // when: DISCO posts a numeric build of the same core
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the newer build is visible and the three-component row is hidden
                visibilityOf(newerBuild) shouldBe true.some()
                visibilityOf(threeComponentRow) shouldBe false.some()
            }
        }

        should("read a legacy dot variant as a variant and hide it under a newer build of that variant") {
            val dotVariantRow = javaVersion(Distribution.LIBERICA, "17.0.19.crac")
            val newerCracBuild = javaVersion(Distribution.LIBERICA, "17.0.20.0-crac+1")

            withCleanDatabase {
                // given: a crac row in the legacy dot spelling is visible
                insertVersions(dotVariantRow)

                // when: DISCO posts a newer crac build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerCracBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the newer crac build is visible and the dot-variant row is hidden
                visibilityOf(newerCracBuild) shouldBe true.some()
                visibilityOf(dotVariantRow) shouldBe false.some()
            }
        }

        should("leave an unreadable row visible while the post hides its readable series member") {
            val unreadableRow = javaVersion(Distribution.LIBERICA_NIK, "25.0.4.r25")
            val readableHead = javaVersion(Distribution.LIBERICA_NIK, "25.0.1.0+1")
            val newerBuild = javaVersion(Distribution.LIBERICA_NIK, "25.0.5.0+1")

            withCleanDatabase {
                // given: an unreadable row and a readable head are both visible
                insertVersions(unreadableRow, readableHead)

                // when: DISCO posts a newer build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the newer build is visible, the readable head is hidden, the unreadable row stays visible
                visibilityOf(newerBuild) shouldBe true.some()
                visibilityOf(readableHead) shouldBe false.some()
                visibilityOf(unreadableRow) shouldBe true.some()
            }
        }
    })
