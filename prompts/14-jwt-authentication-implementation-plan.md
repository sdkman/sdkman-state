# JWT Authentication — Implementation Plan

## Reference

- **Specification**: `specs/jwt-authentication.md`

The spec is the source of truth for domain model, endpoint contracts, database schema, JWT claims structure, configuration, and access matrix. Refer to it for any details not repeated here.

## Rules

**Before implementing each slice, you MUST read and internalize:**
- rules/kotlin.md
- rules/domain-driven-design.md
- rules/kotest.md
- rules/hexagonal-architecture.md

**If this plan conflicts with the rules, THE RULES WIN.**

## Slices

---

### - [ ] Slice 1: Database Migrations

**Goal:** Create the `users` table and recreate the `vendor_audit` table with JWT-aware columns.

**Spec reference:** Database Schema (V13 users, V14 vendor_audit recreation)

**Files to create:**
- `src/main/resources/db/migration/V13__create_users_table.sql`
- `src/main/resources/db/migration/V14__recreate_vendor_audit_table.sql`

**Implementation steps:**
1. Create `V13__create_users_table.sql`:
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
2. Create `V14__recreate_vendor_audit_table.sql`:
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
3. Run the build to verify migrations apply cleanly against PostgreSQL.

**Tests to write:**
- No new test files — migration correctness is verified by the build and by existing tests that call `withCleanDatabase` (which runs Flyway).

**Acceptance criteria:**
- [ ] V13 and V14 migrations exist and apply without errors
- [ ] Build passes (`./gradlew build`)
- [ ] Existing tests still pass (the `withCleanDatabase` helper in `src/test/kotlin/io/sdkman/support/Postgres.kt` will need the `VendorAuditTable` object and `withCleanDatabase` function updated to reflect the new column names — `username` becomes `user_email`, and `user_id UUID` is added. Also update `selectAuditRecords`, `selectAuditRecordsByUsername` and related helpers)

---

### - [ ] Slice 2: Configuration & Security Domain Model

**Goal:** Replace `ApiAuthenticationConfig` with the new security config classes, add the `User` sealed class, `AuthContext`, value objects, and update `AuditRecord`/`AuditRepository` to use JWT identity. Wire new config into `Application.kt`.

**Spec reference:** Domain (User Model, Auth Context, Value Objects, Updated Audit Record), Configuration (Removed/Added/ApplicationConfig Changes)

**Files to modify:**
- `src/main/kotlin/io/sdkman/config/ApplicationConfig.kt` — replace `ApiAuthenticationConfig` with `AdminConfig`, `JwtConfig`, `SecurityConfig`; update `ApplicationConfig` and `configureAppConfig()` to read from `security.*` config keys instead of `api.username`/`api.password`
- `src/main/kotlin/io/sdkman/domain/Domain.kt` — add `User` sealed class, `AuthContext`, `ADMIN_USER_ID` constant; update `AuditRecord` (replace `username` with `userId: UUID` and `userEmail: String`); update `AuditRepository.recordAudit` signature to accept `AuthContext` instead of `username: String`
- `src/main/kotlin/io/sdkman/Application.kt` — change `configureBasicAuthentication(appConfig.apiAuthenticationConfig)` to use new security config (temporarily comment out or adapt — full JWT auth plugin comes in Slice 3)
- `src/main/resources/application.conf` — remove `api.username`/`api.password`, add `security.admin.email`/`security.admin.password` and `security.jwt.secret`/`security.jwt.expiry-minutes` with env var overrides

**Files to create:**
- `src/main/kotlin/io/sdkman/domain/Auth.kt` — value objects (`GeneratedPassword`, `PasswordHash`, `JwtSecret`); DTOs (`LoginRequest`, `LoginResponse`, `CreateUserRequest`, `CreateUserResponse`)

