# JWT Authentication Specification

## Overview

Replace HTTP Basic Authentication with JWT-based authentication for all write operations in the SDKMAN State API. The current system uses a single shared credential loaded from environment variables. The new system introduces two user types — an admin (in-memory) and publishers (database-backed, multi-candidate-scoped) — with short-lived JWT tokens for stateless authorization. This enables per-vendor access control, better audit trails, and the ability to revoke access by deleting publisher accounts.

**Key Properties:**
- Stateless authorization — all access decisions are made from JWT claims, no per-request DB lookups
- Short-lived tokens (10 minutes default) limit exposure in case of breach
- Hard cutover from basic auth — no transition period (only two controlled clients)
- Publishers can manage multiple candidates (e.g., Azul publishes both `java` and `zulu-mission-control`)
- Admin is the sole user manager — no self-service registration or password reset

## Requirements

- All write endpoints (`POST /versions`, `DELETE /versions`, `DELETE /versions/tags`) require a valid JWT
- JWT carries a `candidates` claim; publishers can only operate on candidates in their list
- Admin bypasses all candidate-scoping checks
- `POST /auth/login` accepts email + password, returns a signed JWT for both admin and publisher users
- `POST /admin/users` idempotently creates or updates a publisher account, always regenerating the password
- `DELETE /admin/users/{id}` hard-deletes a publisher account
- Admin user is loaded from environment variables (`ADMIN_EMAIL`, `ADMIN_PASSWORD`), not persisted to the database
- Publisher passwords are generated server-side (32-char alphanumeric via `SecureRandom`), hashed with BCrypt
- All existing Basic Auth configuration and code is removed
- The `vendor_audit` table is dropped and recreated with JWT-aware columns (using nil UUID sentinel for admin, no nullable fields)
- The old `audit` table (V3/V4) is left untouched — it is used by `sdkman-broker-2`
- No audit logging for admin user-management operations
- GET endpoints, health check, and login remain publicly accessible

## Rules

**Before implementing, you MUST read and internalize:**
- rules/kotlin.md
- rules/domain-driven-design.md
- rules/kotest.md
- rules/hexagonal-architecture.md

**If this prompt conflicts with the rules, THE RULES WIN.**

## Domain

### User Model

```kotlin
sealed class User {
    abstract val email: String

    data class Admin(
        override val email: String,
    ) : User()

    data class Publisher(
        val id: UUID,
        override val email: String,
        val passwordHash: String,
        val candidates: List<String>,
        val createdAt: Instant,
        val updatedAt: Instant,
    ) : User()
}
```

### Auth Context (extracted from JWT)

```kotlin
data class AuthContext(
    val userId: Option<UUID>,   // None for admin
    val email: String,
    val candidates: List<String>,
    val isAdmin: Boolean,
) {
    fun canAccessCandidate(target: String): Boolean =
        isAdmin || candidates.contains(target)
}
```

### DTOs

```kotlin
@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val token: String,
    val expiresAt: String,  // ISO 8601
)

@Serializable
data class CreateUserRequest(
    val email: String,
    val candidates: Option<List<String>> = None,  // optional on update
)

@Serializable
data class CreateUserResponse(
    val id: String,         // UUID as string
    val email: String,
    val candidates: List<String>,
    val password: String,   // plaintext generated password, returned once
)
```

### Value Objects

```kotlin
@JvmInline
value class GeneratedPassword(val value: String) {
    init { require(value.length == 32) { "Generated password must be 32 characters" } }
}

@JvmInline
value class PasswordHash(val value: String) {
    init { require(value.isNotBlank()) { "Password hash must not be blank" } }
}

@JvmInline
value class JwtSecret(val value: String) {
    init { require(value.length >= 32) { "JWT secret must be at least 32 characters" } }
}
```

### Updated Audit Record

```kotlin
@Serializable
data class AuditRecord(
    val id: Long = 0,
    val userId: UUID,           // nil UUID for admin
    val userEmail: String,
    val timestamp: Instant,
    val operation: AuditOperation,
    val versionData: String,
)
```

