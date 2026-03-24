# JWT Authentication — Implementation Plan

> Spec: `specs/jwt-authentication.md`
> Branch: `jwt_authentication`
> Status: **0 / 37 tasks complete** — implementation not yet started

## Legend

- [ ] Not started
- [x] Complete

---

## 1. Dependencies & Configuration

- [ ] **1.1 Add JWT dependencies to `build.gradle.kts`**
  - `at.favre.lib:bcrypt:0.10.2` — BCrypt password hashing
  - `io.ktor:ktor-server-auth-jwt:$ktor_version` — Ktor JWT authentication plugin
  - `com.auth0:java-jwt:4.5.0` — JWT token creation and verification

- [ ] **1.2 Add admin/JWT config to `application.conf`**
  - Add `admin.email` / `admin.password` with env var overrides (`ADMIN_EMAIL`, `ADMIN_PASSWORD`)
  - Add `jwt.secret` (required, from `JWT_SECRET`) and `jwt.expiry` (default 3 minutes, from `JWT_EXPIRY_MINUTES`)
  - Remove `api.username` / `api.password` basic auth config

- [ ] **1.3 Update `config/AppConfig.kt`**
  - Add `adminEmail`, `adminPassword`, `jwtSecret`, `jwtExpiry` properties to `AppConfig` interface
  - Implement in `DefaultAppConfig` — `jwtSecret` must fail fast if missing (no default value)
  - Remove `authUsername` / `authPassword` properties

---

## 2. Database Migrations

- [ ] **2.1 Create `V13__create_vendors_table.sql`**
  - `vendors` table with UUID PK (`gen_random_uuid()`), email (unique), password (BCrypt), candidates (text array), timestamps, soft-delete `deleted_at`
  - Current latest migration: V12 (`V12__create_version_tags_table.sql`)

- [ ] **2.2 Create `V14__recreate_vendor_audit_table.sql`**
  - Drop existing `vendor_audit` table (created in V11) and its indexes
  - Recreate with `vendor_id` (UUID, NOT NULL), `email` (TEXT, NOT NULL) replacing `username`
  - Add indexes on `vendor_id`, `email`, `timestamp DESC`, `operation`, and GIN on `version_data`

---

## 3. Domain Layer

- [ ] **3.1 Create `domain/model/Vendor.kt`**
  - `Vendor` data class: `id: UUID`, `email: String`, `password: String`, `candidates: List<String>`, `createdAt: Instant`, `updatedAt: Instant`, `deletedAt: Option<Instant>`

- [ ] **3.2 Create `domain/repository/VendorRepository.kt`**
  - Port interface with: `findByEmail(email): Either<DatabaseFailure, Option<Vendor>>`, `findById(id): Either<DatabaseFailure, Option<Vendor>>`, `upsert(vendor): Either<DatabaseFailure, Vendor>`, `softDelete(id): Either<DatabaseFailure, Option<Vendor>>`, `findAll(): Either<DatabaseFailure, List<Vendor>>`

- [ ] **3.3 Create `domain/service/AuthService.kt`**
  - Port interface with: `authenticate(email, password): Either<DomainError, TokenResponse>`, `createOrUpdateVendor(email, candidates): Either<DomainError, VendorWithPassword>`, `softDeleteVendor(id): Either<DomainError, Vendor>`, `listVendors(): Either<DomainError, List<Vendor>>`

- [ ] **3.4 Update `domain/error/DomainError.kt`**
  - Add error variants: `Unauthorized(message: String)`, `Forbidden(message: String)`, `VendorNotFound(id: UUID)`
  - Current variants: `VersionNotFound`, `TagConflict`, `TagNotFound`, `ValidationFailed`, `ValidationFailures`, `DatabaseError`

- [ ] **3.5 Create `domain/model/AuditContext.kt`**
  - `AuditContext` data class: `vendorId: UUID`, `email: String`
  - Define nil UUID constant (`00000000-0000-0000-0000-000000000000`) for admin audit entries

