# Switchable Login Rate Limiting Specification

## Overview

The State API throttles the login endpoint with a fixed rate limiter: `RateLimiter` (`application/service/RateLimiter.kt`) allows **5 attempts per 60-second rolling window per client IP**, with both bounds baked in as the private constants `MAX_ATTEMPTS = 5` and `WINDOW_SECONDS = 60L`. `AuthServiceImpl.login` calls `rateLimiter.checkAndRecord(clientIp)` first and returns `AuthError.RateLimitExceeded` (mapped to HTTP `429` in `LoginRoutes.kt`) when the window is full. This is correct protection against credential brute-forcing for normal operation, and there is no way to turn it off.

That fixed limit is now an operational blocker. The Mongo→Postgres **`versions` backfill** (control-plane `docs/specs/04-mongo-postgres-backfill.md`) runs a per-candidate migration tool that performs a fresh admin login on every `migrate <candidate>` invocation. With ~80 candidates rolled back-to-back, the tool trips the limiter after the 5th candidate in any minute and receives `429`s on the rest, forcing an artificial ≥13s pace between candidates (≈16-minute floor for the whole roll) purely to stay under the login budget.

This spec makes the limiter **switchable** via a single boolean config toggle, default **on**, so a controlled maintenance window (the backfill) can disable it and restart. It deliberately mirrors the existing `validation.semverish.candidates` **restart-to-apply** config that the same backfill already brackets the `java` run with — one more toggle the runbook flips, applies with a restart, and restores afterwards. Thresholds stay hardcoded; this is an on/off switch, not tunable limits.

**Key Properties:**
- New boolean config `auth.rateLimit.enabled`, default `true`, with a `${?RATE_LIMIT_ENABLED}` env override — declared once in `application.conf` (HOCON single source), no Kotlin default literal.
- Read once at startup into `AppConfig.rateLimitEnabled` (**restart-to-apply**, exactly like `semverishCandidates`).
- When disabled, `RateLimiter.checkAndRecord` always returns `false` (never limited) and records nothing; the attempts map and the periodic `cleanup` loop stay dormant.
- The `5 / 60s` thresholds remain hardcoded constants — out of scope to make tunable.
- Default behaviour is byte-for-byte unchanged: unset or `true` ⇒ the limiter behaves exactly as today, including the existing `429` contract.

## Requirements

- **R1.** `application.conf` gains an `auth.rateLimit.enabled` key defaulting to `true`, with a `enabled = ${?RATE_LIMIT_ENABLED}` env override, following the `${?ENV}` convention of its siblings.
- **R2.** `AppConfig` gains `val rateLimitEnabled: Boolean`; `DefaultAppConfig` reads it as a required property — `config.property("auth.rateLimit.enabled").getString().toBoolean()` — with no Kotlin fallback literal (per the HOCON single-source rule).
- **R3.** `RateLimiter` takes an `enabled: Boolean` constructor parameter. When `enabled` is `false`, `checkAndRecord` returns `false` without recording the attempt; `cleanup` remains safe to call (a no-op over an empty map).
- **R4.** `Application.kt` constructs `RateLimiter(appConfig.rateLimitEnabled)`. The existing 60-second `cleanup` coroutine is retained unchanged (harmless when disabled — see Extra Considerations for the optional gating decision).
- **R5.** The test config builder `testApplicationConfig()` supplies `auth.rateLimit.enabled` (required reads throw on a missing key, since tests load `MapApplicationConfig`, not `application.conf`). All other `RateLimiter()` call sites are updated to pass an explicit `enabled` argument — namely `support/Application.kt` (`withTestApplication`), `acceptance/HealthCheckAcceptanceSpec.kt`, and every construction in `RateLimiterUnitSpec`.
- **R6.** No change to the `5 / 60s` thresholds, to any route, to the `429` response, or to the database schema. With the flag unset or `true`, production and every existing test behave identically. `./gradlew check` passes.

## Rules

