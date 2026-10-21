package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.None
import arrow.core.Option
import arrow.core.left
import arrow.core.nel
import arrow.core.right
import arrow.core.some
import arrow.core.toNonEmptyListOrNone
import io.sdkman.state.adapter.primary.rest.dto.VersionRequest
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import kotlinx.serialization.json.Json

internal object VersionFieldRules {
    private val HEX_PATTERN_32 = Regex("^[0-9a-fA-F]{32}$")
    private val HEX_PATTERN_64 = Regex("^[0-9a-fA-F]{64}$")
    private val HEX_PATTERN_128 = Regex("^[0-9a-fA-F]{128}$")

    private val json = Json { explicitNulls = false }

    fun decodeRequest(jsonString: String): Either<NonEmptyList<ValidationError>, VersionRequest> =
        Either
            .catch { json.decodeFromString<VersionRequest>(jsonString) }
            .mapLeft { DeserializationError("request", "Invalid JSON: ${it.message}").nel() }

    fun validateVersion(version: Option<String>): Either<NonEmptyList<ValidationError>, String> =
        version.fold(
            { EmptyFieldError("version").nel().left() },
            { value ->
                when {
                    value.isBlank() -> EmptyFieldError("version").nel().left()
                    else -> value.right()
                }
            },
        )

    fun validatePlatform(platform: Option<String>): Either<NonEmptyList<ValidationError>, Platform> =
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

    fun validateUrl(url: Option<String>): Either<NonEmptyList<ValidationError>, String> =
        url.fold(
            { EmptyFieldError("url").nel().left() },
            { value ->
                when {
                    value.isBlank() -> EmptyFieldError("url").nel().left()
                    !UrlRules.HTTPS_URL_PATTERN.matches(value) ->
                        InvalidUrlError(url = value).nel().left()

                    else -> value.right()
                }
            },
        )

    fun validateDistribution(distribution: Option<String>): Either<NonEmptyList<ValidationError>, Option<Distribution>> =
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

    fun validateMd5sum(md5sum: Option<String>): Either<NonEmptyList<ValidationError>, Option<String>> =
        validateHash("md5sum", md5sum, 32, HEX_PATTERN_32)

    fun validateSha256sum(sha256sum: Option<String>): Either<NonEmptyList<ValidationError>, Option<String>> =
        validateHash("sha256sum", sha256sum, 64, HEX_PATTERN_64)

    fun validateSha512sum(sha512sum: Option<String>): Either<NonEmptyList<ValidationError>, Option<String>> =
        validateHash("sha512sum", sha512sum, 128, HEX_PATTERN_128)

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

    fun validateTags(tagsOpt: Option<List<String>>): Either<NonEmptyList<ValidationError>, Option<List<String>>> =
        tagsOpt.fold(
            { None.right() },
            { tags ->
                val tagErrors =
                    tags.flatMapIndexed { index, tag ->
                        TagNameRules.validate("tags[$index]", tag)
                    }
                tagErrors.toNonEmptyListOrNone().fold(
                    { tags.some().right() },
                    { errors -> errors.left() },
                )
            },
        )
}