- [ ] **3.6 Update `domain/repository/AuditRepository.kt`**
  - Change `recordAudit` signature: replace `username: String` with `context: AuditContext`
  - Current signature: `suspend fun recordAudit(username: String, operation: AuditOperation, data: Auditable)`

- [ ] **3.7 Update `domain/service/VersionService.kt` interface**
  - Change `createOrUpdate(version, username: String)` → `createOrUpdate(version, context: AuditContext)`
  - Change `delete(uniqueVersion, username: String)` → `delete(uniqueVersion, context: AuditContext)`

- [ ] **3.8 Update `domain/service/TagService.kt` interface**
  - Change `deleteTag(uniqueTag, username: String)` → `deleteTag(uniqueTag, context: AuditContext)`

---

## 4. Application Layer

- [ ] **4.1 Create `application/service/AuthServiceImpl.kt`**
  - Implement `AuthService` port
  - Admin login: BCrypt-verify against config password, issue JWT with `role: "admin"`
  - Vendor login: lookup by email (active only — `deleted_at` is NULL), BCrypt verify, issue JWT with `role: "vendor"` + `candidates`
  - Constant-time BCrypt comparison: always perform BCrypt verify even for non-existent/deleted emails using a dummy hash (same cost factor 12)
  - Password generation: 32 bytes from `SecureRandom`, base64-encoded (~43 chars, ~192 bits entropy)
  - BCrypt hashing (cost factor 12) on vendor create/update — hash before passing to repository
  - Vendor CRUD: create/update (idempotent by email, resurrect soft-deleted), soft delete, list all

- [ ] **4.2 Implement JWT token creation**
  - HMAC-SHA256 algorithm via `com.auth0:java-jwt`
  - Claims: `iss`, `aud` = `"sdkman-state"`, `sub` (email), `role`, `candidates` (vendor only), `iat`, `exp`
  - Configurable expiry from `AppConfig.jwtExpiry` (minutes)

- [ ] **4.3 Create `application/validation/VendorRequestValidator.kt`**
  - Validate email against RFC-compliant regex pattern
  - Reject if email matches configured admin email (400 Bad Request)
  - Validate candidates list is not empty when provided

- [ ] **4.4 Update `VersionServiceImpl.kt` audit calls**
  - Change `createOrUpdate` and `delete` to accept `AuditContext` instead of `username: String`
  - Pass `AuditContext` to `AuditRepository.recordAudit()`

- [ ] **4.5 Update `TagServiceImpl.kt` audit calls**
  - Change `deleteTag` to accept `AuditContext` instead of `username: String`
  - Pass `AuditContext` to `AuditRepository.recordAudit()`

---

## 5. Adapter Layer — Secondary (Persistence)

- [ ] **5.1 Create `adapter/secondary/persistence/PostgresVendorRepository.kt`**
  - Implement `VendorRepository` port
  - Exposed `VendorsTable` object mapping to `vendors` table
  - Repository receives pre-hashed passwords (BCrypt hashing is in `AuthServiceImpl`, not here)
  - Upsert: insert or update by email, clear `deleted_at` on resurrect

- [ ] **5.2 Update `adapter/secondary/persistence/PostgresAuditRepository.kt`**
  - Update `AuditTable` columns: replace `username` (TEXT) with `vendor_id` (UUID) + `email` (TEXT)
  - Update `recordAudit` to accept `AuditContext` matching new port signature
  - Current table object references `val username = text("username")` — must change

---

## 6. Adapter Layer — Primary (REST)

- [ ] **6.1 Replace basic auth with JWT in `config/Authentication.kt`**
  - Remove `configureBasicAuthentication()` (currently installs `basic("auth-basic")` scheme)
  - Install JWT authentication plugin with HMAC-SHA256 verification
  - Verify `iss` and `aud` claims = `"sdkman-state"`
  - Accept only HS256 algorithm, reject all others including `none`
  - Create custom principal data class with `vendorId: UUID`, `email: String`, `role: String`, `candidates: List<String>`

