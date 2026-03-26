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
