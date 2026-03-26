# JWT Authentication Fixes

**Status:** Post-review fixes for PR #37
**Depends on:** `specs/jwt-authentication.md`
**Service:** sdkman-state (Kotlin/Ktor)

Fixes nine issues identified during code review of the JWT authentication implementation.

---

## 1. Rate Limiter: Extract Real Client IP

**Severity:** BLOCKER

**Problem:** `call.request.local.remoteHost` returns the reverse proxy IP in production. All clients share one rate limit bucket.

**Fix:**

1. Add the `ktor-server-forwarded-header` dependency to `build.gradle.kts`:
   ```kotlin
   implementation("io.ktor:ktor-server-forwarded-header:$ktor_version")
   ```

2. Install the `XForwardedHeaders` plugin in `Application.module()`:
   ```kotlin
   install(XForwardedHeaders)
   ```

3. Replace all uses of `call.request.local.remoteHost` for rate limiting with `call.request.origin.remoteHost`. After `XForwardedHeaders` is installed, `origin.remoteHost` automatically reads from `X-Forwarded-For`.

4. If `X-Forwarded-For` is absent (direct connection), `origin.remoteHost` falls back to the socket address — no special handling needed.

**Acceptance criteria:**

- **Test: independent rate limit buckets** — Two requests from different `X-Forwarded-For` IPs should have independent rate limit counters. IP `1.2.3.4` hitting the limit must not affect IP `5.6.7.8`.
- **Test: fallback on direct connection** — A request with no `X-Forwarded-For` header uses the direct socket IP for rate limiting (no crash, no empty key).

---

## 2. Rate Limiter: Atomic Check-and-Record

**Severity:** BLOCKER

**Problem:** `isRateLimited()` and `recordAttempt()` are separate calls. Concurrent requests from the same IP can all pass the check before any records.

**Fix:**

1. In `RateLimiter`, replace `isRateLimited()` and `recordAttempt()` with a single method:
   ```kotlin
   fun checkAndRecord(clientIp: String): Boolean
   ```
   - Returns `true` → client IS rate-limited (attempt NOT recorded).
   - Returns `false` → client is allowed (attempt recorded atomically).

2. Use `ConcurrentHashMap.compute` to make the check and record atomic:
   ```kotlin
   fun checkAndRecord(clientIp: String): Boolean {
       var rateLimited = false
       attempts.compute(clientIp) { _, existing ->
           val now = Instant.now()
           val list = (existing ?: mutableListOf()).apply {
               removeAll { it.isBefore(now.minus(window)) }
           }
           if (list.size >= maxAttempts) {
               rateLimited = true
               list // return unchanged — do not record
           } else {
               rateLimited = false
               list.apply { add(now) } // record atomically
           }
       }
       return rateLimited
   }
   ```

3. Remove `isRateLimited()` and `recordAttempt()` from the public API.

4. Update `AuthServiceImpl.login()` to call `checkAndRecord(clientIp)` instead of the two-step check.

**Acceptance criteria:**

- **Test: exactly N succeed under concurrency** — Launch 10 concurrent login requests from the same IP with `maxAttempts = 5`. Exactly 5 must succeed (HTTP 200) and exactly 5 must be rate-limited (HTTP 429). Not 6, not 4.
- **Test: interface compiles** — `isRateLimited()` and `recordAttempt()` no longer exist on `RateLimiter`.

---

## 3. Rate Limiter: Periodic Cleanup

**Problem:** `cleanup()` is defined but never called. The `attempts` map grows unbounded under brute-force from many IPs.

**Fix:**

In `Application.module()`, after constructing the `RateLimiter`, launch a cleanup coroutine:

```kotlin
launch {
    while (isActive) {
        delay(60_000) // 60 seconds
        rateLimiter.cleanup()
    }
}
```

Use the application's coroutine scope (available inside `Application.module()` via `this` or `application`).

**Acceptance criteria:**

- **Test: stale entries removed** — Insert entries with timestamps older than the rate limit window. Call `cleanup()`. Verify those entries are gone from the internal map.
- **Test: fresh entries preserved** — Insert entries within the window. Call `cleanup()`. Verify they remain.
- **Verify in Application.module()** — The coroutine launch is present and uses `isActive` for cancellation.

---

## 4. authenticatedVendorId() Safe Parsing

**Problem:** If a valid JWT lacks `vendor_id`, `UUID.fromString(null)` throws `IllegalArgumentException`, producing an uncaught 500.

**Fix:**

In `RequestExtensions.kt`, make `authenticatedVendorId()` never throw:

```kotlin
fun ApplicationCall.authenticatedVendorId(): UUID =
    principal<JWTPrincipal>()
        .toOption()
        .flatMap {
            runCatching {
                UUID.fromString(it.payload.getClaim("vendor_id").asString())
            }.getOrNull().toOption()
        }
        .getOrElse { UUID(0L, 0L) }
```

Key behaviours:
- No `JWTPrincipal` → returns `UUID(0L, 0L)` (nil UUID).
- `vendor_id` claim missing or null → returns nil UUID.
- `vendor_id` claim present but not a valid UUID string → returns nil UUID.
- Valid `vendor_id` → returns parsed UUID.

**Acceptance criteria:**

