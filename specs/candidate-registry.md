# Candidate Registry

The State API owns every `versions` row but has no idea what a candidate *is*. Candidate metadata — the name, description and website that `sdk list` renders — lives in a MongoDB `candidates` collection read directly by the Candidates Service. This feature moves that collection into Postgres and makes `candidates` a first-class State API resource.

It also resolves a duplication that already exists inside this service. `src/main/resources/candidates.txt` is a 79-line resource loaded by `CandidateLoader` and enforced by `VersionRequestValidator` as the allow-list for `POST /versions`. That file **is** a candidate registry, maintained by hand, deployed with the service, and independent of the 84-entry Mongo collection it shadows. The two disagreed: `cuba` and `ktx` were retired by deleting them from the file, while `jpx` and `ksrc` fell out of it by accident and cannot be published to at all. This feature collapses both into one table, and `candidates.txt` and `CandidateLoader` are deleted.

*Reference: this is phase 2 of the MongoDB→PostgreSQL move. Scope, measurements and the decisions behind it are in [`../../../docs/specs/candidates-end-game.md`](../../../docs/specs/candidates-end-game.md); the hard-to-reverse calls are recorded as [`docs/decisions/0007`](../../../docs/decisions/0007-platform-classification-deleted.md), [`0008`](../../../docs/decisions/0008-registry-enforced-in-application.md) (which supersedes [`0006`](../../../docs/decisions/0006-candidate-foreign-key.md)) and [`0009`](../../../docs/decisions/0009-candidate-descriptions-normalised.md). The word `candidates` also names the **vendor authorisation scope** (`vendors.candidates`, the JWT claim, `CreateVendorRequest.candidates`). That is a different concept and this feature does not touch it.*

## Behaviour

A candidate is a registered installable, identified by a short lowercase name (`java`, `gradle`, `scalacli`). Registering one is an administrative act: it makes the candidate visible to `sdk list` and makes it publishable through `POST /versions`. Those are now the same act, where before they were two.

**Reading.** `GET /candidates` returns every registered candidate, ascending by identifier, with its presentation metadata. Each candidate also carries a **derived** `default`: the version currently holding the `lts` tag for that candidate. The value is derived per request from `version_tags` and is never stored, so it cannot go stale.

Resolving `default` needs a platform, because tags are scoped to one. The candidate record does not say which platform a candidate lives on — that classification existed in Mongo, was unreliable, and is deliberately not carried across ([`0007`](../../../docs/decisions/0007-platform-classification-deleted.md)). Instead a fixed order applies: **prefer the `UNIVERSAL` row, fall back to `LINUX_X64`**. A candidate with no `lts` tag at either platform has no `default`, and the field is absent.

`java` is **excluded** from `default`. Its `lts` tag exists once per distribution, so a single value would be arbitrary, and its public identifier is distribution-qualified (`25.0.4-tem`) in a way no other candidate's is. Callers needing java's default resolve it themselves through the existing `GET /versions/java/tags/lts?platform=LINUX_X64&distribution=TEMURIN`. `java` still appears in the listing like any other candidate; only its `default` is absent.

`GET /candidates` is public and unauthenticated, like the version read routes, and carries the same cache headers.

**Writing.** An admin registers a candidate with `POST /admin/candidates`. Registration is an upsert keyed on the candidate name: posting an existing candidate updates its metadata. `DELETE /admin/candidates/{candidate}` removes one, but only if it has no versions — a candidate with versions cannot be deleted, and the request is refused rather than orphaning rows.

**Publishing.** `POST /versions` continues to reject an unknown candidate with `400`, but the answer now comes from the table rather than from a file on the classpath. Registering a candidate is therefore sufficient to publish to it, with no deploy.

## API Contract

### `GET /candidates`

```
GET /candidates
```

Public. No parameters. No pagination — the registry is order-of-100 rows.

| Status | Body | When |
|---|---|---|
| `200 OK` | `CandidateDto[]` | Always, including an empty registry (`[]`) |
| `500 Internal Server Error` | `ErrorResponse` | Database error |

Response body (`CandidateDto`):

| Field | Type | Notes |
|---|---|---|
| `candidate` | string | the identifier, e.g. `gradle` |
| `name` | string | display name, e.g. `Gradle` |
| `description` | string | free text, up to 2000 characters, a single paragraph of printable ASCII |
| `website_url` | string | always `https` |
| `default` | string, optional | the `lts`-tagged version; absent when unresolved, and always absent for `java` |

```json
[
  {
    "candidate": "gradle",
    "name": "Gradle",
    "description": "Gradle is a build tool with a focus on build automation.",
    "website_url": "https://gradle.org/",
    "default": "8.14"
  },
  {
    "candidate": "java",
    "name": "Java",
    "description": "Java Platform, Standard Edition.",
    "website_url": "https://www.oracle.com/java/"
  }
]
```

Rows are ordered ascending by `candidate`. The order is part of the contract: the Candidates Service renders `sdk list` straight from it.

### `POST /admin/candidates`

```
POST /admin/candidates
Authorization: Bearer <admin token>
Content-Type: application/json
```

Request body (`CreateCandidateRequest`):

| Field | Required | Description |
|---|---|---|
| `candidate` | yes | identifier, `^[a-z][a-z0-9]*$`, 1–20 characters |
| `name` | yes | display name, 1–100 characters |
| `description` | yes | free text, 1–2000 characters, a single paragraph of printable ASCII |
| `website_url` | yes | absolute `https` URL, up to 500 characters |

| Status | Body | When |
|---|---|---|
| `201 Created` | `CandidateAdminDto` | Candidate did not exist and was registered |
| `200 OK` | `CandidateAdminDto` | Candidate existed and its metadata was updated |
| `400 Bad Request` | `ValidationErrorResponse` | Validation failure or malformed JSON |
| `401 Unauthorized` | `ErrorResponse` | Missing, invalid, or non-admin token |
| `500 Internal Server Error` | `ErrorResponse` | Database error |

