package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.nel
import arrow.core.raise.either
import arrow.core.right
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

    fun validate(request: VersionRequest): Either<NonEmptyList<ValidationError>, Version> =
        Either.zipOrAccumulate(
            validateCandidate(request.candidate),
            VersionFieldRules.validateVersion(request.version),
            VersionFieldRules.validatePlatform(request.platform),
            VersionFieldRules.validateUrl(request.url),
            VersionFieldRules.validateDistribution(request.distribution),
            VersionFieldRules.validateMd5sum(request.md5sum),
            VersionFieldRules.validateSha256sum(request.sha256sum),
            VersionFieldRules.validateSha512sum(request.sha512sum),
            VersionFieldRules.validateTags(request.tags),
        ) { candidate, version, platform, url, distribution, md5sum, sha256sum, sha512sum, tags ->
            Version(
                candidate = candidate,
                version = version,
                platform = platform,
                url = url,
                visible = request.visible,
                distribution = distribution,
                md5sum = md5sum,
                sha256sum = sha256sum,
                sha512sum = sha512sum,
                tags = tags,
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
