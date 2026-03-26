# JWT Authentication Implementation Plan

Prioritised, ordered implementation plan for replacing Basic Auth with JWT authentication. Each task is atomic and maps to a single commit. Tasks are grouped into phases with explicit dependencies. Each phase leaves the codebase in a compiling, non-regressing state.

Spec reference: `specs/jwt-authentication.md`

---

## Validation Summary

Validated 2026-03-26 against the current codebase (branch `jwt_authentication_replay`). **Phase 1–10 complete (36/47 tasks done).** Full JWT authentication stack implemented: domain layer, persistence adapters, application services (AuthServiceImpl, RateLimiter), REST adapters (AdminRoutes, JWT auth config, admin DTOs), application wiring, and Basic Auth fully removed. All existing acceptance tests migrated from Basic Auth to JWT Bearer tokens. Test infrastructure (JwtTestSupport, test Application.kt) aligned with JWT auth.

### Evidence

- No JWT-related files exist (`Vendor.kt`, `AuthError.kt`, `VendorRepository.kt`, `AuthService.kt`, `PostgresVendorRepository.kt`, `RateLimiter.kt`, `AuthServiceImpl.kt`, `AdminRoutes.kt`, `AdminDto.kt`, `JwtTestSupport.kt` — all absent)
- No JWT/BCrypt dependencies in `build.gradle.kts` (only `ktor-server-auth-jvm`, no `ktor-server-auth-jwt`, `com.auth0:java-jwt`, or `at.favre.lib:bcrypt`)
- Zero matches for `jwt|JWT|JsonWebToken` across all `.kt` files
- Only V1–V12 migrations exist (no V13/V14)
- `Authentication.kt` still configures `basic("auth-basic")`; `AuditRepository` still uses `username: String`
- `application.conf` has no `admin` or `jwt` config blocks; `AppConfig` still has `authUsername`/`authPassword`
- All write-path acceptance tests use `basicAuth` or `BASIC_AUTH_HEADER`
- OpenAPI spec uses `basicAuth` security scheme

### Gaps and Issues Found

1. **Compilation break between Phase 6.6 and Phase 9.2 (FIXED)** -- Phase 6.6 changes `configureRouting`'s signature (adding `authService`, `vendorRepository`, `appConfig` parameters), which breaks the test `Application.kt` that also calls `configureRouting`. Phase 7.1 fixes the main `Application.kt`, but the test `Application.kt` was not fixed until Phase 9.2 — leaving a multi-phase compilation gap. **Fix:** Phase 7.1 now updates both the main and test `Application.kt` atomically. Phase 9.2 is narrowed to refine test config values (JWT secret, admin credentials) and is no longer responsible for the initial wiring.

2. **`flywaydb:flyway-database-postgresql` dependency is already present** -- The current build uses Flyway 12.1.1 which requires the separate `flyway-database-postgresql` module (already in `build.gradle.kts`). The V13 migration's `gen_random_uuid()` is a PostgreSQL built-in function (available since PG 13). No additional Flyway dependencies are required.

3. **`TagService.replaceTags` audit parameter propagation** -- Task 4.2 notes that `TagService.replaceTags` does not take audit params. However, `replaceTags` is called from `VersionServiceImpl.processTags` which calls `logAudit` separately. This means `VersionServiceImpl.createOrUpdate` needs the `vendorId`/`email` params to pass to its own `logAudit` call, which is correctly captured in task 4.2. No gap here.

4. **`PostVersionTagsAcceptanceSpec` in Phase 10.7** -- This test uses `POST /versions` (which creates versions with tags). It uses Basic Auth and will need JWT migration. Correctly identified.

5. **All spec items are covered** -- All 15 happy path tests, all 19 unhappy path tests, all 4 API endpoints, all security requirements (constant-time comparison, rate limiting, algorithm restriction), and all OpenAPI changes are accounted for in the plan. Phase 13.3 is actually more thorough than the spec by explicitly testing `DELETE /versions/tags` with unauthorized candidate (the spec only numbers `POST /versions` and `DELETE /versions` 403 tests).

6. **Compilation break in Phase 6.1 removing Basic Auth prematurely (FIXED)** -- Phase 6.1 originally removed `configureBasicAuthentication()` immediately, but callers (`Application.kt`, test `Application.kt`) still reference it until Phase 7.1. **Fix:** Phase 6.1 now adds `configureJwtAuthentication()` alongside the existing `configureBasicAuthentication()` (which remains intact). Phase 7.1 performs the switchover and removal atomically.

7. **Compilation break in Phase 6.3 removing `authenticatedUsername()` prematurely (FIXED)** -- Phase 6.3 originally removed `authenticatedUsername()` from `RequestExtensions.kt`, but callers in `VersionRoutes.kt` and `TagRoutes.kt` aren't updated until Phase 6.5. **Fix:** Phase 6.3 now adds the new JWT-aware helper functions alongside the existing `authenticatedUsername()`. Phase 6.5 switches callers to the new functions and removes the old one.

8. **`explicitNulls = false` affects vendor response serialization** -- The content negotiation serializer has `explicitNulls = false` set globally, which means `Option.None` fields (like `deleted_at` for active vendors) will be omitted from JSON rather than shown as `"deleted_at": null`. This is acceptable API behaviour — the spec's `"deleted_at": null` examples are illustrative. No code change needed, but implementers should be aware.