- [ ] **6.2 Create `adapter/primary/rest/AdminRoutes.kt`**
  - `POST /admin/login` — public endpoint, authenticate and return JWT token
  - `GET /admin/vendors` — admin-only, list all vendors (no passwords in response)
  - `POST /admin/vendors` — admin-only, create/update vendor, return vendor with generated plaintext password
  - `DELETE /admin/vendors/{id}` — admin-only, soft delete vendor
  - Request/response DTOs: `LoginRequest`, `LoginResponse`, `VendorRequest`, `VendorResponse`, `VendorWithPasswordResponse`

- [ ] **6.3 Update `adapter/primary/rest/Routing.kt`**
  - Replace `authenticate("auth-basic")` with JWT scheme name
  - Add `POST /admin/login` outside authenticated block (public endpoint)
  - Add admin routes within JWT-authenticated block with admin-role guard
  - Add candidate-level authorization for version/tag write endpoints

- [ ] **6.4 Update `adapter/primary/rest/RequestExtensions.kt`**
  - Replace `authenticatedUsername()` (currently extracts from `UserIdPrincipal`) with JWT principal extraction → `AuditContext`
  - Add `authorizeCandidate(candidate)` helper: returns 403 if vendor lacks candidate in claim, passes for admin

- [ ] **6.5 Update `adapter/primary/rest/VersionRoutes.kt`**
  - Add 403 Forbidden check: vendor must have candidate in their `candidates` claim
  - Pass `AuditContext` to service layer instead of `username`
  - Currently calls `call.authenticatedUsername()` and passes to service

- [ ] **6.6 Update `adapter/primary/rest/TagRoutes.kt`**
  - Add 403 Forbidden check for candidate authorization
  - Pass `AuditContext` to service layer instead of `username`
  - Currently calls `call.authenticatedUsername()` and passes to service

- [ ] **6.7 Update error handling in `RequestExtensions.kt`**
  - Add `respondDomainError` cases for `Unauthorized` → 401, `Forbidden` → 403, `VendorNotFound` → 404

---

## 7. Application Entry Point

- [ ] **7.1 Update `Application.kt`**
  - Wire `PostgresVendorRepository` and `AuthServiceImpl`
  - Replace `configureBasicAuthentication(appConfig)` with JWT configuration call
  - Pass `AuthService` to routing configuration

---

## 8. Test Infrastructure

- [ ] **8.1 Create `test/support/JwtTestSupport.kt`**
  - Utility to generate valid, expired, and invalid JWT tokens for test scenarios
  - Helpers: `adminToken()`, `vendorToken(candidates)`, `expiredToken()`, `invalidSignatureToken()`
  - Use a fixed test JWT secret

- [ ] **8.2 Update `test/support/Application.kt`**
  - Add JWT config (`admin.email`, `admin.password`, `jwt.secret`, `jwt.expiry`) to `testApplicationConfig()`
  - Replace `configureBasicAuthentication` with JWT auth configuration
  - Wire `AuthServiceImpl` and `PostgresVendorRepository`
  - Currently configures `api.username` / `api.password` for basic auth

- [ ] **8.3 Update `test/support/Postgres.kt`**
  - Update `VendorAuditRecord` data class: replace `username` with `vendorId: UUID` + `email: String`
  - Rename `selectAuditRecordsByUsername` → `selectAuditRecordsByEmail` / `selectAuditRecordsByVendorId`
  - Add `vendors` table to `withCleanDatabase` cleanup
  - Add helpers: `insertVendor()`, `selectVendors()`, `selectVendorByEmail()`

---

## 9. Acceptance Tests

- [ ] **9.1 Create `acceptance/AdminLoginAcceptanceSpec.kt`**
  - Happy: admin login returns JWT with `role: "admin"` (spec test 4)
  - Happy: vendor login returns JWT with `role: "vendor"` + `candidates` (spec test 3)
  - Unhappy: wrong password → 401 (spec test 1)
  - Unhappy: non-existent email → 401 (spec test 2)
  - Unhappy: soft-deleted vendor → 401 (spec test 3)