The `AuditRepository` interface changes to accept `AuthContext` instead of a username string:

```kotlin
interface AuditRepository {
    suspend fun recordAudit(
        authContext: AuthContext,
        operation: AuditOperation,
        version: Version,
    ): Either<DatabaseFailure, Unit>
}
```

The admin sentinel UUID is defined as a constant:

```kotlin
val ADMIN_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
```

When recording audit for admin, use `ADMIN_USER_ID`. For publishers, use their actual UUID from the JWT `sub` claim.

## Database Schema

### New Table: `users` (Migration V13)

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    candidates TEXT[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_users_email ON users(email);
```

### Recreated Table: `vendor_audit` (Migration V14)

```sql
DROP TABLE vendor_audit;

CREATE TABLE vendor_audit (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    user_email TEXT NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    operation TEXT NOT NULL,
    version_data JSONB NOT NULL
);

CREATE INDEX idx_vendor_audit_user_id ON vendor_audit(user_id);
CREATE INDEX idx_vendor_audit_timestamp ON vendor_audit(timestamp);
CREATE INDEX idx_vendor_audit_operation ON vendor_audit(operation);
```

**Design decisions:**
- `user_id UUID NOT NULL` — admin uses nil UUID `00000000-0000-0000-0000-000000000000` as sentinel, no nullable fields
- `user_email` replaces the old `username` column
- No `request_ip` or `user_agent` columns — keeping it minimal
- Existing audit data is discarded (acceptable: low traffic, basic auth credentials are not meaningful identity)

## JWT Structure

### Signing

- Algorithm: **HMAC-SHA256 (HS256)**
- Secret: minimum 32 characters, loaded from `JWT_SECRET` environment variable
- Library: `com.auth0:java-jwt`

### Claims — Admin Token

```json
{
  "email": "admin@sdkman.io",
  "admin": true,
  "candidates": [],
  "iat": 1709900000,
  "exp": 1709900600,
  "iss": "sdkman-state",
  "aud": "sdkman-api"
}
```

No `sub` claim for admin (no persistent identity).

### Claims — Publisher Token

```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "email": "vendor@azul.com",
  "admin": false,
  "candidates": ["java", "zulu-mission-control"],
  "iat": 1709900000,
  "exp": 1709900600,
  "iss": "sdkman-state",
  "aud": "sdkman-api"
}
```

### Token Validation

On every authenticated request:
1. Verify signature (HS256 with `JWT_SECRET`)
2. Verify `exp` has not passed
3. Verify `iss` is `sdkman-state`
4. Verify `aud` is `sdkman-api`
5. Extract `AuthContext` from claims

No database lookup is performed during token validation — authorization is fully stateless.

## Endpoints

### POST /auth/login

Authenticate and receive a JWT. Works for both admin and publisher users.

**Authentication:** None (public endpoint)

**Precedence:** Admin email is checked first. If the email matches `ADMIN_EMAIL`, validate against the admin password. Otherwise, look up the `users` table. The admin email should never be used for a publisher account.

**Request:**
```json
{
  "email": "vendor@azul.com",
  "password": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
}
```

**Response (200 OK):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresAt": "2026-03-09T12:10:00Z"
}
```

**Error responses:**
- `400 Bad Request` — missing or blank email/password
- `401 Unauthorized` — invalid credentials (same response for wrong email and wrong password to avoid enumeration)

### POST /admin/users

Create or update a publisher account. Idempotent — keyed on email address.

**Authentication:** JWT with `admin: true`

**Request:**
```json
{
  "email": "vendor@azul.com",
  "candidates": ["java", "zulu-mission-control"]
}
```

**Behaviour:**
- **New email:** Creates user, generates 32-char random password, hashes with BCrypt, stores everything. Returns the plaintext password.
- **Existing email:** Regenerates password (new random + new hash), updates `candidates` list if provided (omit to leave unchanged), updates `updated_at`. Returns the new plaintext password.
- The generated password is returned **once** in the response and is never retrievable again.