9. **`AdminDto.kt` requires `@file:UseSerializers(OptionSerializer::class)`** -- Since `CreateVendorRequest` and `VendorResponse` use Arrow `Option` fields, the file needs the serializer annotation. Noted explicitly in Task 6.2.

---

## Phase 1: Dependencies and Configuration

Foundation work -- no behavioural changes yet. Existing Basic Auth continues to work.

- [x] **1.1 Add JWT and BCrypt dependencies to `build.gradle.kts`**
  Add `io.ktor:ktor-server-auth-jwt:$ktor_version`, `com.auth0:java-jwt:4.5.0`, and `at.favre.lib:bcrypt:0.10.2` to implementation dependencies. The project already has `io.ktor:ktor-server-auth-jvm` and `flyway-database-postgresql` (Flyway 12.1.1). No additional Flyway modules are needed.
  - File: `build.gradle.kts`

- [x] **1.2 Add `admin` and `jwt` config blocks to `application.conf`**
  Add `admin { email, password }` and `jwt { secret, expiry }` config blocks with env var overrides (`ADMIN_EMAIL`, `ADMIN_PASSWORD`, `JWT_SECRET`, `JWT_EXPIRY_MINUTES`). `jwt.secret` has no default (fail fast). Keep existing `api` block for now (removed later).
  - File: `src/main/resources/application.conf`

- [x] **1.3 Extend `AppConfig` with admin and JWT config properties**
  Add `adminEmail: String`, `adminPassword: String`, `jwtSecret: String`, `jwtExpiry: Int` to the `AppConfig` interface and `DefaultAppConfig` implementation. `jwtSecret` must use a getter that throws if the env var is missing. Keep `authUsername`/`authPassword` for now (removed in Phase 7). Use existing `ConfigExtensions.kt` helpers (`getStringOrDefault`, `getIntOrDefault`) for the new properties.
  - File: `src/main/kotlin/io/sdkman/state/config/AppConfig.kt`

---

## Phase 2: Domain Layer

Domain models, error types, and port interfaces -- no infrastructure dependencies. The `AuditRepository` signature change is deferred to Phase 4 to avoid a compilation break spanning multiple phases.

- [x] **2.1 Create `Vendor` domain model**
  Create `Vendor` data class with `id: UUID`, `email: String`, `hashedPassword: String`, `candidates: List<String>`, `createdAt: Instant`, `updatedAt: Instant`, `deletedAt: Option<Instant>`. Place in `domain/model/`. No `@Serializable` annotation (domain model, not a DTO). Use `java.util.UUID` and `java.time.Instant` (consistent with Exposed column types; test support converts to `kotlin.time.Instant` for assertions where needed).
  - File: `src/main/kotlin/io/sdkman/state/domain/model/Vendor.kt`

- [x] **2.2 Add `AuthError` sealed interface to domain errors**
  Define `AuthError` sealed interface with variants: `InvalidCredentials`, `RateLimitExceeded`, `TokenCreationFailed`. Place alongside existing `DomainError` in the error package. These are separate from `DomainError` since auth is a distinct concern.
  - File: `src/main/kotlin/io/sdkman/state/domain/error/AuthError.kt`

- [x] **2.3 Create `VendorRepository` port interface**
  Define methods: `findByEmail(email: String): Either<DatabaseFailure, Option<Vendor>>`, `findAll(includeDeleted: Boolean): Either<DatabaseFailure, List<Vendor>>`, `upsert(email: String, hashedPassword: String, candidates: Option<List<String>>): Either<DatabaseFailure, Pair<Vendor, Boolean>>` (returns vendor and whether it was created), `softDelete(id: UUID): Either<DatabaseFailure, Option<Vendor>>`, `findById(id: UUID): Either<DatabaseFailure, Option<Vendor>>`. Place in `domain/repository/`.
  - File: `src/main/kotlin/io/sdkman/state/domain/repository/VendorRepository.kt`

- [x] **2.4 Create `AuthService` port interface**
  Define: `login(email: String, password: String, clientIp: String): Either<AuthError, String>` (returns JWT token string). Place in `domain/service/`.
  - File: `src/main/kotlin/io/sdkman/state/domain/service/AuthService.kt`

---

## Phase 3: Database Migrations

Must come before persistence adapters. Migrations are additive and do not affect existing functionality.

- [x] **3.1 Add V13 migration: Create `vendors` table**
  Create `vendors` table with columns: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `email TEXT NOT NULL UNIQUE`, `password TEXT NOT NULL`, `candidates TEXT[] NOT NULL DEFAULT '{}'`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `deleted_at TIMESTAMPTZ`. No foreign keys to other tables.
  - File: `src/main/resources/db/migration/V13__create_vendors_table.sql`

