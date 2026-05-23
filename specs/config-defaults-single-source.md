# Config Defaults — Single Source of Truth Specification

## Overview

The `perf/concurrency-optimisations` branch introduced a new `database.pool.*` config block. In wiring it up, `DefaultAppConfig` adopted a pattern where each default value is declared **twice** — once in `application.conf` (HOCON) and again as a literal in the Kotlin `getXxxOrDefault(path, default)` call. This duplication:

1. Lets the two copies drift (e.g. HOCON says `maxSize = 20`, Kotlin says `20` — nothing keeps them in sync).
2. Spawned a new helper, `getLongOrDefault`, and a new unit test, `DefaultAppConfigUnitSpec`, whose only job is to assert that the Kotlin-side fallbacks equal the HOCON-side constants. Those fallbacks are **dead code in production** — `application.conf` always supplies the keys, so the Kotlin default never fires outside a hand-built `MapApplicationConfig`. The test therefore exercises the helper plumbing, not real behaviour.

The deviation wasn't invented from nothing: the pre-existing `databaseHost` / `databasePort` / `databaseName` / `cacheMaxAge` / `jwtExpiry` fields already used the same duplicated-default `getXxxOrDefault` style. The new pool fields followed the weaker of two patterns already in the codebase. The cleaner pattern — default lives **only** in HOCON, value read without a Kotlin fallback — was already present too, on `databaseUsername` / `databasePassword` (read as `Option<String>`) and on `jwtSecret` (read as a required property).

This spec makes HOCON the **single source of truth** for every config default and removes the duplication everywhere it has crept in. It does **not** change any runtime config values, the public API, or the database schema.

**Key Properties:**
- Every config default is declared exactly once, in `application.conf`.
- `DefaultAppConfig` reads each value directly from the (always-present) HOCON key — required reads for mandatory values, `Option` for genuinely optional ones.
- The `getStringOrDefault` / `getIntOrDefault` / `getLongOrDefault` / `getCommaSeparatedSetOrDefault` helpers (which exist only to hold a duplicated default) are deleted.
- `DefaultAppConfigUnitSpec` is deleted — it tests the helper plumbing, not behaviour, and has nothing meaningful left to assert once defaults live solely in HOCON.
- Test config builders supply config explicitly (tests load `MapApplicationConfig`, not `application.conf`, so HOCON defaults never apply to them by design).

## Context: Why Tests Must Be Explicit (Non-Goal)

Acceptance and integration tests construct `DefaultAppConfig` from a hand-built `MapApplicationConfig` (`testApplicationConfig()` in `src/test/kotlin/io/sdkman/state/support/Application.kt`), **not** from `application.conf`. HOCON defaults are therefore never visible to tests. Today the `getXxxOrDefault` fallbacks silently paper over keys the test maps omit (e.g. `database.name`, which no test map currently supplies). Once defaults move to HOCON-only, those omissions become required reads that throw at construction, so the test maps must be made complete.

**Out of scope for this spec:**
- Loading `application.conf` into tests, or otherwise merging HOCON defaults into the test config.
- Changing any default *value* (every value stays exactly what it is today).
- Adding new env-var override points beyond the one noted in R3.

**In scope:** making `testApplicationConfig()` and `HikariPoolIntegrationSpec` supply the keys they need, now that those reads are required.

## Requirements

- **R1.** Every config default that today appears in both `application.conf` and a Kotlin `getXxxOrDefault(..., default)` literal is declared **only** in `application.conf`. No Kotlin literal duplicates a HOCON default.
- **R2.** `DefaultAppConfig` reads each mandatory value via a required property read (e.g. `config.property(path).getString()` and `.toInt()` / `.toLong()` as needed), matching the existing `jwtSecret` style. `databaseUsername` / `databasePassword` remain `Option<String>` via `getOptionString`. `semverishCandidates` remains a parsed `Set<String>`.
- **R3.** `database.name` — currently defaulted only in Kotlin (`"sdkman"`) and absent from `application.conf` — is added to `application.conf` as `name = "sdkman"` with a `name = ${?DATABASE_NAME}` env override, for consistency with its `database.*` siblings.
- **R4.** The now-unused helpers `getStringOrDefault`, `getIntOrDefault`, `getLongOrDefault`, and `getCommaSeparatedSetOrDefault` are removed from `ConfigExtensions.kt`. `getOptionString` is kept (real `Option` conversion). The comma-separated parsing logic is retained as a non-defaulting helper (e.g. `getCommaSeparatedSet`) since it does genuine work.
- **R5.** `DefaultAppConfigUnitSpec` is deleted.
- **R6.** `testApplicationConfig()` supplies `database.name`. `HikariPoolIntegrationSpec` no longer builds a partial, divergent `MapApplicationConfig`; it derives from `testApplicationConfig()` and overrides only the pool keys under test.
- **R7.** No config *value* changes. Production startup and all existing tests behave identically. `./gradlew check` passes with no modified assertions (beyond the deleted spec).