**Before implementing, you MUST read and internalize:**
- `.claude/rules/hocon.md` — every config default lives once in `application.conf`; required reads, no `getXxxOrDefault` helper, no Kotlin default literal.
- `.claude/rules/kotlin.md` — Arrow `Option` over nullables, `val`, expression bodies, immutability at the boundary.
- `.claude/rules/hexagonal-architecture.md` — config is wired at the application boundary; the domain/service stays framework-free.
- `.claude/rules/kotest.md` — ShouldSpec, three-layer strategy (acceptance → integration → unit), Testcontainers for DB tests, one assertion per intention.
- `.claude/rules/quality-gates.md` — no `@Disabled`/`@Ignore`, no `@Suppress`, no relaxing detekt/ktlint to make things pass.

**If this spec conflicts with the rules, THE RULES WIN.**

## Domain

The change is a pure toggle on an existing infrastructure-side service — no new domain types, aggregates, or ports.

```kotlin
// RateLimiter — gains an `enabled` flag; thresholds unchanged.
class RateLimiter(private val enabled: Boolean) {
    private val attempts = ConcurrentHashMap<String, MutableList<Instant>>()

    // when disabled: short-circuit before touching the map — never limited, nothing recorded.
    fun checkAndRecord(clientIp: String): Boolean {
        if (!enabled) return false
        // ...existing windowed logic unchanged (MAX_ATTEMPTS = 5, WINDOW_SECONDS = 60L)...
    }

    fun cleanup() { /* unchanged; a no-op over the empty map when disabled */ }
}
```

```kotlin
// AppConfig — one new field, read once at startup (restart-to-apply).
interface AppConfig {
    // ...existing fields...
    val rateLimitEnabled: Boolean
}

class DefaultAppConfig(private val config: ApplicationConfig) : AppConfig {
    // ...existing reads...
    override val rateLimitEnabled: Boolean =
        config.property("auth.rateLimit.enabled").getString().toBoolean()
}
```

`AuthServiceImpl.login` is **unchanged** — it keeps calling `rateLimiter.checkAndRecord(clientIp)`; the flag lives entirely inside `RateLimiter`, so the service and its call graph don't know the limiter can be off.

## Endpoints

No route, method, path, request, or response shape changes.

- `POST /login` — still returns `429 Too Many Requests` (`AuthError.RateLimitExceeded`) when the limiter is **enabled** and the window is full. When the limiter is **disabled**, `checkAndRecord` never reports limited, so `429` is simply never produced by this path — but the status remains part of the documented contract (already present in `openapi/documentation.yaml`, so **no OpenAPI change is required**).

## Access Matrix

Unchanged. The toggle affects *whether* a login attempt can be throttled, not *who* may call any endpoint.

| Endpoint | Anonymous | Admin | Vendor |
|----------|-----------|-------|--------|
| POST /login | Yes (may receive 429 when limiter enabled) | Yes | Yes |

## Configuration

### `application.conf` — add the `auth.rateLimit` block

A new top-level `auth` block (there is no existing one), consistent with the `${?ENV}` override style used throughout:

```hocon
auth {
    rateLimit {
        enabled = true
        enabled = ${?RATE_LIMIT_ENABLED}
    }
}
```

`true` is the safe production default — unset `RATE_LIMIT_ENABLED` ⇒ limiter on, current behaviour. Set `RATE_LIMIT_ENABLED=false` (and restart) to disable it for a maintenance window.

### `AppConfig.kt` — one required read, no Kotlin default

```kotlin
// interface
val rateLimitEnabled: Boolean

// DefaultAppConfig — eager required read, mirrors the semverishCandidates style
override val rateLimitEnabled: Boolean =
    config.property("auth.rateLimit.enabled").getString().toBoolean()
```

No `getBooleanOrDefault` helper is introduced — the default lives only in HOCON (hocon.md RULE-003). `String.toBoolean()` is the standard Kotlin parse (`"true"`, case-insensitive, ⇒ `true`; anything else ⇒ `false`).

