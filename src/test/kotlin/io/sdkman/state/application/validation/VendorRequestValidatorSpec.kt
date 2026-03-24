package io.sdkman.state.application.validation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight

class VendorRequestValidatorSpec :
    ShouldSpec({

        context("validate") {

            should("accept valid email and candidates") {
                val result = VendorRequestValidator.validate("vendor@example.com", listOf("java"), "admin@sdkman.io")
                result.shouldBeRight()
            }

            should("reject invalid email format") {
                val result = VendorRequestValidator.validate("not-an-email", listOf("java"), "admin@sdkman.io")
                result.shouldBeLeft()
                result.onLeft { it.shouldBeInstanceOf<DomainError.ValidationFailed>() }
            }

            should("reject email matching admin email") {
                val result = VendorRequestValidator.validate("admin@sdkman.io", listOf("java"), "admin@sdkman.io")
                result.shouldBeLeft()
                result.onLeft { it.shouldBeInstanceOf<DomainError.ValidationFailed>() }
            }

            should("reject empty candidates list") {
                val result = VendorRequestValidator.validate("vendor@example.com", emptyList(), "admin@sdkman.io")
                result.shouldBeLeft()
                result.onLeft { it.shouldBeInstanceOf<DomainError.ValidationFailed>() }
            }
        }
    })