`201` and `200` are told apart by the upsert itself — whether the row already existed, reported by the write — not by a preceding `SELECT`. A concurrent double-post therefore cannot answer `201` twice.

Malformed JSON is a `400` `ValidationErrorResponse` like any other failure, never a deserialisation `500`. That means taking the raw body and mapping the parse failure into the accumulated-error shape, as `VersionRequestValidator.validateRequest` already does. It is explicitly *not* the `call.receive<CreateVendorRequest>()` the neighbouring vendor admin routes use, which throws before the handler can shape a response. This is the one place where following the vendor precedent gives the wrong status.

`CandidateAdminDto` is `CandidateDto` without the derived `default`, plus `created_at` and `updated_at` as ISO-8601 instants. The JSON field is `updated_at`, matching `VendorResponse`; the column behind it is `last_updated_at`, matching `versions` and `version_tags`. The two conventions already disagree in this codebase, so each layer follows its own. The column *type* follows `vendors` too: `TIMESTAMPTZ`, so rendering an instant is a conversion rather than an assumption about the server's zone. See *Database Schema*.

### `DELETE /admin/candidates/{candidate}`

```
DELETE /admin/candidates/{candidate}
Authorization: Bearer <admin token>
```

| Status | Body | When |
|---|---|---|
| `200 OK` | `CandidateAdminDto` | Deleted; body is the removed record |
| `401 Unauthorized` | `ErrorResponse` | Missing, invalid, or non-admin token |
| `404 Not Found` | `ErrorResponse` | No such candidate |
| `409 Conflict` | `CandidateConflictResponse` | The candidate still has versions |
| `500 Internal Server Error` | `ErrorResponse` | Database error |

`CandidateConflictResponse` carries `error`, `message` and `version_count`, following the structured-conflict shape `DELETE /versions` already uses for `TagConflictResponse` rather than interpolating a count into prose.

Only versions are counted. A `version_tags` row cannot outlive its version (`version_tags.version_id REFERENCES versions(id) ON DELETE RESTRICT`), so a tags-only conflict is unreachable in practice.

Deletion is hard, not soft — unlike vendors, a candidate carries no history worth preserving once its versions are gone.

## Business Rules

1. **The registry is the allow-list.** `POST /versions` accepts a candidate if and only if it is registered. There is no second list, no classpath resource, and no configuration override.
2. **Registration is an upsert.** `POST /admin/candidates` for an existing candidate updates `name`, `description` and `website_url` and refreshes `last_updated_at`. The identifier itself is never mutated; renaming a candidate is a delete plus a create.
3. **A candidate with versions cannot be deleted.** The service counts the candidate's versions and returns `409`. There is no database constraint behind it: this count *is* the mechanism, not a friendlier surface over one. It therefore carries its own test rather than leaning on a backstop. It is a check and not a lock: a `POST /versions` arriving after the count is not stopped by it, and rule 13 records why that window is accepted. Delete the versions first.
4. **`default` is derived, never stored.** It is read from `version_tags` on every request. Nothing writes a default onto a candidate row, and there is no endpoint to set one — moving a default means moving the `lts` tag.
5. **`default` resolves `UNIVERSAL` first, then `LINUX_X64`.** No other platform is consulted — the resolution is restricted to those two, not merely ordered by them. A candidate whose `lts` sits only on, say, `MAC_ARM64` has no `default`.
6. **`default` only considers rows with no distribution**, matched on **`versions.distribution`**, not `version_tags.distribution`. Every non-java candidate stores `distribution` as `NULL` (see the `NA`→`NULL` convergence in [`distribution-na-to-null.md`](distribution-na-to-null.md)). `PostgresVersionRepository.findByTag` filters the same column, so the two agree by construction; filtering the other one would let `GET /candidates` and `GET /versions/{c}/tags/lts` disagree for the same candidate.
7. **`default` does not filter on `visible`.** `findByTag` does not either, and `V18` knowingly leaves `lts` pointing at retired rows until DISCO next hydrates the tag (it even `RAISE NOTICE`s them). Filtering here would make the `sdk list` header and `sdk default` show different versions for exactly that window. The two reads must agree, even when what they agree on is temporarily stale.
8. **`java` never carries a `default`.** It is excluded by name. This is the one candidate-specific rule in the service and it exists because java is the one candidate with per-distribution tags.
9. **`website_url` must be `https`.** Enforced on write, reusing the existing `HTTPS_URL_PATTERN` from version download URLs. That pattern is currently a private companion constant inside `VersionRequestValidator`, so reuse means hoisting it somewhere shared rather than copying it.
10. **Candidate identifiers are lowercase and case-sensitive.** `Java` is not `java`; it is rejected by the pattern.
11. **Ordering is ascending by `candidate`**, matching the sort the Mongo repository applied.
12. **The vendor authorisation scope is unaffected.** `vendors.candidates` remains a free-form `TEXT[]` and a vendor may still be scoped to a candidate that does not exist. Nothing is foreign-keyed to this table, so that is not an exception to a rule; it is the same rule.
13. **`versions.candidate` is not a foreign key, and an orphan row would be inert.** A version whose candidate is absent from the registry never appears in `GET /candidates`, so `sdk list` cannot reach it, but it still resolves by exact identifier on the download path, which does not consult the registry. Rule 3's `409` is a check rather than a lock, so the concrete way an orphan can now arise is a `DELETE /admin/candidates/{candidate}` racing a `POST /versions`: the count sees no versions, the delete commits, and the publish lands after it. **That race is accepted, not closed.** No isolation level closes it, because the publish path reads the in-memory registry and never touches the `candidates` table, so Postgres has no read/write conflict to detect; closing it would take either the foreign key [`0008`](../../../docs/decisions/0008-registry-enforced-in-application.md) declined or a registry read inside the publish transaction, which is the database read the in-memory allow-list exists to avoid. The window is also wider than a transaction: the publishing instance keeps accepting the deleted candidate until its cached set next refreshes (see *Validation*). The trade is taken knowingly — deletion is a rare administrative act, and the outcome is an inert row rather than a broken read. No such row exists in production today — every `versions` row names a registered candidate — so this remains a consequence the design accepts rather than one it has observed. (`cuba` and `ktx` are the inverse case, not an example: Postgres holds none of their rows, while Mongo still lists the candidates.) Consumers reading `versions` directly must not assume the join holds ([`../../../docs/contracts.md`](../../../docs/contracts.md) §4.6).
14. **Descriptions are a single paragraph of printable ASCII.** Enforced on write, and applied to the backfilled set by `candidates_migration/`. `sdk list` renders descriptions into a fixed-width terminal box, where an embedded newline breaks the layout and a non-ASCII codepoint is mojibake under a non-UTF-8 locale. See [`0009`](../../../docs/decisions/0009-candidate-descriptions-normalised.md).

