package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.support.deleteJavaVersion
import io.sdkman.state.support.insertTaggedVersion
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.postJavaVersion
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.selectTagHolder
import io.sdkman.state.support.shouldBeNone
import io.sdkman.state.support.tagsOf
import io.sdkman.state.support.visibilityOf
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class JavaPlacementHealingAcceptanceSpec :
    ShouldSpec({

        fun latestHolder(
            distribution: Distribution,
            platform: Platform,
        ) = selectTagHolder("java", "latest", distribution.some(), platform)

        should("heal the distribution after a vendor delete") {
            val hiddenRow = javaVersion(Distribution.ZULU, "8.0.504+1", visible = false)

            withCleanDatabase {
                // given: a stray latest on LINUX_X32, a 27 on LINUX_X64 and a hidden untagged row there
                insertTaggedVersion(javaVersion(Distribution.ZULU, "17.0.21.0+9", Platform.LINUX_X32), "latest")
                insertVersions(javaVersion(Distribution.ZULU, "27.0.1.0+9"), hiddenRow)

                // when: DISCO deletes the hidden row
                withTestApplication {
                    deleteJavaVersion(hiddenRow, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: LINUX_X32 holds no latest
                latestHolder(Distribution.ZULU, Platform.LINUX_X32).shouldBeNone()
            }
        }

        should("heal the distribution after a post stored hidden") {
            val olderBuild = javaVersion(Distribution.TEMURIN, "17.0.20.0+8", Platform.MAC_X64)

            withCleanDatabase {
                // given: a stray latest on 17 on MAC_X64, whose highest row is 26, and a 27 on LINUX_X64
                insertTaggedVersion(javaVersion(Distribution.TEMURIN, "17.0.20.1+1", Platform.MAC_X64), "latest")
                insertVersions(
                    javaVersion(Distribution.TEMURIN, "26.0.2.1+1", Platform.MAC_X64),
                    javaVersion(Distribution.TEMURIN, "27.0.1.0+9"),
                )

                // when: DISCO posts an older 17 build for MAC_X64
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(olderBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the older build is hidden, and MAC_X64 holds no latest
                visibilityOf(olderBuild) shouldBe false.some()
                latestHolder(Distribution.TEMURIN, Platform.MAC_X64).shouldBeNone()
            }
        }

        should("move a tag from a hidden row to the head") {
            val hiddenLtsHolder = javaVersion(Distribution.ZULU, "25.0.4.1+1", visible = false)
            val head = javaVersion(Distribution.ZULU, "25.0.5.0+8")

            withCleanDatabase {
                // given: a hidden 25 holds lts beside a visible 25 head
                insertTaggedVersion(hiddenLtsHolder, "lts")
                insertVersions(head)

                // when: DISCO posts a 21 build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(
                        javaVersion(Distribution.ZULU, "21.0.13.0+9"),
                        javaVendorToken(),
                    ).status shouldBe HttpStatusCode.NoContent
                }

                // then: the head takes lts, and the hidden row is untagged
                tagsOf(head) shouldContain "lts"
                tagsOf(hiddenLtsHolder).shouldBeEmpty()
            }
        }

        should("never give a variant lts or latest") {
            val plainHead = javaVersion(Distribution.ZULU, "27.0.1.0+9", Platform.MAC_X64)
            val variant = javaVersion(Distribution.ZULU, "27.0.2.0-crac+1", Platform.MAC_X64)

            withCleanDatabase {
                // given: a visible plain 27 on MAC_X64
                insertVersions(plainHead)

                // when: DISCO posts a newer crac build for MAC_X64
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(variant, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the crac build is untagged, and the plain head takes latest
                tagsOf(variant).shouldBeEmpty()
                tagsOf(plainHead) shouldContain "latest"
            }
        }

        should("keep the tags where they are when heads cannot be ordered") {
            val unknownBuildHead = javaVersion(Distribution.KONA, "25.0.5.0+r17")
            val numberedBuildHead = javaVersion(Distribution.KONA, "25.0.5.0+1")
            val orderedHeadElsewhere = javaVersion(Distribution.KONA, "25.0.5.0+1", Platform.MAC_ARM64)

            withCleanDatabase {
                // given: two heads of one core on LINUX_X64, as the admin left them, the unknown build holding lts and latest,
                // and an untagged 25 head on MAC_ARM64
                insertTaggedVersion(unknownBuildHead, "lts", "latest")
                insertVersions(numberedBuildHead, orderedHeadElsewhere)

                // when: DISCO posts a 21 build for LINUX_X64
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(
                        javaVersion(Distribution.KONA, "21.0.10.0+1"),
                        javaVendorToken(),
                    ).status shouldBe HttpStatusCode.NoContent
                }

                // then: the unknown build keeps both tags and the numbered build stays untagged, while the MAC_ARM64 head takes lts
                tagsOf(unknownBuildHead) shouldContainAll listOf("lts", "latest")
                tagsOf(numberedBuildHead).shouldBeEmpty()
                tagsOf(orderedHeadElsewhere) shouldContain "lts"
            }
        }

        should("place nothing after a refused post") {
            val latestHolderBelowLtsFeature = javaVersion(Distribution.ZULU, "17.0.21.0+9", Platform.LINUX_X32)

            withCleanDatabase {
                // given: a 17 holds latest on LINUX_X32
                insertTaggedVersion(latestHolderBelowLtsFeature, "latest")

                // when: DISCO posts a three-component version for LINUX_X64
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(
                        javaVersion(Distribution.ZULU, "25.0.5"),
                        javaVendorToken(),
                    ).status shouldBe HttpStatusCode.BadRequest
                }

                // then: the 17 keeps latest
                tagsOf(latestHolderBelowLtsFeature) shouldContain "latest"
            }
        }
    })
