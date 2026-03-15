package io.sdkman.state.application.validation

import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight

class UniqueTagValidatorSpec :
    ShouldSpec({

        context("validate") {
            should("accept a valid unique tag with distribution") {
                // given: a fully specified unique tag
                val uniqueTag =
                    UniqueTag(
                        candidate = "java",
                        tag = "latest",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )

                // when: validating
                val result = UniqueTagValidator.validate(uniqueTag)

                // then: returns Right with the same tag
                result shouldBeRight uniqueTag
            }

            should("accept a valid unique tag without distribution") {
                // given: a unique tag for a non-distribution candidate
                val uniqueTag =
                    UniqueTag(
                        candidate = "gradle",
                        tag = "latest",
                        distribution = arrow.core.None,
                        platform = Platform.UNIVERSAL,
                    )

                // when: validating
                val result = UniqueTagValidator.validate(uniqueTag)

                // then: returns Right
                result shouldBeRight uniqueTag
            }

            should("reject when candidate is blank") {
                // given: a tag with blank candidate
                val uniqueTag =
                    UniqueTag(
                        candidate = "",
                        tag = "latest",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )

                // when: validating
                val result = UniqueTagValidator.validate(uniqueTag)

                // then: returns Left with validation error
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe EmptyFieldError("candidate")
            }

            should("reject when tag is blank") {
                // given: a tag with blank tag name
                val uniqueTag =
                    UniqueTag(
                        candidate = "java",
                        tag = "",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )

                // when: validating
                val result = UniqueTagValidator.validate(uniqueTag)

                // then: returns Left with validation error
                val errors = result.shouldBeLeft()
                errors.size shouldBe 1
                errors.first() shouldBe EmptyFieldError("tag")
            }

            should("accumulate errors when both candidate and tag are blank") {
                // given: a tag with both fields blank
                val uniqueTag =
                    UniqueTag(
                        candidate = "",
                        tag = "",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )

                // when: validating
                val result = UniqueTagValidator.validate(uniqueTag)

                // then: returns Left with both errors accumulated
                val errors = result.shouldBeLeft()
                errors.size shouldBe 2
                errors.toList() shouldBe
                    listOf(
                        EmptyFieldError("candidate"),
                        EmptyFieldError("tag"),
                    )
            }
        }
    })