## Validation

Structural validation only, following the accumulated-error pattern (all failures returned in one `400` `ValidationErrorResponse`):

- `candidate` must be present, non-blank, at most 20 characters, and match `^[a-z][a-z0-9]*$`.
- `name` must be present, non-blank, at most 100 characters.
- `description` must be present, non-blank, at most 2000 characters, and a **single paragraph of printable ASCII**: every character in `0x20`–`0x7E`, no control characters, no line breaks, and no runs of consecutive spaces.
- `website_url` must be present and match the existing HTTPS URL pattern, at most 500 characters.

`VersionRequestValidator`'s candidate check changes source, not shape. `InvalidCandidateError` still returns the same message and enumerates the allowed values, but reads them from the registry instead of `CandidateLoader.allowedCandidates`, **sorted ascending** — the file order it inherits today disappears with the file.

**The check is against an in-memory set, and validation stays synchronous.** The registry is order-of-100 rows, static in nature, and consulted on every `POST /versions`. Holding it in memory keeps validation a pure, non-`suspend` computation, which is what avoids a coroutine ripple through `VersionRoutes.kt`. Staying synchronous is the property that matters; how the set reaches the validator is an implementation-plan question, and the *Domain & Implementation Notes* record why the obvious answer does not work.

**The set must not be loaded once for the process lifetime.** `CandidateLoader` loads `by lazy`, which is right for a classpath resource and wrong for a table that `POST /admin/candidates` mutates. Inheriting that would mean a candidate registered through the API is rejected by `POST /versions` until the service restarts — the `jpx` / `ksrc` failure the merged registry exists to remove, surviving the migration in a new form.

**Freshness is bounded, not instantaneous.** A successful `POST /admin/candidates` **or `DELETE /admin/candidates/{candidate}`** refreshes the set in the process that served it — both writes evict, not just the register, or a deleted candidate stays publishable on the very instance that removed it. A TTL refresh backs that up, because eager invalidation is only complete on a single instance: a sibling that did not serve the write keeps its own copy until its TTL expires. The observable contract is therefore *a newly registered candidate becomes publishable within the TTL, a deleted one stops being publishable within the TTL, and neither needs a restart*. `sdkman-candidates` warms and refreshes the same registry on the same pattern ([`candidates-end-game.md`](../../../docs/specs/candidates-end-game.md) §10).

**The TTL is five minutes, and it is configuration.** The default lives once, in `application.conf`, per the service's [HOCON rule](../.claude/rules/hocon.md); no Kotlin fallback literal mirrors it.

```hocon
candidates {
    registry {
        refreshIntervalMs = 300000
        refreshIntervalMs = ${?CANDIDATE_REGISTRY_REFRESH_INTERVAL_MS}
    }
}
```

Five minutes is chosen against the write path, which is the only thing this TTL governs: how long after a `POST /admin/candidates` a sibling instance starts accepting that candidate on `POST /versions`. It bounds rule 13's delete window on the same path. It is deliberately not derived from `api.cache.control`, and it must not be read as bounding how long the registry takes to reach `sdk list` — that is a different chain with three hops:

| Hop | Mechanism | Today |
|---|---|---|
| `sdkman-state` → the wire | HTTP `max-age` from `api.cache.control`, inherited by `GET /candidates` because `CachingHeaders` installs on the root route | 600s |
| the wire → `sdkman-candidates` | Play WS response cache (`play.ws.cache.enabled=true`) honours that `max-age` | 600s |
| `sdkman-candidates` → the listing | that service's own candidate cache ([`candidate-registry-read-flip.md`](../../../candidates/sdkman-candidates/specs/candidate-registry-read-flip.md)) | 300s |

Worst case a newly registered candidate is **900 seconds** from appearing in `sdk list`, because the in-process refresh can re-fetch and be served the still-cached HTTP body. That is the read chain's number, not this one, and nothing here changes it. Aligning the two would mean either raising this TTL to 600s or giving `GET /candidates` its own `max-age`; both are out of scope, and the second is entangled with the `CachingHeaders` fix in *Domain & Implementation Notes*.

**A refresh failure serves the last good set; a cold failure is a `500`, never a `400`.** If a refresh fails, the previous set keeps serving — the registry changes rarely enough that a briefly stale allow-list beats rejecting valid publishes. If the *first* load fails there is nothing to fall back to, and `POST /versions` must answer `500`. The allow-list used to be a classpath resource and could not fail; a transient database error must not now surface as "candidate is not valid", which reads as permanent to a retrying client.

## Database Schema

