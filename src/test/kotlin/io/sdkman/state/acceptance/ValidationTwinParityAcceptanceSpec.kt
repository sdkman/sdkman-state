package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

/**
 * Proves each `/validate/` twin answers a rejected body exactly as its write route does, over every
 * `400` body the write routes' own acceptance specs post.
 */
@Tags("acceptance")
class ValidationTwinParityAcceptanceSpec :
    ShouldSpec({
        fun versionBody(
            version: String = "27.0.2.0",
            tags: String = "[]",
        ): String =
            """
            {"candidate":"java","version":"$version","distribution":"TEMURIN","platform":"LINUX_X64","url":"https://cdn.example.com/java-$version.tar.gz","tags":$tags}
            """.trimIndent()

        fun registrationBody(
            candidate: String = "jbang",
            description: String = "Java scripting, without a build.",
            websiteUrl: String = "https://jbang.dev/",
        ): String =
            """
            {"candidate":"$candidate","name":"JBang","description":"$description","website_url":"$websiteUrl"}
            """.trimIndent()

        val rejectedVersionBodies =
            mapOf(
                "an invalid distribution" to
                    """{"candidate":"java","version":"17.0.1.0","platform":"LINUX_X64","url":"https://example.com/java.tar.gz","visible":true,"distribution":"INVALID_DISTRO"}""",
                "several invalid fields" to
                    """{"candidate":"invalid-candidate","version":"","platform":"INVALID_PLATFORM","url":"http://not-https.com/file.zip"}""",
                "missing required fields" to """{"visible":true}""",
                "a non-conforming semverish version" to versionBody(version = "25.0.2.fx"),
                "a three-component semverish core" to versionBody(version = "29.0.0+ea.10"),
                "a variant outside the fx or crac vocabulary" to versionBody(version = "25.0.2.0-graal"),
                "a tag with invalid characters" to versionBody(tags = """["inv@lid!"]"""),
                "a blank tag" to versionBody(tags = """["   "]"""),
                "a tag over 50 characters" to versionBody(tags = """["${"a".repeat(51)}"]"""),
                "a tag starting with a dot" to versionBody(tags = """[".hidden"]"""),
                "several invalid tags" to versionBody(tags = """["   ", ".hidden"]"""),
                "a mix of valid and invalid tags" to versionBody(tags = """["latest", "inv@lid!"]"""),
                "an unregistered candidate" to
                    """{"candidate":"jpx","version":"1.0.0","platform":"UNIVERSAL","url":"https://jpx.example.com/jpx-1.0.0.zip","visible":true}""",
            )

        val rejectedRegistrationBodies =
            mapOf(
                "a website_url that is not https" to registrationBody(websiteUrl = "http://jbang.dev/"),
                "an uppercase candidate identifier" to registrationBody(candidate = "JBang"),
                "an over-long description" to registrationBody(description = "d".repeat(2001)),
                "a description carrying a trademark symbol" to registrationBody(description = "Apache Tomcat® software."),
                "a description spanning two lines" to registrationBody(description = """Java scripting.\nWithout a build."""),
                "a description carrying consecutive spaces" to registrationBody(description = "Java scripting,  without a build."),
                "malformed JSON" to """{"candidate":"jbang",""",
            )

        suspend fun ApplicationTestBuilder.postAsAdmin(
            route: String,
            body: String,
        ): HttpResponse =
            client.post(route) {
                contentType(ContentType.Application.Json)
                setBody(body)
                bearerAuth(JwtTestSupport.adminToken())
            }

        suspend fun ApplicationTestBuilder.postAnonymously(
            route: String,
            body: String,
        ): HttpResponse =
            client.post(route) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

        fun shouldAnswerAsWriteRoute(
            writeRoute: String,
            twinRoute: String,
            rejectedBodies: Map<String, String>,
        ) = rejectedBodies.forEach { (case, body) ->
            should("answer $twinRoute with the same 400 as $writeRoute for $case") {
                withCleanDatabase {
                    withTestApplication {
                        // given: java is registered, so only the case's own defect rejects the body
                        registerCandidates("java")

                        // when: an admin writes the body, and anyone validates it
                        val written = postAsAdmin(writeRoute, body)
                        val validated = postAnonymously(twinRoute, body)

                        // then: both refuse it with the same answer
                        written.status shouldBe HttpStatusCode.BadRequest
                        validated.status shouldBe HttpStatusCode.BadRequest
                        validated.bodyAsText() shouldBe written.bodyAsText()
                    }
                }
            }
        }

        shouldAnswerAsWriteRoute("/versions", "/validate/versions", rejectedVersionBodies)
        shouldAnswerAsWriteRoute("/admin/candidates", "/validate/candidates", rejectedRegistrationBodies)
    })
