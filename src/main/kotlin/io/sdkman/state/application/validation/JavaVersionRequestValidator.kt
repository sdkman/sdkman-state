package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.None
import arrow.core.Option
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.nel
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import io.sdkman.state.adapter.primary.rest.dto.VersionRequest
import io.sdkman.state.domain.model.JAVA_CANDIDATE
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.service.CandidateAllowList

enum class JavaPoster { ADMIN, VENDOR }

class JavaVersionRequestValidator(
    private val candidateAllowList: CandidateAllowList,
) {
    fun allowListLoaded(): Boolean = candidateAllowList.registered().isSome()

    fun validateRequest(
        jsonString: String,
        poster: JavaPoster,
    ): Either<NonEmptyList<ValidationError>, Version> =
        either {
            val request = VersionFieldRules.decodeRequest(jsonString).bind()
            ensure(javaIsRegistered()) { InvalidRequestError("candidate '$JAVA_CANDIDATE' is not registered").nel() }
            validate(request, poster).bind()
        }

    private fun javaIsRegistered(): Boolean = JAVA_CANDIDATE in candidateAllowList.registered().getOrElse { emptySet() }

    private fun validate(
        request: VersionRequest,
        poster: JavaPoster,
    ): Either<NonEmptyList<ValidationError>, Version> =
        Either.zipOrAccumulate(
            requireAbsent(request.candidate, FixedByRouteError("candidate", JAVA_POST_ROUTE)),
            validateVersion(request.version, poster),
            VersionFieldRules.validatePlatform(request.platform),
            VersionFieldRules.validateUrl(request.url),
            VersionFieldRules.validateDistribution(request.distribution),
            VersionFieldRules.validateMd5sum(request.md5sum),
            VersionFieldRules.validateSha256sum(request.sha256sum),
            VersionFieldRules.validateSha512sum(request.sha512sum),
            validateVisible(request.visible, poster),
            validateTags(request.tags, poster),
        ) { _, version, platform, url, distribution, md5sum, sha256sum, sha512sum, visible, tags ->
            Version(
                candidate = JAVA_CANDIDATE,
                version = version,
                platform = platform,
                url = url,
                visible = visible,
                distribution = distribution,
                md5sum = md5sum,
                sha256sum = sha256sum,
                sha512sum = sha512sum,
                tags = tags,
            )
        }

    private fun validateVersion(
        version: Option<String>,
        poster: JavaPoster,
    ): Either<NonEmptyList<ValidationError>, String> =
        VersionFieldRules.validateVersion(version).flatMap { value ->
            when (poster) {
                JavaPoster.ADMIN -> value.right()
                JavaPoster.VENDOR -> SemverishValidator.validate(value).mapLeft { it.nel() }
            }
        }

    private fun validateVisible(
        visible: Option<Boolean>,
        poster: JavaPoster,
    ): Either<NonEmptyList<ValidationError>, Option<Boolean>> =
        when (poster) {
            JavaPoster.ADMIN -> visible.right()
            JavaPoster.VENDOR -> requireAbsent(visible, ServerDecidedFieldError("visible"))
        }

    private fun validateTags(
        tags: Option<List<String>>,
        poster: JavaPoster,
    ): Either<NonEmptyList<ValidationError>, Option<List<String>>> =
        when (poster) {
            JavaPoster.ADMIN -> VersionFieldRules.validateTags(tags)
            JavaPoster.VENDOR -> requireAbsent(tags, ServerDecidedFieldError("tags"))
        }

    private fun <A> requireAbsent(
        field: Option<A>,
        errorWhenPresent: ValidationError,
    ): Either<NonEmptyList<ValidationError>, Option<A>> =
        field.fold(
            { None.right() },
            { errorWhenPresent.nel().left() },
        )
}
