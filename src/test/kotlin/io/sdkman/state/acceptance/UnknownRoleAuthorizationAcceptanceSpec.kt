package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.TagAssignment
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertTag
import io.sdkman.state.support.insertVersionWithId
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class UnknownRoleAuthorizationAcceptanceSpec :
    ShouldSpec({
        val jpxVersion =
            Version(
                candidate = "jpx",
                version = "1.2.0",
                platform = Platform.LINUX_X64,
                url = "https://jpx-1.2.0",
                visible = true.some(),
                distribution = none(),
            )
        val jpxUniqueVersion = UniqueVersion("jpx", "1.2.0", none(), Platform.LINUX_X64)
        val jpxLatestTag = UniqueTag("jpx", "latest", none(), Platform.LINUX_X64)

        should("return 403 when an unknown role POSTs a version") {
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("jpx")

                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(jpxVersion.toJsonString())
                            bearerAuth(JwtTestSupport.observerToken())
                        }

                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        should("return 403 when an unknown role DELETEs a version") {
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("jpx")
                    insertVersionWithId(jpxVersion)

                    val response =
                        client.delete("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(jpxUniqueVersion.toJsonString())
                            bearerAuth(JwtTestSupport.observerToken())
                        }

                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        should("return 403 when an unknown role POSTs a tag") {
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("jpx")
                    insertVersionWithId(jpxVersion)

                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(TagAssignment("jpx", "1.2.0", none(), Platform.LINUX_X64, "latest").toJsonString())
                            bearerAuth(JwtTestSupport.observerToken())
                        }

                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        should("return 403 when an unknown role DELETEs a tag") {
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("jpx")
                    val versionId = insertVersionWithId(jpxVersion)
                    insertTag("jpx", "latest", none(), Platform.LINUX_X64, versionId)

                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(jpxLatestTag.toJsonString())
                            bearerAuth(JwtTestSupport.observerToken())
                        }

                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    })