**Response (201 Created for new, 200 OK for update):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "vendor@azul.com",
  "candidates": ["java", "zulu-mission-control"],
  "password": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
}
```

**Error responses:**
- `400 Bad Request` — missing or blank email, invalid candidates
- `401 Unauthorized` — missing or invalid JWT
- `403 Forbidden` — valid JWT but not admin

### DELETE /admin/users/{id}

Hard-delete a publisher account by UUID.

**Authentication:** JWT with `admin: true`

**Response:**
- `204 No Content` — user deleted
- `404 Not Found` — user with that UUID does not exist
- `401 Unauthorized` — missing or invalid JWT
- `403 Forbidden` — valid JWT but not admin

**Note:** If a deleted publisher has an active JWT, it remains valid until expiry (max 10 minutes). This is acceptable given the short token lifetime.

### POST /versions (modified)

Existing endpoint. Authentication changes from Basic Auth to JWT.

**Authentication:** JWT required. Publisher must have the target `candidate` in their `candidates` claim. Admin bypasses this check.

**Candidate authorization:** The `candidate` field in the request body is checked against `AuthContext.canAccessCandidate()`. If the publisher is not authorized for that candidate, return `403 Forbidden`.

**All other behaviour remains unchanged.**

### DELETE /versions (modified)

Existing endpoint. Authentication changes from Basic Auth to JWT.

**Authentication:** JWT required. Same candidate-scoping rules as POST /versions.

**Candidate authorization:** The `candidate` field in the request body is checked against `AuthContext.canAccessCandidate()`.

**All other behaviour remains unchanged.**

### DELETE /versions/tags (new — included for completeness)

Not yet implemented in routing, but included in this spec for the access matrix. The domain model (`UniqueTag`, `TagsRepository.deleteTag`) already exists.

**Authentication:** JWT required. Publisher must have the target `candidate` in their `candidates` claim.

**Request body:**
```json
{
  "candidate": "java",
  "tag": "latest",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64"
}
```

**Response:**
- `204 No Content` — tag deleted
- `404 Not Found` — tag does not exist
- `400 Bad Request` — validation failure
- `401 Unauthorized` — missing or invalid JWT
- `403 Forbidden` — not authorized for candidate

## Access Matrix

| Endpoint | Anonymous | Publisher | Admin |
|----------|-----------|-----------|-------|
| GET /meta/health | Yes | Yes | Yes |
| GET /versions/{candidate} | Yes | Yes | Yes |
| GET /versions/{candidate}/{version} | Yes | Yes | Yes |
| GET /versions/{candidate}/tags/{tag} | Yes | Yes | Yes |
| POST /auth/login | Yes | N/A | N/A |
| POST /versions | No | Candidate-scoped | Yes |
| DELETE /versions | No | Candidate-scoped | Yes |
| DELETE /versions/tags | No | Candidate-scoped | Yes |
| POST /admin/users | No | No | Yes |
| DELETE /admin/users/{id} | No | No | Yes |

## Configuration

### Removed

```hocon
api {
    username = "testuser"
    username = ${?BASIC_AUTH_USERNAME}
    password = "password123"
    password = ${?BASIC_AUTH_PASSWORD}
}
```

### Added

```hocon
security {
    admin {
        email = "admin@sdkman.io"
        email = ${?ADMIN_EMAIL}
        password = "admin-password"
        password = ${?ADMIN_PASSWORD}
    }
    jwt {
        secret = "change-me-to-a-32-char-minimum-secret"
        secret = ${?JWT_SECRET}
        expiry-minutes = 10
        expiry-minutes = ${?JWT_EXPIRY_MINUTES}
    }
}
```

### Retained (unchanged)

```hocon
api {
    cache.control = 600
}
```

### ApplicationConfig Changes

Replace `ApiAuthenticationConfig` with:

```kotlin
data class AdminConfig(
    val email: String,
    val password: String,
)

data class JwtConfig(
    val secret: JwtSecret,
    val expiryMinutes: Int,
)

