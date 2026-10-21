package io.sdkman.state.adapter.primary.rest

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.adapter.primary.rest.dto.JavaUniqueVersionDto
import io.sdkman.state.adapter.primary.rest.dto.toDomain
import io.sdkman.state.application.validation.EmptyFieldError
import io.sdkman.state.application.validation.JavaPoster
import io.sdkman.state.application.validation.JavaVersionRequestValidator
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.JAVA_CANDIDATE
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.service.JavaVersionService
import io.sdkman.state.domain.service.VersionService

fun Route.javaVersionWriteRoutes(
    versionService: VersionService,
    javaVersionService: JavaVersionService,
    javaVersionRequestValidator: JavaVersionRequestValidator,
) {
    javaVersionCreateRoute(javaVersionService, javaVersionRequestValidator)
    javaVersionDeleteRoute(versionService)
}

private fun Route.javaVersionCreateRoute(
    javaVersionService: JavaVersionService,
    javaVersionRequestValidator: JavaVersionRequestValidator,
) {
    post("/versions/java") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.javaPoster().fold(
            ifEmpty = { call.respondForbiddenFor(JAVA_CANDIDATE) },
            ifSome = { poster ->
                javaVersionRequestValidator.checkJavaVersionBody(call.receiveText(), poster).fold(
                    ifLeft = { rejection -> call.respondRejectedBody(rejection) },
                    ifRight = { version ->
                        call.storeJavaVersion(javaVersionService, version, poster).fold(
                            ifLeft = { error -> call.respondDomainError(error) },
                            ifRight = { call.respond(HttpStatusCode.NoContent) },
                        )
                    },
                )
            },
        )
    }
}

private suspend fun ApplicationCall.storeJavaVersion(
    javaVersionService: JavaVersionService,
    version: Version,
    poster: JavaPoster,
): Either<DomainError, Unit> =
    when (poster) {
        JavaPoster.ADMIN -> javaVersionService.overrideAsAdmin(version, authenticatedVendorId(), authenticatedEmail())
        JavaPoster.VENDOR -> javaVersionService.publish(version, authenticatedVendorId(), authenticatedEmail())
    }

private fun Route.javaVersionDeleteRoute(versionService: VersionService) {
    delete("/versions/java") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (call.javaPoster().isNone()) {
            call.respondForbiddenFor(JAVA_CANDIDATE)
            return@delete
        }
        either {
            val uniqueVersion = call.receiveRequestBody<JavaUniqueVersionDto>().map { it.toDomain() }.bind()
            ensure(uniqueVersion.version.isNotBlank()) { DomainError.ValidationFailed(EmptyFieldError("version").message) }
            versionService.delete(uniqueVersion, call.authenticatedVendorId(), call.authenticatedEmail()).bind()
        }.fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { call.respond(HttpStatusCode.NoContent) },
        )
    }
}