**Files to modify (cascading changes):**
- `src/main/kotlin/io/sdkman/repos/AuditRepositoryImpl.kt` — update `VendorAuditTable` object to match new schema (`user_id`, `user_email` instead of `username`); update `recordAudit` to accept `AuthContext` and map `userId`/`email` to the new columns (use `ADMIN_USER_ID` for admin)
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — update `auditRepo.recordAudit(...)` call sites to pass an `AuthContext` instead of `username`. Temporarily construct an `AuthContext` from the basic auth principal to keep things compiling until Slice 3 replaces the auth mechanism.
- `src/main/kotlin/io/sdkman/plugins/Authentication.kt` — temporarily keep basic auth working with the new config shape (it will be fully replaced in Slice 3)
- `src/test/kotlin/io/sdkman/support/Postgres.kt` — update `VendorAuditTable`, `selectAuditRecords`, `selectAuditRecordsByUsername` (rename to `selectAuditRecordsByEmail`), `withCleanDatabase` to match new schema. Add a `Users` table object and helpers: `insertUser`, `selectUserByEmail`, `deleteAllUsers`. Add `users` table cleanup to `withCleanDatabase`.
- `src/test/kotlin/io/sdkman/support/Application.kt` — update `withTestApplication` to work with new config shape
- `src/test/kotlin/io/sdkman/VendorAuditSpec.kt` — update assertions to check `userId` and `userEmail` instead of `username`

**Implementation steps:**
1. Read and internalize all rules files
2. Update `application.conf` — remove old basic auth keys, add security config block
3. Replace config classes in `ApplicationConfig.kt`
4. Add domain types in `Domain.kt` and create `Auth.kt`
5. Update `AuditRepositoryImpl.kt` to match new schema and interface
6. Update `Routing.kt` audit calls — use a temporary `AuthContext` from basic auth principal
7. Update `Authentication.kt` to read from new config path (keep basic auth temporarily)
8. Update `Application.kt` to wire new config
9. Update all test support files
10. Update `VendorAuditSpec.kt`
11. Run `./gradlew build` to verify everything compiles and tests pass

**Tests to write:**
- No new test files — update existing `VendorAuditSpec.kt` to assert on `userId`/`userEmail` instead of `username`

**Acceptance criteria:**
- [ ] `ApiAuthenticationConfig` is gone — replaced by `SecurityConfig` containing `AdminConfig` and `JwtConfig`
- [ ] `application.conf` has `security.admin.*` and `security.jwt.*` with env var overrides
- [ ] `BASIC_AUTH_USERNAME`/`BASIC_AUTH_PASSWORD` env vars are no longer referenced
- [ ] `User` sealed class, `AuthContext`, and value objects exist in domain
- [ ] `AuditRepository.recordAudit` accepts `AuthContext`
- [ ] `AuditRepositoryImpl` writes `user_id` and `user_email` to new schema
- [ ] All existing tests pass
- [ ] Code formatted and ktlint clean

---

### - [ ] Slice 3: JWT Infrastructure & Authentication Plugin

**Goal:** Add JWT dependencies, implement JWT creation/validation services, and replace the Ktor basic auth plugin with JWT authentication. After this slice, authenticated endpoints require a Bearer token.

**Spec reference:** JWT Structure (Signing, Claims, Token Validation), Libraries (Add), Extra Considerations (Impact on Application.kt)

**Files to modify:**
- `build.gradle.kts` — add dependencies: `io.ktor:ktor-server-auth-jwt:$ktor_version`, `com.auth0:java-jwt` (check latest version on Maven Central), `at.favre.lib:bcrypt:0.10.2`
- `src/main/kotlin/io/sdkman/plugins/Authentication.kt` — replace `configureBasicAuthentication` with `configureJwtAuthentication(securityConfig: SecurityConfig)`. Install Ktor's JWT auth provider (`jwt("auth-jwt")`). Configure: verifier (HS256 with `JwtSecret`), issuer/audience validation, principal extraction (build `AuthContext` from claims). The `AuthContext` should be set as the Ktor principal.
- `src/main/kotlin/io/sdkman/Application.kt` — replace `configureBasicAuthentication(appConfig.apiAuthenticationConfig)` with `configureJwtAuthentication(appConfig.securityConfig)`
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — change `authenticate("auth-basic")` to `authenticate("auth-jwt")`. Replace `call.principal<UserIdPrincipal>()` with `call.principal<AuthContext>()` (or whatever wrapper holds the `AuthContext`). Remove the temporary basic auth `AuthContext` construction from Slice 2.