Next free version is **V19**, but it is reserved by [`java-version-supersession.md`](java-version-supersession.md)'s all-migrated-series pass. Note that `V19` appears only on branch `spec/v19-all-migrated-series`; the copy of that spec checked out here describes `V17` and `V18` only. This feature therefore claims **V20**, and only V20. If that pass ships first, the number holds; if it never ships, V19 stays an intentional gap, which Flyway tolerates.

**`V19` must land before `V20` or not at all.** `Migration.kt` is a bare `Flyway.configure()`, so `outOfOrder` is false and `validateOnMigrate` is true: a `V19` applied *after* `V20` fails validation and the service refuses to boot. An unfilled gap is harmless; a late fill is not. The two sit on different branches (`spec/v19-all-migrated-series` and this one), so the ordering is a release dependency to check at merge, not one to assume. If the supersession pass is still unmerged when this is ready, renumber this migration above it rather than leaving V19 to land late.

**One migration, and it carries no data.** `V20` creates an empty table. Nothing on `versions` or `version_tags` is altered, because the registry is enforced by the request validator rather than by a constraint ([`0008`](../../../docs/decisions/0008-registry-enforced-in-application.md)), and the rows arrive afterwards over `POST /admin/candidates` rather than inside the migration ([`0009`](../../../docs/decisions/0009-candidate-descriptions-normalised.md) covers what happens to them on the way). See *Rollout*.

`src/main/resources/db/migration/V20__create_candidates_table.sql` — the DDL, and nothing else:

```sql
-- Candidate metadata, migrated from the MongoDB `candidates` collection.
-- `candidate` is the natural primary key. It is NOT a foreign-key target:
-- `versions.candidate` and `version_tags.candidate` stay plain columns, and the
-- registry is enforced in the application (docs/decisions/0008).
--
-- The column is still TEXT, matching them exactly -- `versions` was recreated
-- with modern TEXT types in V5, superseding V2's VARCHAR(20), and V12 declared
-- version_tags.candidate TEXT -- so that adding the constraint later stays a
-- one-line ALTER per table rather than a type migration. The CHECK carries the
-- shape the request validator enforces, so the two cannot drift.
--
-- This migration creates the table and stops. The rows arrive over
-- POST /admin/candidates after deploy; see the spec's Rollout section.
--
-- (Implementer note: PostgresVersionRepository maps this column as
-- varchar("candidate", length = 20). That is a pre-existing mismap of a TEXT
-- column; do not take it as evidence of the real type.)
--
-- The timestamps are TIMESTAMPTZ, following `vendors` (V13) rather than
-- `versions` (V2) and `version_tags` (V12), which are zoneless. The two
-- conventions already disagree in this schema, and this table follows the one
-- whose output shape matches: CandidateAdminDto renders these as ISO-8601
-- instants exactly as VendorResponse does, and an instant cannot be rendered
-- from a zoneless column without silently assuming the server's zone.
--
-- Deliberately absent:
--   `default`      -- derived from version_tags at read time, never stored
--   `distribution` -- the Mongo platform classification (UNIVERSAL /
--                     PLATFORM_SPECIFIC); unreliable, and collides by name with
--                     the java vendor distribution. See docs/decisions/0007.

CREATE TABLE candidates
(
    candidate       TEXT         PRIMARY KEY CHECK (candidate ~ '^[a-z][a-z0-9]{0,19}$'),
    name            TEXT         NOT NULL,
    description     TEXT         NOT NULL,
    website_url     TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### The table ships empty

`V20` creates the table and stops. The candidate rows arrive afterwards, over `POST /admin/candidates`, driven by `candidates_migration/` in the parent workspace: it dumps the live Mongo `candidates` collection, drops the retired and fixture candidates, applies the `https` remediation, normalises the descriptions, and posts them. **That retargeting is done.** The tool was originally a generator emitting the SQL seed this revision cancelled (`dc89cc2`); it was rebuilt as `{dump, migrate, verify}` in `1e1fbc9` and its suite is green. See [`../../../docs/specs/candidates-end-game.md`](../../../docs/specs/candidates-end-game.md) §8. Which candidates are in the set, and why `cuba`, `ktx` and `test` are not, is [`../../../docs/specs/candidates-end-game.md`](../../../docs/specs/candidates-end-game.md) §6.

An earlier revision carried the 81 rows as an `INSERT` at the end of `V20`. That was forced by a foreign key it no longer has: the constraint had to be added against populated tables, Flyway runs every pending migration before routing is configured, so the rows could not arrive over HTTP in between. Removing the constraint removes the reason, and three things come back with the API route ([`0008`](../../../docs/decisions/0008-registry-enforced-in-application.md)):

- **The write path is exercised in production before anything depends on it.** That was the phase 1 backfill's secondary benefit. A seeded `INSERT` would leave `POST /admin/candidates` unexercised until somebody onboarded a candidate months later.
- **The data stays correctable.** Flyway checksums a migration once it has applied, so 81 descriptions baked into `V20` are frozen: a copy edit becomes a `V22`, or direct SQL. Registration is an upsert (business rule 2), so over the API the backfill is re-runnable and a correction is another `POST`.
- **No SQL escaping rules.** Descriptions carry apostrophes, and the normalisation of [`0009`](../../../docs/decisions/0009-candidate-descriptions-normalised.md) is a rendering contract rather than a defence against a `'` closing a string literal or a `${` tripping Flyway's placeholder replacement, which is on by default (`Migration.kt` uses a bare `Flyway.configure()`).

**There is deliberately no second migration.** The earlier revision also added `V21__add_candidate_foreign_keys.sql`, making `versions.candidate` and `version_tags.candidate` foreign keys with `ON DELETE RESTRICT`. That is not done, for the reasons in [`0008`](../../../docs/decisions/0008-registry-enforced-in-application.md). The column types above are chosen so the trade can be re-taken later as a one-line `ALTER` per table.

