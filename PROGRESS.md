# Progress Log

Append-only log of implementation progress. Each entry follows this template:

```
## [Date] — [Item Name]
**Status:** Complete
**Changes:**
- bullet list of changes made
**Verification:** test results summary
```

---

## 2026-03-26 — Fix 1: Rate Limiter: Extract Real Client IP

**Status:** Complete
**Changes:**
- Added `io.ktor:ktor-server-forwarded-header` dependency to `build.gradle.kts`
- Installed `XForwardedHeaders` plugin in `HTTP.kt` so `origin.remoteHost` reads from `X-Forwarded-For`
- Changed `call.request.local.remoteHost` → `call.request.origin.remoteHost` in `AdminRoutes.kt` for rate limiting
- Added `import io.ktor.server.plugins.origin` to resolve the Ktor 3.x extension
**Verification:** `./gradlew check` — BUILD SUCCESSFUL (detekt, ktlint, all tests pass)

## 2026-03-26 — Fix 2: Rate Limiter: Atomic Check-and-Record

**Status:** Complete
**Changes:**
- Replaced separate `isRateLimited()` + `recordAttempt()` with single atomic `checkAndRecord()` in `RateLimiter.kt` using `ConcurrentHashMap.compute` — eliminates TOCTOU race condition
- `checkAndRecord()` atomically prunes expired timestamps, checks the limit, and records the attempt in one `compute` block; does not record when already rate-limited
- Updated `AuthServiceImpl.kt` login method to call `checkAndRecord()` instead of two separate methods
- Updated `RateLimiterUnitSpec` to test via `checkAndRecord()` API, added test verifying no recording when rate-limited
- Updated `AuthServiceImplUnitSpec` mocks to use `checkAndRecord()`
**Verification:** `./gradlew check` — BUILD SUCCESSFUL (detekt, ktlint, all tests pass)

## 2026-03-26 — Fix 3: Rate Limiter: Periodic Cleanup

**Status:** Complete
**Changes:**
- Added `launch` coroutine in `Application.module()` that calls `rateLimiter.cleanup()` every 60 seconds to prevent unbounded memory growth in the `ConcurrentHashMap`
- Uses Ktor's `Application` coroutine scope — the cleanup job is automatically cancelled on application shutdown
- Added `kotlinx.coroutines.delay` and `kotlinx.coroutines.launch` imports
**Verification:** `./gradlew check` — BUILD SUCCESSFUL (detekt, ktlint, all tests pass)

## 2026-03-26 — Fix 4: authenticatedVendorId() Safe Parsing

**Status:** Complete
**Changes:**
- Changed `authenticatedVendorId()` in `RequestExtensions.kt` from `.map { UUID.fromString(...) }` to `.flatMap { runCatching { UUID.fromString(...) }.getOrNull().toOption() }`
- Missing, null, or malformed `vendor_id` claims now return nil UUID (`UUID(0L, 0L)`) instead of throwing `IllegalArgumentException` (500 error)
**Verification:** `./gradlew check` — BUILD SUCCESSFUL (detekt, ktlint, all tests pass)