data class SecurityConfig(
    val admin: AdminConfig,
    val jwt: JwtConfig,
)
```

`ApplicationConfig` changes from:

```kotlin
data class ApplicationConfig(
    val databaseConfig: DatabaseConfig,
    val apiAuthenticationConfig: ApiAuthenticationConfig,
    val apiCacheConfig: ApiCacheConfig,
)
```

to:

```kotlin
data class ApplicationConfig(
    val databaseConfig: DatabaseConfig,
    val securityConfig: SecurityConfig,
    val apiCacheConfig: ApiCacheConfig,
)
```

## Libraries

### Add

| Library | Purpose |
|---------|---------|
| `io.ktor:ktor-server-auth-jwt` | Ktor JWT authentication plugin |
| `com.auth0:java-jwt` | JWT creation and validation |
| `at.favre.lib:bcrypt` | Password hashing (replaces unmaintained `jbcrypt`) |

### Remove

No libraries to remove — Basic Auth uses Ktor's built-in `ktor-server-auth` which is still needed for the JWT plugin.

## Extra Considerations

### Login Precedence

When `POST /auth/login` receives a request:
1. Compare email against `ADMIN_EMAIL` environment variable
2. If match: validate password against `ADMIN_PASSWORD` env var, issue admin token
3. If no match: query `users` table by email, validate BCrypt hash, issue publisher token
4. If neither matches or password is wrong: return `401 Unauthorized`

The error response must be identical for "unknown email" and "wrong password" to prevent user enumeration.

### Password Generation

Publisher passwords are generated server-side, never chosen by the user:
- 32 characters, alphanumeric (`[a-zA-Z0-9]`)
- Generated using `java.security.SecureRandom`
- Hashed with BCrypt before storage
- Plaintext returned exactly once in the `POST /admin/users` response
- Admin delivers plaintext to vendor via GPG-encrypted side-channel

### Idempotent User Creation

`POST /admin/users` with an existing email:
1. **Password**: always regenerated and returned (primary use case for re-calling this endpoint)
2. **Candidates**: updated if provided in the request body, left unchanged if `candidates` field is omitted
3. **Response**: `200 OK` for update (vs `201 Created` for new)

This eliminates the need for a separate password-reset endpoint.

### Candidate Authorization on Write Endpoints

For `POST /versions`, `DELETE /versions`, and `DELETE /versions/tags`:
1. Extract `AuthContext` from the validated JWT
2. Read the `candidate` field from the request body
3. Call `authContext.canAccessCandidate(candidate)` — returns `true` if admin or candidate is in list
4. If `false`: respond `403 Forbidden` with `ErrorResponse("Forbidden", "Not authorized for candidate: $candidate")`

### Audit Identity Mapping

When recording audit entries:
- **Admin**: `user_id = 00000000-0000-0000-0000-000000000000`, `user_email = <admin email from JWT>`
- **Publisher**: `user_id = <UUID from JWT sub claim>`, `user_email = <email from JWT>`

### Impact on Existing Routing

The `authenticate("auth-basic")` block in `Routing.kt` is replaced with a JWT-based authentication block. The principal type changes from `UserIdPrincipal` to a custom `AuthContext`-bearing principal. All routes within the authenticated block gain candidate-scoping logic.

### Impact on Application.kt

`configureBasicAuthentication(appConfig.apiAuthenticationConfig)` is replaced with `configureJwtAuthentication(appConfig.securityConfig)`.

### Impact on Test Support

Test setup currently uses Basic Auth credentials for authenticated requests. All test utilities must be updated to:
1. Obtain a JWT via `POST /auth/login` (or generate one directly for test convenience)
2. Pass the JWT as a `Bearer` token in the `Authorization` header

## Testing Considerations

All tests use Kotest `ShouldSpec` style as outside-in acceptance tests with real PostgreSQL.

### Login Tests

**Happy path:**
- Admin login with valid credentials returns JWT with `admin: true`
- Publisher login with valid credentials returns JWT with correct `candidates` claim
- Token contains expected claims (`iss`, `aud`, `exp`, `email`, `candidates`)
- Token expiry is ~10 minutes from issuance

**Unhappy path:**
- Login with unknown email returns `401 Unauthorized`
- Login with wrong password returns `401 Unauthorized`
- Login with blank email returns `400 Bad Request`
- Login with blank password returns `400 Bad Request`
- Error response is identical for unknown email and wrong password (no enumeration)

### User Management Tests

**Happy path:**
- Create new publisher returns `201` with generated password, id, email, candidates
- Create with existing email returns `200` with new password (idempotent update)
- Update with candidates field changes the candidate list
- Update without candidates field preserves existing candidates
- Generated password is 32 alphanumeric characters
- Created user can log in with the returned password

**Unhappy path:**
- Create user without admin JWT returns `401`
- Create user with publisher JWT returns `403`
- Create user with blank email returns `400`
- Delete non-existent user returns `404`
- Delete user without admin JWT returns `401`
- Delete user with publisher JWT returns `403`

### Candidate Authorization Tests

**Happy path:**
- Publisher with `candidates: ["java"]` can POST a java version
- Publisher with `candidates: ["java", "gradle"]` can POST both java and gradle versions
- Admin can POST any candidate version

**Unhappy path:**
- Publisher with `candidates: ["java"]` gets `403` when POSTing a gradle version
- Publisher with `candidates: ["java"]` gets `403` when DELETEing a gradle version
- Publisher with `candidates: ["java"]` gets `403` when DELETEing a gradle tag
- Expired JWT returns `401`
- Malformed JWT returns `401`
- JWT with wrong signature returns `401`
- Missing Authorization header returns `401`

### Audit Tests

**Happy path:**
- POST /versions records audit with publisher's UUID and email
- POST /versions by admin records audit with nil UUID and admin email
- DELETE /versions records audit with correct user identity
- Audit `version_data` JSONB contains the version details

### Regression Tests

- All existing GET endpoint tests pass without modification
- Health check remains publicly accessible
- POST /versions behaviour (validation, tags, idempotency) is unchanged apart from auth mechanism
- DELETE /versions behaviour is unchanged apart from auth mechanism

## Specification by Example

### Publisher Login
```http
POST /auth/login
Content-Type: application/json

