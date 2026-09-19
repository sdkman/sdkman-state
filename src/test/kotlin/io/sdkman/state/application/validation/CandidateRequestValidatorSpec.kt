package io.sdkman.state.application.validation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight

// Covers every rejection rule of `CandidateRequestValidator`. The rules exist because a
// registry row is rendered by `sdk list` into a fixed-width terminal box and is addressed by
// its identifier in a public URL, so a malformed row is unrecoverable rather than merely wrong.
class CandidateRequestValidatorSpec :
    ShouldSpec({

        fun requestJson(
            candidate: String = "scala",
            name: String = "Scala",
            description: String = "Scala is a programming language for the JVM.",
            websiteUrl: String = "https://www.scala-lang.org/",
        ) = """
            {
                "candidate": "$candidate",
                "name": "$name",
                "description": "$description",
                "website_url": "$websiteUrl"
            }
            """.trimIndent()

        context("validateRequest") {

            should("accept a well-formed registration") {
                // given: a request satisfying every structural rule
                val json = requestJson()

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: returns the registration carrying every posted field
                result shouldBeRight
                    CandidateRegistration(
                        candidate = "scala",
                        name = "Scala",
                        description = "Scala is a programming language for the JVM.",
                        websiteUrl = "https://www.scala-lang.org/",
                    )
            }

            should("reject an identifier containing an uppercase letter") {
                // given: an identifier that breaks `^[a-z][a-z0-9]*$`
                val json = requestJson(candidate = "Scala")

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: returns the identifier-shape error, which mirrors the table CHECK
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe InvalidCandidateIdentifierError(candidate = "Scala")
            }

            should("reject an identifier of 21 characters") {
                // given: an identifier one character past the 20-character bound
                val json = requestJson(candidate = "a".repeat(21))

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: returns the length error reporting both bounds
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe FieldTooLongError("candidate", 20, 21)
            }

            should("reject a blank name") {
                // given: a name of whitespace only
                val json = requestJson(name = "   ")

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: returns the empty-field error on `name`
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe EmptyFieldError("name")
            }

            should("reject a name of 101 characters") {
                // given: a name one character past the 100-character bound
                val json = requestJson(name = "n".repeat(101))

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: returns the length error on `name`
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe FieldTooLongError("name", 100, 101)
            }

            should("reject a website url that is not https") {
                // given: a url on the plain http scheme
                val json = requestJson(websiteUrl = "http://www.scala-lang.org/")

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: returns the url error naming the JSON field the client posted
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe
                    InvalidUrlError(field = "website_url", url = "http://www.scala-lang.org/")
            }

            should("reject a website url of 501 characters") {
                // given: a well-formed https url one character past `UrlRules.MAX_LENGTH`
                val prefix = "https://example.com/"
                val json = requestJson(websiteUrl = prefix + "u".repeat(501 - prefix.length))

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: the length bound is reported before the pattern is consulted
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe FieldTooLongError("website_url", UrlRules.MAX_LENGTH, 501)
            }

            should("reject a description containing a trademark symbol") {
                // given: a description with a codepoint outside 0x20-0x7E
                val json = requestJson(description = "Java\\u2122 is a programming language.")

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: returns the printable-ASCII rejection
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe
                    InvalidDescriptionError(
                        reason = "must be a single paragraph of printable ASCII characters",
                    )
            }

            should("reject a description containing a line break") {
                // given: a description spanning two lines, which breaks the `sdk list` layout
                val json = requestJson(description = "Scala is a language.\\nIt runs on the JVM.")

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: the ASCII range check rejects the control character
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe
                    InvalidDescriptionError(
                        reason = "must be a single paragraph of printable ASCII characters",
                    )
            }

            should("reject a description containing consecutive spaces") {
                // given: a description with a double space, which survives no reflow
                val json = requestJson(description = "Scala is a  programming language.")

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: returns the consecutive-spaces rejection
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe
                    InvalidDescriptionError(reason = "must not contain consecutive spaces")
            }

            should("reject a description of 2001 characters") {
                // given: a description one character past the 2000-character bound
                val json = requestJson(description = "d".repeat(2001))

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: returns the length error on `description`
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe FieldTooLongError("description", 2000, 2001)
            }

            should("reject a body that is not valid JSON") {
                // given: a body the decoder cannot parse
                val json = "not json at all"

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: malformed JSON is one accumulated failure, never a deserialisation 500
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first().shouldBeInstanceOf<DeserializationError>().field shouldBe "request"
            }
        }
    })