- [ ] **9.2 Create `acceptance/AdminVendorManagementAcceptanceSpec.kt`**
  - Happy: list vendors — 200 empty list (spec test 1)
  - Happy: create vendor — 200 with generated password (spec test 2)
  - Happy: list vendors after create — 200 with vendor, no password (spec test 10)
  - Happy: update vendor (idempotent) — 200 new password (spec test 9)
  - Happy: login after password reset — 200 with updated candidates (spec test 11)
  - Happy: soft delete — 200 with `deleted_at` (spec test 12)
  - Happy: list after delete — deleted vendor present with `deleted_at` (spec test 13)
  - Happy: resurrect vendor — 200, `deleted_at` cleared, new password (spec test 14)
  - Unhappy: without token → 401 (spec tests 8-10)
  - Unhappy: with vendor token → 401 (spec tests 9, 11, 16)
  - Unhappy: invalid email → 400 (spec test 12)
  - Unhappy: empty candidates → 400 (spec test 13)
  - Unhappy: admin email → 400 (spec test 14)
  - Unhappy: delete non-existent → 404 (spec test 17)
  - Unhappy: delete already-deleted → 404 (spec test 18)

- [ ] **9.3 Create `acceptance/JwtAuthorizationAcceptanceSpec.kt`**
  - Happy: vendor token POST/DELETE versions for authorized candidate — success (spec tests 5-7)
  - Happy: admin token POST versions for any candidate — success (spec test 8)
  - Unhappy: no token → 401 (spec test 4)
  - Unhappy: expired token → 401 (spec test 5)
  - Unhappy: vendor token for unauthorized candidate → 403 (spec tests 6-7)

- [ ] **9.4 Update existing acceptance tests**
  - Replace `BASIC_AUTH_HEADER` with JWT Bearer tokens in all 12 existing acceptance specs
  - Update `VendorAuditAcceptanceSpec.kt` to verify new audit schema (vendor_id + email instead of username)

---

## 10. Integration Tests

- [ ] **10.1 Create `adapter/secondary/persistence/PostgresVendorRepositoryIntegrationSpec.kt`**
  - Test CRUD operations: create, find by email, find by id, upsert (update existing), soft delete, find all
  - Test resurrect: upsert on soft-deleted vendor clears `deleted_at`
  - Test soft delete idempotency: already-deleted returns `None`

- [ ] **10.2 Update `adapter/secondary/persistence/PostgresAuditRepositoryIntegrationSpec.kt`**
  - Update to use `AuditContext` (vendor_id + email) instead of username

---

## 11. Unit Tests

- [ ] **11.1 Create `application/service/AuthServiceUnitSpec.kt`**
  - Admin authentication (success, wrong password)
  - Vendor authentication (success, wrong password, soft-deleted, not found)
  - Constant-time comparison: verify BCrypt verify is always called (even for missing emails)
  - Vendor CRUD: create, update (idempotent), resurrect, soft delete, list
  - Password generation: verify length and randomness

- [ ] **11.2 Create `application/validation/VendorRequestValidatorSpec.kt`**
  - Valid email passes
  - Invalid email formats rejected
  - Admin email collision rejected
  - Empty candidates rejected

- [ ] **11.3 Update `application/service/VersionServiceUnitSpec.kt`**
  - Update mocked audit calls to use `AuditContext` signature

- [ ] **11.4 Update `application/service/TagServiceUnitSpec.kt`**
  - Update mocked audit calls to use `AuditContext` signature

---

## Implementation Order (Recommended)

1. Dependencies & config (1.x)
2. Database migrations (2.x)
3. Domain layer (3.x) — includes interface changes that cascade
4. Application layer (4.x)
5. Secondary adapters (5.x)
6. Primary adapters — JWT auth + admin routes (6.x)
7. Application wiring (7.x)
8. Test infrastructure (8.x)
9. Acceptance tests (9.x)
10. Integration tests (10.x)
11. Unit tests (11.x)
12. Run `./gradlew check` and fix any lint/detekt/test issues
