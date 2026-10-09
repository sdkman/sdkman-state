package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class CandidateValidationParityAcceptanceSpec :
    ShouldSpec({
        fun body(vararg fields: Pair<String, String>): String =
            fields.joinToString(prefix = "{", postfix = "}") { (name, json) -> "\"$name\": $json" }

        val candidate = "candidate" to "\"scala\""
        val name = "name" to "\"Scala\""
        val description = "description" to "\"Scala is a programming language for the JVM.\""
        val websiteUrl = "website_url" to "\"https://www.scala-lang.org/\""
        val urlPrefix = "https://example.com/"

        val invalidBodies =
            listOf(
                "malformed JSON" to "not json at all",
                "missing candidate" to body(name, description, websiteUrl),
                "missing name" to body(candidate, description, websiteUrl),
                "missing description" to body(candidate, name, websiteUrl),
                "missing website_url" to body(candidate, name, description),
                "blank candidate" to body("candidate" to "\"   \"", name, description, websiteUrl),
                "blank name" to body(candidate, "name" to "\"   \"", description, websiteUrl),
                "blank description" to body(candidate, name, "description" to "\"   \"", websiteUrl),
                "blank website_url" to body(candidate, name, description, "website_url" to "\"   \""),
                "over-long candidate" to body("candidate" to "\"${"a".repeat(21)}\"", name, description, websiteUrl),
                "over-long name" to body(candidate, "name" to "\"${"n".repeat(101)}\"", description, websiteUrl),
                "over-long description" to
                    body(candidate, name, "description" to "\"${"d".repeat(2001)}\"", websiteUrl),
                "over-long website_url" to
                    body(
                        candidate,
                        name,
                        description,
                        "website_url" to "\"$urlPrefix${"u".repeat(501 - urlPrefix.length)}\"",
                    ),
                "uppercase identifier" to body("candidate" to "\"Scala\"", name, description, websiteUrl),
                "non-ASCII description" to
                    body(candidate, name, "description" to "\"Java\\u2122 is a programming language.\"", websiteUrl),
                "description with a line break" to
                    body(candidate, name, "description" to "\"Scala is a language.\\nIt runs on the JVM.\"", websiteUrl),
                "description with consecutive spaces" to
                    body(candidate, name, "description" to "\"Scala is a  programming language.\"", websiteUrl),
                "non-HTTPS website_url" to
                    body(candidate, name, description, "website_url" to "\"http://www.scala-lang.org/\""),
                "several missing fields at once" to "{}",
                "several invalid fields at once" to
                    body(
                        "candidate" to "\"Scala\"",
                        "name" to "\"   \"",
                        "description" to "\"Scala is a  programming language.\"",
                        "website_url" to "\"http://www.scala-lang.org/\"",
                    ),
            )

        invalidBodies.forEach { (case, invalidBody) ->
            should("refuse a body with $case identically on the write route and its validation twin") {
                withCleanDatabase {
                    withTestApplication {
                        // when: an admin registers the body, and anyone validates it
                        val registered =
                            client.post("/admin/candidates") {
                                contentType(ContentType.Application.Json)
                                setBody(invalidBody)
                                bearerAuth(JwtTestSupport.adminToken())
                            }
                        val validated =
                            client.post("/validate/candidates") {
                                contentType(ContentType.Application.Json)
                                setBody(invalidBody)
                            }

                        // then: both refuse it with the same 400 body, byte for byte
                        registered.status shouldBe HttpStatusCode.BadRequest
                        validated.status shouldBe HttpStatusCode.BadRequest
                        validated.bodyAsText() shouldBe registered.bodyAsText()
                    }
                }
            }
        }
    })