- **Test: missing vendor_id claim** — A valid JWT (correct secret, valid expiry) with no `vendor_id` claim must NOT produce a 500. Should return nil UUID (downstream authorization handles access).
- **Test: malformed vendor_id** — A JWT with `vendor_id: "not-a-uuid"` must NOT produce a 500. Returns nil UUID.
- **Test: valid vendor_id** — A JWT with a proper UUID in `vendor_id` returns that UUID.

---

## 5. Atomic Upsert in PostgresVendorRepository

**Problem:** `upsert()` does SELECT then INSERT/UPDATE. Two concurrent requests for the same email both see null, both INSERT, one hits a unique constraint violation → 500.

**Fix:**

Replace the select-then-insert/update pattern with a single PostgreSQL `INSERT ... ON CONFLICT` statement. Using Exposed's `upsert` DSL or raw SQL:

```sql
INSERT INTO vendors (email, password, candidates, created_at, updated_at)
VALUES (?, ?, ?, NOW(), NOW())
ON CONFLICT (email) DO UPDATE SET
    password = EXCLUDED.password,
    candidates = CASE WHEN ? THEN EXCLUDED.candidates ELSE vendors.candidates END,
    updated_at = NOW(),
    deleted_at = NULL
```

Logic for `candidates` column:
- If the incoming `candidates` parameter is `Some(list)` → replace with new value (`EXCLUDED.candidates`).
- If the incoming `candidates` parameter is `None` → preserve existing (`vendors.candidates`).

The `deleted_at = NULL` in the ON CONFLICT clause ensures a soft-deleted vendor is restored on upsert.

Ensure the `email` column has a UNIQUE constraint (or unique index) — verify this exists in the migration.

**Acceptance criteria:**

- **Test: concurrent upsert same email** — Launch two concurrent upsert calls for the same email. Both must complete without exceptions. No 500. The final password is from whichever wrote last (last-write-wins).
- **Test: candidates preservation** — Upsert with `candidates = None` must not overwrite existing candidates. Upsert with `candidates = Some(listOf("java"))` must replace.
- **Test: soft-delete restoration** — Upserting an email that was previously soft-deleted sets `deleted_at = NULL`.

---

## 6. Extract Shared BCRYPT_COST Constant

**Problem:** `BCRYPT_COST = 12` is independently defined in `AuthServiceImpl.kt` and `AdminRoutes.kt`. Divergence silently breaks authentication.

**Fix:**

1. Create `src/main/kotlin/io/sdkman/state/security/SecurityConstants.kt`:
   ```kotlin
   package io.sdkman.state.security

   const val BCRYPT_COST = 12
   ```

2. In `AuthServiceImpl.kt`: remove the private `BCRYPT_COST` constant. Import `io.sdkman.state.security.BCRYPT_COST`.

3. In `AdminRoutes.kt`: remove the private `BCRYPT_COST` constant. Import `io.sdkman.state.security.BCRYPT_COST`.

**Acceptance criteria:**

- **Verify:** `BCRYPT_COST` is defined in exactly one location.
- **Verify:** Both `AuthServiceImpl.kt` and `AdminRoutes.kt` import from `io.sdkman.state.security.SecurityConstants`.
- **Test:** Project compiles. Existing auth tests pass unchanged.

---

## 7. SecureRandom Singleton

**Problem:** `SecureRandom()` is instantiated on every call to `generatePassword()` in `AdminRoutes.kt`. `SecureRandom` is thread-safe; creating it per-request wastes entropy pool initialisation time.

**Fix:**

In `AdminRoutes.kt`, move to a file-level singleton:

```kotlin
private val secureRandom = SecureRandom()
```

Update `generatePassword()` to use `secureRandom` instead of creating a new instance.

**Acceptance criteria:**

- **Verify:** No `SecureRandom()` constructor calls inside `generatePassword()`.
- **Test:** `generatePassword()` still produces valid passwords (existing tests pass).

---

## 8. Single Instant.now() in softDelete

**Problem:** `PostgresVendorRepository.softDelete()` calls `Instant.now()` separately for `deletedAt` and `updatedAt`, producing different microsecond timestamps.

**Fix:**

Capture once:

```kotlin
val now = Instant.now()
it[deletedAt] = now
it[updatedAt] = now
```

**Acceptance criteria:**

- **Test:** After soft-deleting a vendor, `deleted_at` and `updated_at` are identical timestamps.

---

## 9. Document Admin Bypass on Version/Tag Routes

**Problem:** Admin tokens bypass candidate authorization on `POST /versions`, `DELETE /versions`, and `DELETE /versions/tags`. This is intentional but not documented in code.

**Fix:**

Add an explicit comment above the authorization check in both `VersionRoutes.kt` and `TagRoutes.kt`:

```kotlin
// Admin tokens bypass candidate authorization — admin can operate on any candidate
if (role == "vendor" && validVersion.candidate !in candidates) {
    // ...
}
```

No behavioural change. Code comment only.

**Acceptance criteria:**

- **Verify:** Comment is present in both `VersionRoutes.kt` and `TagRoutes.kt` above the relevant `if` block.
- **Test:** Admin token can still publish/delete any candidate's version. Vendor token is still restricted to own candidates. Existing tests pass.
