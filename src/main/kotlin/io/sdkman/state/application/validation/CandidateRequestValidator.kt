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

// Structural validation for a candidate registration, following the accumulated-error
// pattern of `VersionRequestValidator`: every field failure is returned together in one
// `NonEmptyList` and rendered as a single `400 ValidationErrorResponse`. Malformed JSON is
// one of those failures rather than a deserialisation `500`, which is why the request DTO
// models every field as an `Option` and the decode happens here.
//
// This validator is purely structural. It performs no registry lookup: the publish path
// still authorises a candidate against `candidates.txt`, and nothing reads the table to
// authorise a write in this part of the migration.
object CandidateRequestValidator {
    private const val MAX_CANDIDATE_LENGTH = 20
    private const val MAX_NAME_LENGTH = 100
    private const val MAX_DESCRIPTION_LENGTH = 2000

    // The identifier shape is mirrored by the `candidates` table CHECK constraint, so the
    // two cannot drift. The length bound is carried separately, as the constraint spells it.
    private val CANDIDATE_PATTERN = Regex("^[a-z][a-z0-9]*$")

    // A description is a single paragraph of printable ASCII: `sdk list` renders it into a
    // fixed-width terminal box, where a line break breaks the layout, a non-ASCII codepoint
    // is mojibake under a non-UTF-8 locale, and a run of spaces survives no reflow.
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
