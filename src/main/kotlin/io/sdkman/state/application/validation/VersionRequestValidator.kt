package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.nel
import arrow.core.raise.either
import arrow.core.right
import arrow.core.toNonEmptyListOrNone
import io.sdkman.state.adapter.primary.rest.dto.VersionRequest
import io.sdkman.state.domain.model.JAVA_CANDIDATE
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.service.CandidateAllowList

class VersionRequestValidator(
    private val candidateAllowList: CandidateAllowList,
) {
    // A registry that has never loaded is not a validation failure: reporting it as one would
    // surface a transient database fault to a publisher as "candidate is not valid".
    fun allowListLoaded(): Boolean = candidateAllowList.registered().isSome()

    fun validateRequest(jsonString: String): Either<NonEmptyList<ValidationError>, Version> =
        either {
            val request = VersionFieldRules.decodeRequest(jsonString).bind()
            validate(request).bind()
        }

    fun validate(request: VersionRequest): Either<NonEmptyList<ValidationError>, Version> {
        val candidateResult = validateCandidate(request.candidate)
        val versionResult = VersionFieldRules.validateVersion(request.version)
        val platformResult = VersionFieldRules.validatePlatform(request.platform)
        val urlResult = VersionFieldRules.validateUrl(request.url)
        val distributionResult = VersionFieldRules.validateDistribution(request.distribution)
        val md5sumResult = VersionFieldRules.validateMd5sum(request.md5sum)
        val sha256sumResult = VersionFieldRules.validateSha256sum(request.sha256sum)
        val sha512sumResult = VersionFieldRules.validateSha512sum(request.sha512sum)
        val tagsResult = VersionFieldRules.validateTags(request.tags)

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
                val registeredCandidates = candidateAllowList.registered().getOrElse { emptySet() }
                when {
                    value.isBlank() -> EmptyFieldError("candidate").nel().left()
                    value == JAVA_CANDIDATE -> JavaRouteError(JAVA_POST_ROUTE).nel().left()
                    value !in registeredCandidates ->
                        InvalidCandidateError(
                            candidate = value,
                            allowedCandidates = registeredCandidates.sorted(),
                        ).nel()
                            .left()

                    else -> value.right()
                }
            },
        )
}
