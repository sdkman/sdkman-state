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
import arrow.core.toNonEmptyListOrNone
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
    ): Either<NonEmptyList<ValidationError>, Version> {
        val candidateResult = requireAbsent(request.candidate, FixedByRouteError("candidate", JAVA_POST_ROUTE))
        val versionResult = validateVersion(request.version, poster)
        val platformResult = VersionFieldRules.validatePlatform(request.platform)
        val urlResult = VersionFieldRules.validateUrl(request.url)
        val distributionResult = VersionFieldRules.validateDistribution(request.distribution)
        val md5sumResult = VersionFieldRules.validateMd5sum(request.md5sum)
        val sha256sumResult = VersionFieldRules.validateSha256sum(request.sha256sum)
        val sha512sumResult = VersionFieldRules.validateSha512sum(request.sha512sum)
        val visibleResult = validateVisible(request.visible, poster)
        val tagsResult = validateTags(request.tags, poster)

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
                visibleResult,
                tagsResult,
            ).flatMap { it.fold({ errs -> errs }, { emptyList() }) }

        return errors.toNonEmptyListOrNone().fold(
            {
                either {
                    Version(
                        candidate = JAVA_CANDIDATE,
                        version = versionResult.bind(),
                        platform = platformResult.bind(),
                        url = urlResult.bind(),
                        visible = visibleResult.bind(),
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
