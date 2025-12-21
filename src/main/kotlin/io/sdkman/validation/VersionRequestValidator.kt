package io.sdkman.validation

import arrow.core.*
import arrow.core.raise.either
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.Version
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object VersionRequestValidator {

    private val ALLOWED_CANDIDATES =
        listOf("java", "maven", "gradle", "kotlin", "scala", "groovy", "sbt")
    private val HTTPS_URL_PATTERN = Regex("^https://[a-zA-Z0-9.-]+(/.*)?$")
    private val HEX_PATTERN_32 = Regex("^[0-9a-fA-F]{32}$")
    private val HEX_PATTERN_64 = Regex("^[0-9a-fA-F]{64}$")
    private val HEX_PATTERN_128 = Regex("^[0-9a-fA-F]{128}$")

    fun validateRequest(jsonString: String): Either<NonEmptyList<ValidationError>, Version> =
        either {
            val jsonObject =
                Either.catch { Json.parseToJsonElement(jsonString) as JsonObject }
                    .mapLeft {
                        DeserializationError("request", "Invalid JSON: ${it.message}")
                            .nel()
                    }
                    .bind()

            validateVersionFromJson(jsonObject).bind()
        }

    private fun validateVersionFromJson(jsonObject: JsonObject): Either<NonEmptyList<ValidationError>, Version> {
        // Helper to extract string values, treating JSON null as None
        fun JsonObject.getStringOrNone(key: String): Option<String> =
            this.getOrNone(key).flatMap { element ->
                when (element) {
                    is JsonPrimitive if element.isString -> element.content.toOption()
                    is JsonPrimitive -> None // null or other primitive type
                    else -> None
                }
            }

        // Helper to extract boolean values, treating JSON null as None
        fun JsonObject.getBooleanOrNone(key: String): Option<Boolean> =
            this.getOrNone(key).flatMap { element ->
                when (element) {
                    is JsonPrimitive -> {
                        if (element.content == "null") None
                        else element.content.toBooleanStrictOrNull().toOption()
                    }

                    else -> None
                }
            }

        // Extract all fields to Option immediately at JSON boundary (RULE-001)
        val candidateOpt: Option<String> = jsonObject.getStringOrNone("candidate")
        val versionOpt: Option<String> = jsonObject.getStringOrNone("version")
        val platformOpt: Option<String> = jsonObject.getStringOrNone("platform")
        val urlOpt: Option<String> = jsonObject.getStringOrNone("url")
        val visibleOpt: Option<Boolean> = jsonObject.getBooleanOrNone("visible")
        val distributionOpt: Option<String> = jsonObject.getStringOrNone("distribution")
        val md5sumOpt: Option<String> = jsonObject.getStringOrNone("md5sum")
        val sha256sumOpt: Option<String> = jsonObject.getStringOrNone("sha256sum")
        val sha512sumOpt: Option<String> = jsonObject.getStringOrNone("sha512sum")

        // Validate all fields
        val candidateResult = validateCandidate(candidateOpt)
        val versionResult = validateVersion(versionOpt)
        val platformResult = validatePlatform(platformOpt)
        val urlResult = validateUrl(urlOpt)
        val distributionResult = validateDistribution(distributionOpt)
        val md5sumResult = validateHash("md5sum", md5sumOpt, 32, HEX_PATTERN_32)
        val sha256sumResult = validateHash("sha256sum", sha256sumOpt, 64, HEX_PATTERN_64)
        val sha512sumResult = validateHash("sha512sum", sha512sumOpt, 128, HEX_PATTERN_128)

        val errors =
            listOfNotNull(
                candidateResult.swap().getOrNull(),
                versionResult.swap().getOrNull(),
                platformResult.swap().getOrNull(),
                urlResult.swap().getOrNull(),
                distributionResult.swap().getOrNull(),
                md5sumResult.swap().getOrNull(),
                sha256sumResult.swap().getOrNull(),
                sha512sumResult.swap().getOrNull()
            )
                .flatten()

        return errors.toNonEmptyListOrNone().fold(
            {
                either {
                    Version(
                        candidate = candidateResult.bind(),
                        version = versionResult.bind(),
                        platform = platformResult.bind(),
                        url = urlResult.bind(),
                        visible = visibleOpt,
                        distribution = distributionResult.bind(),
                        md5sum = md5sumResult.bind(),
                        sha256sum = sha256sumResult.bind(),
                        sha512sum = sha512sumResult.bind()
                    )
                }
            },
            { errorList -> errorList.left() }
        )
    }

    private fun validateCandidate(
        candidate: Option<String>
    ): Either<NonEmptyList<ValidationError>, String> =
        candidate.fold(
            { EmptyFieldError("candidate").nel().left() },
            { value ->
                when {
                    value.isBlank() -> EmptyFieldError("candidate").nel().left()
                    value !in ALLOWED_CANDIDATES ->
                        InvalidCandidateError(
                            candidate = value,
                            allowedCandidates = ALLOWED_CANDIDATES
                        )
                            .nel()
                            .left()

                    else -> value.right()
                }
            }
        )

    private fun validateVersion(
        version: Option<String>
    ): Either<NonEmptyList<ValidationError>, String> =
        version.fold(
            { EmptyFieldError("version").nel().left() },
            { value ->
                when {
                    value.isBlank() -> EmptyFieldError("version").nel().left()
                    else -> value.right()
                }
            }
        )

    private fun validatePlatform(
        platform: Option<String>
    ): Either<NonEmptyList<ValidationError>, Platform> =
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
            }
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
            }
        )

    private fun validateDistribution(
        distribution: Option<String>
    ): Either<NonEmptyList<ValidationError>, Option<Distribution>> =
        distribution.fold(
            { None.right() }, // Missing distribution is valid
            { value ->
                when {
                    value.isBlank() ->
                        InvalidOptionalFieldError(
                            "distribution",
                            "field cannot be empty"
                        )
                            .nel()
                            .left()

                    else ->
                        Either.catch { Distribution.valueOf(value) }
                            .mapLeft {
                                InvalidDistributionError(distribution = value).nel()
                            }
                            .map { it.some() }
                }
            }
        )

    private fun validateHash(
        field: String,
        hashOpt: Option<String>,
        expectedLength: Int,
        pattern: Regex
    ): Either<NonEmptyList<ValidationError>, Option<String>> =
        hashOpt.fold(
            { None.right() }, // Missing hash is valid
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
            }
        )
}
