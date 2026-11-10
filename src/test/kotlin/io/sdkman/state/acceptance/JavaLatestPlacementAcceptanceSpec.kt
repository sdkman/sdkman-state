package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.support.insertTaggedVersion
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.postJavaVersion
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.selectTagHolder
import io.sdkman.state.support.shouldBeNone
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.tagsOf
import io.sdkman.state.support.visibilityOf
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import io.sdkman.state.support.withTestApplicationAtLtsFeature

@Tags("acceptance")
class JavaLatestPlacementAcceptanceSpec :
    ShouldSpec({

        fun latestHolder(
            distribution: Distribution,
            platform: Platform,
        ) = selectTagHolder("java", "latest", distribution.some(), platform)

        should("take latest off a platform without the top feature") {
            val topFeatureHolder = javaVersion(Distribution.ZULU, "27.0.1.0+9", Platform.LINUX_X64)
            val strayHolder = javaVersion(Distribution.ZULU, "17.0.20.1+1", Platform.LINUX_X32)
            val newerBuild = javaVersion(Distribution.ZULU, "17.0.21.0+9", Platform.LINUX_X32)

            withCleanDatabase {
                // given: latest sits on 27 on LINUX_X64 and on 17 on LINUX_X32, which has no 27
                insertTaggedVersion(topFeatureHolder, "latest")
                insertTaggedVersion(strayHolder, "latest")

                // when: DISCO posts a newer 17 build for LINUX_X32
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the post is visible without latest, LINUX_X32 holds no latest, LINUX_X64 keeps it on 27
                visibilityOf(newerBuild) shouldBe true.some()
                tagsOf(newerBuild) shouldNotContain "latest"
                latestHolder(Distribution.ZULU, Platform.LINUX_X32).shouldBeNone()
                latestHolder(Distribution.ZULU, Platform.LINUX_X64) shouldBeSome "27.0.1.0+9"
            }
        }

        should("take latest off the other platforms with the first post of a new top feature") {
            val firstPlatform = Platform.LINUX_X64
            val secondPlatform = Platform.MAC_ARM64

            withCleanDatabase {
                // given: 27 holds latest on both platforms
                insertTaggedVersion(javaVersion(Distribution.TEMURIN, "27.0.1.0+9", firstPlatform), "latest")
                insertTaggedVersion(javaVersion(Distribution.TEMURIN, "27.0.1.0+9", secondPlatform), "latest")

                withTestApplication {
                    registerCandidates("java")

                    // when: DISCO posts 28 early access for the first platform
                    postJavaVersion(
                        javaVersion(Distribution.TEMURIN, "28.0.0.0+ea.5", firstPlatform),
                        javaVendorToken(),
                    ).status shouldBe HttpStatusCode.NoContent

                    // then: 28 takes latest on the first platform, and the second platform holds none
                    latestHolder(Distribution.TEMURIN, firstPlatform) shouldBeSome "28.0.0.0+ea.5"
                    latestHolder(Distribution.TEMURIN, secondPlatform).shouldBeNone()

                    // when: DISCO posts 28 early access for the second platform
                    postJavaVersion(
                        javaVersion(Distribution.TEMURIN, "28.0.0.0+ea.5", secondPlatform),
                        javaVendorToken(),
                    ).status shouldBe HttpStatusCode.NoContent
                }

                // then: 28 takes latest on the second platform too
                latestHolder(Distribution.TEMURIN, secondPlatform) shouldBeSome "28.0.0.0+ea.5"
            }
        }

        should("place latest on early access as the bleeding edge") {
            val earlyAccess = javaVersion(Distribution.OPENJDK, "28.0.0.0+ea.24")

            withCleanDatabase {
                // given: a GA 27 and an earlier 28 early access are visible
                insertVersions(
                    javaVersion(Distribution.OPENJDK, "27.0.1.0+3"),
                    javaVersion(Distribution.OPENJDK, "28.0.0.0+ea.23"),
                )

                // when: DISCO posts the next 28 early access
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(earlyAccess, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the early access holds latest
                tagsOf(earlyAccess) shouldContain "latest"
            }
        }

        should("give no platform latest when the distribution is below the LTS feature") {
            val platforms = listOf(Platform.LINUX_X64, Platform.MAC_ARM64, Platform.WINDOWS_X64)

            withCleanDatabase {
                // given: 21 is the highest row on every platform and holds latest there
                platforms.forEach { platform ->
                    insertTaggedVersion(javaVersion(Distribution.KONA, "21.0.9.0+1", platform), "latest")
                }

                // when: DISCO posts a newer 21 build for LINUX_X64
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(
                        javaVersion(Distribution.KONA, "21.0.10.0+1", Platform.LINUX_X64),
                        javaVendorToken(),
                    ).status shouldBe HttpStatusCode.NoContent
                }

                // then: no platform holds latest
                platforms.forEach { platform -> latestHolder(Distribution.KONA, platform).shouldBeNone() }
            }
        }

        should("keep a newer latest on a platform still serving the old lts") {
            val oldLtsHolder = javaVersion(Distribution.KONA, "25.0.8.0+1")
            val newerBuild = javaVersion(Distribution.KONA, "28.0.2.0+1")

            withCleanDatabase {
                // given: 25 holds lts on LINUX_X64, and 28 is the highest row on every platform, holding latest
                insertTaggedVersion(oldLtsHolder, "lts")
                listOf(Platform.LINUX_X64, Platform.MAC_ARM64).forEach { platform ->
                    insertTaggedVersion(javaVersion(Distribution.KONA, "28.0.1.0+1", platform), "latest")
                }

                // when: DISCO posts a newer 28 build for LINUX_X64 while the LTS feature is 29
                withTestApplicationAtLtsFeature(29) {
                    registerCandidates("java")
                    postJavaVersion(newerBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the newer 28 build holds latest and 25 keeps lts, while MAC_ARM64, holding no lts, loses latest
                tagsOf(newerBuild) shouldContain "latest"
                tagsOf(oldLtsHolder) shouldContain "lts"
                latestHolder(Distribution.KONA, Platform.MAC_ARM64).shouldBeNone()
            }
        }

        should("place latest with lts when nothing is newer than the LTS feature") {
            val newerBuild = javaVersion(Distribution.CORRETTO, "25.0.5.0+9")

            withCleanDatabase {
                // given: 21 and 25 are visible and nothing is above 25
                insertVersions(
                    javaVersion(Distribution.CORRETTO, "21.0.13.0+10"),
                    javaVersion(Distribution.CORRETTO, "25.0.4.0+9"),
                )

                // when: DISCO posts a newer 25 build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the newer 25 build holds both lts and latest
                tagsOf(newerBuild) shouldContainAll listOf("lts", "latest")
            }
        }
    })