Every text column is `TEXT`, matching what V5 established for `versions`. The longest live description is 966 characters. The lengths in *Validation* are application-level guards, not column widths; the only shape the database enforces is the `candidate` `CHECK`. That check outlives the foreign keys that first justified it, and for a better reason: `candidate` is referenced by value from two other tables and from every public URL, so a malformed identifier landing there is unrecoverable rather than merely wrong.

## Rollout

**Two state releases with a backfill between them.** The registry becomes load-bearing only in the second, which is what keeps the sequence free of any window in which publishing is refused.

1. **Release A** — `V20` creates the empty table; `GET /candidates` and both `/admin/candidates` routes appear. `VersionRequestValidator` is **unchanged** and still reads `candidates.txt`, so the empty registry gates nothing. `GET /candidates` returns `[]`, which no deployed consumer reads yet. The registry holder, its TTL configuration and its readiness state are **release B**: nothing in A consults the table for validation, and shipping a holder nothing reads would only add a failure mode. A's only new database reads are the three routes' own.
2. **Backfill** — `candidates_migration/` posts the 81 candidates through `POST /admin/candidates` and verifies the result with `GET /candidates`. Re-runnable, because registration is an upsert.
3. **Release B** — the validator switches to the registry; `candidates.txt`, `CandidateLoader` and the `ALLOWED_CANDIDATES` companion constant are deleted.
4. The Candidates Service read flip follows, specced separately.

**Why the split, given the migration no longer forces one.** Collapsing A and B into one release would work, at the cost of a window: between the deploy and the backfill the registry is empty and load-bearing, so every `POST /versions` returns `400`. It would last seconds to minutes and a rejected DISCO publish simply retries the next day, so the risk is small rather than absent. The split removes it outright and buys something better — the backfill runs against a service where nothing yet depends on the table, so a botched or partial run is harmless and simply re-run. `sdkman-state` autodeploys on push, so the extra release is cheap.

This is the phase 1 shape: build the write path, exercise it with real data, then flip the readers. The earlier objection to a split — that it produced a CI-passing but undeployable branch — was the foreign key's doing. Release A here is deployable on its own; it just does not use the new table yet.

**Nothing is locked, and nothing can refuse to boot.** An earlier revision added the foreign keys in a `V21`. That `ALTER TABLE` took `ACCESS EXCLUSIVE` on `versions` — the table Broker 2 reads directly on the download path ([`../../../docs/contracts.md`](../../../docs/contracts.md) §4.2) — and turned the first deploy into a pre-flight that would refuse to start if any production candidate fell outside the seeded set. Both properties go with the constraint. `V20` creates a new table and touches nothing already being read, so it takes no meaningful lock and has no precondition to fail.

**The local rehearsal is deleted.** `test/migration-rehearsal.sh` in the parent workspace existed to prove the `ALTER` applied cleanly against populated tables, and its `negative` phase to prove an orphan row failed cleanly rather than half-applying. With no constraint neither tests anything, and its `load` phase only existed to give them a production-shaped database. `seed/seed.sh` becomes the only local seeding mechanism. Note the ordering this release creates: once the validator reads the registry, candidates must be registered before any version is posted, or every `POST /versions` is refused.

**Rollback.** From release B, redeploy release A: the validator returns to `candidates.txt` and the table is left in place, unread. Safe in both directions, because no constraint was ever added and the registry is a superset of the file — the two candidates the file lacks, `jpx` and `ksrc`, simply become unpublishable again, which is the state they are in today. From release A, redeploy the prior release: the table is orphaned but harmless, and re-running the backfill after rolling forward is idempotent.

## Auditing

**None.** Candidate writes are recorded by `created_at` and `last_updated_at` on the row and nothing else. No `vendor_audit` entries, no `AuditOperation` value, no new table. Registration is a rare administrative act, and the audit machinery exists for the high-volume vendor-driven version writes. The one write worth a second thought is deletion, and it is guarded ahead of the fact by the `409` version count rather than recorded after it.

## Domain & Implementation Notes