**Files to create:**
- `src/main/kotlin/io/sdkman/security/JwtService.kt` — service for creating and verifying JWTs. Methods: `createToken(user: User, config: SecurityConfig): LoginResponse` (builds claims based on user type — admin vs publisher), and the verifier configuration used by the Ktor plugin. Use `com.auth0:java-jwt` for token creation. Token expiry: `config.jwt.expiryMinutes` (default 10). Claims: see spec for admin vs publisher structure.
- `src/main/kotlin/io/sdkman/security/PasswordService.kt` — service for password operations. Methods: `generatePassword(): GeneratedPassword` (32-char alphanumeric via `SecureRandom`), `hashPassword(password: String): PasswordHash` (BCrypt via `at.favre.lib:bcrypt`), `verifyPassword(password: String, hash: PasswordHash): Boolean`.

**Files to modify (test support):**
- `src/test/kotlin/io/sdkman/support/Application.kt` — update `withTestApplication` to configure JWT auth instead of basic auth. The test config needs `security.jwt.secret` and `security.admin.*` values.
- All test files using `BASIC_AUTH_HEADER` — replace Basic Auth header with a JWT Bearer token. Create a test helper function (e.g. `generateTestJwt(...)`) in `src/test/kotlin/io/sdkman/support/` that creates valid JWTs for tests using the test JWT secret.

**Implementation steps:**
1. Add dependencies to `build.gradle.kts`
2. Create `PasswordService.kt` with password generation and BCrypt hashing
3. Create `JwtService.kt` with token creation (admin and publisher claim structures)
4. Replace `Authentication.kt` — remove basic auth, install JWT auth provider with verifier, audience/issuer validation, and `AuthContext` extraction
5. Update `Application.kt` to call `configureJwtAuthentication`
6. Update `Routing.kt` — change auth block name and principal extraction
7. Create test JWT helper in test support
8. Update all test files to use Bearer tokens instead of Basic Auth headers
9. Run `./gradlew build` — all tests should pass with JWT auth

**Tests to write:**
- Create `src/test/kotlin/io/sdkman/AuthenticationApiSpec.kt` — acceptance tests for the JWT auth mechanism itself:
  - Request to protected endpoint with valid JWT returns success (use POST /versions as the protected endpoint)
  - Request to protected endpoint without Authorization header returns `401`
  - Request with expired JWT returns `401`
  - Request with malformed JWT returns `401`
  - Request with JWT signed by wrong secret returns `401`
  - GET endpoints remain accessible without any auth

**Acceptance criteria:**
- [ ] `build.gradle.kts` includes JWT, java-jwt, and bcrypt dependencies
- [ ] `JwtService` creates tokens with correct claims for admin and publisher
- [ ] `PasswordService` generates 32-char passwords and hashes/verifies with BCrypt
- [ ] Ktor JWT auth plugin validates signature, expiry, issuer, audience
- [ ] `AuthContext` is extracted from JWT claims and available as principal in routes
- [ ] All existing tests pass with JWT auth (no Basic Auth references remain in tests)
- [ ] New auth mechanism tests pass
- [ ] Code formatted and ktlint clean

---

### - [ ] Slice 4: Login Endpoint

**Goal:** Implement `POST /auth/login` for both admin and publisher authentication.

**Spec reference:** Endpoints (POST /auth/login), Extra Considerations (Login Precedence, Password Generation)

