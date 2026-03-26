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