- [x] **3.2 Add V14 migration: Recreate `vendor_audit` table**
  Drop existing `vendor_audit` table (currently created by V11 with columns: `id BIGSERIAL`, `username TEXT`, `timestamp TIMESTAMPTZ`, `operation TEXT`, `version_data JSONB` plus indexes on `username`, `timestamp DESC`, `operation`, and GIN on `version_data`) and recreate with: `id BIGSERIAL PRIMARY KEY`, `vendor_id UUID NOT NULL` (no FK -- admin uses nil UUID sentinel), `email TEXT NOT NULL`, `timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `operation TEXT NOT NULL`, `version_data JSONB NOT NULL`. Add indexes on `vendor_id`, `email`, `timestamp DESC`, `operation`, and GIN index on `version_data`. Service is not yet live, so no data loss concern.
  - File: `src/main/resources/db/migration/V14__recreate_vendor_audit_table.sql`

---

## Phase 4: Secondary Adapters (Persistence) + Audit Port Update

Depends on Phase 2 (ports) and Phase 3 (migrations). The `AuditRepository` port signature change is bundled here so the compilation break is resolved within a single phase.

- [x] **4.1 Create `PostgresVendorRepository`**
  Implement `VendorRepository` port with Exposed ORM. Define `VendorsTable` Exposed table object mapping all columns including the PostgreSQL `TEXT[]` array for `candidates`. Implement all methods: `findByEmail`, `findAll` (filter on `deleted_at IS NULL` unless `includeDeleted`), `upsert` (insert or update by email with resurrection -- clear `deleted_at`, update `updated_at`, regenerate password; return `Pair<Vendor, Boolean>` indicating created vs updated), `softDelete` (set `deleted_at`, return `None` if not found or already deleted), `findById`.
  - File: `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresVendorRepository.kt`

- [x] **4.2 Update `AuditRepository` port, `PostgresAuditRepository`, and service callers atomically**
  Change `AuditRepository.recordAudit(username: String, ...)` to `recordAudit(vendorId: UUID, email: String, ...)`. Simultaneously update `PostgresAuditRepository` (replace `username` column mapping with `vendorId` uuid and `email` text columns in `AuditTable`), `VersionServiceImpl`, `TagServiceImpl`, and their port interfaces (`VersionService`, `TagService`) to pass `vendorId: UUID` and `email: String` instead of `username: String`. This is a single atomic commit that keeps the codebase compiling. Specifically: `VersionService.createOrUpdate(version, username)` -> `createOrUpdate(version, vendorId: UUID, email: String)`, `VersionService.delete(uniqueVersion, username)` -> `delete(uniqueVersion, vendorId: UUID, email: String)`, `TagService.deleteTag(uniqueTag, username)` -> `deleteTag(uniqueTag, vendorId: UUID, email: String)`. Note: `TagService.replaceTags` does not take audit params (audit is handled by the version create flow via `VersionServiceImpl.logAudit`).
  - Files: `src/main/kotlin/io/sdkman/state/domain/repository/AuditRepository.kt`, `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresAuditRepository.kt`, `src/main/kotlin/io/sdkman/state/domain/service/VersionService.kt`, `src/main/kotlin/io/sdkman/state/domain/service/TagService.kt`, `src/main/kotlin/io/sdkman/state/application/service/VersionServiceImpl.kt`, `src/main/kotlin/io/sdkman/state/application/service/TagServiceImpl.kt`

---

## Phase 5: Application Layer

Depends on Phase 4 (repositories and updated audit signatures).

- [x] **5.1 Create `RateLimiter` component**
  Create a standalone `RateLimiter` class in `application/service/` with a `ConcurrentHashMap<String, MutableList<Instant>>` tracking per-IP timestamps. Expose `isRateLimited(clientIp: String): Boolean` (returns true if >= 5 attempts in the last 60 seconds) and `recordAttempt(clientIp: String)`. Include periodic cleanup of expired entries to prevent unbounded memory growth. This isolates rate limiting logic for independent testability and keeps `AuthServiceImpl` focused on authentication.
  - File: `src/main/kotlin/io/sdkman/state/application/service/RateLimiter.kt`

- [x] **5.2 Create `AuthServiceImpl`**
  Implement `AuthService` port. Constructor takes `VendorRepository`, `AppConfig`, and `RateLimiter`. Login logic: (1) check rate limit first via `RateLimiter.isRateLimited(clientIp)`, return `RateLimitExceeded` if exceeded; (2) record attempt via `RateLimiter.recordAttempt(clientIp)`; (3) check admin email first (compare against `AppConfig.adminEmail`), verify against BCrypt-hashed admin password held in memory (hashed once at construction time, cost factor 12); (4) if not admin, query `VendorRepository.findByEmail`; (5) if vendor found and not soft-deleted, BCrypt verify; (6) if email not found or soft-deleted, BCrypt verify against a dummy hash (same cost factor 12) to ensure constant-time behaviour. JWT creation with `com.auth0:java-jwt` using HS256: claims `iss: "sdkman-state"`, `aud: "sdkman-state"`, `sub: email`, `role`, `vendor_id` (for vendors, nil UUID for admin), `candidates` (for vendors), `iat`, `exp`. Note: JWT token creation uses `com.auth0:java-jwt` directly in the application layer -- accepted pragmatic exception to hexagonal purity since JWT is a stateless transport concern with no infrastructure side effects.
  - File: `src/main/kotlin/io/sdkman/state/application/service/AuthServiceImpl.kt`

---

## Phase 6: Primary Adapters (REST / Auth Config)

Depends on Phase 5 (application services) and Phase 1.3 (config).

- [x] **6.1 Add JWT authentication configuration to `Authentication.kt`**
  Add `configureJwtAuthentication(config: AppConfig)` alongside the existing `configureBasicAuthentication()` (do NOT remove basic auth yet — it is still called by `Application.kt` and test `Application.kt`; removal happens in Phase 7.1). The new function installs the Ktor JWT authentication plugin with name `"auth-jwt"`. Configure HS256 verification using `com.auth0:java-jwt`'s `Algorithm.HMAC256(config.jwtSecret)`. Validate issuer (`"sdkman-state"`) and audience (`"sdkman-state"`). The verifier must accept only HS256 and reject other algorithms including `none`.
  - File: `src/main/kotlin/io/sdkman/state/config/Authentication.kt`

- [x] **6.2 Create admin route DTOs**
  Create request/response DTOs for admin endpoints with `@Serializable`: `LoginRequest(email: String, password: String)`, `LoginResponse(token: String)`, `CreateVendorRequest(email: String, candidates: Option<List<String>>)` -- Arrow `Option` serializes as absent/null in JSON via `OptionSerializer`, so omitting `candidates` in the request means `None` (keep existing), while providing `candidates: []` is an explicit empty list (rejected with 400). Also: `VendorResponse(id: String, email: String, candidates: List<String>, createdAt: String, updatedAt: String, deletedAt: Option<String>)`, `VendorWithPasswordResponse` (includes `password: String` for create/update only). The file requires `@file:UseSerializers(OptionSerializer::class)` since multiple DTOs use Arrow `Option` fields. Place in `adapter/primary/rest/dto/`.
  - File: `src/main/kotlin/io/sdkman/state/adapter/primary/rest/dto/AdminDto.kt`

- [x] **6.3 Add JWT-aware helpers to `RequestExtensions.kt`**
  Add new JWT-aware helper functions alongside the existing `authenticatedUsername()` (do NOT remove it yet — callers in `VersionRoutes.kt` and `TagRoutes.kt` still reference it; removal happens in Phase 6.5): `authenticatedVendorId(): UUID` (from `vendor_id` claim, nil UUID for admin), `authenticatedEmail(): String` (from `sub` claim), `authenticatedRole(): String` (from `role` claim), `authenticatedCandidates(): List<String>` (from `candidates` claim, empty for admin). Extract from `JWTPrincipal` payload claims.
  - File: `src/main/kotlin/io/sdkman/state/adapter/primary/rest/RequestExtensions.kt`

- [x] **6.4 Create `AdminRoutes.kt`**
  Implement admin endpoints: `POST /admin/login` (public, not behind authenticate block -- validate request, call `authService.login` passing client IP, map `AuthError` to HTTP status: `InvalidCredentials` -> 401, `RateLimitExceeded` -> 429, `TokenCreationFailed` -> 500). `GET /admin/vendors` (behind JWT auth, admin role check -- call `vendorRepo.findAll`, query param `include_deleted`). `POST /admin/vendors` (admin role check -- validate email with RFC regex, reject admin email collision with 400, reject explicitly provided empty candidates `[]` with 400 but allow omitted candidates, generate 32-byte `SecureRandom` base64 password, BCrypt hash it cost 12, call `vendorRepo.upsert`, return 201 if created or 200 if updated with plaintext password). `DELETE /admin/vendors/{id}` (admin role check -- parse UUID, call `vendorRepo.softDelete`, 404 if not found or already deleted). Admin role check: verify `role` claim is `"admin"`, return 401 otherwise (no info leakage about admin-only endpoints).
  - File: `src/main/kotlin/io/sdkman/state/adapter/primary/rest/AdminRoutes.kt`

- [x] **6.5 Add candidate authorization to write routes and remove old `authenticatedUsername()`**
  In `VersionRoutes.kt` and `TagRoutes.kt`, replace `call.authenticatedUsername()` with JWT claim extraction (`authenticatedVendorId()`, `authenticatedEmail()`, `authenticatedRole()`, `authenticatedCandidates()`). Add authorization logic: if role is `"vendor"`, verify the requested candidate is in the token's `candidates` claim list; return 403 Forbidden with `ErrorResponse("Forbidden", "Not authorized for candidate: $candidate")` if unauthorized. For `TagRoutes.kt`, the candidate must be extracted from the parsed `UniqueTagDto` request body before the authorization check. Admin role bypasses candidate check. Pass `vendorId` and `email` to service methods instead of `username`. Now that all callers are migrated, remove the old `authenticatedUsername()` function and its `UserIdPrincipal` import from `RequestExtensions.kt`.
  - Files: `src/main/kotlin/io/sdkman/state/adapter/primary/rest/VersionRoutes.kt`, `src/main/kotlin/io/sdkman/state/adapter/primary/rest/TagRoutes.kt`, `src/main/kotlin/io/sdkman/state/adapter/primary/rest/RequestExtensions.kt`

- [x] **6.6 Update `Routing.kt` to wire JWT auth and admin routes**
  Add `authService: AuthService`, `vendorRepository: VendorRepository`, and `appConfig: AppConfig` parameters to `configureRouting`. Replace `authenticate("auth-basic")` with `authenticate("auth-jwt")`. Add admin routes: `POST /admin/login` outside the authenticate block (public endpoint), `GET/POST/DELETE /admin/vendors*` inside the JWT authenticate block. Pass `authService`, `vendorRepository`, and `appConfig` to admin routes.
  - File: `src/main/kotlin/io/sdkman/state/adapter/primary/rest/Routing.kt`

---

## Phase 7: Application Wiring and Cleanup

Depends on Phase 6 (all adapters and config ready).

- [x] **7.1 Update main and test `Application.kt` module wiring, remove Basic Auth**
  **Main:** Replace `configureBasicAuthentication(appConfig)` with `configureJwtAuthentication(appConfig)`. Instantiate `PostgresVendorRepository`, `RateLimiter`, and `AuthServiceImpl(vendorRepo, appConfig, rateLimiter)`. Pass `authService`, `vendorRepository`, and `appConfig` to `configureRouting`. The `AuthServiceImpl` constructor BCrypt-hashes the admin password once at startup.
  **Test:** Update the test `Application.kt` to match the new `configureRouting` signature. Replace `configureBasicAuthentication(appConfig)` with `configureJwtAuthentication(appConfig)`. Add `admin.email`, `admin.password`, `jwt.secret`, `jwt.expiry` to `testApplicationConfig()` with placeholder test values. Instantiate `PostgresVendorRepository`, `RateLimiter`, and `AuthServiceImpl`. Pass all new parameters to `configureRouting`. This ensures both main and test code compile atomically after `configureRouting`'s signature change in Phase 6.6.
  **Cleanup:** Remove the now-unused `configureBasicAuthentication()` function from `Authentication.kt` (added in Phase 6.1 alongside `configureJwtAuthentication()`, now safe to remove since no callers remain).
  - Files: `src/main/kotlin/io/sdkman/state/Application.kt`, `src/test/kotlin/io/sdkman/state/support/Application.kt`, `src/main/kotlin/io/sdkman/state/config/Authentication.kt`

- [x] **7.2 Remove old Basic Auth config**
  Remove the `api { username, password }` block from `application.conf` (keep `api.cache.control`). Remove `authUsername`/`authPassword` from `AppConfig` interface and `DefaultAppConfig`.
  - Files: `src/main/resources/application.conf`, `src/main/kotlin/io/sdkman/state/config/AppConfig.kt`

---

## Phase 8: OpenAPI Specification

Can proceed after API changes are finalised.

- [ ] **8.1 Replace `basicAuth` with `bearerAuth` security scheme**
  Remove `basicAuth` security scheme. Add `bearerAuth` scheme with `type: http`, `scheme: bearer`, `bearerFormat: JWT`. Update security references on all existing protected endpoints (`POST /versions`, `DELETE /versions`, `DELETE /versions/tags`).
  - File: `src/main/resources/openapi/documentation.yaml`

- [ ] **8.2 Document `POST /admin/login` endpoint**
  Add path with request body schema (`LoginRequest`), `200` response (`LoginResponse`), `401 Unauthorized`, and `429 Too Many Requests` error responses. No security scheme (public endpoint).
  - File: `src/main/resources/openapi/documentation.yaml`

- [ ] **8.3 Document admin vendor management endpoints**
  Add `GET /admin/vendors` (with `include_deleted` query param, `200` response with array of `VendorResponse`), `POST /admin/vendors` (request body `CreateVendorRequest`, `201`/`200` response with `VendorWithPasswordResponse`, `400` for validation errors), `DELETE /admin/vendors/{id}` (`200` response with `VendorResponse`, `404`). All secured with `bearerAuth`. Document `401` on all.
  - File: `src/main/resources/openapi/documentation.yaml`

- [ ] **8.4 Add `403 Forbidden` responses to existing write endpoints**
  Update `POST /versions`, `DELETE /versions`, `DELETE /versions/tags` to document `403` response for candidate authorization failures when vendor token lacks the required candidate.
  - File: `src/main/resources/openapi/documentation.yaml`

---

## Phase 9: Test Infrastructure

Depends on Phase 6.1 (JWT auth config). Must be done before test migration.

- [x] **9.1 Create `JwtTestSupport` utility**
  Create test utility that generates: valid admin tokens (role `"admin"`, sub `"admin@sdkman.io"`, `vendor_id` as nil UUID), valid vendor tokens (with specific `vendor_id`, `candidates` list, role `"vendor"`), expired tokens, and tokens signed with a wrong secret. Use `com.auth0:java-jwt` with a known test secret (e.g., `"test-jwt-secret"`). Define the test secret as a constant. Include a helper for generating tokens with custom claims for edge-case tests.
  - File: `src/test/kotlin/io/sdkman/state/support/JwtTestSupport.kt`

- [x] **9.2 Align test `Application.kt` config with `JwtTestSupport` constants**
  Update `testApplicationConfig()` values set in Phase 7.1 to use the canonical test constants from `JwtTestSupport`: set `jwt.secret` to `JwtTestSupport.TEST_SECRET`, `admin.email` to `"admin@sdkman.io"`, `admin.password` to `"testadminpassword"`. Remove `api.username`/`api.password` (no longer needed after Phase 7.2). Keep `api.cache.control`. This ensures acceptance tests can use tokens from `JwtTestSupport` that are validated by the test app's JWT verifier.
  - File: `src/test/kotlin/io/sdkman/state/support/Application.kt`

- [x] **9.3 Update test `Postgres.kt` support for new audit schema**
  Update `VendorAuditRecord` data class: replace `username: String` with `vendorId: UUID` and `email: String`. Note: the existing `timestamp` field uses `kotlin.time.Instant` -- maintain this convention (use the `toKotlinTimeInstant()` converter from `PostgresConnectivity.kt` or equivalent inline conversion). Update `selectAuditRecords()`, rename `selectAuditRecordsByUsername()` to `selectAuditRecordsByEmail()`, update `selectAuditRecordsByOperation()` -- all to read `vendor_id` and `email` from the new `AuditTable` columns. Update `withCleanDatabase` to also clear the `vendors` table (via `VendorsTable`). Add helper functions: `insertVendor(vendor)` and `selectVendors()` for test setup/verification.
  - File: `src/test/kotlin/io/sdkman/state/support/Postgres.kt`

---

## Phase 10: Update Existing Tests

Depends on Phase 9 (test infrastructure). All existing acceptance tests that use Basic Auth must switch to JWT Bearer tokens. Read-only endpoint tests (`GetVersionsAcceptanceSpec`, `GetVersionAcceptanceSpec`, `GetVersionTagsAcceptanceSpec`, `HealthCheckAcceptanceSpec`) do not use authentication and require no auth-related changes -- they will continue to pass once the test `Application.kt` is rewired in Phase 9.2.

- [x] **10.1 Update `PostVersionAcceptanceSpec` to use JWT auth**
  Replace `basicAuth("testuser", "password123")` / `BASIC_AUTH_HEADER` headers with `bearerAuth(adminToken)` using token from `JwtTestSupport`. Update audit assertions to check `vendorId` and `email` instead of `username`.
  - File: `src/test/kotlin/io/sdkman/state/acceptance/PostVersionAcceptanceSpec.kt`

- [x] **10.2 Update `IdempotentPostVersionAcceptanceSpec` to use JWT auth**
  Replace Basic Auth header with JWT Bearer token from `JwtTestSupport`.
  - File: `src/test/kotlin/io/sdkman/state/acceptance/IdempotentPostVersionAcceptanceSpec.kt`

- [x] **10.3 Update `DeleteVersionAcceptanceSpec` to use JWT auth**
  Replace Basic Auth header with JWT Bearer token. Update audit assertions for new schema.
  - File: `src/test/kotlin/io/sdkman/state/acceptance/DeleteVersionAcceptanceSpec.kt`

- [x] **10.4 Update `DeleteTaggedVersionAcceptanceSpec` to use JWT auth**
  Replace Basic Auth header with JWT Bearer token.
  - File: `src/test/kotlin/io/sdkman/state/acceptance/DeleteTaggedVersionAcceptanceSpec.kt`

- [x] **10.5 Update `DeleteTagAcceptanceSpec` to use JWT auth**
  Replace Basic Auth header with JWT Bearer token. Update audit assertions for new schema.
  - File: `src/test/kotlin/io/sdkman/state/acceptance/DeleteTagAcceptanceSpec.kt`

- [x] **10.6 Update `PostVersionVisibilityAcceptanceSpec` to use JWT auth**
  Replace Basic Auth header with JWT Bearer token.
  - File: `src/test/kotlin/io/sdkman/state/acceptance/PostVersionVisibilityAcceptanceSpec.kt`

- [x] **10.7 Update `PostVersionTagsAcceptanceSpec` to use JWT auth**
  Replace Basic Auth header with JWT Bearer token.
  - File: `src/test/kotlin/io/sdkman/state/acceptance/PostVersionTagsAcceptanceSpec.kt`

- [x] **10.8 Update `VendorAuditAcceptanceSpec` for new audit schema**
  Update assertions to check `vendorId` (nil UUID for admin) and `email` columns instead of `username`. Use JWT Bearer token. Update `selectAuditRecordsByEmail()` calls.
  - File: `src/test/kotlin/io/sdkman/state/acceptance/VendorAuditAcceptanceSpec.kt`

- [x] **10.9 Update `PostgresAuditRepositoryIntegrationSpec` for new schema**
  Update test to pass `vendorId: UUID` and `email: String` instead of `username`. Update assertions for new `vendor_id` and `email` columns in `AuditTable`.
  - File: `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresAuditRepositoryIntegrationSpec.kt`

- [x] **10.10 Update `VersionServiceUnitSpec` for new audit parameters**
  Update mock expectations: `auditRepo.recordAudit` now expects `(vendorId: UUID, email: String, ...)` instead of `(username, ...)`. Update `versionService.createOrUpdate` and `delete` calls to pass `vendorId` and `email`.
  - File: `src/test/kotlin/io/sdkman/state/application/service/VersionServiceUnitSpec.kt`

- [x] **10.11 Update `TagServiceUnitSpec` for new audit parameters**
  Update mock expectations: `auditRepo.recordAudit` now expects `(vendorId: UUID, email: String, ...)`. Update `tagService.deleteTag` calls to pass `vendorId` and `email`.
  - File: `src/test/kotlin/io/sdkman/state/application/service/TagServiceUnitSpec.kt`

---

## Phase 11: New Integration Tests

Depends on Phase 4 (persistence adapters) and Phase 9 (test infrastructure).

- [ ] **11.1 Add `PostgresVendorRepositoryIntegrationSpec`**
  Test all `VendorRepository` methods against Testcontainers Postgres: create new vendor via `upsert`, `findByEmail` returns the vendor, `findById` returns the vendor, `findAll(includeDeleted=false)` excludes deleted, `findAll(includeDeleted=true)` includes deleted, `softDelete` sets `deleted_at`, `softDelete` on already-deleted returns `None`, `upsert` on existing vendor updates password and candidates, `upsert` on soft-deleted vendor resurrects (clears `deleted_at`), `findByEmail` on non-existent returns `None`.
  - File: `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresVendorRepositoryIntegrationSpec.kt`

---

## Phase 12: New Unit Tests

Depends on Phase 5 (application services).

- [ ] **12.1 Add `RateLimiterUnitSpec`**
  Test rate limiter in isolation: first 5 attempts within 1 minute are allowed, 6th attempt is rate-limited, attempts after 60 seconds reset the window, different IPs are tracked independently, concurrent access does not corrupt state.
  - File: `src/test/kotlin/io/sdkman/state/application/service/RateLimiterUnitSpec.kt`

- [ ] **12.2 Add `AuthServiceImplUnitSpec`**
  Test login logic with MockK for `VendorRepository` and `RateLimiter`: admin login success (correct email + password -> JWT with role `"admin"`), vendor login success (correct email + password -> JWT with role `"vendor"` and `candidates`), wrong password for admin returns `InvalidCredentials`, wrong password for vendor returns `InvalidCredentials`, non-existent email returns `InvalidCredentials` (constant-time -- still performs BCrypt compare), soft-deleted vendor returns `InvalidCredentials`, rate limiting (mock `RateLimiter.isRateLimited` returns true -> `RateLimitExceeded`), generated JWT contains correct claims (`iss`, `aud`, `sub`, `role`, `vendor_id`, `candidates`, `iat`, `exp`).
  - File: `src/test/kotlin/io/sdkman/state/application/service/AuthServiceImplUnitSpec.kt`

---

## Phase 13: New Acceptance Tests

Depends on Phase 9 (test infrastructure) and Phase 10 (existing tests updated).

- [ ] **13.1 Add admin login acceptance tests**
  Happy paths: admin login returns 200 with valid JWT containing `role: "admin"`, `sub: admin email`; vendor login returns 200 with valid JWT containing `role: "vendor"`, `candidates` claim, `vendor_id` claim.
  Unhappy paths: wrong password returns 401, non-existent email returns 401, soft-deleted vendor returns 401, rate limit exceeded (6th attempt) returns 429.
  - File: `src/test/kotlin/io/sdkman/state/acceptance/AdminLoginAcceptanceSpec.kt`

- [ ] **13.2 Add vendor management acceptance tests**
  Happy paths: list vendors (empty) returns 200 with `[]`, create vendor returns 201 with generated password, list vendors (populated) returns 200 with vendor details (no password), update vendor returns 200 with regenerated password, soft delete returns 200 with `deleted_at`, list with `include_deleted=true` includes deleted vendor, list without filter excludes deleted vendor, resurrect soft-deleted vendor via `POST /admin/vendors` returns 200 with `deleted_at` cleared and new password.
  Unhappy paths: all admin endpoints without token return 401, all admin endpoints with vendor token return 401, create with invalid email returns 400, create with empty candidates `[]` returns 400, create with admin email returns 400, delete non-existent returns 404, delete already-deleted returns 404.
  - File: `src/test/kotlin/io/sdkman/state/acceptance/AdminVendorManagementAcceptanceSpec.kt`

- [ ] **13.3 Add vendor authorization acceptance tests**
  Happy paths: vendor token can `POST /versions` for authorized candidate (204), vendor token can `DELETE /versions` for authorized candidate (204), vendor token can `DELETE /versions/tags` for authorized candidate (204), admin token can access any candidate (204).
  Unhappy paths: vendor token returns 403 for unauthorized candidate on `POST /versions`, `DELETE /versions`, `DELETE /versions/tags`.
  - File: `src/test/kotlin/io/sdkman/state/acceptance/VendorAuthorizationAcceptanceSpec.kt`

- [ ] **13.4 Add token validation acceptance tests**
  Unhappy paths: request without token returns 401, expired token returns 401, token with invalid signature returns 401.
  - File: `src/test/kotlin/io/sdkman/state/acceptance/TokenValidationAcceptanceSpec.kt`

---

## Phase 14: Final Validation

- [ ] **14.1 Run full validation chain**
  Execute `./gradlew check` to verify compile, detekt, ktlintCheck, and all tests pass. Fix any issues that arise.

---

## Dependency Graph

```
Phase 1 (Config) ─────────────────────────────────────────────────┐
Phase 2 (Domain) ──────────┬──────────────────────────────────────┤
Phase 3 (Migrations) ──────┤                                      │
                            v                                      │
