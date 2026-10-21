package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.TagAssignment
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertTaggedVersion
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.tagsOf
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class JavaTagRoutesAcceptanceSpec :
    ShouldSpec({
        val temurinHead = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
        val ltsAssignment =
            TagAssignment(
                candidate = temurinHead.candidate,
                version = temurinHead.version,
                distribution = temurinHead.distribution,
                platform = temurinHead.platform,
                tag = "lts",
            )
        val ltsTag =
            UniqueTag(
                candidate = temurinHead.candidate,
                tag = "lts",
                distribution = temurinHead.distribution,
                platform = temurinHead.platform,
            )

        suspend fun ApplicationTestBuilder.assignLts(token: String): HttpStatusCode =
            client
                .post("/versions/tags") {
                    contentType(ContentType.Application.Json)
                    setBody(ltsAssignment.toJsonString())
                    bearerAuth(token)
                }.status

        suspend fun ApplicationTestBuilder.removeLts(token: String): HttpStatusCode =
            client
                .delete("/versions/tags") {
                    contentType(ContentType.Application.Json)
                    setBody(ltsTag.toJsonString())
                    bearerAuth(token)
                }.status

        should("refuse with 403 DISCO's assignment of lts to a java row and leave the row untagged") {
            withCleanDatabase {
                // given: TEMURIN 25.0.4.1+1 is visible and untagged
                insertVersions(temurinHead)

                // when: DISCO assigns lts to it through POST /versions/tags
                withTestApplication {
                    assignLts(javaVendorToken()) shouldBe HttpStatusCode.Forbidden
                }

                // then: the row gains no tag
                tagsOf(temurinHead).shouldBeEmpty()
            }
        }

        should("refuse with 403 DISCO's removal of lts from a java row and keep the tag") {
            withCleanDatabase {
                // given: TEMURIN 25.0.4.1+1 is tagged lts
                insertTaggedVersion(temurinHead, "lts")

                // when: DISCO removes lts through DELETE /versions/tags
                withTestApplication {
                    removeLts(javaVendorToken()) shouldBe HttpStatusCode.Forbidden
                }

                // then: the row still holds lts
                tagsOf(temurinHead) shouldContainExactly listOf("lts")
            }
        }

        should("refuse with 403 the community role's assignment of lts to a java row and leave the row untagged") {
            withCleanDatabase {
                // given: TEMURIN 25.0.4.1+1 is visible and untagged
                insertVersions(temurinHead)

                // when: the community role assigns lts to it through POST /versions/tags
                withTestApplication {
                    assignLts(JwtTestSupport.communityToken()) shouldBe HttpStatusCode.Forbidden
                }

                // then: the row gains no tag
                tagsOf(temurinHead).shouldBeEmpty()
            }
        }

        should("refuse with 403 the community role's removal of lts from a java row and keep the tag") {
            withCleanDatabase {
                // given: TEMURIN 25.0.4.1+1 is tagged lts
                insertTaggedVersion(temurinHead, "lts")

                // when: the community role removes lts through DELETE /versions/tags
                withTestApplication {
                    removeLts(JwtTestSupport.communityToken()) shouldBe HttpStatusCode.Forbidden
                }

                // then: the row still holds lts
                tagsOf(temurinHead) shouldContainExactly listOf("lts")
            }
        }

        should("let the admin assign lts to a java row with 204") {
            withCleanDatabase {
                // given: TEMURIN 25.0.4.1+1 is visible and untagged
                insertVersions(temurinHead)

                // when: the admin assigns lts to it through POST /versions/tags
                withTestApplication {
                    assignLts(JwtTestSupport.adminToken()) shouldBe HttpStatusCode.NoContent
                }

                // then: the row holds lts
                tagsOf(temurinHead) shouldContainExactly listOf("lts")
            }
        }

        should("let the admin remove lts from a java row with 204") {
            withCleanDatabase {
                // given: TEMURIN 25.0.4.1+1 is tagged lts
                insertTaggedVersion(temurinHead, "lts")

                // when: the admin removes lts through DELETE /versions/tags
                withTestApplication {
                    removeLts(JwtTestSupport.adminToken()) shouldBe HttpStatusCode.NoContent
                }

                // then: the row holds no tag
                tagsOf(temurinHead).shouldBeEmpty()
            }
        }
    })
