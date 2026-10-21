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
class JavaSupersessionTagsAcceptanceSpec :
    ShouldSpec({

        should("keep lts in its series when a post heads another series") {
            val ltsHead = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
            val nextFeatureBuild = javaVersion(Distribution.TEMURIN, "29.0.0.0+ea.1")

            withCleanDatabase {
                // given: the head of series 25 holds lts and 25
                insertTaggedVersion(ltsHead, "lts", "25")

                // when: DISCO posts the first build of series 29
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(nextFeatureBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the post takes 29 but not lts, the head of series 25 keeps its visibility and tags
                tagsOf(nextFeatureBuild) shouldContain "29"
                tagsOf(nextFeatureBuild) shouldNotContain "lts"
                visibilityOf(ltsHead) shouldBe true.some()
                tagsOf(ltsHead) shouldContainAll listOf("lts", "25")
            }
        }

        should("move lts from a hidden row of the series onto the next head") {
            val hiddenLtsHolder = javaVersion(Distribution.TEMURIN, "25.0.4.1+1", visible = false)
            val visibleHead = javaVersion(Distribution.TEMURIN, "25.0.3.0")
            val newerBuild = javaVersion(Distribution.TEMURIN, "25.0.5.0+1")

            withCleanDatabase {
                // given: a hidden row holds lts and the visible head holds 25
                insertTaggedVersion(hiddenLtsHolder, "lts")
                insertTaggedVersion(visibleHead, "25")

                // when: DISCO posts a newer build of the series
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the newer build is the visible head holding lts and 25, the hidden row is untagged
                visibilityOf(newerBuild) shouldBe true.some()
                tagsOf(newerBuild) shouldContainAll listOf("lts", "25")
                tagsOf(hiddenLtsHolder).shouldBeEmpty()
            }
        }

        should("move latest to the head of a higher series, early access included") {
            val latestHolder = javaVersion(Distribution.TEMURIN, "27.0.0.0+35")
            val earlyAccess = javaVersion(Distribution.TEMURIN, "28.0.0.0+ea.5")

            withCleanDatabase {
                // given: the GA head of series 27 holds 27 and latest
                insertTaggedVersion(latestHolder, "27", "latest")

                // when: DISCO posts an early-access build of series 28
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(earlyAccess, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the early-access build is visible holding 28 and latest, the GA head stays visible holding 27
                visibilityOf(earlyAccess) shouldBe true.some()
                tagsOf(earlyAccess) shouldContainAll listOf("28", "latest")
                visibilityOf(latestHolder) shouldBe true.some()
                tagsOf(latestHolder) shouldContain "27"
            }
        }

        should("leave latest on a higher series when a lower series gains a new head") {
            val latestHolder = javaVersion(Distribution.OPENJDK, "28.0.0.0+ea.20")
            val lowerSeriesHead = javaVersion(Distribution.OPENJDK, "27.0.0+35")
            val lowerSeriesBuild = javaVersion(Distribution.OPENJDK, "27.0.1.0+3")

            withCleanDatabase {
                // given: the early-access head of series 28 holds 28 and latest, the head of series 27 holds 27
                insertTaggedVersion(latestHolder, "28", "latest")
                insertTaggedVersion(lowerSeriesHead, "27")

                // when: DISCO posts a newer build of series 27
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(lowerSeriesBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the post is the visible head holding 27, latest stays on series 28
                visibilityOf(lowerSeriesBuild) shouldBe true.some()
                tagsOf(lowerSeriesBuild) shouldContain "27"
                tagsOf(latestHolder) shouldContain "latest"
            }
        }

        should("give a series that never had a major tag its major tag but not lts") {
            val untaggedHead = javaVersion(Distribution.TEMURIN, "21.0.12+1.1")
            val newerBuild = javaVersion(Distribution.TEMURIN, "21.0.13.0+1")

            withCleanDatabase {
                // given: the head of series 21 is visible and untagged
                insertVersions(untaggedHead)

                // when: DISCO posts a newer build of the series
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the newer build holds 21 but not lts
                tagsOf(newerBuild) shouldContain "21"
                tagsOf(newerBuild) shouldNotContain "lts"
            }
        }

        should("move the major tag to an interim release and leave the feature line visible with lts") {
            val featureLineHead = javaVersion(Distribution.GRAALVM, "21.0.0.0.1")
            val interimRelease = javaVersion(Distribution.GRAALVM, "21.5.0.0.1")

            withCleanDatabase {
                // given: the feature line head holds lts and 21
                insertTaggedVersion(featureLineHead, "lts", "21")

                // when: DISCO posts the first build of the interim line
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(interimRelease, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the interim release is visible holding 21, the feature line head stays visible holding lts
                visibilityOf(interimRelease) shouldBe true.some()
                tagsOf(interimRelease) shouldContain "21"
                visibilityOf(featureLineHead) shouldBe true.some()
                tagsOf(featureLineHead) shouldContain "lts"
            }
        }

        should("let a newer interim release hide the previous interim release") {
            val previousInterim = javaVersion(Distribution.GRAALCE, "25.3.4+1.r25")
            val newerInterim = javaVersion(Distribution.GRAALCE, "25.4.1.0")

            withCleanDatabase {
                // given: the previous interim release is visible
                insertVersions(previousInterim)

                // when: DISCO posts a newer interim release of the same feature
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerInterim, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the newer interim release is visible and the previous one is hidden
                visibilityOf(newerInterim) shouldBe true.some()
                visibilityOf(previousInterim) shouldBe false.some()
            }
        }
    })