## Rules

**Before implementing, you MUST read and internalize:**
- `.claude/rules/kotlin.md` — Arrow `Option`, no nullables, expression bodies, immutability.
- `.claude/rules/hexagonal-architecture.md` — config wiring stays at the application boundary.
- `.claude/rules/kotest.md` — ShouldSpec, three-layer strategy, Testcontainers for DB tests.
- `.claude/rules/quality-gates.md` — no `@Suppress`, no `@Disabled`, no relaxing detekt/ktlint.

**If this spec conflicts with the rules, THE RULES WIN.**

## Configuration

### `application.conf` — add `database.name`

The `database` block currently has no `name` key; `databaseName` falls back to the Kotlin literal `"sdkman"`. Add it to HOCON so the default lives in one place:

```hocon
database {
    host = "localhost"
    host = ${?DATABASE_HOST}
    port = 5432
    port = ${?DATABASE_PORT}
    name = "sdkman"
    name = ${?DATABASE_NAME}
    username = "postgres"
    username = ${?DATABASE_USERNAME}
    password = "postgres"
    password = ${?DATABASE_PASSWORD}
    pool { … unchanged … }
}
```

All other keys (`pool.*`, `api.cache.control`, `admin.*`, `jwt.*`, `validation.semverish.candidates`) already exist in `application.conf` with their defaults — they are left untouched.

### `AppConfig.kt` — required reads, no Kotlin defaults

`DefaultAppConfig` reads each value directly. Illustrative before/after:

```kotlin
// before
override val databasePort: Int = config.getIntOrDefault("database.port", 5432)
override val databasePoolMaxLifetimeMs: Long = config.getLongOrDefault("database.pool.maxLifetimeMs", 1_800_000L)
override val databaseName: String = config.getStringOrDefault("database.name", "sdkman")

// after (default now lives only in application.conf)
override val databasePort: Int = config.property("database.port").getString().toInt()
override val databasePoolMaxLifetimeMs: Long = config.property("database.pool.maxLifetimeMs").getString().toLong()
override val databaseName: String = config.property("database.name").getString()
```

Unchanged in spirit:
```kotlin
override val databaseUsername: Option<String> = config.getOptionString("database.username")
override val databasePassword: Option<String> = config.getOptionString("database.password")
override val jwtSecret: String get() = config.property("jwt.secret").getString()   // already this style
override val semverishCandidates: Set<String> = config.getCommaSeparatedSet("validation.semverish.candidates")
```

### `ConfigExtensions.kt` — trim to helpers that do real work

```kotlin
// kept — Option conversion at the boundary
fun ApplicationConfig.getOptionString(path: String): Option<String> =
    propertyOrNull(path).toOption().map { it.getString() }

// kept, renamed — genuine parsing, no defaulting
fun ApplicationConfig.getCommaSeparatedSet(path: String): Set<String> =
    property(path).getString()
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

// removed: getStringOrDefault, getIntOrDefault, getLongOrDefault, getCommaSeparatedSetOrDefault
// jdbcUrl: unchanged
```

> **Decision point — inline vs. thin typed helpers.** The spec above inlines `config.property(path).getString().toInt()` / `.toLong()` to match the existing `jwtSecret` line and keep `ConfigExtensions.kt` minimal. If the implementer finds the repeated `.getString().toInt()` noisy, thin **non-defaulting** helpers (`getInt(path)`, `getLong(path)`) are an acceptable alternative — provided they carry no default. Pick one and apply it uniformly. Simpler is better.

## Extra Considerations