- **Domain model:** a new `Candidate(candidate, name, description, websiteUrl, createdAt, lastUpdatedAt)`, and a read model carrying the derived `default` as an `Option<String>`.
- **Repository:** a new `CandidateRepository` / `PostgresCandidateRepository` with `findAll()`, `findAllWithDefaults()`, `upsert(...)`, and `delete(candidate)`. `findAllWithDefaults` is a single `LEFT JOIN` against `version_tags` and `versions` — the derived default must not become a per-row query.
- **Platform preference in SQL:** one row per candidate, restricted to the two platforms. `DISTINCT ON (candidate) ... WHERE version_tags.platform IN ('UNIVERSAL','LINUX_X64') ORDER BY candidate, CASE version_tags.platform WHEN 'UNIVERSAL' THEN 0 ELSE 1 END` is the shape. Qualify the column: both tables carry `platform`, and `findByTag` filters `version_tags.platform` alongside `versions.distribution`, so the two reads must agree on both predicates for the same reason rule 6 gives. A bare `LIMIT 1` is global, not per candidate, and returns a single row for the whole registry; an `ELSE 1` ordering without the `IN` restriction admits every platform and leaks a `MAC_ARM64`-only `lts` into `sdk list`. Both were reproduced against a real database.
- **Delete conflict:** count the candidate's versions and return `409` on a non-zero count. With no foreign key there is nothing to catch, so this count is the only thing standing between a delete and an orphaned set of version rows, and it needs a test of its own rather than an incidental one. It does **not** serialise against a concurrent `POST /versions`, and no isolation level makes it: the publish path reads the in-memory registry and never touches the `candidates` table, so there is no conflict for Postgres to see. Do not write a test asserting that it does. Rule 13 accepts the window. A new `DomainError` (e.g. `CandidateInUse`) carries the count to the route layer.
- **Validator: a frozen `Set<String>` at construction does not work.** The tempting move is to mirror `semverishCandidates: Set<String>`, which `Application.kt` reads once at startup. It cannot satisfy this feature. An immutable set captured at construction can be neither refreshed on write nor expired on a TTL, and it cannot represent "never loaded" as distinct from "loaded and genuinely empty" — which is the distinction the `500`-vs-`400` rule depends on. Whatever shape is chosen must therefore carry a *live* view plus a readiness state, not a value. Candidates for the implementation plan: inject the holder itself; inject a `() -> Set<String>` supplier; pass the set per call; or rebuild the validator on each refresh. Each trades purity against signature churn differently, and the choice is out of scope here. What the spec fixes is the behaviour: synchronous, no database read on the happy path, refreshable, and readiness-aware.
- **Registry holder:** a new component owns the set — startup load, configurable TTL refresh, eager refresh after a successful `POST /admin/candidates`. A failed refresh leaves the previous set in place. A failed *first* load leaves the holder unready, and `POST /versions` must answer `500` while it stays that way. That readiness check has to sit somewhere validation cannot swallow into a `ValidationError`, since the route maps those to `400`: either the route consults the holder before validating, or a distinct error type carries `500` out of it.
- **Every construction site gains an argument.** There are six, all currently single-argument: `Application.kt`, the test `support/Application.kt`, `HealthCheckAcceptanceSpec`, `LoginRateLimitDisabledAcceptanceSpec`, `VersionRequestValidatorSpec` and `VersionRequestSemverishValidatorSpec`. The last two also depend on `candidates.txt` today through `ALLOWED_CANDIDATES` — they use `java` heavily, plus `gradle`, `kotlin`, `maven` and `scala` — so each case with a valid candidate must now be handed a set containing it. This is signature churn, but it is not `suspend` churn, which is the expensive kind.
- **The rejection message needs a defined order.** `InvalidCandidateError.allowedCandidates` is a `List<String>` rendered with `joinToString(", ")`, and today's order is `candidates.txt` file order. A `Set` has no defined iteration order, so the message must enumerate the registry **sorted ascending**, matching `GET /candidates`. `VersionRequestValidatorSpec` asserts only that the message contains `"Allowed values:"`, so nothing currently catches a scrambled list.
- **Routes:** `versionReadRoutes` installs `CachingHeaders` on the **root** route (`Routing.kt` calls it on the `routing { }` receiver), so every JSON response already carries `max-age=appConfig.cacheMaxAge`. `GET /candidates` inherits it by existing, not by joining a group, and that is intended: the registry is static in nature and every consumer should cache it. The two admin routes join the `authenticate("auth-jwt")` block alongside the vendor admin routes and must respond `Cache-Control: no-store`. **`CachingHeaders` appends rather than replaces** — the plugin ends by `append`ing every computed header — and its options block fires for any `application/json` body, so simply setting `no-store` yields *two* `Cache-Control` values plus an `Expires`. The options block must return `null` for the admin routes, or those routes must suppress the plugin. The existing `CacheHeadersAcceptanceSpec` cannot see this, because `response.headers[HttpHeaders.CacheControl]` returns only the first value. Note that `/admin/vendors` is already affected the same way, setting `no-store` by the same call. Returning `null` from the options block for `/admin` paths fixes both; suppressing the plugin on the candidate routes alone leaves vendors emitting two values. Either is acceptable, but make it a decision — only the candidate routes are in this feature's acceptance criteria.
- **Non-admin handling:** `/admin/candidates` returns `401` for a valid non-admin token, matching `/admin/vendors` rather than the `403` used by the version write routes. Consistency with the neighbouring admin routes wins.
- **No nullable types.** Arrow `Option` throughout, per the existing convention.
- **Existing tests need a registered candidate.** Nothing in the suite seeds an allow-list today: the acceptance specs rely on `candidates.txt` being on the classpath with `java`, `gradle` and friends already in it. Once the validator reads the registry, an acceptance spec posting a version against a fresh Testcontainers database finds it empty and gets a `400`. The ~14 specs that post versions need a shared fixture registering the candidate first — one helper in `support/`, not fourteen ad-hoc inserts. (26 of 55 test files touch `versions`; only those going through `POST /versions` are affected, because a direct insert no longer meets a constraint.) Two things soften this relative to a foreign-key design: unit tests of the validator are untouched, because they construct it with an explicit set, and a test that wants an orphan version row can still insert one directly, since the database will not stop it.
- **Deletions:** `src/main/resources/candidates.txt` and `io/sdkman/state/config/CandidateLoader.kt` are removed in **release B**, along with the `ALLOWED_CANDIDATES` companion constant. Release A leaves all three in place and working.

## Access Matrix

| Endpoint | Anonymous | Vendor | Admin |
|---|---|---|---|
| `GET /candidates` | Yes | Yes | Yes |
| `POST /admin/candidates` | No (401) | No (401) | Yes |
| `DELETE /admin/candidates/{candidate}` | No (401) | No (401) | Yes |

## Examples

