package io.sdkman.state.application.validation

import arrow.core.nonEmptyListOf
import arrow.core.none
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.JAVA_CANDIDATE
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.allowList
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.unreadyAllowList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val TEMURIN_URL = "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5.tar.gz"

private val validJavaFields: Map<String, JsonElement> =
    mapOf(
        "version" to JsonPrimitive("25.0.5.0+3"),
        "distribution" to JsonPrimitive("TEMURIN"),
        "platform" to JsonPrimitive("LINUX_X64"),
        "url" to JsonPrimitive(TEMURIN_URL),
    )

private fun javaBody(vararg overrides: Pair<String, JsonElement>): String = JsonObject(validJavaFields + overrides).toString()

private fun tagList(vararg tags: String): JsonArray = JsonArray(tags.map { JsonPrimitive(it) })

class JavaVersionRequestValidatorSpec :
    ShouldSpec({

        val validator = JavaVersionRequestValidator(allowList("java", "gradle"))

        context("a vendor post") {

            should("return the java version as posted, without visibility or tags") {
                // given: a vendor body with a semverish version
                val json = javaBody()

                // when: validating it for a vendor
                val result = validator.validateRequest(json, JavaPoster.VENDOR)

                // then: the version is fixed to java, leaving visibility and tags to the server
                result shouldBeRight
                    Version(
                        candidate = JAVA_CANDIDATE,
                        version = "25.0.5.0+3",
                        platform = Platform.LINUX_X64,
                        url = TEMURIN_URL,
                        distribution = Distribution.TEMURIN.some(),
                    )
            }

            should("reject tags on the tags field") {
                // given: a vendor body carrying tags
                val json = javaBody("tags" to tagList("lts"))

                // when: validating it for a vendor
                val result = validator.validateRequest(json, JavaPoster.VENDOR)

                // then: exactly one error, on the tags field
                result shouldBeLeft nonEmptyListOf(ServerDecidedFieldError("tags"))
            }

            should("reject an empty tags list on the tags field") {
                // given: a vendor body carrying an empty tags list
                val json = javaBody("tags" to tagList())

                // when: validating it for a vendor
                val result = validator.validateRequest(json, JavaPoster.VENDOR)

                // then: the field is refused because it is present at all
                result shouldBeLeft nonEmptyListOf(ServerDecidedFieldError("tags"))
            }

            should("reject visible on the visible field") {
                // given: a vendor body carrying visible true
                val json = javaBody("visible" to JsonPrimitive(true))

                // when: validating it for a vendor
                val result = validator.validateRequest(json, JavaPoster.VENDOR)

                // then: exactly one error, on the visible field
                result shouldBeLeft nonEmptyListOf(ServerDecidedFieldError("visible"))
            }

            should("reject a version that fails the grammar") {
                // given: a three-component version
                val json = javaBody("version" to JsonPrimitive("25.0.5"))

                // when: validating it for a vendor
                val result = validator.validateRequest(json, JavaPoster.VENDOR)

                // then: exactly one format error, on the version field
                result shouldBeLeft nonEmptyListOf(InvalidVersionFormatError(version = "25.0.5"))
            }

            should("describe the open-ended format in the version failure message") {
                // given: a three-component early-access version
                val json = javaBody("version" to JsonPrimitive("29.0.0+ea.10"))

                // when: validating it for a vendor
                val result = validator.validateRequest(json, JavaPoster.VENDOR)

                // then: the version failure names the open-ended format
                val errors = result.shouldBeLeft()
                errors.head.message shouldContain "FEATURE.INTERIM.UPDATE.PATCH[.N...][-fx|-crac][+build-metadata]"
            }

            should("report a blank version as empty, not as a grammar failure") {
                // given: a blank version
                val json = javaBody("version" to JsonPrimitive(""))

                // when: validating it for a vendor
                val result = validator.validateRequest(json, JavaPoster.VENDOR)

                // then: only the empty-field error
                result shouldBeLeft nonEmptyListOf(EmptyFieldError("version"))
            }

            should("accumulate the grammar failure with the other field errors") {
                // given: a body failing the grammar, the url and the tags rule at once
                val json =
                    javaBody(
                        "version" to JsonPrimitive("25.0.5"),
                        "url" to JsonPrimitive("http://example.com/java.tar.gz"),
                        "tags" to tagList("lts"),
                    )

                // when: validating it for a vendor
                val result = validator.validateRequest(json, JavaPoster.VENDOR)

                // then: every failure is reported
                result.shouldBeLeft().map { it.field } shouldContainExactlyInAnyOrder listOf("version", "url", "tags")
            }
        }

        context("an admin post") {

            should("accept a legacy version") {
                // given: an admin body with a version the grammar rejects
                val json = javaBody("version" to JsonPrimitive("25.0.3"))

                // when: validating it for the admin
                val result = validator.validateRequest(json, JavaPoster.ADMIN)

                // then: the legacy version is kept as given
                result.map { it.version } shouldBeRight "25.0.3"
            }

            should("carry visible and tags as posted") {
                // given: an admin body hiding the row and listing a tag
                val json = javaBody("visible" to JsonPrimitive(false), "tags" to tagList("25"))

                // when: validating it for the admin
                val result = validator.validateRequest(json, JavaPoster.ADMIN)

                // then: both fields are kept as posted
                result.map { it.visible to it.tags } shouldBeRight (false.some() to listOf("25").some())
            }

            should("leave visible and tags absent when they are not posted") {
                // given: an admin body without visible or tags
                val json = javaBody()

                // when: validating it for the admin
                val result = validator.validateRequest(json, JavaPoster.ADMIN)

                // then: both fields stay absent
                result.map { it.visible to it.tags } shouldBeRight (none<Boolean>() to none<List<String>>())
            }

            should("apply the tag rules to the listed tags") {
                // given: an admin body listing a tag that starts with a hyphen
                val json = javaBody("tags" to tagList("-lts"))

                // when: validating it for the admin
                val result = validator.validateRequest(json, JavaPoster.ADMIN)

                // then: the tag fails on its indexed field
                result.shouldBeLeft().map { it.field } shouldBe listOf("tags[0]")
            }
        }

        context("either poster") {

            JavaPoster.entries.forEach { poster ->

                should("reject a candidate field from the $poster as fixed by the route") {
                    // given: a body naming the candidate the route already fixes
                    val json = javaBody("candidate" to JsonPrimitive("java"))

                    // when: validating it
                    val result = validator.validateRequest(json, poster)

                    // then: the candidate field is refused, pointing at the route
                    result shouldBeLeft nonEmptyListOf(FixedByRouteError("candidate", "POST /versions/java"))
                }

                should("report an unregistered java to the $poster as a request error") {
                    // given: a registry that has loaded without java
                    val validatorWithoutJava = JavaVersionRequestValidator(allowList("gradle"))

                    // when: validating a valid body
                    val result = validatorWithoutJava.validateRequest(javaBody(), poster)

                    // then: a request error naming java
                    result shouldBeLeft nonEmptyListOf(InvalidRequestError("candidate 'java' is not registered"))
                }
            }

            should("name the route in the fixed-candidate message") {
                // given: a body naming the candidate
                val json = javaBody("candidate" to JsonPrimitive("java"))

                // when: validating it
                val result = validator.validateRequest(json, JavaPoster.VENDOR)

                // then: the message points at the java route
                result.shouldBeLeft().head.message shouldContain "POST /versions/java"
            }

            should("report malformed JSON on the request field") {
                // given: a body that is not JSON
                val json = "{not json"

                // when: validating it
                val result = validator.validateRequest(json, JavaPoster.VENDOR)

                // then: a deserialisation error on the request field
                val errors = result.shouldBeLeft()
                errors.head.shouldBeInstanceOf<DeserializationError>().field shouldBe "request"
            }

            should("report every missing required field") {
                // given: an empty body
                val json = "{}"

                // when: validating it
                val result = validator.validateRequest(json, JavaPoster.ADMIN)

                // then: version, platform and url are each reported empty
                result.shouldBeLeft() shouldContainExactlyInAnyOrder
                    listOf(EmptyFieldError("version"), EmptyFieldError("platform"), EmptyFieldError("url"))
            }
        }

        context("the candidate registry") {

            should("count as loaded once it has answered") {
                // given: a registry that has loaded
                // when: asking whether it has loaded
                val loaded = validator.allowListLoaded()

                // then: it has
                loaded shouldBe true
            }

            should("not count as loaded before it ever answers") {
                // given: a registry that has never loaded
                val validatorWithoutRegistry = JavaVersionRequestValidator(unreadyAllowList())

                // when: asking whether it has loaded
                val loaded = validatorWithoutRegistry.allowListLoaded()

                // then: it has not
                loaded shouldBe false
            }
        }

        context("the generic routes' java error") {

            should("identify candidate and name java and the java route") {
                // given: the error the generic delete will answer for java
                val error = JavaRouteError("DELETE /versions/java")

                // when: reading its field and message
                val fieldAndMessage = error.field to error.message

                // then: both point the caller at the java route
                fieldAndMessage shouldBe ("candidate" to "candidate 'java' is not accepted here; use DELETE /versions/java")
            }
        }
    })