Phase 4 (Persistence+Audit)┬──────────────────────────────────────┤
                            v                                      │
Phase 5 (Application) ─────┤                                      │
                            v                                      v
Phase 6 (REST/Auth) ────────┬── Phase 8 (OpenAPI) ───────────────>|
                            v                                      │
Phase 7 (Wiring/Cleanup) ──>|                                      │
                            v                                      │
Phase 9 (Test Infra) ──────┬──────────────────────────────────────┤
                            v                                      │
Phase 10 (Update Tests) ───┤                                      │
Phase 11 (Integration) ────┤                                      │
Phase 12 (Unit Tests) ─────┤                                      │
Phase 13 (Acceptance) ─────┤                                      │
                            v                                      │
Phase 14 (Validation) <────────────────────────────────────────────┘
```

## Notes on Ordering and Dependencies

### Audit port signature change in Phase 4.2
The `AuditRepository` port signature change (`username` -> `vendorId` + `email`) is bundled with the persistence adapter update and all service callers (`VersionService`, `TagService`, `VersionServiceImpl`, `TagServiceImpl`) in a single atomic commit. This avoids a compilation break spanning multiple phases -- the old Phase 2.5 approach would have left the codebase non-compiling across Phases 2-5. The trade-off is a larger commit in Phase 4.2, but it touches only signatures and parameter forwarding (no new logic).

### Rate limiting isolation
The `RateLimiter` is extracted as a standalone component (Phase 5.1) rather than being embedded in `AuthServiceImpl` (Phase 5.2). This enables independent unit testing (Phase 12.1) and cleaner separation of concerns. `AuthServiceImpl` depends on `RateLimiter`, so 5.1 must precede 5.2.

### JWT creation in application layer
`AuthServiceImpl` creates JWT tokens using `com.auth0:java-jwt` directly. Strictly, this is an infrastructure dependency in the application layer. This is an accepted pragmatic exception -- JWT creation is a pure computation (no I/O, no side effects) that doesn't warrant a separate port/adapter pair. If this becomes problematic, a `TokenService` port with a `JwtTokenService` adapter can be introduced later.

### Password generation in AdminRoutes
The password generation logic (32-byte `SecureRandom` + base64 + BCrypt hash) is placed in `AdminRoutes.kt` (primary adapter). This is business logic in an adapter, which is a minor hexagonal boundary violation. It's accepted because: (1) the logic is a one-liner using standard library calls, (2) introducing a `VendorService` for this alone would be over-engineering, (3) the `VendorRepository.upsert` already accepts the hashed password, so the adapter merely prepares the input.

### Additive-then-remove pattern for auth migration
Phase 6 follows an additive-then-remove approach to avoid compilation breaks during the Basic Auth to JWT transition. Phase 6.1 adds `configureJwtAuthentication()` alongside `configureBasicAuthentication()`. Phase 6.3 adds JWT-aware helper functions alongside `authenticatedUsername()`. Phase 6.5 migrates all callers and removes `authenticatedUsername()`. Phase 7.1 switches the wiring and removes `configureBasicAuthentication()`. This ensures the codebase compiles at every commit boundary.

### Test infrastructure before test migration
Phase 9 (test infrastructure) must complete before Phase 10 (updating existing tests). Phase 7.1 handles the initial wiring of the test `Application.kt` (JWT auth plugin, new `configureRouting` parameters) to maintain compilation. Phase 9.2 then aligns the test config values with `JwtTestSupport` constants so that test-generated tokens are accepted by the test app's JWT verifier.

### Read-only endpoint tests unaffected
`GetVersionsAcceptanceSpec`, `GetVersionAcceptanceSpec`, `GetVersionTagsAcceptanceSpec`, and `HealthCheckAcceptanceSpec` test unauthenticated read endpoints. They require no auth-related changes and are not listed in Phase 10.

### Timestamp conventions
The domain model uses `java.time.Instant` (consistent with Exposed ORM column types). Test support code uses `kotlin.time.Instant` for assertions -- the `toKotlinTimeInstant()` converter in `PostgresConnectivity.kt` bridges the two.

### Flyway PostgreSQL module
The V13 migration uses `gen_random_uuid()` which is a PostgreSQL built-in function (available since PostgreSQL 13). The project uses Flyway 12.1.1 with the `flyway-database-postgresql` module already present in `build.gradle.kts`. No additional Flyway dependencies are needed.

## Summary

| Phase | Tasks | Description |
|-------|-------|-------------|
| 1 | 3 | Dependencies and configuration |
| 2 | 4 | Domain models, errors, and port interfaces |
| 3 | 2 | Database migrations |
| 4 | 2 | Persistence adapters + audit port/signature update |
| 5 | 2 | Application services (auth + rate limiter) |
| 6 | 6 | REST adapters, JWT auth config, admin routes |
| 7 | 2 | Application wiring and Basic Auth removal |
| 8 | 4 | OpenAPI specification |
| 9 | 3 | Test infrastructure |
| 10 | 11 | Update existing tests for JWT auth |
| 11 | 1 | New integration tests (VendorRepository) |
| 12 | 2 | New unit tests (RateLimiter, AuthServiceImpl) |
| 13 | 4 | New acceptance tests (login, vendor mgmt, authorization, token validation) |
| 14 | 1 | Final validation |
| **Total** | **47** | |