{
  "email": "vendor@azul.com",
  "password": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
}

Response: 200 OK
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresAt": "2026-03-09T12:10:00Z"
}
```

### Admin Login
```http
POST /auth/login
Content-Type: application/json

{
  "email": "admin@sdkman.io",
  "password": "admin-secret-password"
}

Response: 200 OK
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresAt": "2026-03-09T12:10:00Z"
}
```

### Create Publisher
```http
POST /admin/users
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
Content-Type: application/json

{
  "email": "vendor@azul.com",
  "candidates": ["java", "zulu-mission-control"]
}

Response: 201 Created
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "vendor@azul.com",
  "candidates": ["java", "zulu-mission-control"],
  "password": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
}
```

### Update Publisher (idempotent — regenerates password)
```http
POST /admin/users
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
Content-Type: application/json

{
  "email": "vendor@azul.com",
  "candidates": ["java", "zulu-mission-control", "zulu-fx"]
}

Response: 200 OK
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "vendor@azul.com",
  "candidates": ["java", "zulu-mission-control", "zulu-fx"],
  "password": "x9y8z7w6v5u4t3s2r1q0p9o8n7m6l5k4"
}
```

### Update Publisher (preserve candidates, just reset password)
```http
POST /admin/users
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
Content-Type: application/json

{
  "email": "vendor@azul.com"
}

Response: 200 OK
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "vendor@azul.com",
  "candidates": ["java", "zulu-mission-control", "zulu-fx"],
  "password": "j8k7l6m5n4o3p2q1r0s9t8u7v6w5x4y3"
}
```

### Delete Publisher
```http
DELETE /admin/users/550e8400-e29b-41d4-a716-446655440000
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...

Response: 204 No Content
```

### POST Version with JWT (publisher, authorized)
```http
POST /versions
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
Content-Type: application/json