**Files to modify:**
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — add `post("/auth/login")` route outside the `authenticate("auth-jwt")` block (public endpoint). Parse `LoginRequest`, implement login precedence: check admin email first, then query `users` table. On success, call `JwtService.createToken()` and return `LoginResponse`. On failure, return `401` with generic `ErrorResponse("Unauthorized", "Invalid credentials")`.

**Files to create:**
- `src/main/kotlin/io/sdkman/repos/UsersRepository.kt` — repository interface and implementation for the `users` table. Methods needed for this slice: `findByEmail(email: String): Either<DatabaseFailure, Option<User.Publisher>>`. Uses Exposed ORM with the `users` table. Maps `candidates TEXT[]` to `List<String>`.

**Files to modify:**
- `src/main/kotlin/io/sdkman/Application.kt` — pass `UsersRepository` (and `JwtService`, `PasswordService`, `SecurityConfig`) to `configureRouting` or create a separate routing function for auth routes
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — update function signature to accept new dependencies
- `src/test/kotlin/io/sdkman/support/Postgres.kt` — add `insertUser` helper that inserts a user with a BCrypt-hashed password (use `PasswordService` or direct BCrypt call), for use in login tests
- `src/test/kotlin/io/sdkman/support/Application.kt` — update `withTestApplication` to pass new dependencies

**Implementation steps:**
1. Create `UsersRepository.kt` with Exposed table mapping and `findByEmail`
2. Update routing to accept new dependencies
3. Add `post("/auth/login")` route with login precedence logic
4. Update `Application.kt` and test support wiring
5. Add test helpers for inserting users
6. Write acceptance tests
7. Run `./gradlew build`

**Tests to write:**
- Create `src/test/kotlin/io/sdkman/LoginApiSpec.kt`:
  - **Happy path:**
    - Admin login with valid credentials returns `200` with JWT containing `admin: true` and empty `candidates`
    - Publisher login with valid credentials returns `200` with JWT containing correct `candidates` and `admin: false`
    - JWT contains expected claims: `iss: sdkman-state`, `aud: sdkman-api`, valid `exp`
    - Token expiry is approximately 10 minutes from issuance
  - **Unhappy path:**
    - Login with unknown email returns `401` with `ErrorResponse("Unauthorized", "Invalid credentials")`
    - Login with wrong password returns `401` with identical error (no enumeration)
    - Login with blank email returns `400`
    - Login with blank password returns `400`

**Acceptance criteria:**
- [ ] `POST /auth/login` authenticates admin via env var comparison
- [ ] `POST /auth/login` authenticates publisher via BCrypt hash verification
- [ ] Admin email is checked before `users` table lookup
- [ ] Error responses are identical for wrong email and wrong password
- [ ] Returned JWT contains correct claims for each user type
- [ ] `UsersRepository.findByEmail` maps `TEXT[]` to `List<String>`
- [ ] All tests pass
- [ ] Code formatted and ktlint clean

---

### - [ ] Slice 5: User Management Endpoints

**Goal:** Implement `POST /admin/users` (idempotent create/update) and `DELETE /admin/users/{id}` (hard delete), both admin-only.

**Spec reference:** Endpoints (POST /admin/users, DELETE /admin/users/{id}), Extra Considerations (Password Generation, Idempotent User Creation)

**Files to modify:**
- `src/main/kotlin/io/sdkman/repos/UsersRepository.kt` — add methods: `createOrUpdate(email: String, passwordHash: PasswordHash, candidates: Option<List<String>>): Either<DatabaseFailure, Pair<User.Publisher, Boolean>>` (returns user + isNew flag), `deleteById(id: UUID): Either<DatabaseFailure, Int>` (returns rows deleted)
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — add admin-only routes inside a new `authenticate("auth-jwt")` block (or nested within existing one with an admin check):
  - `post("/admin/users")`: extract `AuthContext`, verify `isAdmin`, parse `CreateUserRequest`, generate password, hash it, call `createOrUpdate`, return `CreateUserResponse` with `201` (new) or `200` (update)
  - `delete("/admin/users/{id}")`: extract `AuthContext`, verify `isAdmin`, parse UUID from path, call `deleteById`, return `204` or `404`
