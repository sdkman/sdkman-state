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
import io.sdkman.state.support.insertVersionWithId
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.selectTagNames
import io.sdkman.state.support.selectVersion
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
                url = "https://jpx-1.2.0",
                visible = true.some(),
                distribution = none(),
            )
        val jpxUniqueVersion = UniqueVersion("jpx", "1.2.0", none(), Platform.LINUX_X64)

        should("allow the community role to post, overwrite and delete a version") {
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("jpx")

                    val postResponse =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(jpxVersion.toJsonString())
                            bearerAuth(JwtTestSupport.communityToken())
                        }
                    postResponse.status shouldBe HttpStatusCode.NoContent

                    val overwriteResponse =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(jpxVersion.copy(url = "https://jpx-1.2.0-moved").toJsonString())
                            bearerAuth(JwtTestSupport.communityToken())
                        }
                    overwriteResponse.status shouldBe HttpStatusCode.NoContent
                    selectVersion("jpx", "1.2.0", none(), Platform.LINUX_X64).shouldBeSome().url shouldBe
                        "https://jpx-1.2.0-moved"

                    val deleteResponse =
                        client.delete("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(jpxUniqueVersion.toJsonString())
                            bearerAuth(JwtTestSupport.communityToken())
                        }
                    deleteResponse.status shouldBe HttpStatusCode.NoContent
                    selectVersion("jpx", "1.2.0", none(), Platform.LINUX_X64) shouldBe none()
                }
            }
        }

        should("allow the community role to assign and delete the latest tag") {
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("jpx")
                    val versionId = insertVersionWithId(jpxVersion)

                    val assignResponse =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(TagAssignment("jpx", "1.2.0", none(), Platform.LINUX_X64, "latest").toJsonString())
                            bearerAuth(JwtTestSupport.communityToken())
                        }
                    assignResponse.status shouldBe HttpStatusCode.NoContent
                    selectTagNames(versionId) shouldBe listOf("latest")

                    val deleteResponse =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(UniqueTag("jpx", "latest", none(), Platform.LINUX_X64).toJsonString())
                            bearerAuth(JwtTestSupport.communityToken())
                        }
                    deleteResponse.status shouldBe HttpStatusCode.NoContent
                    selectTagNames(versionId) shouldBe emptyList()
                }
            }
        }

        should("allow the community role to post a version of a candidate in a vendor's scope") {
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("gradle")
                    val vendorResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"gradle@example.com","candidates":["gradle"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    vendorResponse.status shouldBe HttpStatusCode.Created

                    val gradleVersion = jpxVersion.copy(candidate = "gradle", version = "9.1.0", url = "https://gradle-9.1.0")
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(gradleVersion.toJsonString())
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }
    })
