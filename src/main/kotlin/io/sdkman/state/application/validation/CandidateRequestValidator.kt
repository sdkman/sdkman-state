@file:UseSerializers(OptionSerializer::class)

package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.Option
import arrow.core.left
import arrow.core.nel
import arrow.core.none
import arrow.core.raise.either
import arrow.core.right
import arrow.core.serialization.OptionSerializer
import arrow.core.toNonEmptyListOrNone
import io.sdkman.state.domain.model.CandidateRegistration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json

// Wire shape of a `POST /admin/candidates` body. Every field is optional at the
// deserialization boundary so that a missing field becomes an accumulated
// `EmptyFieldError` rather than a deserialization failure that hides every other
// fault in the same request. `VersionRequest` is the precedent.
@Serializable
internal data class CandidateRequestBody(
    val candidate: Option<String> = none(),
    val name: Option<String> = none(),
    val description: Option<String> = none(),
    @SerialName("website_url")
    val websiteUrl: Option<String> = none(),
)

// Structural validation for candidate registration, following the accumulated-error
// pattern of `VersionRequestValidator`: every field is checked, and all failures come
// back together in one `NonEmptyList` so a caller fixes them in a single round trip.
// Malformed JSON is a validation failure too (`DeserializationError`), never an
// exception escaping to a 500 — see the candidate registry spec's API Contract.
object CandidateRequestValidator {
    private const val MAX_CANDIDATE_LENGTH = 20
    private const val MAX_NAME_LENGTH = 100
    private const val MAX_DESCRIPTION_LENGTH = 2000
    private const val MAX_WEBSITE_URL_LENGTH = 500

    private val CANDIDATE_PATTERN = Regex("^[a-z][a-z0-9]*$")

    // A single paragraph of printable ASCII: every character in 0x20-0x7E, which
    // excludes control characters and line breaks. Consecutive spaces are rejected
    // separately because the pattern cannot express "no run of two" and stay readable.
    private val PRINTABLE_ASCII_PATTERN = Regex("^[\\x20-\\x7E]+$")
    private const val CONSECUTIVE_SPACES = "  "

    private val json = Json { explicitNulls = false }

    fun validateRequest(jsonString: String): Either<NonEmptyList<ValidationError>, CandidateRegistration> =
        either {
            val request =
                Either
                    .catch { json.decodeFromString<CandidateRequestBody>(jsonString) }
                    .mapLeft {
                        DeserializationError("request", "Invalid JSON: ${it.message}")
                            .nel()
                    }.bind()

            validate(request).bind()
        }

    private fun validate(request: CandidateRequestBody): Either<NonEmptyList<ValidationError>, CandidateRegistration> {
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
                        FieldTooLongError("candidate", MAX_CANDIDATE_LENGTH).nel().left()

                    !CANDIDATE_PATTERN.matches(value) ->
                        InvalidCandidateIdError(candidate = value).nel().left()

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
                        FieldTooLongError("name", MAX_NAME_LENGTH).nel().left()

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
                        FieldTooLongError("description", MAX_DESCRIPTION_LENGTH).nel().left()

                    !PRINTABLE_ASCII_PATTERN.matches(value) ->
                        InvalidDescriptionError().nel().left()

                    value.contains(CONSECUTIVE_SPACES) ->
                        InvalidDescriptionError().nel().left()

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

                    value.length > MAX_WEBSITE_URL_LENGTH ->
                        FieldTooLongError("website_url", MAX_WEBSITE_URL_LENGTH).nel().left()

                    !UrlRules.HTTPS_URL_PATTERN.matches(value) ->
                        InvalidUrlError(field = "website_url", url = value).nel().left()

                    else -> value.right()
                }
            },
        )
}
