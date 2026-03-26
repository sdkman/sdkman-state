# Progress Log

**Append-only log of iterative work. Never edit or remove previous entries.**

## Entry Template

Each entry must follow this structure exactly:

---

### [YYYY-MM-DD HH:mm] — [IMPLEMENTATION_PLAN.md item reference]

**Summary:** One-line description of what was done.

**Files changed:**
- `path/to/file` — brief reason

**Test outcome:** PASS | FAIL (with detail if failed)

**Learnings:**
- _Patterns:_ e.g. "this codebase uses X for Y"
- _Gotchas:_ e.g. "don't forget to update Z when changing W"
- _Context:_ e.g. "the audit table relies on index X for this query pattern"

---

### [2026-03-26 00:01] — Phase 1: Tasks 1.1, 1.2, 1.3

**Summary:** Added JWT and BCrypt dependencies, admin/jwt config blocks, and extended AppConfig with new properties.

**Files changed:**
- `build.gradle.kts` — added `ktor-server-auth-jwt`, `com.auth0:java-jwt:4.5.0`, `at.favre.lib:bcrypt:0.10.2`
- `src/main/resources/application.conf` — added `admin { email, password }` and `jwt { secret, expiry }` config blocks
- `src/main/kotlin/io/sdkman/state/config/AppConfig.kt` — added `adminEmail`, `adminPassword`, `jwtSecret` (lazy getter, fail-fast), `jwtExpiry` to interface and implementation

**Test outcome:** PASS — `./gradlew check` passes (compile, detekt, ktlint, all tests)

**Learnings:**
- _Patterns:_ `DefaultAppConfig` constructor param changed from `config` to `private val config` to support the lazy `jwtSecret` getter
- _Gotchas:_ `jwtSecret` uses a `get()` getter (not an init-time val) so it only fails when accessed, not at construction — this lets existing tests pass without adding `jwt.secret` to test config yet
- _Context:_ Test config (`testApplicationConfig()`) does not yet include `jwt.secret`/`admin.*` entries — deferred to Phase 9.2

---

### [2026-03-26 00:15] — Phase 2: Tasks 2.1, 2.2, 2.3, 2.4

**Summary:** Created all domain layer types for JWT authentication: Vendor model, AuthError sealed interface, VendorRepository port, and AuthService port.

**Files changed:**
- `src/main/kotlin/io/sdkman/state/domain/model/Vendor.kt` — new Vendor data class with UUID id, email, hashedPassword, candidates list, timestamps, and soft-delete support via Option<Instant>
- `src/main/kotlin/io/sdkman/state/domain/error/AuthError.kt` — new AuthError sealed interface with InvalidCredentials, RateLimitExceeded, TokenCreationFailed variants
- `src/main/kotlin/io/sdkman/state/domain/repository/VendorRepository.kt` — new VendorRepository port interface with findByEmail, findAll, upsert, softDelete, findById methods
- `src/main/kotlin/io/sdkman/state/domain/service/AuthService.kt` — new AuthService port interface with login method returning Either<AuthError, String>

**Test outcome:** PASS — `./gradlew check` passes (compile, detekt, ktlint, all tests)

**Learnings:**
- _Patterns:_ Vendor uses `java.util.UUID` and `java.time.Instant` (consistent with Exposed column types), not kotlin.time.Instant — this matches the plan's note about test support converting where needed
- _Patterns:_ AuthError is separate from DomainError since auth is a distinct concern with different error semantics (401/429 vs 400/404/409)
- _Context:_ All Phase 2 items are pure domain types with no infrastructure dependencies — they compile independently and don't affect existing functionality

---

### [2026-03-26 00:30] — Phase 3: Tasks 3.1, 3.2 + Phase 4: Task 4.2

**Summary:** Added V13/V14 database migrations (vendors table, vendor_audit recreation) and atomically updated AuditRepository port from `username: String` to `vendorId: UUID, email: String` across all layers.