- `src/main/kotlin/io/sdkman/Application.kt` — ensure dependencies are wired

**Implementation steps:**
1. Add `createOrUpdate` and `deleteById` to `UsersRepository`
2. Add admin routes to `Routing.kt` with admin-only guard (`if (!authContext.isAdmin)` → 403)
3. Wire `PasswordService` into routing for password generation and hashing
4. Idempotent create logic: check if email exists → if yes, update (regenerate password, update candidates if provided, update `updated_at`); if no, insert new row
5. Write acceptance tests
6. Run `./gradlew build`

**Tests to write:**
- Create `src/test/kotlin/io/sdkman/UserManagementApiSpec.kt`:
  - **Happy path:**
    - Create new publisher returns `201` with id, email, candidates, and 32-char alphanumeric password
    - Create with existing email returns `200` with new password (password changes)
    - Update with `candidates` field changes the candidate list
    - Update without `candidates` field preserves existing candidates
    - Created user can log in with the returned password (end-to-end: create → login → verify JWT)
    - Delete existing user returns `204`
    - Deleted user can no longer log in
  - **Unhappy path:**
    - Create user without JWT returns `401`
    - Create user with publisher JWT returns `403`
    - Create user with blank email returns `400`
    - Delete non-existent user returns `404`
    - Delete without JWT returns `401`
    - Delete with publisher JWT returns `403`

**Acceptance criteria:**
- [ ] `POST /admin/users` creates new publisher with generated password and returns `201`
- [ ] `POST /admin/users` with existing email regenerates password and returns `200`
- [ ] Candidates are updated when provided, preserved when omitted
- [ ] `DELETE /admin/users/{id}` hard-deletes the user
- [ ] Both endpoints reject non-admin JWTs with `403`
- [ ] Both endpoints reject missing/invalid JWTs with `401`
- [ ] Generated passwords are 32 alphanumeric characters
- [ ] All tests pass
- [ ] Code formatted and ktlint clean

---

### - [ ] Slice 6: Candidate-Scoped Authorization on Protected Endpoints

**Goal:** Add candidate-scoping authorization to `POST /versions` and `DELETE /versions`. Publishers can only operate on candidates listed in their JWT. Admin bypasses all checks. Update audit recording to use `AuthContext` identity (UUID + email).

**Spec reference:** Endpoints (POST /versions modified, DELETE /versions modified), Extra Considerations (Candidate Authorization on Write Endpoints, Audit Identity Mapping), Access Matrix

**Files to modify:**
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — in the `authenticate("auth-jwt")` block:
  - `post("/versions")`: after receiving and validating the request body, extract the `candidate` field. Call `authContext.canAccessCandidate(candidate)`. If `false`, return `403 Forbidden` with `ErrorResponse("Forbidden", "Not authorized for candidate: $candidate")`. Update `auditRepo.recordAudit(authContext, ...)` to pass the full `AuthContext`.
  - `delete("/versions")`: same candidate check on the `candidate` field from the `UniqueVersion` request body. Same audit update.
- `src/test/kotlin/io/sdkman/VendorAuditSpec.kt` — update to use JWT auth and verify audit records contain correct `userId` (UUID or nil) and `userEmail`

**Implementation steps:**
1. Add candidate authorization check to `post("/versions")` route
2. Add candidate authorization check to `delete("/versions")` route
3. Ensure `AuthContext` is properly passed to audit recording
4. Update `VendorAuditSpec.kt` to verify JWT-based audit identity
5. Write candidate authorization tests
6. Run `./gradlew build`

