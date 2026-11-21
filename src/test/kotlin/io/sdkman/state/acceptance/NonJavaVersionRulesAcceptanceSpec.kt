package io.sdkman.state.acceptance

import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.none
import arrow.core.some
import arrow.core.toOption
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.TagAssignment
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertTaggedVersion
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.tagsOf
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Tags("acceptance")
class NonJavaVersionRulesAcceptanceSpec :
    ShouldSpec({
        val jpx =
            Version(
                candidate = "jpx",
                version = "1.2.0",
                platform = Platform.LINUX_X64,
                url = "https://jpx.example.com/jpx-1.2.0-linux-x64.tar.gz",
                visible = true.some(),
                distribution = none(),
            )

        suspend fun ApplicationTestBuilder.postVersion(
            body: String,
            token: String,
        ): HttpResponse =
            client.post("/versions") {
                contentType(ContentType.Application.Json)
                setBody(body)
                bearerAuth(token)
            }

        suspend fun ApplicationTestBuilder.assignTag(
            version: Version,
            tag: String,
        ): HttpResponse =
            client.post("/versions/tags") {
                contentType(ContentType.Application.Json)
                setBody(
                    TagAssignment(
                        candidate = version.candidate,
                        version = version.version,
                        distribution = version.distribution,
                        platform = version.platform,
                        tag = tag,
                    ).toJsonString(),
                )
                bearerAuth(JwtTestSupport.adminToken())
            }

        suspend fun ApplicationTestBuilder.jpxDefault(): Option<String> =
            Json
                .decodeFromString<JsonArray>(client.get("/candidates").bodyAsText())
                .map { it.jsonObject }
                .firstOrNone { it.getValue("candidate").jsonPrimitive.content == "jpx" }
                .flatMap { entry -> entry["default"].toOption() }
                .map { it.jsonPrimitive.content }

        suspend fun HttpResponse.failedFields(): List<String> =
            Json.decodeFromString<ValidationErrorResponse>(bodyAsText()).failures.map { it.field }

        mapOf(
            "admin" to { JwtTestSupport.adminToken() },
            "vendor" to { JwtTestSupport.vendorToken(candidates = listOf("jpx")) },
            "community" to { JwtTestSupport.communityToken() },
        ).forEach { (caller, token) ->
            should("answer 400 on the version field when the $caller role posts jpx 1.2.0 LTS") {
                withCleanDatabase {
                    withTestApplication {
                        // given: a registered candidate
                        registerCandidates("jpx")

                        // when: the caller posts a version outside the grammar
                        val response = postVersion(jpx.copy(version = "1.2.0 LTS").toJsonString(), token())

                        // then: the version field is refused
                        response.status shouldBe HttpStatusCode.BadRequest
                        response.failedFields() shouldBe listOf("version")
                    }
                }
            }
        }

        mapOf(
            "UNIVERSAL beside a LINUX_X64 row" to (Platform.LINUX_X64 to Platform.UNIVERSAL),
            "LINUX_X64 beside a UNIVERSAL row" to (Platform.UNIVERSAL to Platform.LINUX_X64),
        ).forEach { (case, platforms) ->
            val (storedPlatform, postedPlatform) = platforms
            should("answer 400 on the platform field when jpx 1.2.0 is posted for $case") {
                withCleanDatabase {
                    // given: jpx 1.2.0 holds one platform shape
                    insertVersions(jpx.copy(platform = storedPlatform))

                    withTestApplication {
                        registerCandidates("jpx")

                        // when: an admin posts the same version in the other shape
                        val response =
                            postVersion(jpx.copy(platform = postedPlatform).toJsonString(), JwtTestSupport.adminToken())

                        // then: the platform field is refused
                        response.status shouldBe HttpStatusCode.BadRequest
                        response.failedFields() shouldBe listOf("platform")
                    }
                }
            }
        }

        should("answer 400 on the tags field when jpx 1.2.0 is posted with lts") {
            withCleanDatabase {
                withTestApplication {
                    // given: a registered candidate
                    registerCandidates("jpx")

                    // when: an admin posts a version tagged lts
                    val response =
                        postVersion(jpx.copy(tags = listOf("lts").some()).toJsonString(), JwtTestSupport.adminToken())

                    // then: the tag is refused
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.failedFields() shouldBe listOf("tags[0]")
                }
            }
        }

        should("answer 400 on the tag field when POST /versions/tags assigns lts to jpx 1.2.0") {
            withCleanDatabase {
                // given: jpx 1.2.0 exists
                insertVersions(jpx)

                withTestApplication {
                    registerCandidates("jpx")

                    // when: an admin assigns lts through the tag route
                    val response = assignTag(jpx, "lts")

                    // then: the tag is refused
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.failedFields() shouldBe listOf("tag")
                }
            }
        }

        should("clear stable from jpx 1.1.0 on UNIVERSAL when jpx 1.2.0 is posted stable on LINUX_X64") {
            withCleanDatabase {
                // given: jpx 1.1.0 holds stable on UNIVERSAL
                val older = jpx.copy(version = "1.1.0", platform = Platform.UNIVERSAL)
                insertTaggedVersion(older, "stable")

                withTestApplication {
                    registerCandidates("jpx")

                    // when: jpx 1.2.0 is posted stable on LINUX_X64
                    val response =
                        postVersion(jpx.copy(tags = listOf("stable").some()).toJsonString(), JwtTestSupport.adminToken())

                    // then: the older UNIVERSAL row loses stable
                    response.status shouldBe HttpStatusCode.NoContent
                    tagsOf(older).shouldBeEmpty()
                    tagsOf(jpx) shouldContainExactlyInAnyOrder listOf("stable")
                }
            }
        }

        should("give jpx the default 1.2.0 once jpx 1.2.0 is posted stable on LINUX_X64 over a UNIVERSAL stable") {
            withCleanDatabase {
                // given: jpx 1.1.0 holds stable on UNIVERSAL
                insertTaggedVersion(jpx.copy(version = "1.1.0", platform = Platform.UNIVERSAL), "stable")

                withTestApplication {
                    registerCandidates("jpx")

                    // when: jpx 1.2.0 is posted stable on LINUX_X64
                    postVersion(jpx.copy(tags = listOf("stable").some()).toJsonString(), JwtTestSupport.adminToken())
                        .status shouldBe HttpStatusCode.NoContent

                    // then: the default follows the new stable
                    jpxDefault() shouldBe "1.2.0".some()
                }
            }
        }

        should("clear stable from jpx 1.1.0 on LINUX_X64 and MAC_ARM64 when jpx 1.2.0 is posted stable on UNIVERSAL") {
            withCleanDatabase {
                // given: jpx 1.1.0 holds stable on LINUX_X64 and MAC_ARM64
                val olderLinux = jpx.copy(version = "1.1.0")
                val olderMac = jpx.copy(version = "1.1.0", platform = Platform.MAC_ARM64)
                insertTaggedVersion(olderLinux, "stable")
                insertTaggedVersion(olderMac, "stable")

                withTestApplication {
                    registerCandidates("jpx")

                    // when: jpx 1.2.0 is posted stable on UNIVERSAL
                    val universal = jpx.copy(platform = Platform.UNIVERSAL, tags = listOf("stable").some())
                    val response = postVersion(universal.toJsonString(), JwtTestSupport.adminToken())

                    // then: no platform row of 1.1.0 keeps stable, and the default is 1.2.0
                    response.status shouldBe HttpStatusCode.NoContent
                    tagsOf(olderLinux).shouldBeEmpty()
                    tagsOf(olderMac).shouldBeEmpty()
                    jpxDefault() shouldBe "1.2.0".some()
                }
            }
        }

        should("keep latest when the tags field tags the latest jpx 1.2.0 stable") {
            withCleanDatabase {
                // given: jpx 1.2.0 holds latest on LINUX_X64
                insertTaggedVersion(jpx, "latest")

                withTestApplication {
                    registerCandidates("jpx")

                    // when: the same version is posted with stable only
                    val response =
                        postVersion(
                            jpx.copy(tags = listOf("stable").some()).toJsonString(),
                            JwtTestSupport.adminToken(),
                        )

                    // then: the row carries both tags
                    response.status shouldBe HttpStatusCode.NoContent
                    tagsOf(jpx) shouldContainExactlyInAnyOrder listOf("stable", "latest")
                }
            }
        }

        should("keep latest when POST /versions/tags tags the latest jpx 1.2.0 stable") {
            withCleanDatabase {
                // given: jpx 1.2.0 holds latest on LINUX_X64
                insertTaggedVersion(jpx, "latest")

                withTestApplication {
                    registerCandidates("jpx")

                    // when: the tag route assigns stable
                    val response = assignTag(jpx, "stable")

                    // then: the row carries both tags
                    response.status shouldBe HttpStatusCode.NoContent
                    tagsOf(jpx) shouldContainExactlyInAnyOrder listOf("stable", "latest")
                }
            }
        }

        should("leave jpx 1.2.0 holding stable only when jpx 1.3.0 is posted latest") {
            withCleanDatabase {
                // given: jpx 1.2.0 holds stable and latest on LINUX_X64
                insertTaggedVersion(jpx, "stable", "latest")

                withTestApplication {
                    registerCandidates("jpx")

                    // when: jpx 1.3.0 is posted latest on LINUX_X64
                    val newer = jpx.copy(version = "1.3.0", tags = listOf("latest").some())
                    val response = postVersion(newer.toJsonString(), JwtTestSupport.adminToken())

                    // then: latest moves and stable stays
                    response.status shouldBe HttpStatusCode.NoContent
                    tagsOf(newer) shouldContainExactlyInAnyOrder listOf("latest")
                    tagsOf(jpx) shouldContainExactlyInAnyOrder listOf("stable")
                }
            }
        }

        should("clear stable from another jpx version on UNIVERSAL when POST /versions/tags assigns stable on LINUX_X64") {
            withCleanDatabase {
                // given: jpx 1.1.0 holds stable on UNIVERSAL and jpx 1.2.0 exists on LINUX_X64
                val older = jpx.copy(version = "1.1.0", platform = Platform.UNIVERSAL)
                insertTaggedVersion(older, "stable")
                insertVersions(jpx)

                withTestApplication {
                    registerCandidates("jpx")

                    // when: the tag route assigns stable to jpx 1.2.0 on LINUX_X64
                    val response = assignTag(jpx, "stable")

                    // then: the UNIVERSAL stable is cleared
                    response.status shouldBe HttpStatusCode.NoContent
                    tagsOf(older).shouldBeEmpty()
                    tagsOf(jpx) shouldContainExactlyInAnyOrder listOf("stable")
                }
            }
        }
    })