```gherkin
Feature: Candidate registry

  Scenario: List candidates in ascending order
    Given the candidates "gradle", "ant" and "scala" are registered
    When a client sends GET /candidates
    Then the response status is 200
      And the candidates are returned in the order "ant", "gradle", "scala"

  Scenario: An empty registry returns an empty array
    Given no candidates are registered
    When a client sends GET /candidates
    Then the response status is 200
      And the body is an empty array

  Scenario: A candidate's default comes from its lts tag at UNIVERSAL
    Given the candidate "gradle" is registered
      And version "8.14" of "gradle" on "UNIVERSAL" is tagged "lts"
    When a client sends GET /candidates
    Then "gradle" has a default of "8.14"

  Scenario: A candidate's default falls back to LINUX_X64
    Given the candidate "kuml" is registered
      And no "kuml" version on "UNIVERSAL" is tagged "lts"
      And version "0.20.5" of "kuml" on "LINUX_X64" is tagged "lts"
    When a client sends GET /candidates
    Then "kuml" has a default of "0.20.5"

  Scenario: UNIVERSAL wins when the lts tag exists at both platforms
    Given the candidate "scala" is registered
      And version "3.4.3" of "scala" on "UNIVERSAL" is tagged "lts"
      And version "3.3.1" of "scala" on "LINUX_X64" is tagged "lts"
    When a client sends GET /candidates
    Then "scala" has a default of "3.4.3"

  Scenario: A candidate with no lts tag has no default
    Given the candidate "jpx" is registered
      And no "jpx" version is tagged "lts"
    When a client sends GET /candidates
    Then "jpx" is listed
      And "jpx" has no default

  Scenario: Java never carries a default, even when an lts tag would otherwise resolve
    Given the candidate "java" is registered
      And version "25.0.4" of "java" with no distribution on "UNIVERSAL" is tagged "lts"
    When a client sends GET /candidates
    Then "java" is listed
      And "java" has no default

  # The row above deliberately has no distribution and sits on UNIVERSAL, so it
  # satisfies every other rule. Only the by-name exclusion can make this pass.
  # A scenario using a TEMURIN-tagged row would pass on rule 6 alone and would
  # never exercise rule 7.

  Scenario: A candidate whose only lts tag is on another platform has no default
    Given the candidate "connor" is registered
      And version "1.0.0" of "connor" on "MAC_ARM64" is tagged "lts"
      And no "connor" version on "UNIVERSAL" or "LINUX_X64" is tagged "lts"
    When a client sends GET /candidates
    Then "connor" has no default

  Scenario: Register a new candidate
    When an admin sends POST /admin/candidates
      | candidate   | jbang                          |
      | name        | JBang                          |
      | description | Java scripting, without a build |
      | website_url | https://jbang.dev/             |
    Then the response status is 201
      And "jbang" appears in GET /candidates

  Scenario: Re-registering a candidate updates its metadata
    Given the candidate "jbang" is registered with the name "JBang"
    When an admin posts "jbang" again with the name "JBang!"
    Then the response status is 200
      And "jbang" has the name "JBang!"

  Scenario: Registering a candidate makes it publishable
    Given the candidate "jpx" is not registered
      And posting a version of "jpx" returns 400
    When an admin registers "jpx"
    Then posting a version of "jpx" succeeds
      And no restart is required

  Scenario: Publishing to an unregistered candidate is rejected
    Given the candidate "nonesuch" is not registered
    When an authorized vendor posts a version of "nonesuch"
    Then the response status is 400
      And the error enumerates the registered candidates

  Scenario: The rejection message enumerates candidates in ascending order
    Given the candidates "scala", "ant" and "gradle" are registered
    When an authorized vendor posts a version of "nonesuch"
    Then the response status is 400
      And the error lists "ant, gradle, scala" in that order

  Scenario: A non-https website url is rejected
    When an admin registers a candidate with website_url "http://jbang.dev/"
    Then the response status is 400

  Scenario: An uppercase candidate identifier is rejected
    When an admin registers a candidate named "JBang" with identifier "JBang"
    Then the response status is 400

  Scenario: Delete a candidate with no versions
    Given the candidate "jbang" is registered
      And "jbang" has no versions
    When an admin sends DELETE /admin/candidates/jbang
    Then the response status is 200
      And "jbang" no longer appears in GET /candidates

  Scenario: Deleting a candidate that has versions is refused
    Given the candidate "gradle" is registered
      And "gradle" has versions
    When an admin sends DELETE /admin/candidates/gradle
    Then the response status is 409
      And "gradle" still appears in GET /candidates

  Scenario: Deleting an unknown candidate
    When an admin sends DELETE /admin/candidates/nonesuch
    Then the response status is 404

  Scenario: Deleting a candidate stops it being publishable, without a restart
    Given the candidate "jbang" is registered
      And "jbang" has no versions
    When an admin sends DELETE /admin/candidates/jbang
    Then posting a version of "jbang" to the same instance returns 400

  Scenario: A vendor cannot register a candidate
    Given a vendor token authorized for "gradle"
    When the vendor sends POST /admin/candidates
    Then the response status is 401

  Scenario: An unauthenticated client cannot register a candidate
    When an unauthenticated client sends POST /admin/candidates
    Then the response status is 401

  Scenario: An orphaned version row is inert
    Given a version row exists for the unregistered candidate "cuba"
    When a client sends GET /candidates
    Then the response status is 200
      And "cuba" does not appear in the listing
      And the service starts and serves normally

  Scenario: A registry refresh failure keeps serving the last good set
    Given the candidate "gradle" is registered and the registry has loaded
      And the database then becomes unavailable
    When a vendor publishes a version of "gradle"
    Then the version is accepted

  Scenario: A registry that has never loaded rejects with 500, not 400
    Given the registry has never loaded successfully
    When a vendor publishes a version of "gradle"
    Then the response status is 500

  Scenario: A description containing a trademark symbol is rejected
    When an admin registers a candidate described as "Apache Tomcat® software"
    Then the response status is 400

  Scenario: A description containing a line break is rejected
    When an admin registers a candidate whose description spans two lines
    Then the response status is 400

  Scenario: An over-long description is rejected
    When an admin registers a candidate with a 2001-character description
    Then the response status is 400

  Scenario: Malformed JSON is a validation failure, not a server error
    When an admin sends POST /admin/candidates with a body that is not valid JSON
    Then the response status is 400
      And the body is a ValidationErrorResponse
```

## Out of Scope

