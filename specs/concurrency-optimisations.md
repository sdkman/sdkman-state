# Concurrency & Throughput Optimisations Specification

## Overview

A vendor consuming `POST /versions` reports client-side `IOException: too many concurrent streams` when driving requests from a virtual-thread HTTP/2 client. Investigation found:

1. The application server (Ktor Netty) speaks HTTP/1.1 only — the HTTP/2 stream limit lives in the load balancer in front of it, not in our code.
2. The backend, however, has several real concurrency issues that make every request slower than necessary, which keeps HTTP/2 streams open longer and causes the LB's per-connection stream cap to be hit far sooner than it should be.

This spec addresses the backend issues. It does **not** change the public API, the database schema, the OpenAPI document, or the HTTP/2 settings of the load balancer.

**Key Properties:**
- Introduce HikariCP as a real JDBC connection pool (currently `DriverManager` opens a fresh TCP+auth connection per transaction).
- Make `POST /versions` write atomically in **one** transaction for version + tags (today it opens three independent transactions).
- Replace the racy check-then-act in `createOrUpdate` with a Postgres UPSERT (`INSERT … ON CONFLICT DO UPDATE`) against the existing `UNIQUE (candidate, version, distribution, platform)` index.
- Keep audit-log writes as best-effort and out of the main transaction (today's behaviour, intentionally preserved).

## Context: HTTP/2 Stream Limit (Non-Goal)

The `too many concurrent streams` error is raised by the **client's** HTTP/2 stack when it exceeds the server's `SETTINGS_MAX_CONCURRENT_STREAMS`. In this deployment that setting is owned by the DigitalOcean Load Balancer (or whatever ingress terminates TLS), not by Ktor — `application.conf` declares only a plaintext connector on port 8080 and there is no SSL/ALPN configuration, so the app itself serves HTTP/1.1 only.

**Out of scope for this spec:**
- Changing or documenting the LB's `MAX_CONCURRENT_STREAMS`.
- Enabling HTTP/2 on the Ktor server.
- Client-side guidance for the reporter (handled separately).

**In scope:** reducing per-request backend latency so that streams complete faster, which is the most leveraged way for us to reduce how often clients hit the LB's limit.

## Requirements

- **R1.** A pooled `javax.sql.DataSource` (HikariCP) is wired into Exposed and Flyway; no production code path uses `Database.connect(url, user, password, driver)`.
- **R2.** Pool size, minimum idle, connection timeout, max lifetime, and idle timeout are configurable through `application.conf` and environment variables, with safe defaults for a single-pod deployment against default Postgres (`max_connections = 100`).
- **R3.** A single `POST /versions` request performs **at most one** JDBC connection acquisition for the version write + tag replacement. Audit may use a separate short-lived transaction by design — see R6.
- **R4.** The version write is idempotent under concurrency: two concurrent `POST /versions` requests with the same `(candidate, version, distribution, platform)` both return `204 No Content` and result in exactly one row, with no unique-constraint exception surfacing to the client.
- **R5.** Tag replacement is atomic with the version write — if tag replacement fails, the version write rolls back (today they are in separate transactions).
- **R6.** Audit logging remains best-effort, in its own transaction. An audit insert failure must not roll back the version write and must surface only as a `WARN` log (preserves current behaviour).
- **R7.** The JDBC URL no longer enables verbose pgjdbc logging (`loglevel=2` removed).
- **R8.** `./gradlew check` passes. Existing acceptance, integration, and unit tests continue to pass without modifying their assertions. New tests cover the concurrency race and the pool wiring.

## Rules

**Before implementing, you MUST read and internalize:**
- `.claude/rules/kotlin.md` — Arrow `Either`/`Option`, no nullables, smart constructors.
- `.claude/rules/hexagonal-architecture.md` — infrastructure stays in the secondary adapter layer; ports/domain do not import `HikariCP`.
- `.claude/rules/kotest.md` — ShouldSpec, three-layer test strategy, Testcontainers for DB tests.
- `.claude/rules/quality-gates.md` — no `@Suppress`, no `@Disabled`, no relaxing detekt/ktlint.

**If a slice prompt conflicts with the rules, THE RULES WIN.**

## Configuration

### New `database.pool` block in `application.conf`

```hocon
database {
    host = "localhost"
    host = ${?DATABASE_HOST}
    port = 5432
    port = ${?DATABASE_PORT}
    username = "postgres"
    username = ${?DATABASE_USERNAME}
    password = "postgres"
    password = ${?DATABASE_PASSWORD}

    pool {
        maxSize = 20
        maxSize = ${?DATABASE_POOL_MAX_SIZE}
        minIdle = 2
        minIdle = ${?DATABASE_POOL_MIN_IDLE}
        connectionTimeoutMs = 5000
        connectionTimeoutMs = ${?DATABASE_POOL_CONNECTION_TIMEOUT_MS}
        maxLifetimeMs = 1800000
        maxLifetimeMs = ${?DATABASE_POOL_MAX_LIFETIME_MS}
        idleTimeoutMs = 600000
        idleTimeoutMs = ${?DATABASE_POOL_IDLE_TIMEOUT_MS}
    }
}
```

### `AppConfig` additions

```kotlin
interface AppConfig {
    // existing fields …
    val databasePoolMaxSize: Int
    val databasePoolMinIdle: Int
    val databasePoolConnectionTimeoutMs: Long
    val databasePoolMaxLifetimeMs: Long
    val databasePoolIdleTimeoutMs: Long
}
```

Defaults tuned for **single-pod deployment against Postgres `max_connections = 100`**:
- `maxSize = 20` → leaves ample headroom (Flyway, ad-hoc queries, future scale).
- `minIdle = 2` → keeps a couple of connections warm to absorb idle-to-active spikes.
- `connectionTimeoutMs = 5000` → fail fast if the pool is starved rather than holding HTTP streams open indefinitely.
- `maxLifetimeMs = 30 min`, `idleTimeoutMs = 10 min` → standard HikariCP recommendations.

### `jdbcUrl` change

In `ConfigExtensions.kt`, drop the `loglevel=2` query parameter:

```kotlin
// before
get() = "jdbc:postgresql://$databaseHost:$databasePort/$databaseName?sslMode=prefer&loglevel=2"
// after
get() = "jdbc:postgresql://$databaseHost:$databasePort/$databaseName?sslMode=prefer"
```

### Dependency added

`gradle/libs.versions.toml`:
```toml
hikari = "5.1.0"   # latest stable at time of writing — bump as appropriate
hikaricp = { module = "com.zaxxer:HikariCP", version.ref = "hikari" }
```

`build.gradle.kts`:
```kotlin
implementation(libs.hikaricp)
```

## Extra Considerations

- **Transaction nesting in Exposed.** `dbQuery` uses `newSuspendedTransaction(Dispatchers.IO) { … }`. When invoked inside an enclosing `newSuspendedTransaction`, Exposed reuses the same connection — no new helper is needed. The slice that wraps version + tag work simply opens one outer `newSuspendedTransaction` in `VersionServiceImpl.createOrUpdate` and lets the nested repository calls participate.
- **Audit remains separate by design.** Per R6, `auditRepo.recordAudit(...)` must execute **outside** the outer transaction so that an audit failure does not roll back the version write. The current best-effort warn-log behaviour is preserved verbatim.
- **UPSERT target.** Exposed 0.57 ships an `upsert {}` builder that compiles to Postgres `INSERT … ON CONFLICT (…) DO UPDATE SET …`. Target the `(candidate, version, distribution, platform)` columns. Note: the underlying database column is still named `vendor` despite the Kotlin field being `distribution` — the Exposed `VersionsTable` already maps this correctly, so the upsert conflict target uses `VersionsTable.distribution`.
- **Returning the version id.** Use the upsert's `RETURNING id` rather than issuing a follow-up `SELECT id`. The post-upsert flow needs the id for tag replacement.
- **Flyway shares the pool.** Flyway runs once at startup and must be configured with the **same** `HikariDataSource` (`Flyway.configure().dataSource(ds)`), not a separate `DriverManager` URL.
- **Health repository uses the pool.** `PostgresHealthRepository` must execute against the pool (otherwise the health check is unrepresentative of the data path). The single `Database.connect(dataSource)` wiring in `configureDatabase` should cover this transparently — verify when implementing.
- **No schema migrations.** The UPSERT relies on the existing unique constraints from `V2` (versions) and `V12` (version_tags). No new Flyway migration files.
- **No public API or OpenAPI changes.** Request/response contracts for all endpoints are untouched; `src/main/resources/openapi/documentation.yaml` does **not** need updating.
- **Detekt / ktlint.** New configuration helpers must follow existing patterns in `ConfigExtensions.kt` (Arrow `Option`, no nullables, expression bodies where natural). No suppressions.

## Testing Considerations

**Framework:** Kotest `ShouldSpec`, MockK for unit tests, Testcontainers Postgres for integration/acceptance tests (already in place).

**Acceptance (`@Tag("acceptance")`)** — golden-path coverage for the optimisations:
- **Concurrent same-version POST.** Fire N (e.g. 20) coroutines simultaneously, each posting the same `(candidate, version, distribution, platform)` payload, all expected to return `204 No Content`; assert exactly one row exists in `versions` after. Exercises the UPSERT race fix (R4).
- **Concurrent same-version POST with tags.** Same scenario but with `tags` payload; assert tag rows match the final write only (no orphaned tags from a partially applied attempt). Exercises R5.
- **Existing `IdempotentPostVersionAcceptanceSpec` continues to pass unchanged.**

**Integration (`@Tag("integration")`)**:
- **Pool wiring.** Open more concurrent `dbQuery {}` calls than `maxSize`, with a small fixed `maxSize`, and assert all complete successfully (queued requests served as connections free up).
- **Single-transaction write path.** A focused test on `VersionServiceImpl` with a spy/fake repository asserting that the version + tags work executes inside a single outer `newSuspendedTransaction` and audit executes in its own. Acceptable alternative: enable `log_statement = 'all'` on the test container and assert a single `BEGIN/COMMIT` pair for the version+tags work.

**Unit**:
- **`AppConfig` parsing.** Verify the new `database.pool.*` keys load from HOCON with defaults applied when env vars are absent.
- **Audit failure isolation.** Stub `auditRepo.recordAudit` to return a `Left`; assert the `POST /versions` flow still returns `Right(Unit)` and writes the version (preserved best-effort semantics — R6).

**Performance — out of scope.** No benchmarks required. The optimisations are validated by correctness tests (R3, R4) and qualitative production observation post-deploy.

## Suggested Slice Breakdown

Independently committable, in dependency order. Each slice is a single Conventional Commit.

1. **`chore: drop verbose pgjdbc loglevel from JDBC URL`** — one-line change in `ConfigExtensions.kt`. No test changes. Smallest, safest slice; lands first so subsequent slice verification isn't polluted with driver-level noise.
2. **`feat: introduce HikariCP connection pool`** — add Hikari dependency in `libs.versions.toml` and `build.gradle.kts`; add `database.pool.*` to `application.conf`; extend `AppConfig`/`DefaultAppConfig`; change `configureDatabase` to build a `HikariDataSource` and pass it to both `Database.connect(dataSource)` and `Flyway.configure().dataSource(ds)`. New unit test for `AppConfig` parsing; new integration test for pool starvation behaviour.
3. **`refactor: upsert versions to remove create-or-update race`** — replace check-then-act in `PostgresVersionRepository.createOrUpdate` with Exposed `upsert {}` targeting `(candidate, version, distribution, platform)`. New acceptance test for concurrent same-version POSTs (R4). Existing `IdempotentPostVersionAcceptanceSpec` continues to pass.
4. **`refactor: write version and tags in a single transaction`** — wrap the version+tags work in `VersionServiceImpl.createOrUpdate` in one outer `newSuspendedTransaction`. Keep `logAudit` outside it. New focused test for audit-failure isolation (R6) and single-transaction boundary (R5).

## Verification

After all slices are merged:

- [ ] `./gradlew check` passes cleanly (compile + detekt + ktlint + tests).
- [ ] `grep -RIn "loglevel" src/main` returns no hits.
- [ ] HikariCP is the only `DataSource` wired into Exposed and Flyway.
- [ ] New concurrent-POST acceptance test passes consistently across at least 10 local runs.
- [ ] Manual smoke: boot against a local Postgres, send 50 concurrent `POST /versions` for the same version, observe all `204` responses and exactly one row in `versions`.
- [ ] OpenAPI spec confirmed **unchanged** (no endpoint contract changes).
- [ ] Deployment notes: confirm `DATABASE_POOL_MAX_SIZE` is either unset (default `20`) or set deliberately in the production environment.
