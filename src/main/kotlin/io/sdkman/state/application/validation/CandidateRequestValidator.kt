package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.Option
import arrow.core.left
import arrow.core.nel
import arrow.core.raise.either
import arrow.core.right
import arrow.core.toNonEmptyListOrNone
import io.sdkman.state.adapter.primary.rest.dto.CreateCandidateRequest
import io.sdkman.state.domain.model.CandidateRegistration
import kotlinx.serialization.json.Json

object CandidateRequestValidator {
    private const val MAX_CANDIDATE_LENGTH = 20
    private const val MAX_NAME_LENGTH = 100
    private const val MAX_DESCRIPTION_LENGTH = 2000

    private val CANDIDATE_PATTERN = Regex("^[a-z][a-z0-9]*$")

    private val PRINTABLE_ASCII_PATTERN = Regex("^[\\x20-\\x7E]*$")
    private const val CONSECUTIVE_SPACES = "  "

    private val json = Json { explicitNulls = false }

    fun validateRequest(jsonString: String): Either<NonEmptyList<ValidationError>, CandidateRegistration> =
        either {
            val request =
                Either
                    .catch { json.decodeFromString<CreateCandidateRequest>(jsonString) }
                    .mapLeft {
                        DeserializationError("request", "Invalid JSON: ${it.message}")
                            .nel()
                    }.bind()

            validate(request).bind()
        }

    private fun validate(request: CreateCandidateRequest): Either<NonEmptyList<ValidationError>, CandidateRegistration> {
        val candidateResult = validateCandidate(request.candidate)
        val nameResult = validateName(request.name)
        val descriptionResult = validateDescription(request.description)
        val websiteUrlResult = validateWebsiteUrl(request.websiteUrl)

        val errors =
            listOf<Either<NonEmptyList<ValidationError>, *>>(
                candidateResult,
                nameResult,
                descriptionResult,
                websiteUrlResult,
            ).flatMap { it.fold({ errs -> errs }, { emptyList() }) }

        return errors.toNonEmptyListOrNone().fold(
            {
                either {
                    CandidateRegistration(
                        candidate = candidateResult.bind(),
                        name = nameResult.bind(),
                        description = descriptionResult.bind(),
                        websiteUrl = websiteUrlResult.bind(),
                    )
                }
            },
            { errorList -> errorList.left() },
        )
    }

    private fun validateCandidate(candidate: Option<String>): Either<NonEmptyList<ValidationError>, String> =
        candidate.fold(
            { EmptyFieldError("candidate").nel().left() },
            { value ->
                when {
                    value.isBlank() -> EmptyFieldError("candidate").nel().left()

                    value.length > MAX_CANDIDATE_LENGTH ->
                        FieldTooLongError("candidate", MAX_CANDIDATE_LENGTH, value.length).nel().left()

                    !CANDIDATE_PATTERN.matches(value) ->
                        InvalidCandidateIdentifierError(candidate = value).nel().left()

                    else -> value.right()
                }
            },
        )

    private fun validateName(name: Option<String>): Either<NonEmptyList<ValidationError>, String> =
        name.fold(
            { EmptyFieldError("name").nel().left() },
            { value ->
                when {
                    value.isBlank() -> EmptyFieldError("name").nel().left()

                    value.length > MAX_NAME_LENGTH ->
                        FieldTooLongError("name", MAX_NAME_LENGTH, value.length).nel().left()

                    else -> value.right()
                }
            },
        )

    private fun validateDescription(description: Option<String>): Either<NonEmptyList<ValidationError>, String> =
        description.fold(
            { EmptyFieldError("description").nel().left() },
            { value ->
                when {
                    value.isBlank() -> EmptyFieldError("description").nel().left()

                    value.length > MAX_DESCRIPTION_LENGTH ->
                        FieldTooLongError("description", MAX_DESCRIPTION_LENGTH, value.length).nel().left()

                    !PRINTABLE_ASCII_PATTERN.matches(value) ->
                        InvalidDescriptionError(
                            reason = "must be a single paragraph of printable ASCII characters",
                        ).nel().left()

                    value.contains(CONSECUTIVE_SPACES) ->
                        InvalidDescriptionError(
                            reason = "must not contain consecutive spaces",
                        ).nel().left()

                    else -> value.right()
                }
            },
        )

    private fun validateWebsiteUrl(websiteUrl: Option<String>): Either<NonEmptyList<ValidationError>, String> =
        websiteUrl.fold(
            { EmptyFieldError("website_url").nel().left() },
            { value ->
                when {
                    value.isBlank() -> EmptyFieldError("website_url").nel().left()

                    value.length > UrlRules.MAX_LENGTH ->
                        FieldTooLongError("website_url", UrlRules.MAX_LENGTH, value.length).nel().left()

                    !UrlRules.HTTPS_URL_PATTERN.matches(value) ->
                        InvalidUrlError(field = "website_url", url = value).nel().left()

                    else -> value.right()
                }
            },
        )
}