**Tests to write:**
- Create `src/test/kotlin/io/sdkman/CandidateAuthorizationSpec.kt`:
  - **Happy path:**
    - Publisher with `candidates: ["java"]` can POST a java version → `204`
    - Publisher with `candidates: ["java", "gradle"]` can POST both java and gradle versions
    - Admin can POST any candidate version
    - Admin can DELETE any candidate version
  - **Unhappy path:**
    - Publisher with `candidates: ["java"]` gets `403` when POSTing a gradle version
    - Publisher with `candidates: ["java"]` gets `403` when DELETEing a gradle version
    - `403` response body matches `ErrorResponse("Forbidden", "Not authorized for candidate: gradle")`
- Update `src/test/kotlin/io/sdkman/VendorAuditSpec.kt`:
  - POST /versions by publisher records audit with publisher UUID and email
  - POST /versions by admin records audit with nil UUID and admin email
  - DELETE /versions records audit with correct user identity

**Acceptance criteria:**
- [ ] Publisher can only operate on candidates in their JWT `candidates` claim
- [ ] Admin bypasses candidate checks
- [ ] Unauthorized candidate access returns `403` with descriptive error
- [ ] Audit records contain correct `userId` (UUID for publisher, nil UUID for admin) and `userEmail`
- [ ] All existing POST/DELETE version tests still pass (they should use admin or correctly-scoped publisher JWTs)
- [ ] All tests pass
- [ ] Code formatted and ktlint clean

---

### - [ ] Slice 7: DELETE /versions/tags Endpoint

**Goal:** Implement the `DELETE /versions/tags` route with JWT authentication and candidate-scoped authorization. The domain model (`UniqueTag`, `TagsRepository.deleteTag`) already exists.

**Spec reference:** Endpoints (DELETE /versions/tags), Access Matrix

**Files to modify:**
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — add `delete("/versions/tags")` inside the `authenticate("auth-jwt")` block. Parse `UniqueTag` from request body, validate it, check candidate authorization via `authContext.canAccessCandidate(uniqueTag.candidate)`, call `tagsRepo.deleteTag(uniqueTag)`, return `204` or `404` based on rows deleted. Record audit on successful deletion.

**Implementation steps:**
1. Add `delete("/versions/tags")` route
2. Add request validation (reuse or adapt `UniqueVersionValidator` pattern for `UniqueTag`)
3. Add candidate authorization check
4. Call `tagsRepo.deleteTag()` and map result to response
5. Write acceptance tests
6. Run `./gradlew build`

**Tests to write:**
- Create `src/test/kotlin/io/sdkman/DeleteVersionTagsApiSpec.kt`:
  - **Happy path:**
    - Delete existing tag returns `204`
    - Tag is actually removed from database after deletion
    - Admin can delete tag on any candidate
    - Publisher with correct candidate can delete tag
  - **Unhappy path:**
    - Delete non-existent tag returns `404`
    - Delete without JWT returns `401`
    - Publisher without candidate access returns `403`
    - Blank candidate in request returns `400`
    - Blank tag in request returns `400`

**Acceptance criteria:**
- [ ] `DELETE /versions/tags` route exists and is JWT-protected
- [ ] Candidate-scoped authorization is enforced
- [ ] Successful deletion returns `204`
- [ ] Non-existent tag returns `404`
- [ ] Request validation rejects blank candidate/tag
- [ ] All tests pass
- [ ] Code formatted and ktlint clean

---

## Post-Implementation

- [ ] Run full test suite: `./gradlew build`
- [ ] Verify no references to `auth-basic`, `BASIC_AUTH_USERNAME`, `BASIC_AUTH_PASSWORD`, or `ApiAuthenticationConfig` remain in codebase
- [ ] Verify no `UserIdPrincipal` usage remains
- [ ] Verify all GET endpoints work without authentication
- [ ] Verify health check works without authentication
- [ ] Review `specs/jwt-authentication.md` verification checklist — all items should be satisfied
- [ ] Code formatted and ktlint clean: `./gradlew ktlintFormat`