**Files changed:**
- `src/main/resources/db/migration/V13__create_vendors_table.sql` — new migration creating `vendors` table with UUID PK, email, password, candidates array, timestamps, soft-delete
- `src/main/resources/db/migration/V14__recreate_vendor_audit_table.sql` — drops V11's `vendor_audit`, recreates with `vendor_id UUID` + `email TEXT` replacing `username TEXT`
- `src/main/kotlin/io/sdkman/state/domain/repository/AuditRepository.kt` — changed `recordAudit(username)` to `recordAudit(vendorId, email)`
- `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresAuditRepository.kt` — updated `AuditTable` columns and `recordAudit` implementation
- `src/main/kotlin/io/sdkman/state/domain/service/VersionService.kt` — `createOrUpdate` and `delete` now take `vendorId: UUID, email: String`
- `src/main/kotlin/io/sdkman/state/domain/service/TagService.kt` — `deleteTag` now takes `vendorId: UUID, email: String`
- `src/main/kotlin/io/sdkman/state/application/service/VersionServiceImpl.kt` — updated to pass vendorId/email through to audit
- `src/main/kotlin/io/sdkman/state/application/service/TagServiceImpl.kt` — updated to pass vendorId/email through to audit
- `src/main/kotlin/io/sdkman/state/adapter/primary/rest/VersionRoutes.kt` — temporarily passes NIL_UUID + Basic Auth username as email
- `src/main/kotlin/io/sdkman/state/adapter/primary/rest/TagRoutes.kt` — temporarily passes NIL_UUID + Basic Auth username as email
- `src/test/kotlin/io/sdkman/state/support/Postgres.kt` — `VendorAuditRecord` now has `vendorId`/`email`; `selectAuditRecordsByUsername` renamed to `selectAuditRecordsByEmail`
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresAuditRepositoryIntegrationSpec.kt` — updated to use vendorId/email params and assertions
- `src/test/kotlin/io/sdkman/state/application/service/VersionServiceUnitSpec.kt` — updated mock expectations for new signature
- `src/test/kotlin/io/sdkman/state/application/service/TagServiceUnitSpec.kt` — updated mock expectations for new signature
- `src/test/kotlin/io/sdkman/state/acceptance/VendorAuditAcceptanceSpec.kt` — assertions changed from `username` to `vendorId`/`email`
- `src/test/kotlin/io/sdkman/state/acceptance/DeleteTagAcceptanceSpec.kt` — assertion changed from `username` to `email`

**Test outcome:** PASS — `./gradlew check` passes (compile, detekt, ktlint, all 185 tests)

**Learnings:**
- _Gotchas:_ Phase 3 and 4.2 MUST be bundled atomically — V14 migration drops the `username` column from `vendor_audit`, so the `PostgresAuditRepository` and all tests referencing that column break immediately. The implementation plan's claim that "migrations are additive and do not affect existing functionality" is incorrect for V14 (it's destructive to the existing schema).
- _Patterns:_ Routes temporarily use a NIL_UUID sentinel (`UUID(0L, 0L)`) and pass the Basic Auth username as the `email` parameter. This is the transitional state until JWT auth is wired in (Phase 6.3/6.5).
- _Context:_ The `selectAuditRecordsByUsername` test helper was renamed to `selectAuditRecordsByEmail` since the underlying column changed.

---

### [2026-03-26 00:45] — Phase 4: Task 4.1

**Summary:** Implemented `PostgresVendorRepository` with custom `TextArrayColumnType` for PostgreSQL `TEXT[]` array mapping in Exposed 0.57.0.

**Files changed:**
- `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresVendorRepository.kt` — new file implementing `VendorRepository` port with `VendorsTable` Exposed table object, custom `TextArrayColumnType` for `TEXT[]` columns, and all five repository methods (findByEmail, findAll, upsert with resurrection, softDelete, findById)

**Test outcome:** PASS — `./gradlew check` passes (compile, detekt, ktlint, all 185 tests)

**Learnings:**
- _Gotchas:_ Exposed 0.57.0 does not have a built-in array column type. Required a custom `TextArrayColumnType` extending `ColumnType<List<String>>()` with `sqlType() = "TEXT[]"`, `valueFromDB` handling `java.sql.Array`, and `notNullValueToDB` using `connection.createArrayOf("text", ...)`. Note: `notNullValueToDB` takes the generic type `List<String>`, not `Any`.
- _Gotchas:_ `VendorsTable` uses plain `Table` (not `IdTable`/`UUIDTable`) since `exposed-dao` is not a dependency. UUID primary key uses `uuid("id").autoGenerate()` with manual `PrimaryKey(id)` override. Insert uses `Table.insert` (not `insertAndGetId`), followed by a re-query by email to return the created entity.
- _Patterns:_ Follows the same `Either.catch { dbQuery { ... } }.mapLeft { DatabaseFailure.QueryExecutionFailure(...) }` pattern used by all other repository implementations in this codebase.

---

### [2026-03-26 01:00] — Phase 5: Task 5.1

**Summary:** Created `RateLimiter` component with per-IP tracking using `ConcurrentHashMap` and sliding window of 5 attempts per 60 seconds.

**Files changed:**
- `src/main/kotlin/io/sdkman/state/application/service/RateLimiter.kt` — new standalone rate limiter with `isRateLimited`, `recordAttempt`, and `cleanup` methods

**Test outcome:** PASS — `./gradlew check` passes (compile, detekt, ktlint, all tests)

**Learnings:**
- _Patterns:_ ktlint enforces no blank line at start of class body — class fields must start immediately after opening brace
- _Context:_ `RateLimiter` is a pure application-layer component with no infrastructure dependencies, making it independently testable (Phase 12.1)

---

### [2026-03-26 01:15] — Phase 5: Task 5.2

**Summary:** Implemented `AuthServiceImpl` with admin/vendor login, constant-time BCrypt verification, JWT token creation via `com.auth0:java-jwt`, and rate limiting integration.

**Files changed:**
- `src/main/kotlin/io/sdkman/state/application/service/AuthServiceImpl.kt` — new AuthService implementation with admin password hashed once at construction, dummy hash for constant-time verification on unknown/deleted emails, JWT creation with HS256 and all required claims

**Test outcome:** PASS — `./gradlew check` passes (compile, detekt, ktlint, all tests)

**Learnings:**
- _Patterns:_ ktlint enforces `Either.catch { ... }.mapLeft { ... }` to be formatted as `Either\n.catch { ... }.mapLeft { ... }` with chain method continuation on new lines
- _Gotchas:_ BCrypt `hash()` returns `byte[]`, must wrap in `String()` constructor for storage; `verify()` takes `byte[]` for both password and hash
- _Context:_ Admin password is BCrypt-hashed once at `AuthServiceImpl` construction time and held in memory — avoids repeated hashing on every login attempt

---

### [2026-03-26 01:45] — Phase 6, 7, 9, 10: Complete JWT auth stack and test migration

**Summary:** Implemented full JWT authentication primary adapters (Authentication.kt, AdminRoutes.kt, AdminDto.kt, RequestExtensions.kt), updated Routing.kt and both Application.kt files, removed Basic Auth entirely, created JwtTestSupport, updated Postgres.kt test support, and migrated all 8 acceptance test files from Basic Auth to JWT Bearer tokens.

**Files changed:**
- `src/main/kotlin/io/sdkman/state/config/Authentication.kt` — replaced basic auth with JWT auth (HS256, issuer/audience validation)
- `src/main/kotlin/io/sdkman/state/adapter/primary/rest/dto/AdminDto.kt` — new DTOs: LoginRequest/Response, CreateVendorRequest, VendorResponse, VendorWithPasswordResponse
- `src/main/kotlin/io/sdkman/state/adapter/primary/rest/RequestExtensions.kt` — added JWT helpers (authenticatedVendorId, authenticatedEmail, authenticatedRole, authenticatedCandidates), removed authenticatedUsername
- `src/main/kotlin/io/sdkman/state/adapter/primary/rest/AdminRoutes.kt` — new admin routes: login, list/create/delete vendors with role-based authorization
- `src/main/kotlin/io/sdkman/state/adapter/primary/rest/VersionRoutes.kt` — replaced Basic Auth with JWT claim extraction, added candidate authorization (403 for unauthorized)
- `src/main/kotlin/io/sdkman/state/adapter/primary/rest/TagRoutes.kt` — same JWT migration and candidate authorization
- `src/main/kotlin/io/sdkman/state/adapter/primary/rest/Routing.kt` — switched to authenticate("auth-jwt"), added admin routes, new parameters
- `src/main/kotlin/io/sdkman/state/Application.kt` — wired JWT auth, PostgresVendorRepository, RateLimiter, AuthServiceImpl
- `src/main/kotlin/io/sdkman/state/config/AppConfig.kt` — removed authUsername/authPassword
- `src/main/resources/application.conf` — removed api.username/password
- `src/test/kotlin/io/sdkman/state/support/JwtTestSupport.kt` — new test utility for generating admin/vendor/expired/invalid tokens
- `src/test/kotlin/io/sdkman/state/support/Application.kt` — switched to JWT auth, aligned config with JwtTestSupport constants
- `src/test/kotlin/io/sdkman/state/support/Postgres.kt` — added VendorsTable cleanup to withCleanDatabase
- `src/test/kotlin/io/sdkman/state/acceptance/HealthCheckAcceptanceSpec.kt` — updated inline app setup for JWT auth
- `src/test/kotlin/io/sdkman/state/acceptance/PostVersionAcceptanceSpec.kt` — Basic Auth → JWT Bearer
- `src/test/kotlin/io/sdkman/state/acceptance/DeleteVersionAcceptanceSpec.kt` — Basic Auth → JWT Bearer
- `src/test/kotlin/io/sdkman/state/acceptance/DeleteTagAcceptanceSpec.kt` — Basic Auth → JWT Bearer, updated audit assertion to admin email
- `src/test/kotlin/io/sdkman/state/acceptance/VendorAuditAcceptanceSpec.kt` — Basic Auth → JWT Bearer, updated all audit assertions (email, vendorId)
- `src/test/kotlin/io/sdkman/state/acceptance/IdempotentPostVersionAcceptanceSpec.kt` — Basic Auth → JWT Bearer
- `src/test/kotlin/io/sdkman/state/acceptance/DeleteTaggedVersionAcceptanceSpec.kt` — Basic Auth → JWT Bearer
- `src/test/kotlin/io/sdkman/state/acceptance/PostVersionVisibilityAcceptanceSpec.kt` — Basic Auth → JWT Bearer
- `src/test/kotlin/io/sdkman/state/acceptance/PostVersionTagsAcceptanceSpec.kt` — Basic Auth → JWT Bearer

**Test outcome:** PASS — `./gradlew check` passes (compile, detekt, ktlint, all tests)

**Learnings:**
- _Gotchas:_ Phases 6.5, 6.6, 7.1, 7.2, 9.1-9.3, and 10.1-10.11 MUST be bundled atomically — removing `authenticatedUsername()` and changing `configureRouting`'s signature breaks all existing tests until they are migrated to JWT Bearer tokens. The plan's claim of independent atomic tasks was incorrect for this transition.
- _Gotchas:_ Ktor 3.x uses `call.request.local.remoteHost` (not `origin.remoteAddress`) for client IP.
- _Gotchas:_ `HealthCheckAcceptanceSpec` has its own inline app setup (not using `withTestApplication`), requiring separate JWT migration.
- _Patterns:_ Detekt `LongMethod` threshold of 60 lines required splitting `adminVendorRoutes` into 3 separate route functions and `versionWriteRoutes` into 2.
- _Context:_ `VendorsTable` is `internal` in the main source but accessible from tests since they're in the same Kotlin module.

---
