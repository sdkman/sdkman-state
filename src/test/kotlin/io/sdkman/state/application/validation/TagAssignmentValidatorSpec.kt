package io.sdkman.state.application.validation

import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.TagAssignment
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight

class TagAssignmentValidatorSpec :
    ShouldSpec({

        context("validate") {
            should("accept a valid assignment with distribution") {
                // given: a fully specified tag assignment
                val assignment =
                    TagAssignment(
                        candidate = "java",
                        version = "27.0.2",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                        tag = "latest",
                    )

                // when: validating
                val result = TagAssignmentValidator.validate(assignment)

                // then: returns Right with the same assignment
                result shouldBeRight assignment
            }

            should("accept a valid assignment without distribution") {
                // given: an assignment for a non-distribution candidate
                val assignment =
                    TagAssignment(
                        candidate = "gradle",
                        version = "8.12",
                        distribution = arrow.core.none(),
                        platform = Platform.UNIVERSAL,
                        tag = "latest",
                    )

                // when: validating
                val result = TagAssignmentValidator.validate(assignment)

                // then: returns Right
                result shouldBeRight assignment
            }

            should("reject when candidate is blank") {
                // given: an assignment with blank candidate
                val assignment =
                    TagAssignment(
                        candidate = "",
                        version = "27.0.2",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                        tag = "latest",
                    )

                // when: validating
                val result = TagAssignmentValidator.validate(assignment)

                // then: returns Left with the candidate error
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe EmptyFieldError("candidate")
            }

            should("reject when version is blank") {
                // given: an assignment with blank version
                val assignment =
                    TagAssignment(
                        candidate = "java",
                        version = "",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                        tag = "latest",
                    )

                // when: validating
                val result = TagAssignmentValidator.validate(assignment)

                // then: returns Left with the version error
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe EmptyFieldError("version")
            }

            should("reject when tag format is invalid") {
                // given: an assignment with a tag that breaks the tag-name rule
                val assignment =
                    TagAssignment(
                        candidate = "java",
                        version = "27.0.2",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                        tag = "-bad-",
                    )

                // when: validating
                val result = TagAssignmentValidator.validate(assignment)

                // then: returns Left with an invalid-tag error on the `tag` field
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first().shouldBeInstanceOf<InvalidTagError>().field shouldBe "tag"
            }

            should("accumulate errors for blank candidate, blank version and invalid tag") {
                // given: an assignment failing every structural rule at once
                val assignment =
                    TagAssignment(
                        candidate = "",
                        version = "",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                        tag = "-bad-",
                    )

                // when: validating
                val result = TagAssignmentValidator.validate(assignment)

                // then: returns Left with all three errors accumulated
                val errors = result.shouldBeLeft()
                errors.size shouldBe 3
                errors.toList()[0] shouldBe EmptyFieldError("candidate")
                errors.toList()[1] shouldBe EmptyFieldError("version")
                errors.toList()[2].shouldBeInstanceOf<InvalidTagError>().field shouldBe "tag"
            }
        }
    })
