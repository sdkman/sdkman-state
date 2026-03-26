package io.sdkman.state.adapter.primary.rest

import arrow.core.Some
import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.adapter.primary.rest.dto.CreateVendorRequest
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.LoginRequest
import io.sdkman.state.adapter.primary.rest.dto.LoginResponse
import io.sdkman.state.adapter.primary.rest.dto.VendorResponse
import io.sdkman.state.adapter.primary.rest.dto.VendorWithPasswordResponse
import io.sdkman.state.config.AppConfig
import io.sdkman.state.domain.error.AuthError
import io.sdkman.state.domain.model.Vendor
import io.sdkman.state.domain.repository.VendorRepository
import io.sdkman.state.domain.service.AuthService
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

private const val BCRYPT_COST = 12
private const val PASSWORD_BYTES = 32
private val EMAIL_REGEX = Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

fun Route.adminLoginRoute(authService: AuthService) {
    post("/admin/login") {
        val request = call.receive<LoginRequest>()
        val clientIp = call.request.local.remoteHost
        authService.login(request.email, request.password, clientIp).fold(
            ifLeft = { error ->
                when (error) {
                    is AuthError.InvalidCredentials ->
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Unauthorized", "Invalid credentials"),
                        )

                    is AuthError.RateLimitExceeded ->
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            ErrorResponse("Too Many Requests", "Rate limit exceeded"),
                        )

                    is AuthError.TokenCreationFailed ->
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Internal Server Error", "Token creation failed"),
                        )
                }
            },
            ifRight = { token ->
                call.respond(HttpStatusCode.OK, LoginResponse(token))
            },
        )
    }
}

fun Route.adminListVendorsRoute(vendorRepository: VendorRepository) {
    get("/admin/vendors") {
        val role = call.authenticatedRole()
        if (role != "admin") {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", "Invalid or expired token"))
            return@get
        }
        val includeDeleted = call.request.queryParameters["include_deleted"] == "true"
        vendorRepository.findAll(includeDeleted).fold(
            ifLeft = {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Internal Server Error", it.message),
                )
            },
            ifRight = { vendors ->
                call.respond(HttpStatusCode.OK, vendors.map { it.toVendorResponse() })
            },
        )
    }
}

fun Route.adminCreateVendorRoute(
    vendorRepository: VendorRepository,
    appConfig: AppConfig,
) {
    post("/admin/vendors") {
        val role = call.authenticatedRole()
        if (role != "admin") {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", "Invalid or expired token"))
            return@post
        }
        val request = call.receive<CreateVendorRequest>()

        if (!EMAIL_REGEX.matches(request.email)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request", "Invalid email format"))
            return@post
        }
        if (request.email == appConfig.adminEmail) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request", "Cannot use admin email"))
            return@post
        }
        if (request.candidates is Some && request.candidates.value.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request", "Candidates list cannot be empty"))
            return@post
        }

        val plaintextPassword = generatePassword()
        val hashedPassword = String(BCrypt.withDefaults().hash(BCRYPT_COST, plaintextPassword.toByteArray()))

        vendorRepository.upsert(request.email, hashedPassword, request.candidates).fold(
            ifLeft = {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Internal Server Error", it.message),
                )
            },
            ifRight = { (vendor, created) ->
                val status = if (created) HttpStatusCode.Created else HttpStatusCode.OK
                call.respond(status, vendor.toVendorWithPasswordResponse(plaintextPassword))
            },
        )
    }
}

fun Route.adminDeleteVendorRoute(vendorRepository: VendorRepository) {
    delete("/admin/vendors/{id}") {
        val role = call.authenticatedRole()
        if (role != "admin") {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", "Invalid or expired token"))
            return@delete
        }
        val idString = call.parameters["id"]
        val vendorId =
            try {
                UUID.fromString(idString)
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Not Found", "Vendor not found"))
                return@delete
            }
        vendorRepository.softDelete(vendorId).fold(
            ifLeft = {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Internal Server Error", it.message),
                )
            },
            ifRight = { maybeVendor ->
                maybeVendor.fold(
                    ifEmpty = {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("Not Found", "Vendor not found or already deleted"),
                        )
                    },
                    ifSome = { vendor ->
                        call.respond(HttpStatusCode.OK, vendor.toVendorResponse())
                    },
                )
            },
        )
    }
}

private fun generatePassword(): String {
    val bytes = ByteArray(PASSWORD_BYTES)
    SecureRandom().nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}

private fun Vendor.toVendorResponse(): VendorResponse =
    VendorResponse(
        id = id.toString(),
        email = email,
        candidates = candidates,
        createdAt = ISO_FORMATTER.format(createdAt.atOffset(ZoneOffset.UTC)),
        updatedAt = ISO_FORMATTER.format(updatedAt.atOffset(ZoneOffset.UTC)),
        deletedAt = deletedAt.map { ISO_FORMATTER.format(it.atOffset(ZoneOffset.UTC)) },
    )

private fun Vendor.toVendorWithPasswordResponse(plaintextPassword: String): VendorWithPasswordResponse =
    VendorWithPasswordResponse(
        id = id.toString(),
        email = email,
        password = plaintextPassword,
        candidates = candidates,
        createdAt = ISO_FORMATTER.format(createdAt.atOffset(ZoneOffset.UTC)),
        updatedAt = ISO_FORMATTER.format(updatedAt.atOffset(ZoneOffset.UTC)),
    )
