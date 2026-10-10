package io.sdkman.state.acceptance

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import arrow.core.toOption
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
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
                // given: a retired gradle row the lts tag still points at — the window the java
                // supersession pass leaves open, where the two reads must not disagree
                val versionId = insertVersionWithId(version)
                insertTag("gradle", "lts", none(), Platform.UNIVERSAL, versionId)

                withTestApplication {
                    registerGradle().status shouldBe HttpStatusCode.Created

                    // when: the tag route resolves lts at the platform the default prefers
                    val resolved = resolveTagVersion("lts")

                    // then: the listing derives the very same version as its default
                    derivedGradleDefault() shouldBe resolved.some()
                }
            }
        }

        should("derive the same default as the stable tag route resolves") {
            val version =
                Version(
                    candidate = "gradle",
                    version = "8.14",
                    platform = Platform.UNIVERSAL,
                    url = "https://gradle-8.14.zip",
                    visible = false.some(),
                    distribution = none(),
                    tags = listOf("stable").some(),
                )

            withCleanDatabase {
                // given: a retired gradle row the stable tag still points at
                val versionId = insertVersionWithId(version)
                insertTag("gradle", "stable", none(), Platform.UNIVERSAL, versionId)

                withTestApplication {
                    registerGradle().status shouldBe HttpStatusCode.Created

                    // when: the tag route resolves stable at the platform the default prefers
                    val resolved = resolveTagVersion("stable")

                    // then: the listing derives the very same version, the retired 8.14, as its default
                    (derivedGradleDefault() to resolved) shouldBe ("8.14".some() to "8.14")
                }
            }
        }
    })

// gradle is registered over the admin route, so the comparison runs against a row the service itself wrote
private suspend fun ApplicationTestBuilder.registerGradle(): HttpResponse =
    client.post("/admin/candidates") {
        contentType(ContentType.Application.Json)
        setBody(
            """
            {"candidate":"gradle","name":"Gradle",
             "description":"Gradle build tool.",
             "website_url":"https://gradle.org/"}
            """.trimIndent(),
        )
        bearerAuth(JwtTestSupport.adminToken())
    }

private suspend fun ApplicationTestBuilder.resolveTagVersion(tag: String): String {
    val tagResponse = client.get("/versions/gradle/tags/$tag?platform=UNIVERSAL")
    tagResponse.status shouldBe HttpStatusCode.OK
    return Json
        .decodeFromString<JsonObject>(tagResponse.bodyAsText())
        .getValue("version")
        .jsonPrimitive.content
}

private suspend fun ApplicationTestBuilder.derivedGradleDefault(): Option<String> {
    val listing = Json.decodeFromString<JsonArray>(client.get("/candidates").bodyAsText())
    val gradleEntry = listing.map { it.jsonObject }.single()
    return gradleEntry["default"].toOption().map { it.jsonPrimitive.content }
}
