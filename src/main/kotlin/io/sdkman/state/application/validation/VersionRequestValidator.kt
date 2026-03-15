package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.None
import arrow.core.Option
import arrow.core.left
import arrow.core.nel
import arrow.core.raise.either
import arrow.core.right
import arrow.core.some
import arrow.core.toNonEmptyListOrNone
import io.sdkman.state.adapter.primary.rest.dto.VersionRequest
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import kotlinx.serialization.json.Json

object VersionRequestValidator {
    private val ALLOWED_CANDIDATES = CandidateLoader.allowedCandidates
    private val HTTPS_URL_PATTERN = Regex("^https://[a-zA-Z0-9.-]+(/.*)?$")
    private val HEX_PATTERN_32 = Regex("^[0-9a-fA-F]{32}$")
    private val HEX_PATTERN_64 = Regex("^[0-9a-fA-F]{64}$")
    private val HEX_PATTERN_128 = Regex("^[0-9a-fA-F]{128}$")
    private val TAG_NAME_PATTERN = Regex("^[a-zA-Z0-9]([a-zA-Z0-9._-]{0,48}[a-zA-Z0-9])?$")

    private val json = Json { explicitNulls = false }

    fun validateRequest(jsonString: String): Either<NonEmptyList<ValidationError>, Version> =
        either {
            val request =
                Either
                    .catch { json.decodeFromString<VersionRequest>(jsonString) }
                    .mapLeft {
                        DeserializationError("request", "Invalid JSON: ${it.message}")
                            .nel()
                    }.bind()

            validate(request).bind()
        }

    fun validate(request: VersionRequest): Either<NonEmptyList<ValidationError>, Version> {
        val candidateResult = validateCandidate(request.candidate)
        val versionResult = validateVersion(request.version)
        val platformResult = validatePlatform(request.platform)
        val urlResult = validateUrl(request.url)
        val distributionResult = validateDistribution(request.distribution)
        val md5sumResult = validateHash("md5sum", request.md5sum, 32, HEX_PATTERN_32)
        val sha256sumResult = validateHash("sha256sum", request.sha256sum, 64, HEX_PATTERN_64)
        val sha512sumResult = validateHash("sha512sum", request.sha512sum, 128, HEX_PATTERN_128)
        val tagsResult = validateTags(request.tags)

        val errors =
            listOf<Either<NonEmptyList<ValidationError>, *>>(
                candidateResult,
                versionResult,
                platformResult,
                urlResult,
                distributionResult,
                md5sumResult,
                sha256sumResult,
                sha512sumResult,
                tagsResult,
            ).flatMap { it.fold({ errs -> errs }, { emptyList() }) }

        return errors.toNonEmptyListOrNone().fold(
            {
                either {
                    Version(
                        candidate = candidateResult.bind(),
                        version = versionResult.bind(),
                        platform = platformResult.bind(),
                        url = urlResult.bind(),
                        visible = request.visible,
                        distribution = distributionResult.bind(),
                        md5sum = md5sumResult.bind(),
                        sha256sum = sha256sumResult.bind(),
                        sha512sum = sha512sumResult.bind(),
                        tags = tagsResult.bind(),
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
                    value !in ALLOWED_CANDIDATES ->
                        InvalidCandidateError(
                            candidate = value,
                            allowedCandidates = ALLOWED_CANDIDATES,
                        ).nel()
                            .left()

                    else -> value.right()
                }
            },
        )

    private fun validateVersion(version: Option<String>): Either<NonEmptyList<ValidationError>, String> =
        version.fold(
            { EmptyFieldError("version").nel().left() },
            { value ->
                when {
                    value.isBlank() -> EmptyFieldError("version").nel().left()
                    else -> value.right()
                }
            },
        )

    private fun validatePlatform(platform: Option<String>): Either<NonEmptyList<ValidationError>, Platform> =
        platform.fold(
            { EmptyFieldError("platform").nel().left() },
            { value ->
                when {
                    value.isBlank() -> EmptyFieldError("platform").nel().left()
                    else ->
                        Either.catch { Platform.valueOf(value) }.mapLeft {
                            InvalidPlatformError(platform = value).nel()
                        }
                }
            },
        )

    private fun validateUrl(url: Option<String>): Either<NonEmptyList<ValidationError>, String> =
        url.fold(
            { EmptyFieldError("url").nel().left() },
            { value ->
                when {
                    value.isBlank() -> EmptyFieldError("url").nel().left()
                    !HTTPS_URL_PATTERN.matches(value) ->
                        InvalidUrlError(url = value).nel().left()

                    else -> value.right()
                }
            },
        )

    private fun validateDistribution(distribution: Option<String>): Either<NonEmptyList<ValidationError>, Option<Distribution>> =
        distribution.fold(
            { None.right() },
            { value ->
                when {
                    value.isBlank() ->
                        InvalidOptionalFieldError(
                            "distribution",
                            "field cannot be empty",
                        ).nel()
                            .left()

                    else ->
                        Either
                            .catch { Distribution.valueOf(value) }
                            .mapLeft {
                                InvalidDistributionError(distribution = value).nel()
                            }.map { it.some() }
                }
            },
        )

    private fun validateHash(
        field: String,
        hashOpt: Option<String>,
        expectedLength: Int,
        pattern: Regex,
    ): Either<NonEmptyList<ValidationError>, Option<String>> =
        hashOpt.fold(
            { None.right() },
            { hash ->
                when {
                    hash.isBlank() ->
                        InvalidOptionalFieldError(field, "field cannot be empty")
                            .nel()
                            .left()

                    hash.length != expectedLength ->
                        InvalidHashFormatError(field, hash, expectedLength).nel().left()

                    !pattern.matches(hash) ->
                        InvalidHashFormatError(field, hash, expectedLength).nel().left()

                    else -> hash.some().right()
                }
            },
        )

    private fun validateTags(tagsOpt: Option<List<String>>): Either<NonEmptyList<ValidationError>, Option<List<String>>> =
        tagsOpt.fold(
            { None.right() },
            { tags ->
                val tagErrors =
                    tags.flatMapIndexed { index, tag ->
                        validateTag(index, tag)
                    }
                tagErrors.toNonEmptyListOrNone().fold(
                    { tags.some().right() },
                    { errors -> errors.left() },
                )
            },
        )

    private fun validateTag(
        index: Int,
        tag: String,
    ): List<ValidationError> =
        when {
            tag.isBlank() ->
                listOf(InvalidTagError("tags[$index]", "Tag must not be blank"))

            tag.length > 50 ->
                listOf(InvalidTagError("tags[$index]", "Tag must not exceed 50 characters"))

            !TAG_NAME_PATTERN.matches(tag) ->
                listOf(
                    InvalidTagError(
                        "tags[$index]",
                        "Tag must contain only alphanumeric characters, dots, hyphens, and underscores, " +
                            "and must start and end with an alphanumeric character",
                    ),
                )

            else -> emptyList()
        }
}
