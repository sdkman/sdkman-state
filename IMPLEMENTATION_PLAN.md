# Implementation Plan — JWT Authentication Fixes

**Goal:** Apply the 9 corrective actions from `specs/jwt-authentication-fixes.md` (post-review fixes for PR #37).

**Status:** The base JWT authentication feature (`specs/jwt-authentication.md`) is fully implemented. None of the 9 post-review fixes have been applied yet.

**Last verified:** 2026-03-26 — all line numbers and findings confirmed against current source.

---

## BLOCKER Priority

- [x] **Fix 1 — Rate Limiter: Extract Real Client IP** ✅
  - Added `ktor-server-forwarded-header` dependency to `build.gradle.kts`
  - Installed `XForwardedHeaders` plugin in `HTTP.kt`
  - Changed `call.request.local.remoteHost` → `call.request.origin.remoteHost` in `AdminRoutes.kt`

- [x] **Fix 2 — Rate Limiter: Atomic Check-and-Record** ✅
  - Replaced `isRateLimited()` + `recordAttempt()` with single `checkAndRecord()` using `ConcurrentHashMap.compute`
  - Updated `AuthServiceImpl.kt` to use the new atomic method
  - Updated `RateLimiterUnitSpec` and `AuthServiceImplUnitSpec` tests

## HIGH Priority

- [x] **Fix 3 — Rate Limiter: Periodic Cleanup** ✅
  - Launched a background coroutine in `Application.module()` that calls `rateLimiter.cleanup()` every 60 seconds
  - Uses Ktor's `Application` coroutine scope so the job is cancelled on shutdown

- [ ] **Fix 4 — `authenticatedVendorId()` Safe Parsing**
  - In `RequestExtensions.kt:22-26`, wrap `UUID.fromString(...)` (line 25) in `runCatching` inside the `Option` chain
  - Return nil UUID (`UUID(0L, 0L)`) for missing, null, or malformed `vendor_id` claims
  - Currently throws uncaught `IllegalArgumentException` on malformed claims → 500 error

- [ ] **Fix 5 — Atomic Upsert in `PostgresVendorRepository`**
  - Replace SELECT-then-INSERT/UPDATE pattern in `PostgresVendorRepository.kt:110-147` with `INSERT ... ON CONFLICT (email) DO UPDATE`
  - SELECT at line 110, INSERT at line 136 — not atomic, race condition on concurrent upserts
  - Eliminates unique constraint violation 500s

## MEDIUM Priority

- [ ] **Fix 6 — Extract Shared `BCRYPT_COST` Constant**
  - Create `src/main/kotlin/io/sdkman/state/security/SecurityConstants.kt` with `const val BCRYPT_COST = 12`
  - Remove duplicate `private const val BCRYPT_COST = 12` from `AuthServiceImpl.kt:19` and `AdminRoutes.kt:26`
  - Import the shared constant in both files

- [ ] **Fix 7 — `SecureRandom` Singleton**
  - In `AdminRoutes.kt`, move `SecureRandom()` instantiation from inside `generatePassword()` (line 170) to a file-level `private val secureRandom = SecureRandom()`
  - Avoids re-seeding overhead on every password generation call

- [ ] **Fix 8 — Single `Instant.now()` in `softDelete`**
  - In `PostgresVendorRepository.kt:164-165`, capture `val now = Instant.now()` once and assign to both `deletedAt` and `updatedAt`
  - Currently two separate `Instant.now()` calls can produce different timestamps

## LOW Priority

- [ ] **Fix 9 — Document Admin Bypass on Version/Tag Routes**
  - Add comment above the candidate authorization check in `VersionRoutes.kt` (lines 101, 138) and `TagRoutes.kt` (line 40) explaining that admin tokens intentionally bypass the candidate check (the `role == "vendor"` guard lets admin through)
  - No behavioural change — documentation only

---

## Validation

After all fixes are applied, run the full quality gate:

```bash
./gradlew check
```

All existing acceptance, integration, and unit tests must continue to pass. New tests may be needed for fixes 1–5 depending on coverage gaps.
