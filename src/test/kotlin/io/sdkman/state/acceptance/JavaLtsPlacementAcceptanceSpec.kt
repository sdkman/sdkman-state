package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.support.insertTaggedVersion
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaTagHolder
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.postJavaVersion
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.shouldBeNone
import io.sdkman.state.support.tagsOf
import io.sdkman.state.support.visibilityOf
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import io.sdkman.state.support.withTestApplicationAtLtsFeature

@Tags("acceptance")
class JavaLtsPlacementAcceptanceSpec :
    ShouldSpec({

        should("place lts on a distribution that never had it") {
            val ltsFeatureHead = javaVersion(Distribution.MICROSOFT, "25.0.5.0+2")
            val olderFeatureBuild = javaVersion(Distribution.MICROSOFT, "21.0.13.0+1")

            withCleanDatabase {
                // given: a visible 25 holds no lts
                insertVersions(ltsFeatureHead)

                // when: DISCO posts a 21 build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(olderFeatureBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the 25 takes lts, the 21 does not
                tagsOf(ltsFeatureHead) shouldContain "lts"
                tagsOf(olderFeatureBuild) shouldNotContain "lts"
            }
        }

        should("place lts on the newest interim release of the LTS feature") {
            val featureLineHead = javaVersion(Distribution.GRAALVM, "25.0.5.0+3")
            val interimHead = javaVersion(Distribution.GRAALVM, "25.4.4.1+1")
            val newerInterimBuild = javaVersion(Distribution.GRAALVM, "25.5.0.0+1")

            withCleanDatabase {
                // given: the feature line head is untagged, the interim head holds 25 and latest
                insertVersions(featureLineHead)
                insertTaggedVersion(interimHead, "25", "latest")

                // when: DISCO posts a newer interim build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerInterimBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the newer interim build holds 25, lts and latest, the beaten interim head is hidden and untagged,
                // and the feature line head stays visible and untagged
                tagsOf(newerInterimBuild) shouldContainAll listOf("25", "lts", "latest")
                visibilityOf(interimHead) shouldBe false.some()
                tagsOf(interimHead).shouldBeEmpty()
                visibilityOf(featureLineHead) shouldBe true.some()
                tagsOf(featureLineHead).shouldBeEmpty()
            }
        }

        should("never place lts afresh on a feature below the LTS feature") {
            withCleanDatabase {
                // given: an untagged 21 is the highest row on LINUX_X32
                insertVersions(javaVersion(Distribution.LIBERICA, "21.0.13.0+11", Platform.LINUX_X32))

                // when: DISCO posts a newer 21 build for LINUX_X32
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(
                        javaVersion(Distribution.LIBERICA, "21.0.13.0+12", Platform.LINUX_X32),
                        javaVendorToken(),
                    ).status shouldBe HttpStatusCode.NoContent
                }

                // then: LINUX_X32 holds no lts
                javaTagHolder("lts", Distribution.LIBERICA, Platform.LINUX_X32).shouldBeNone()
            }
        }

        should("keep a platform's lts until it has a GA of the new LTS feature") {
            val newLtsFeatureHead = javaVersion(Distribution.TEMURIN, "29.0.0.0+35")
            val keptLtsHolder = javaVersion(Distribution.TEMURIN, "25.0.8.0+7", Platform.MAC_X64)

            withCleanDatabase {
                // given: 25 holds lts on LINUX_X64 and MAC_X64, and only LINUX_X64 has a 29
                insertVersions(newLtsFeatureHead)
                insertTaggedVersion(javaVersion(Distribution.TEMURIN, "25.0.8.0+7"), "lts")
                insertTaggedVersion(keptLtsHolder, "lts")

                // when: DISCO posts a 21 build for LINUX_X64 while the LTS feature is 29
                withTestApplicationAtLtsFeature(29) {
                    registerCandidates("java")
                    postJavaVersion(
                        javaVersion(Distribution.TEMURIN, "21.0.16.0+3"),
                        javaVendorToken(),
                    ).status shouldBe HttpStatusCode.NoContent
                }

                // then: the 29 takes lts on LINUX_X64, and MAC_X64 keeps it on 25
                tagsOf(newLtsFeatureHead) shouldContain "lts"
                tagsOf(keptLtsHolder) shouldContain "lts"
            }
        }

        should("never place lts on early access of the LTS feature") {
            val oldLtsHolder = javaVersion(Distribution.TEMURIN, "25.0.8.0+7")
            val earlyAccess = javaVersion(Distribution.TEMURIN, "29.0.0.0+ea.31")
            val generallyAvailableElsewhere = javaVersion(Distribution.TEMURIN, "29.0.0.0+35", Platform.MAC_ARM64)

            withCleanDatabase {
                // given: 25 holds lts on LINUX_X64 beside a 29 early access, and MAC_ARM64 has a 29 GA
                insertTaggedVersion(oldLtsHolder, "lts")
                insertVersions(javaVersion(Distribution.TEMURIN, "29.0.0.0+ea.30"), generallyAvailableElsewhere)

                // when: DISCO posts the next 29 early access for LINUX_X64 while the LTS feature is 29
                withTestApplicationAtLtsFeature(29) {
                    registerCandidates("java")
                    postJavaVersion(earlyAccess, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: 25 keeps lts and the early access takes latest but not lts, while the 29 GA on MAC_ARM64 takes lts
                tagsOf(oldLtsHolder) shouldContain "lts"
                tagsOf(earlyAccess) shouldContain "latest"
                tagsOf(earlyAccess) shouldNotContain "lts"
                tagsOf(generallyAvailableElsewhere) shouldContain "lts"
            }
        }

        should("move a kept lts to the new head of its series") {
            val keptLtsHolder = javaVersion(Distribution.TEMURIN, "25.0.8.0+7", Platform.MAC_X64)
            val newerBuild = javaVersion(Distribution.TEMURIN, "25.0.9.0+5", Platform.MAC_X64)
            val newLtsFeatureHead = javaVersion(Distribution.TEMURIN, "29.0.0.0+35")

            withCleanDatabase {
                // given: 25 holds lts on MAC_X64, which has no 29, and LINUX_X64 has a 29 GA
                insertTaggedVersion(keptLtsHolder, "lts")
                insertVersions(newLtsFeatureHead)

                // when: DISCO posts a newer 25 build for MAC_X64 while the LTS feature is 29
                withTestApplicationAtLtsFeature(29) {
                    registerCandidates("java")
                    postJavaVersion(newerBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the newer 25 build takes lts and the old one is hidden and untagged, while the 29 GA on LINUX_X64 takes lts
                tagsOf(newerBuild) shouldContain "lts"
                visibilityOf(keptLtsHolder) shouldBe false.some()
                tagsOf(keptLtsHolder).shouldBeEmpty()
                tagsOf(newLtsFeatureHead) shouldContain "lts"
            }
        }

        should("remove a kept lts whose series has no visible head") {
            val newLtsFeatureHead = javaVersion(Distribution.TEMURIN, "29.0.0.0+35")

            withCleanDatabase {
                // given: a hidden 25 holds lts on MAC_X64, which has no visible 25 and no 29, and LINUX_X64 has a 29 GA
                insertTaggedVersion(javaVersion(Distribution.TEMURIN, "25.0.8.0+7", Platform.MAC_X64, visible = false), "lts")
                insertVersions(newLtsFeatureHead)

                // when: DISCO posts a 21 build for MAC_X64 while the LTS feature is 29
                withTestApplicationAtLtsFeature(29) {
                    registerCandidates("java")
                    postJavaVersion(
                        javaVersion(Distribution.TEMURIN, "21.0.16.0+3", Platform.MAC_X64),
                        javaVendorToken(),
                    ).status shouldBe HttpStatusCode.NoContent
                }

                // then: MAC_X64 holds no lts, while the 29 GA on LINUX_X64 takes lts
                javaTagHolder("lts", Distribution.TEMURIN, Platform.MAC_X64).shouldBeNone()
                tagsOf(newLtsFeatureHead) shouldContain "lts"
            }
        }
    })
