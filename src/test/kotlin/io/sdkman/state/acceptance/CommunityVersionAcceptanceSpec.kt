package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.TagAssignment
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.selectAuditRecordsByEmail
import io.sdkman.state.support.selectVersion
import io.sdkman.state.support.shouldBeNone
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class CommunityVersionAcceptanceSpec :
    ShouldSpec({
        val jpxVersion =
            Version(
                candidate = "jpx",
                version = "1.2.0",
                platform = Platform.LINUX_X64,
                url = "https://example.com/jpx-1.2.0-linux-x64.tar.gz",
                visible = true.some(),
                distribution = none(),
            )
        val jpxUniqueVersion = UniqueVersion("jpx", "1.2.0", none(), Platform.LINUX_X64)
        val latestTagAssignment =
            TagAssignment(
                candidate = "jpx",
                version = "1.2.0",
                distribution = none(),
                platform = Platform.LINUX_X64,
                tag = "latest",
            )
        val latestUniqueTag = UniqueTag(candidate = "jpx", tag = "latest", platform = Platform.LINUX_X64)

        suspend fun ApplicationTestBuilder.postAsCommunity(version: Version): HttpStatusCode =
            client
                .post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(version.toJsonString())
                    bearerAuth(JwtTestSupport.communityToken())
                }.status

        suspend fun ApplicationTestBuilder.assignLatestAsCommunity(): HttpStatusCode =
            client
                .post("/versions/tags") {
                    contentType(ContentType.Application.Json)
                    setBody(latestTagAssignment.toJsonString())
                    bearerAuth(JwtTestSupport.communityToken())
                }.status

        should("let the community role post a version with 204 No Content") {
            withCleanDatabase {
                withTestApplication {
                    // given: a registered candidate
                    registerCandidates("jpx")

                    // when: the community role posts a version
                    val status = postAsCommunity(jpxVersion)

                    // then: the version is accepted
                    status shouldBe HttpStatusCode.NoContent
                }
                selectVersion("jpx", "1.2.0", none(), Platform.LINUX_X64).shouldBeSome()
            }
        }

        should("let the community role overwrite a version with 204 No Content") {
            val overwrittenUrl = "https://mirror.example.com/jpx-1.2.0-linux-x64.tar.gz"

            withCleanDatabase {
                withTestApplication {
                    // given: an existing version posted by the community role
                    registerCandidates("jpx")
                    postAsCommunity(jpxVersion)

                    // when: the community role posts it again with a different URL
                    val status = postAsCommunity(jpxVersion.copy(url = overwrittenUrl))

                    // then: the overwrite is accepted
                    status shouldBe HttpStatusCode.NoContent
                }
                selectVersion("jpx", "1.2.0", none(), Platform.LINUX_X64).shouldBeSome().url shouldBe overwrittenUrl
            }
        }

        should("let the community role delete a version with 204 No Content") {
            withCleanDatabase {
                withTestApplication {
                    // given: an existing version
                    registerCandidates("jpx")
                    postAsCommunity(jpxVersion)

                    // when: the community role deletes it
                    val response =
                        client.delete("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(jpxUniqueVersion.toJsonString())
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    // then: the deletion is accepted
                    response.status shouldBe HttpStatusCode.NoContent
                }
                selectVersion("jpx", "1.2.0", none(), Platform.LINUX_X64).shouldBeNone()
            }
        }

        should("let the community role assign the tag latest with 204 No Content") {
            withCleanDatabase {
                withTestApplication {
                    // given: an existing version
                    registerCandidates("jpx")
                    postAsCommunity(jpxVersion)

                    // when: the community role assigns the tag "latest"
                    val status = assignLatestAsCommunity()

                    // then: the assignment is accepted
                    status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("let the community role delete the tag latest with 204 No Content") {
            withCleanDatabase {
                withTestApplication {
                    // given: an existing version tagged "latest"
                    registerCandidates("jpx")
                    postAsCommunity(jpxVersion)
                    assignLatestAsCommunity()

                    // when: the community role deletes the tag
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(latestUniqueTag.toJsonString())
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    // then: the deletion is accepted
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("let the community role post a version of a candidate in a vendor's scope with 204 No Content") {
            val gradleVersion =
                jpxVersion.copy(
                    candidate = "gradle",
                    version = "8.10.0",
                    platform = Platform.UNIVERSAL,
                    url = "https://example.com/gradle-8.10.0.zip",
                )

            withCleanDatabase {
                withTestApplication {
                    // given: a live vendor whose scope includes "gradle"
                    registerCandidates("gradle")
                    client
                        .post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"gradle-vendor@example.com","candidates":["gradle"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }.status shouldBe HttpStatusCode.Created

                    // when: the community role posts a version of "gradle"
                    val status = postAsCommunity(gradleVersion)

                    // then: the version is accepted
                    status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("attribute community version and tag writes to the community account in the audit trail") {
            withCleanDatabase {
                withTestApplication {
                    // given: a registered candidate
                    registerCandidates("jpx")

                    // when: the community role posts a version and tags it
                    postAsCommunity(jpxVersion)
                    assignLatestAsCommunity()
                }

                // then: every audit row carries the community email and sentinel vendor id
                val auditRecords = selectAuditRecordsByEmail(JwtTestSupport.COMMUNITY_EMAIL)
                auditRecords.map { it.operation } shouldContainExactlyInAnyOrder listOf(AuditOperation.CREATE, AuditOperation.TAG)
                auditRecords.map { it.vendorId }.toSet() shouldBe setOf(JwtTestSupport.COMMUNITY_VENDOR_ID)
            }
        }
    })
