package io.sdkman.state.application.validation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight

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
                val json = requestJson()

                val result = CandidateRequestValidator.validateRequest(json)

                result shouldBeRight
                    CandidateRegistration(
                        candidate = "scala",
                        name = "Scala",
                        description = "Scala is a programming language for the JVM.",
                        websiteUrl = "https://www.scala-lang.org/",
                    )
            }

            should("reject an identifier containing an uppercase letter") {
                val json = requestJson(candidate = "Scala")

                val result = CandidateRequestValidator.validateRequest(json)

                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe InvalidCandidateIdentifierError(candidate = "Scala")
            }

            should("reject an identifier of 21 characters") {
                val json = requestJson(candidate = "a".repeat(21))

                val result = CandidateRequestValidator.validateRequest(json)

                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe FieldTooLongError("candidate", 20, 21)
            }

            should("reject a blank name") {
                val json = requestJson(name = "   ")

                val result = CandidateRequestValidator.validateRequest(json)

                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe EmptyFieldError("name")
            }

            should("reject a name of 101 characters") {
                val json = requestJson(name = "n".repeat(101))

                val result = CandidateRequestValidator.validateRequest(json)

                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe FieldTooLongError("name", 100, 101)
            }

            should("reject a website url that is not https") {
                val json = requestJson(websiteUrl = "http://www.scala-lang.org/")

                val result = CandidateRequestValidator.validateRequest(json)

                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe
                    InvalidUrlError(field = "website_url", url = "http://www.scala-lang.org/")
            }

            should("reject a website url of 501 characters") {
                val prefix = "https://example.com/"
                val json = requestJson(websiteUrl = prefix + "u".repeat(501 - prefix.length))

                val result = CandidateRequestValidator.validateRequest(json)

                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe FieldTooLongError("website_url", UrlRules.MAX_LENGTH, 501)
            }

            should("reject a description containing a trademark symbol") {
                val json = requestJson(description = "Java\\u2122 is a programming language.")

                val result = CandidateRequestValidator.validateRequest(json)

                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe
                    InvalidDescriptionError(
                        reason = "must be a single paragraph of printable ASCII characters",
                    )
            }

            should("reject a description containing a line break") {
                val json = requestJson(description = "Scala is a language.\\nIt runs on the JVM.")

                val result = CandidateRequestValidator.validateRequest(json)

                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe
                    InvalidDescriptionError(
                        reason = "must be a single paragraph of printable ASCII characters",
                    )
            }

            should("reject a description containing consecutive spaces") {
                val json = requestJson(description = "Scala is a  programming language.")

                val result = CandidateRequestValidator.validateRequest(json)

                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe
                    InvalidDescriptionError(reason = "must not contain consecutive spaces")
            }

            should("reject a description of 2001 characters") {
                val json = requestJson(description = "d".repeat(2001))

                val result = CandidateRequestValidator.validateRequest(json)

                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe FieldTooLongError("description", 2000, 2001)
            }

            should("reject a body that is not valid JSON") {
                val json = "not json at all"

                val result = CandidateRequestValidator.validateRequest(json)

                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first().shouldBeInstanceOf<DeserializationError>().field shouldBe "request"
            }
        }
    })
