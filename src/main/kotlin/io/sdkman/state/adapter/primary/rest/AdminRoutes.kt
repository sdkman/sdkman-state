package io.sdkman.state.adapter.primary.rest

import arrow.core.getOrElse
import arrow.core.toOption
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.LoginRequest
import io.sdkman.state.adapter.primary.rest.dto.LoginResponse
import io.sdkman.state.adapter.primary.rest.dto.VendorRequest
import io.sdkman.state.adapter.primary.rest.dto.VendorResponse
import io.sdkman.state.adapter.primary.rest.dto.VendorWithPasswordResponse
import io.sdkman.state.application.validation.VendorRequestValidator
import io.sdkman.state.config.AppConfig
import io.sdkman.state.domain.service.AuthService
import java.util.UUID

fun Route.adminLoginRoute(authService: AuthService) {
    post("/admin/login") {
        val request = call.receive<LoginRequest>()
        authService.authenticate(request.email, request.password).fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { tokenResponse ->
                call.respond(HttpStatusCode.OK, LoginResponse(token = tokenResponse.token))
            },
        )
    }
}

fun Route.adminVendorListRoute(authService: AuthService) {
    get("/admin/vendors") {
        if (call.requireAdmin().isNone()) return@get
        authService.listVendors().fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { vendors ->
                call.respond(
                    HttpStatusCode.OK,
                    vendors.map { v ->
                        VendorResponse(
                            id = v.id.toString(),
                            email = v.email,
                            candidates = v.candidates,
                            createdAt = v.createdAt.toString(),
                            updatedAt = v.updatedAt.toString(),
                            deletedAt = v.deletedAt.map { it.toString() },
                        )
                    },
                )
            },
        )
    }
}

fun Route.adminVendorCreateRoute(
    authService: AuthService,
    appConfig: AppConfig,
) {
    post("/admin/vendors") {
        if (call.requireAdmin().isNone()) return@post
        val request = call.receive<VendorRequest>()
        val candidates = request.candidates.getOrElse { emptyList() }

        VendorRequestValidator
            .validate(request.email, candidates, appConfig.adminEmail)
            .fold(
                ifLeft = { error -> call.respondDomainError(error) },
                ifRight = {
                    authService.createOrUpdateVendor(request.email, candidates).fold(
                        ifLeft = { error -> call.respondDomainError(error) },
                        ifRight = { vendorWithPassword ->
                            val v = vendorWithPassword.vendor
                            call.respond(
                                HttpStatusCode.OK,
                                VendorWithPasswordResponse(
                                    id = v.id.toString(),
                                    email = v.email,
                                    password = vendorWithPassword.plaintextPassword,
                                    candidates = v.candidates,
                                    createdAt = v.createdAt.toString(),
                                    updatedAt = v.updatedAt.toString(),
                                ),
                            )
                        },
                    )
                },
            )
    }
}

fun Route.adminVendorDeleteRoute(authService: AuthService) {
    delete("/admin/vendors/{id}") {
        if (call.requireAdmin().isNone()) return@delete
        val idStr =
            call.parameters["id"].toOption().getOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request", "Missing vendor id"))
                return@delete
            }
        val id =
            runCatching { UUID.fromString(idStr) }.getOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request", "Invalid vendor id"))
                return@delete
            }
        authService.softDeleteVendor(id).fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { v ->
                call.respond(
                    HttpStatusCode.OK,
                    VendorResponse(
                        id = v.id.toString(),
                        email = v.email,
                        candidates = v.candidates,
                        createdAt = v.createdAt.toString(),
                        updatedAt = v.updatedAt.toString(),
                        deletedAt = v.deletedAt.map { it.toString() },
                    ),
                )
            },
        )
    }
}
