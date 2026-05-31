package io.sdkman.state.adapter.primary.rest

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.toOption
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.date.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.UniqueVersionDto
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure
import io.sdkman.state.adapter.primary.rest.dto.toDomain
import io.sdkman.state.adapter.primary.rest.dto.toDto
import io.sdkman.state.application.validation.UniqueVersionValidator
import io.sdkman.state.application.validation.VersionRequestValidator
import io.sdkman.state.config.AppConfig
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.service.VersionService
import java.time.Instant

fun Route.versionReadRoutes(
    versionService: VersionService,
    appConfig: AppConfig,
) {
    install(CachingHeaders) {
        options { _, content ->
            content.contentType
                .toOption()
                .map { it.withoutParameters() }
                .filter { it == ContentType.Application.Json }
                .map {
                    CachingOptions(
                        cacheControl = CacheControl.MaxAge(maxAgeSeconds = appConfig.cacheMaxAge),
                        expires = GMTDate(Instant.now().plusSeconds(appConfig.cacheMaxAge.toLong()).toEpochMilli()),
                    )
                }.getOrNull()
        }
    }
    versionsByCandidateRoute(versionService)
    resolveVersionByTagRoute(versionService)
    uniqueVersionRoute(versionService)
}

private fun Route.versionsByCandidateRoute(versionService: VersionService) {
    get("/versions/{candidate}") {
        call.parameters.requiredPathParam("candidate").fold(
            ifLeft = { error -> call.respond(HttpStatusCode.BadRequest, error) },
            ifRight = { candidateId ->
                either {
                    val platform = call.request.platformQueryParam().bind()
                    val distribution = call.request.distributionQueryParam().bind()
                    val visible = call.request.visibleQueryParam().bind()
                    Triple(platform, distribution, visible)
                }.fold(
                    ifLeft = { error -> call.respond(HttpStatusCode.BadRequest, error) },
                    ifRight = { (platform, distribution, visible) ->
                        versionService.findByCandidate(candidateId, platform, distribution, visible).fold(
                            ifLeft = { domainError -> call.respondDomainError(domainError) },
                            ifRight = { versions -> call.respond(HttpStatusCode.OK, versions.map { it.toDto() }) },
                        )
                    },
                )
            },
        )
    }
}

private fun Route.uniqueVersionRoute(versionService: VersionService) {
    get("/versions/{candidate}/{version}") {
        either {
            val candidateId = call.parameters.requiredPathParam("candidate").bind()
            val versionId = call.parameters.requiredPathParam("version").bind()
            Pair(candidateId, versionId)
        }.fold(
            ifLeft = { error -> call.respond(HttpStatusCode.BadRequest, error) },
            ifRight = { (candidateId, versionId) ->
                either {
                    val platform = call.request.requiredPlatformQueryParam().bind()
                    val distribution = call.request.distributionQueryParam().bind()
                    Pair(platform, distribution)
                }.fold(
                    ifLeft = { error -> call.respond(HttpStatusCode.BadRequest, error) },
                    ifRight = { (platform, distribution) ->
                        versionService.findUnique(candidateId, versionId, platform, distribution).fold(
                            ifLeft = { domainError -> call.respondDomainError(domainError) },
                            ifRight = { maybeVersion ->
                                maybeVersion
                                    .map { call.respond(HttpStatusCode.OK, it.toDto()) }
                                    .getOrElse { call.respond(HttpStatusCode.NotFound) }
                            },
                        )
                    },
                )
            },
        )
    }
}

private fun Route.resolveVersionByTagRoute(versionService: VersionService) {
    get("/versions/{candidate}/tags/{tag}") {
        either {
            val candidateId = call.parameters.requiredPathParam("candidate").bind()
            val tag = call.parameters.requiredPathParam("tag").bind()
            Pair(candidateId, tag)
        }.fold(
            ifLeft = { error -> call.respond(HttpStatusCode.BadRequest, error) },
            ifRight = { (candidateId, tag) ->
                either {
                    val platform = call.request.requiredPlatformQueryParam().bind()
                    val distribution = call.request.distributionQueryParam().bind()
                    Pair(platform, distribution)
                }.fold(
                    ifLeft = { error -> call.respond(HttpStatusCode.BadRequest, error) },
                    ifRight = { (platform, distribution) ->
                        versionService.resolveByTag(candidateId, tag, platform, distribution).fold(
                            ifLeft = { error -> call.respondDomainError(error) },
                            ifRight = { maybeVersion ->
                                maybeVersion
                                    .map { call.respond(HttpStatusCode.OK, it.toDto()) }
                                    .getOrElse { call.respond(HttpStatusCode.NotFound) }
                            },
                        )
                    },
                )
            },
        )
    }
}

fun Route.versionWriteRoutes(
    versionService: VersionService,
    versionRequestValidator: VersionRequestValidator,
) {
    versionCreateRoute(versionService, versionRequestValidator)
    versionDeleteRoute(versionService)
}

private fun Route.versionCreateRoute(
    versionService: VersionService,
    versionRequestValidator: VersionRequestValidator,
) {
    post("/versions") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val vendorId = call.authenticatedVendorId()
        val email = call.authenticatedEmail()
        val role = call.authenticatedRole()
        val candidates = call.authenticatedCandidates()
        val requestBody = call.receiveText()
        versionRequestValidator.validateRequest(requestBody).fold(
            ifLeft = { errors ->
                val failures = errors.map { ValidationFailure(it.field, it.message) }
                call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse("Validation failed", failures))
            },
            ifRight = { validVersion ->
                // Admin tokens bypass candidate authorization — admin can operate on any candidate
                if (role == "vendor" && validVersion.candidate !in candidates) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("Forbidden", "Not authorized for candidate: ${validVersion.candidate}"),
                    )
                    return@post
                }
                versionService.createOrUpdate(validVersion, vendorId, email).fold(
                    ifLeft = { error -> call.respondDomainError(error) },
                    ifRight = { call.respond(HttpStatusCode.NoContent) },
                )
            },
        )
    }
}

private fun Route.versionDeleteRoute(versionService: VersionService) {
    delete("/versions") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val vendorId = call.authenticatedVendorId()
        val email = call.authenticatedEmail()
        val role = call.authenticatedRole()
        val candidates = call.authenticatedCandidates()
        either<DomainError, Unit> {
            val uniqueVersion =
                Either
                    .catch { call.receive<UniqueVersionDto>() }
                    .map { it.toDomain() }
                    .mapLeft {
                        DomainError.ValidationFailed(
                            "Invalid request: ${it.message.toOption().getOrElse { "Unknown error" }}",
                        )
                    }.bind()
            val validUniqueVersion =
                UniqueVersionValidator
                    .validate(uniqueVersion)
                    .mapLeft { DomainError.ValidationFailed(it.message) }
                    .bind()
            // Admin tokens bypass candidate authorization — admin can operate on any candidate
            if (role == "vendor" && validUniqueVersion.candidate !in candidates) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Forbidden", "Not authorized for candidate: ${validUniqueVersion.candidate}"),
                )
                return@delete
            }
            versionService.delete(validUniqueVersion, vendorId, email).bind()
        }.fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { call.respond(HttpStatusCode.NoContent) },
        )
    }
}
