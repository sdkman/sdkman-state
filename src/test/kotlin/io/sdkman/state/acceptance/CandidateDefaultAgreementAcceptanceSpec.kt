package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.some
import arrow.core.toOption
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertTag
import io.sdkman.state.support.insertVersionWithId
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Tags("acceptance")
class CandidateDefaultAgreementAcceptanceSpec :
    ShouldSpec({
        should("derive the same default as the lts tag route resolves") {
            val version =
                Version(
                    candidate = "gradle",
                    version = "8.14.3",
                    platform = Platform.UNIVERSAL,
                    url = "https://gradle-8.14.3.zip",
                    visible = false.some(),
                    distribution = none(),
                    tags = listOf("lts").some(),
                )

            withCleanDatabase {
                val versionId = insertVersionWithId(version)
                insertTag("gradle", "lts", none(), Platform.UNIVERSAL, versionId)

                withTestApplication {
                    client
                        .post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """
                                {"candidate":"gradle","name":"Gradle",
                                 "description":"Gradle build tool.",
                                 "website_url":"https://gradle.org/"}
                                """.trimIndent(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }.status shouldBe HttpStatusCode.Created

                    val tagResponse = client.get("/versions/gradle/tags/lts?platform=UNIVERSAL")
                    tagResponse.status shouldBe HttpStatusCode.OK
                    val resolved =
                        Json
                            .decodeFromString<JsonObject>(tagResponse.bodyAsText())
                            .getValue("version")
                            .jsonPrimitive.content

                    val listing = Json.decodeFromString<JsonArray>(client.get("/candidates").bodyAsText())
                    val gradleEntry = listing.map { it.jsonObject }.single()
                    val derived = gradleEntry["default"].toOption().map { it.jsonPrimitive.content }
                    derived shouldBe resolved.some()
                }
            }
        }
    })