### `Application.kt` — pass the flag in

```kotlin
// before
val rateLimiter = RateLimiter()

// after
val rateLimiter = RateLimiter(appConfig.rateLimitEnabled)
```

The `launch { while (true) { delay(60_000); rateLimiter.cleanup() } }` block is left as-is.

### Test config — supply the new required key

`testApplicationConfig()` in `src/test/kotlin/io/sdkman/state/support/Application.kt` builds a `MapApplicationConfig` and therefore must list every required key. Add:

```kotlin
"auth.rateLimit.enabled" to "true",
```

> **Decision point — explicit arg vs. constructor default.** This spec specifies an explicit `enabled` parameter and updating all `RateLimiter(...)` call sites to pass it (`RateLimiter(enabled = true)` in tests that assert the *enabled* behaviour). There are three non-production call sites to update under this approach — `support/Application.kt`, `acceptance/HealthCheckAcceptanceSpec.kt`, and the six constructions in `RateLimiterUnitSpec` — plus the production wiring in `Application.kt`; miss any and the build won't compile. An acceptable alternative is a constructor default `enabled: Boolean = true`, which leaves all the existing call sites (including `HealthCheckAcceptanceSpec` and the enabled-behaviour unit tests) untouched; a constructor default is **not** a config default, so hocon.md does not forbid it. Prefer the explicit parameter for symmetry with the production wiring; if the call-site churn feels noisy, the defaulted parameter is a reasonable trade. Pick one and apply it uniformly.

## Extra Considerations

- **Security — this weakens login protection while off.** Disabling removes brute-force throttling on `POST /login`. It is intended **only** for controlled, time-boxed maintenance windows (the backfill), disabled behind the operator's own network controls, and **restored immediately afterwards** — the same discipline the `java` semverish-toggle bracket already demands. The spec's default (`true`) means you never ship it off by accident; turning it off is a deliberate env + restart.
- **Restart-to-apply, by design.** Like `semverishCandidates`, `rateLimitEnabled` is read once at construction. Changing `RATE_LIMIT_ENABLED` requires a State restart to take effect — this is a config change, not a deploy, and matches the operational model the backfill runbook already uses.
- **`cleanup` coroutine when disabled.** With the limiter off, `checkAndRecord` records nothing, so the attempts map stays empty and the 60-second `cleanup` pass is a no-op. Leaving the loop running is harmless and keeps the wiring simple; gating it on `enabled` is an optional micro-optimisation, not a requirement. If gated, do it without introducing a nullable — e.g. only `launch` the loop when `appConfig.rateLimitEnabled`.
- **No OpenAPI or schema change.** `429` is already documented for the login route and remains reachable when the limiter is enabled, so `openapi/documentation.yaml` is untouched (openapi.md is satisfied). No migration, no table change.
- **Cross-repo follow-up (out of scope here).** For the local end-game stack to exercise the toggle, the control-plane `compose/compose.yaml` `state-api` service should gain `RATE_LIMIT_ENABLED: ${RATE_LIMIT_ENABLED:-true}`, mirroring its existing `SEMVERISH_CANDIDATES` line. That edit lives in the control-plane repo, not this service, and is tracked by the backfill runbook — noted here only so the two stay consistent.
- **Detekt / ktlint.** Expression-body the new read, no nullables, no suppressions — follow the surrounding `DefaultAppConfig` style.

## Testing Considerations

**Framework:** Kotest `ShouldSpec`; Testcontainers Postgres for acceptance (already in place). Follow the three-layer strategy.

**Unit — `RateLimiterUnitSpec`** (extend the existing spec):
- *Enabled* (construct `RateLimiter(enabled = true)`): the current cases stand unchanged — allows the first 5, limits the 6th, tracks IPs independently, unknown IP allowed, cleanup semantics.
- *Disabled* (new, construct `RateLimiter(enabled = false)`): `checkAndRecord(ip)` returns `false` on the 1st call **and** still `false` after well beyond `MAX_ATTEMPTS` (e.g. 20 consecutive calls for one IP) — proving the limiter never engages when off.