- **Required reads fail fast.** `config.property(path)` throws if the key is absent. In production this is desirable — a missing key is a deploy misconfiguration that should surface at startup, not be masked by a silent fallback. `application.conf` supplies every key, so production is unaffected.
- **Tests don't see HOCON.** Tests build `MapApplicationConfig` directly, so every required key must be present in the test map. `testApplicationConfig()` already lists all keys except `database.name` — add it (`"database.name" to "sdkman"`, matching `PostgresTestContainer.withDatabaseName("sdkman")`).
- **`HikariPoolIntegrationSpec` should reuse, not duplicate.** It currently hand-builds a partial map (missing `database.name`, `api.cache.control`, `admin.*`, `jwt.expiry`, `validation.*`). With eager required reads, constructing `DefaultAppConfig` from that map throws. Refactor it to start from `testApplicationConfig()` (which already targets `PostgresTestContainer`) and override only the pool keys it varies. `MapApplicationConfig.put(path, value)` overrides an existing key, so:
  ```kotlin
  val config = DefaultAppConfig(
      testApplicationConfig().apply {
          put("database.pool.maxSize", maxSize.toString())
          put("database.pool.connectionTimeoutMs", "10000")
      },
  )
  ```
  This removes the duplication and keeps the test's intent (small pool, many concurrent queries) explicit.
- **`DefaultAppConfigUnitSpec` has nothing left to test.** Its assertions (`databasePoolMaxSize shouldBe 20`, …) only verified Kotlin-side fallbacks. Once defaults live solely in HOCON, the equivalent assertion would just re-state `application.conf` constants against a config loaded from the same file — circular. Delete the spec; the pool wiring remains covered by `HikariPoolIntegrationSpec` (behavioural) and the acceptance suite (which boots the full app from real config).
- **No new env vars beyond `DATABASE_NAME`.** R3's `${?DATABASE_NAME}` is the only added override, included for consistency with the other `database.*` keys. It defaults to `"sdkman"` when unset — no behaviour change.
- **Detekt / ktlint.** Follow existing `ConfigExtensions.kt` patterns: expression bodies, `Option`, no nullables, no suppressions.
- **No OpenAPI or schema changes.** This is purely internal config plumbing.

## Testing Considerations

**Framework:** Kotest `ShouldSpec`, Testcontainers Postgres for integration/acceptance (already in place).

**Existing coverage is sufficient — no new tests required.** The change is a behaviour-preserving refactor validated by the existing suite:
- **Acceptance** (`HealthCheckAcceptanceSpec`, version/tag/auth specs) boots the full app from `testApplicationConfig()`; a missing required key would throw at startup and fail these. This proves every key the app touches is supplied.
- **Integration** (`HikariPoolIntegrationSpec`) continues to prove pool behaviour after the refactor to reuse `testApplicationConfig()`.

**Deleted:** `DefaultAppConfigUnitSpec` (see Extra Considerations).

**Do not** add a replacement unit test that re-asserts HOCON constants — that reintroduces the circular, low-value testing the spec is removing.

## Suggested Slice Breakdown

Independently committable, in dependency order. Each slice is a single Conventional Commit.

1. **`refactor: move database.name default into application.conf`** — add `name = "sdkman"` / `${?DATABASE_NAME}` to HOCON; add `"database.name"` to `testApplicationConfig()`. `databaseName` still read via `getStringOrDefault` at this point (default now matches HOCON). Smallest, safest first step; isolates the one key that lacked a HOCON entry.
2. **`refactor: read config defaults from HOCON only`** — convert all `getXxxOrDefault` reads in `DefaultAppConfig` to required/`Option`/parsed reads; remove the four `getXxxOrDefault` helpers; rename comma-separated helper to `getCommaSeparatedSet`. `./gradlew check` must stay green.
3. **`refactor: reuse shared test config in HikariPoolIntegrationSpec`** — derive its config from `testApplicationConfig()` with pool overrides instead of a bespoke partial map.
4. **`test: remove hollow DefaultAppConfigUnitSpec`** — delete the spec that only asserted Kotlin-side fallbacks.

## Verification

After all slices are merged:

- [ ] `./gradlew check` passes cleanly (compile + detekt + ktlint + tests).
- [ ] `grep -RIn "OrDefault" src/main` returns no hits (all defaulting helpers gone).
- [ ] Each default value appears exactly once across `application.conf` + `DefaultAppConfig` (no literal in Kotlin duplicates a HOCON value).
- [ ] `DefaultAppConfigUnitSpec` no longer exists.
- [ ] `HikariPoolIntegrationSpec` derives from `testApplicationConfig()`; its assertions are unchanged and it passes.
- [ ] Manual smoke: boot against a local Postgres with no `DATABASE_*` env vars set; app starts and `/meta/health` returns healthy (proves HOCON defaults back every required read).
- [ ] Manual smoke: unset a required key in a scratch copy of `application.conf` and confirm startup fails fast with a clear "property … not found" error (proves required reads surface misconfiguration).
- [ ] OpenAPI spec and DB schema confirmed **unchanged**.
