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
import io.sdkman.state.support.allowList
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

/**
 * Proves `POST /validate/versions` refuses every invalid body with exactly the `400` that
 * `POST /versions` gives an admin, so a contributor's check can never pass or fail differently
 * from the publish it rehearses (specs/public-validation.md, Business Rules 2).
 *
 * The rows mirror every `400` case of `VersionRequestValidatorSpec`. `java` is the opted-in
 * semverish candidate in the test configuration.
 */
@Tags("acceptance")
class VersionValidationParityAcceptanceSpec :
    ShouldSpec({
        val validHash32 = "a".repeat(32)
        val validHash64 = "b".repeat(64)

        fun body(vararg fields: Pair<String, String>): String =
            fields.joinToString(prefix = "{", postfix = "}") { (name, json) -> "\"$name\": $json" }

        val candidate = "candidate" to "\"gradle\""
        val version = "version" to "\"8.10.2\""
        val platform = "platform" to "\"UNIVERSAL\""
        val url = "url" to "\"https://example.com/gradle-8.10.2-bin.zip\""

        val invalidBodies =
            listOf(
                "malformed JSON" to "{\"candidate\": \"gradle\", ",
                "missing candidate" to body(version, platform, url),
                "missing version" to body(candidate, platform, url),
                "missing platform" to body(candidate, version, url),
                "missing url" to body(candidate, version, platform),
                "empty candidate" to body("candidate" to "\"\"", version, platform, url),
                "empty version" to body(candidate, "version" to "\"\"", platform, url),
                "empty platform" to body(candidate, version, "platform" to "\"\"", url),
                "empty url" to body(candidate, version, platform, "url" to "\"\""),
                "unregistered candidate" to body("candidate" to "\"nonesuch\"", version, platform, url),
                "non-HTTPS url" to body(candidate, version, platform, "url" to "\"http://example.com/gradle.zip\""),
                "malformed url" to body(candidate, version, platform, "url" to "\"not-a-url\""),
                "invalid platform" to body(candidate, version, "platform" to "\"INVALID_PLATFORM\"", url),
                "invalid distribution" to body(candidate, version, platform, url, "distribution" to "\"INVALID\""),
                "empty distribution" to body(candidate, version, platform, url, "distribution" to "\"\""),
                "MD5 of wrong length" to body(candidate, version, platform, url, "md5sum" to "\"abc123\""),
                "MD5 with non-hex characters" to
                    body(candidate, version, platform, url, "md5sum" to "\"${"g".repeat(32)}\""),
                "SHA256 of wrong length" to body(candidate, version, platform, url, "sha256sum" to "\"abc123\""),
                "SHA512 of wrong length" to body(candidate, version, platform, url, "sha512sum" to "\"abc123\""),
                "empty hash" to body(candidate, version, platform, url, "md5sum" to "\"\""),
                "blank tag" to body(candidate, version, platform, url, "tags" to "[\" \"]"),
                "over-long tag" to body(candidate, version, platform, url, "tags" to "[\"${"a".repeat(51)}\"]"),
                "tag with invalid characters" to body(candidate, version, platform, url, "tags" to "[\"-latest!\"]"),
                "non-semverish java version" to
                    body(
                        "candidate" to "\"java\"",
                        "version" to "\"25.0.2.fx\"",
                        platform,
                        "url" to "\"https://example.com/java-25.0.2.fx.tar.gz\"",
                    ),
                "several missing fields at once" to body("visible" to "true"),
                "several invalid fields at once" to
                    body(
                        "candidate" to "\"nonesuch\"",
                        "version" to "\"\"",
                        "platform" to "\"INVALID_PLATFORM\"",
                        "url" to "\"http://example.com/file.zip\"",
                        "sha256sum" to "\"\"",
                    ),
                "several invalid hashes at once" to
                    body(
                        candidate,
                        version,
                        platform,
                        url,
                        "md5sum" to "\"tooshort\"",
                        "sha256sum" to "\"not-a-hex-value-!!!\"",
                        "sha512sum" to "\"ABC123\"",
                    ),
                "valid hashes beside an invalid tag" to
                    body(
                        candidate,
                        version,
                        platform,
                        url,
                        "md5sum" to "\"$validHash32\"",
                        "sha256sum" to "\"$validHash64\"",
                        "tags" to "[\"ok\", \"\"]",
                    ),
            )

        invalidBodies.forEach { (case, invalidBody) ->
            should("refuse a body with $case identically on the write route and its validation twin") {
                withCleanDatabase {
                    withTestApplication(allowList("gradle", "java")) {
                        // when: an admin publishes the body, and anyone validates it
                        val published =
                            client.post("/versions") {
                                contentType(ContentType.Application.Json)
                                setBody(invalidBody)
                                bearerAuth(JwtTestSupport.adminToken())
                            }
                        val validated =
                            client.post("/validate/versions") {
                                contentType(ContentType.Application.Json)
                                setBody(invalidBody)
                            }

                        // then: both refuse it with the same 400 body, byte for byte
                        published.status shouldBe HttpStatusCode.BadRequest
                        validated.status shouldBe HttpStatusCode.BadRequest
                        validated.bodyAsText() shouldBe published.bodyAsText()
                    }
                }
            }
        }
    })