**Acceptance — login under each toggle** (Testcontainers, boots the app from config):
- *Enabled path* is already covered by the existing login/authorization acceptance suite (6th attempt in the window ⇒ `429`) — keep it green by supplying `auth.rateLimit.enabled = "true"` (the default in `testApplicationConfig()`).
- *Disabled path* (new): boot a test application whose config overrides `auth.rateLimit.enabled` to `"false"`, fire more than 5 rapid logins from the same client, and assert **none** returns `429` (each returns `200`/`401` per the credentials). Use `testApplicationConfig().apply { put("auth.rateLimit.enabled", "false") }` so only the one key differs, mirroring the `HikariPoolIntegrationSpec` override pattern.

**Config completeness** is proven implicitly: every acceptance/integration spec boots `DefaultAppConfig` from `testApplicationConfig()`, so a missing required `auth.rateLimit.enabled` key would throw at startup and fail the suite. Do **not** add a unit test that merely re-asserts the HOCON default value (circular, per hocon.md RULE-103).

## Specification by Example

**Limiter enabled (default) — 6th login in the window is throttled:**
```
# 5 logins within 60s from 203.0.113.7 → each processed (200/401 by credentials)
POST /login        → 200 (or 401)   ×5
# 6th within the same window:
POST /login
  { "email": "admin@sdkman.io", "password": "…" }
→ 429 Too Many Requests
```

**Limiter disabled (`RATE_LIMIT_ENABLED=false`, restarted) — no throttling:**
```
# 50 logins within 60s from 203.0.113.7
POST /login  ×50
→ 200 (or 401 on bad credentials) for every request; never 429
```

## Suggested Slice Breakdown

Independently committable, in dependency order. Each slice is a single Conventional Commit and keeps `./gradlew check` green.

1. **`feat: add auth.rateLimit.enabled config toggle`** — add the `auth.rateLimit.enabled` HOCON block (default `true`, `${?RATE_LIMIT_ENABLED}`); add `rateLimitEnabled` to `AppConfig`/`DefaultAppConfig` as a required read; add `"auth.rateLimit.enabled" to "true"` to `testApplicationConfig()`. Config plumbing only — nothing consumes the field yet.
2. **`feat: make login rate limiter switchable`** — add the `enabled` constructor parameter to `RateLimiter`, short-circuit `checkAndRecord` when disabled, wire `RateLimiter(appConfig.rateLimitEnabled)` in `Application.kt`, and update the remaining test call sites (`support/Application.kt`, `acceptance/HealthCheckAcceptanceSpec.kt`, and `RateLimiterUnitSpec`); extend `RateLimiterUnitSpec` with the disabled cases.
3. **`test: cover login with rate limiter disabled`** — acceptance spec asserting >5 rapid logins never `429` when the toggle is off (config override), alongside the existing enabled-path coverage.

## Verification

After all slices are merged:

- [ ] `./gradlew check` passes cleanly (compile + detekt + ktlint + tests).
- [ ] `grep -RIn "OrDefault" src/main` still returns no hits (no defaulting helper introduced).
- [ ] `auth.rateLimit.enabled` appears exactly once as a default (in `application.conf`); no Kotlin literal duplicates it.
- [ ] With no `RATE_LIMIT_ENABLED` set: boot the app, fire 6 logins in a minute from one IP → the 6th returns `429` (unchanged behaviour).
- [ ] With `RATE_LIMIT_ENABLED=false` and a restart: fire >5 logins in a minute from one IP → none returns `429`.
- [ ] `RateLimiterUnitSpec` covers both enabled (limits at 6) and disabled (never limits) construction.
- [ ] `openapi/documentation.yaml` and the DB schema confirmed **unchanged**.
- [ ] The backfill's per-candidate migration tool completes a full non-`java` roll with `RATE_LIMIT_ENABLED=false` and **zero** `429`s, at no artificial pacing.
