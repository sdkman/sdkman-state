package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.TagAssignment
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.deleteJavaVersion
import io.sdkman.state.support.insertTaggedVersion
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.postJavaVersion
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.selectTagHolder
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.tagsOf
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import io.sdkman.state.support.withTestApplicationAtLtsFeature

@Tags("acceptance")
class JavaPlacementAdminAcceptanceSpec :
    ShouldSpec({

        fun latestHolder(
            distribution: Distribution,
            platform: Platform,
        ) = selectTagHolder("java", "latest", distribution.some(), platform)

        suspend fun ApplicationTestBuilder.assignLtsAsAdmin(version: Version): HttpStatusCode =
            client
                .post("/versions/tags") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        TagAssignment(
                            candidate = version.candidate,
                            version = version.version,
                            distribution = version.distribution,
                            platform = version.platform,
                            tag = "lts",
                        ).toJsonString(),
                    )
                    bearerAuth(JwtTestSupport.adminToken())
                }.status

        should("hold the admin's lts only until the next vendor write") {
            val ltsFeatureHead = javaVersion(Distribution.TEMURIN, "25.0.5.0+8")
            val olderFeatureHead = javaVersion(Distribution.TEMURIN, "21.0.13.0+9")

            withCleanDatabase {
                // given: 25 holds lts beside a visible 21 on LINUX_X64
                insertTaggedVersion(ltsFeatureHead, "lts")
                insertVersions(olderFeatureHead)

                withTestApplication {
                    registerCandidates("java")

                    // when: the admin assigns lts to the 21
                    assignLtsAsAdmin(olderFeatureHead) shouldBe HttpStatusCode.NoContent

                    // then: the 21 holds lts
                    tagsOf(olderFeatureHead) shouldContain "lts"

                    // when: DISCO posts a 17 build
                    postJavaVersion(
                        javaVersion(Distribution.TEMURIN, "17.0.21.0+9"),
                        javaVendorToken(),
                    ).status shouldBe HttpStatusCode.NoContent
                }

                // then: lts is back on 25, and the 21 no longer holds it
                tagsOf(ltsFeatureHead) shouldContain "lts"
                tagsOf(olderFeatureHead) shouldNotContain "lts"
            }
        }

        should("keep the admin's lts when it agrees with the rules") {
            val oldLtsHolder = javaVersion(Distribution.TEMURIN, "25.0.8.0+7")
            val newLtsFeatureHead = javaVersion(Distribution.TEMURIN, "29.0.0.0+35")

            withCleanDatabase {
                // given: 25 holds lts beside a visible 29 GA on LINUX_X64
                insertTaggedVersion(oldLtsHolder, "lts")
                insertVersions(newLtsFeatureHead)

                // when: the admin assigns lts to the 29, then DISCO posts a 21 build while the LTS feature is 29
                withTestApplicationAtLtsFeature(29) {
                    registerCandidates("java")
                    assignLtsAsAdmin(newLtsFeatureHead) shouldBe HttpStatusCode.NoContent
                    postJavaVersion(
                        javaVersion(Distribution.TEMURIN, "21.0.16.0+3"),
                        javaVendorToken(),
                    ).status shouldBe HttpStatusCode.NoContent
                }

                // then: the 29 keeps lts, and takes latest from the placement that kept it
                tagsOf(newLtsFeatureHead) shouldContainAll listOf("lts", "latest")
            }
        }

        should("keep a stray latest on another platform after an admin post") {
            withCleanDatabase {
                // given: a 17 holds a stray latest on LINUX_X32
                insertTaggedVersion(javaVersion(Distribution.ZULU, "17.0.21.0+9", Platform.LINUX_X32), "latest")

                // when: the admin posts a 27 build for LINUX_X64
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(
                        javaVersion(Distribution.ZULU, "27.0.1.0+9"),
                        JwtTestSupport.adminToken(),
                    ).status shouldBe HttpStatusCode.NoContent
                }

                // then: the 17 still holds latest on LINUX_X32
                latestHolder(Distribution.ZULU, Platform.LINUX_X32) shouldBeSome "17.0.21.0+9"
            }
        }

        should("keep a stray latest on another platform after an admin delete") {
            val hiddenRow = javaVersion(Distribution.ZULU, "8.0.504+1", visible = false)

            withCleanDatabase {
                // given: a stray latest on LINUX_X32, a 27 on LINUX_X64 and a hidden untagged row there
                insertTaggedVersion(javaVersion(Distribution.ZULU, "17.0.21.0+9", Platform.LINUX_X32), "latest")
                insertVersions(javaVersion(Distribution.ZULU, "27.0.1.0+9"), hiddenRow)

                // when: the admin deletes the hidden row
                withTestApplication {
                    deleteJavaVersion(hiddenRow, JwtTestSupport.adminToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the 17 still holds latest on LINUX_X32
                latestHolder(Distribution.ZULU, Platform.LINUX_X32) shouldBeSome "17.0.21.0+9"
            }
        }
    })