- The MongoDB `application` collection, `sdkman-hooks`, and Mongo retirement. Those are phase 3, [`../../../docs/specs/application-end-game.md`](../../../docs/specs/application-end-game.md).
- The Candidates Service's read flip. Specced separately in [`../../../candidates/sdkman-candidates/specs/candidate-registry-read-flip.md`](../../../candidates/sdkman-candidates/specs/candidate-registry-read-flip.md).
- The backfill tool itself. It is an operator-side script driving `POST /admin/candidates` over the contract above; it lives in the parent workspace, not in this repo.
- Foreign-keying the vendor authorisation scope (`vendors.candidates`) to this table.
- Any change to `POST /versions`, `DELETE /versions`, the tag routes, or the version read routes, beyond the source of the candidate allow-list.
- Auditing of candidate writes.
- A per-candidate default that is anything other than the `lts` tag; per-candidate tag configuration remains out of scope, as in [`resolve-version-by-tag.md`](resolve-version-by-tag.md).
- Storing or reviving any platform classification for a candidate.

## Acceptance Criteria

- [ ] `GET /candidates` returns every registered candidate, ascending by `candidate`, with `candidate`, `name`, `description` and `website_url`
- [ ] `GET /candidates` is reachable unauthenticated and carries the same cache headers as the version read routes
- [ ] An empty registry returns `200` with `[]`
- [ ] Each candidate carries a derived `default` resolved from the `lts` tag, preferring `UNIVERSAL` over `LINUX_X64`, matching only rows with no distribution
- [ ] `default` is absent when no `lts` tag resolves, and is always absent for `java`
- [ ] The derived default is computed in a single query, not one query per candidate
- [ ] `POST /admin/candidates` registers a candidate and returns `201`; re-posting an existing candidate updates it and returns `200`
- [ ] `POST /admin/candidates` returns `400` with accumulated failures for a blank field, a non-`https` `website_url`, or an identifier failing `^[a-z][a-z0-9]*$`
- [ ] `POST /admin/candidates` returns `400` for a `candidate` over 20 characters, a `name` over 100, a `description` over 2000, or a `website_url` over 500
- [ ] Malformed JSON on `POST /admin/candidates` returns `400` `ValidationErrorResponse`, not `500`
- [ ] `201` versus `200` is decided by the upsert itself, not by a preceding existence check
- [ ] `DELETE /admin/candidates/{candidate}` returns `200` and removes a candidate with no versions
- [ ] `DELETE /admin/candidates/{candidate}` returns `409` when the candidate has versions, and `404` when it does not exist
- [ ] Both admin routes return `401` for anonymous, vendor, and expired tokens
- [ ] `POST /versions` accepts a candidate registered through the API without a restart, and rejects an unregistered one with `400`
- [ ] `src/main/resources/candidates.txt` and `CandidateLoader` no longer exist
- [ ] `V20` creates an empty `candidates` table and is the only migration this feature adds
- [ ] `V20` is the highest migration in the tree when it merges, with no lower-numbered migration still pending
- [ ] `created_at` and `last_updated_at` are `TIMESTAMPTZ`, and `CandidateAdminDto` renders them as ISO-8601 instants without assuming a server zone
- [ ] No constraint is added to `versions` or `version_tags`, and no migration takes a lock on either
- [ ] A single boot applies `V20` and starts cleanly against a database already carrying versions and version tags
- [ ] A `versions` row referencing an unregistered candidate neither blocks startup nor appears in `GET /candidates`
- [ ] Release A boots with an empty registry and `POST /versions` still works, because the validator is unchanged in that release
- [ ] Every existing acceptance spec that writes a version registers its candidate first; none relies on a classpath allow-list
- [ ] No candidate write produces a `vendor_audit` row
- [ ] The derived `default` is restricted to `UNIVERSAL` and `LINUX_X64`; a candidate whose only `lts` tag is on another platform has no `default`
- [ ] The derived `default` does not filter on `visible`, and agrees with `GET /versions/{candidate}/tags/lts` for the same candidate
- [ ] `DELETE /admin/candidates/{candidate}` returns `409` from the service's own version count, not from a translated database error
- [ ] `POST /versions` performs no database read of the registry; the check is against the in-memory set
- [ ] `VersionRequestValidator.validate` and `validateRequest` remain synchronous
- [ ] The registry is not loaded once for the process lifetime: a candidate registered through `POST /admin/candidates` is publishable without a restart
- [ ] The registry refreshes on a TTL as well as on write, so an instance that did not serve the write picks the candidate up within the TTL
- [ ] `DELETE /admin/candidates/{candidate}` refreshes the registry in the serving process, so the deleted candidate stops being publishable there without waiting for the TTL
- [ ] The refresh interval is read from `application.conf` and defaults to five minutes; no Kotlin literal duplicates it
- [ ] No test asserts that the delete `409` serialises against a concurrent `POST /versions`; rule 13 records that window as accepted
- [ ] A registry refresh failure keeps serving the last good set rather than rejecting valid publishes
- [ ] `POST /versions` returns `500`, not `400`, when the registry has never loaded successfully
- [ ] `InvalidCandidateError` enumerates the registry sorted ascending, asserted on the order rather than only on the `"Allowed values:"` prefix
- [ ] `POST /admin/candidates` rejects a description carrying a non-ASCII character, a control character, a line break, or a run of consecutive spaces
- [ ] `POST /admin/candidates` supports a re-runnable backfill: registering the same candidate twice is an upsert, not a conflict, and the full set is readable back through `GET /candidates`
- [ ] Both admin routes respond with exactly one `Cache-Control`: `headers.getAll("Cache-Control")` is `["no-store"]`, and no `Expires` is sent
- [ ] `POST /admin/candidates` never mutates an existing candidate's identifier
- [ ] A `500` is returned, with `ErrorResponse`, on database error for all three endpoints
- [ ] OpenAPI documentation updated with all three endpoints and the `CandidateDto`, `CandidateAdminDto` and `CreateCandidateRequest` schemas
- [ ] No nullable types used — follows the Arrow `Option` pattern
- [ ] All quality gates pass (`./gradlew check`)
