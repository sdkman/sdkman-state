package io.sdkman.state.application.validation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight

class CandidateRequestValidatorSpec :
    ShouldSpec({

        // A body that passes every rule. Each unhappy case below starts from this
        // shape and breaks exactly one field, so a failure names the rule it proves.
        fun body(
            candidate: String = "jbang",
            name: String = "JBang",
            description: String = "Create, edit and run self-contained source-only Java programs.",
            websiteUrl: String = "https://jbang.dev/",
        ) = """
            {
                "candidate": "$candidate",
                "name": "$name",
                "description": "$description",
                "website_url": "$websiteUrl"
            }
            """.trimIndent()

        context("Happy path tests") {

            should("validate a request carrying all four required fields") {
                // given: a body with every field valid
                val json = body()

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: validation succeeds and every field is carried through
                result.shouldBeRight()
                result.onRight { registration ->
                    registration.candidate shouldBe "jbang"
                    registration.name shouldBe "JBang"
                    registration.description shouldBe
                        "Create, edit and run self-contained source-only Java programs."
                    registration.websiteUrl shouldBe "https://jbang.dev/"
                }
            }
        }

        context("Unhappy path tests - website_url") {

            should("fail when the website url is not https") {
                // given: an http url, which business rule 9 refuses
                val json = body(websiteUrl = "http://jbang.dev/")

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: validation fails on the url alone
                result.shouldBeLeft()
                result.onLeft { errors ->
                    errors.size shouldBe 1
                    errors.head.shouldBeInstanceOf<InvalidUrlError>()
                    errors.head.field shouldBe "website_url"
                }
            }

            should("fail when the website url exceeds 500 characters") {
                // given: an https url one character over the limit
                val json = body(websiteUrl = "https://jbang.dev/" + "a".repeat(483))

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: the length rule fires before the pattern rule
                result.shouldBeLeft()
                result.onLeft { errors ->
                    errors.size shouldBe 1
                    val error = errors.head.shouldBeInstanceOf<FieldTooLongError>()
                    error.field shouldBe "website_url"
                    error.maxLength shouldBe 500
                }
            }
        }

        context("Unhappy path tests - candidate identifier") {

            should("fail when the identifier holds uppercase characters") {
                // given: a display name used as the identifier
                val json = body(candidate = "JBang")

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: validation fails on the identifier shape
                result.shouldBeLeft()
                result.onLeft { errors ->
                    errors.size shouldBe 1
                    errors.head.shouldBeInstanceOf<InvalidCandidateIdError>()
                    errors.head.field shouldBe "candidate"
                }
            }

            should("fail when the identifier exceeds 20 characters") {
                // given: an otherwise well-shaped identifier one character over the limit
                val json = body(candidate = "a".repeat(21))

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: validation fails on the length, matching the column CHECK
                result.shouldBeLeft()
                result.onLeft { errors ->
                    errors.size shouldBe 1
                    val error = errors.head.shouldBeInstanceOf<FieldTooLongError>()
                    error.field shouldBe "candidate"
                    error.maxLength shouldBe 20
                }
            }
        }

        context("Unhappy path tests - field lengths") {

            should("fail when the name exceeds 100 characters") {
                // given: a name one character over the limit
                val json = body(name = "a".repeat(101))

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: validation fails on the name length
                result.shouldBeLeft()
                result.onLeft { errors ->
                    errors.size shouldBe 1
                    val error = errors.head.shouldBeInstanceOf<FieldTooLongError>()
                    error.field shouldBe "name"
                    error.maxLength shouldBe 100
                }
            }

            should("fail when the description exceeds 2000 characters") {
                // given: a description one character over the limit
                val json = body(description = "a".repeat(2001))

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: validation fails on the description length
                result.shouldBeLeft()
                result.onLeft { errors ->
                    errors.size shouldBe 1
                    val error = errors.head.shouldBeInstanceOf<FieldTooLongError>()
                    error.field shouldBe "description"
                    error.maxLength shouldBe 2000
                }
            }
        }

        context("Unhappy path tests - description shape") {

            should("fail when the description holds a non-ASCII character") {
                // given: a registered-trademark sign, outside 0x20-0x7E
                val json = body(description = "JBang® runs Java programs.")

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: the single-paragraph printable-ASCII rule refuses it
                result.shouldBeLeft()
                result.onLeft { errors ->
                    errors.size shouldBe 1
                    errors.head.shouldBeInstanceOf<InvalidDescriptionError>()
                    errors.head.field shouldBe "description"
                }
            }

            should("fail when the description holds a line break") {
                // given: a two-line description; the escape is a real newline after decoding
                val json = body(description = "JBang runs Java programs.\\nIt needs no build file.")

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: a line break makes it more than a single paragraph
                result.shouldBeLeft()
                result.onLeft { errors ->
                    errors.size shouldBe 1
                    errors.head.shouldBeInstanceOf<InvalidDescriptionError>()
                }
            }

            should("fail when the description holds consecutive spaces") {
                // given: a double space, which the ASCII pattern alone cannot catch
                val json = body(description = "JBang runs Java  programs.")

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: the separate consecutive-space rule refuses it
                result.shouldBeLeft()
                result.onLeft { errors ->
                    errors.size shouldBe 1
                    errors.head.shouldBeInstanceOf<InvalidDescriptionError>()
                }
            }
        }

        context("Unhappy path tests - multiple errors") {

            should("accumulate a blank name and a non-https url into one list") {
                // given: two independent faults in the same body
                val json = body(name = "", websiteUrl = "http://jbang.dev/")

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: both come back together, so the caller fixes them in one round trip
                result.shouldBeLeft()
                result.onLeft { errors ->
                    errors.size shouldBe 2
                    errors.any { it is EmptyFieldError && it.field == "name" } shouldBe true
                    errors.any { it is InvalidUrlError && it.field == "website_url" } shouldBe true
                }
            }
        }

        context("Unhappy path tests - malformed body") {

            should("fail with a deserialization error when the body is not valid JSON") {
                // given: a body that is not JSON at all
                val json = "this is not json"

                // when: validating the request
                val result = CandidateRequestValidator.validateRequest(json)

                // then: it is a validation failure, never an exception reaching a 500
                result.shouldBeLeft()
                result.onLeft { errors ->
                    errors.size shouldBe 1
                    errors.head.shouldBeInstanceOf<DeserializationError>()
                    errors.head.field shouldBe "request"
                }
            }
        }
    })