{
  "candidate": "java",
  "version": "27.0.2",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64",
  "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz"
}

Response: 204 No Content
```

### POST Version with JWT (publisher, unauthorized candidate)
```http
POST /versions
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
Content-Type: application/json

{
  "candidate": "gradle",
  "version": "8.12",
  "platform": "UNIVERSAL",
  "url": "https://cdn.example.com/gradle-8.12.zip"
}

Response: 403 Forbidden
{
  "error": "Forbidden",
  "message": "Not authorized for candidate: gradle"
}
```

### Invalid Credentials
```http
POST /auth/login
Content-Type: application/json

{
  "email": "vendor@azul.com",
  "password": "wrong-password"
}

Response: 401 Unauthorized
{
  "error": "Unauthorized",
  "message": "Invalid credentials"
}
```

### Expired Token
```http
POST /versions
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...expired...
Content-Type: application/json

{ ... }

Response: 401 Unauthorized
{
  "error": "Unauthorized",
  "message": "Token has expired"
}
```

## Suggested Slice Breakdown

The following is a suggested decomposition into oneshot prompts. Each slice should be independently testable and committable.

1. **Database foundations** — V13 users table migration, V14 vendor_audit table recreation
2. **Configuration & security domain** — New config classes (`SecurityConfig`, `AdminConfig`, `JwtConfig`), value objects (`GeneratedPassword`, `PasswordHash`, `JwtSecret`), `User` sealed class, `AuthContext`, remove `ApiAuthenticationConfig`
3. **JWT infrastructure** — JWT creation and validation services, Ktor JWT authentication plugin, replace `configureBasicAuthentication` with `configureJwtAuthentication`
4. **Login endpoint** — `POST /auth/login` with admin and publisher authentication, BCrypt password verification
5. **User management endpoints** — `POST /admin/users` (idempotent create/update), `DELETE /admin/users/{id}` (hard delete), admin-only authorization
6. **Migrate protected endpoints** — Replace `authenticate("auth-basic")` with JWT auth on `POST /versions` and `DELETE /versions`, add candidate-scoping authorization, update audit recording to use `AuthContext`
7. **DELETE /versions/tags** — New JWT-protected route (domain model already exists)

## Verification

- [ ] Basic Auth is fully removed — no `auth-basic` references remain in codebase
- [ ] `BASIC_AUTH_USERNAME` and `BASIC_AUTH_PASSWORD` env vars are no longer referenced
- [ ] Admin can log in with `ADMIN_EMAIL`/`ADMIN_PASSWORD` and receives a valid JWT
- [ ] Publisher can log in with email/generated-password and receives a valid JWT
- [ ] JWT tokens expire after 10 minutes (configurable)
- [ ] JWT contains correct claims for admin (no `sub`, `admin: true`, empty `candidates`)
- [ ] JWT contains correct claims for publisher (`sub`, `admin: false`, populated `candidates`)
- [ ] `POST /admin/users` creates a new publisher with generated password
- [ ] `POST /admin/users` with existing email regenerates password and returns it
- [ ] `POST /admin/users` with existing email and no `candidates` field preserves existing candidates
- [ ] `POST /admin/users` with existing email and `candidates` field updates the list
- [ ] `DELETE /admin/users/{id}` removes the user from the database
- [ ] Admin endpoints return `403` for non-admin JWTs
- [ ] `POST /versions` requires JWT and enforces candidate-scoping for publishers
- [ ] `DELETE /versions` requires JWT and enforces candidate-scoping for publishers
- [ ] `DELETE /versions/tags` requires JWT and enforces candidate-scoping for publishers
- [ ] Admin can operate on any candidate
- [ ] `vendor_audit` records use UUID (nil for admin) and email from JWT
- [ ] All GET endpoints remain publicly accessible without authentication
- [ ] Health check remains publicly accessible
- [ ] Login returns identical error for wrong email and wrong password
- [ ] All existing tests updated and passing
- [ ] No nullable types — Arrow `Option` pattern followed throughout
- [ ] Code formatted and ktlint clean