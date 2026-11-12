# Distribution `NA` → `NULL` Convergence Specification

## Overview

The `versions` and `version_tags` tables represent "no distribution" (every non-Java
candidate) with an `'NA'` sentinel string in a `NOT NULL` `distribution` column. `versions`
acquired this in V15; `version_tags` was born with it in V12. The sentinel was introduced
for one reason: Postgres' default `UNIQUE … NULLS DISTINCT` treats two `NULL` distributions
as distinct, so `INSERT … ON CONFLICT (candidate, version, distribution, platform) DO UPDATE`
never fired for distribution-less rows and the single-path UPSERT dedup broke. `'NA'` removed
the `NULL` to keep dedup working.

This is the *only* place in the system that uses the sentinel. Checksums (`md5_sum`, …) are
nullable; the `audit` and `vendor_audit` tables already encode absent distribution as `NULL`
/ JSON `null`; and the downstream `sdkman-broker-2` reader was written against `NULL`
(`distribution IS NOT DISTINCT FROM NULL`, column nullable, spec says *"Never `'NA'`/`'NONE'`"*).
The sentinel is a lone deviation that broke the cross-service contract: with state writing
`'NA'`, broker-2's lookup matches nothing for non-Java candidates.

On **Postgres 15**, `UNIQUE NULLS NOT DISTINCT` makes `NULL`s collide for uniqueness, which
dissolves the sentinel's sole justification. This spec converts both columns back to nullable
`NULL`-for-absent and swaps each unique constraint to `NULLS NOT DISTINCT`, preserving the
single-path UPSERT while restoring the idiomatic, cross-service-correct representation.

**Key Properties:**
- "No distribution" is SQL `NULL` everywhere; the `'NA'` sentinel is removed entirely.
- Each unique constraint becomes `UNIQUE NULLS NOT DISTINCT`, so the existing Exposed
  `upsert(...)` still dedups distribution-less rows (Exposed emits column-list `ON CONFLICT`,
  which Postgres infers against the `NULLS NOT DISTINCT` index — no raw SQL needed).
- The domain model is unchanged: `distribution` stays `Option<Distribution>`. Only the
  persistence boundary changes (`Option` ↔ nullable column instead of `Option` ↔ `'NA'`).
- One Flyway migration (`V16`) covers both tables.
- No API/wire change (the OpenAPI `required`-field correction was already shipped separately).

## Context & Non-Goals

The drift surfaced a latent bug in `sdkman-broker-2` (RO reader of `versions`). That bug is
repaired *at the data source* by this change — broker-2 needs **no production code change**
because it already queries for `NULL`. broker-2's only follow-ups (a fidelity tweak to its
test migration and a contract-pinning test) are recorded in
`sdkman-broker-2/specs/versions_distribution_na_to_null_note.md` and are **out of scope here**.

**Out of scope:**
- Any change to `sdkman-broker-2`.
- The `audit` / `vendor_audit` tables — both already `NULL`/`null`-based; nothing to do.
- `visible` (`Option<Boolean>` over a `NOT NULL` column) and whether `UNIVERSAL` is a sentinel
  — separate modelling concerns.
- OpenAPI — the `required`-field correction for `distribution`/checksums already landed
  (`docs(openapi): mark distribution/checksums optional`).

**In scope:** the `V16` migration, the persistence adapters (`PostgresVersionRepository`,
`PostgresTagRepository`), the `NA_SENTINEL` constant, and the tests/test-support that encode
the sentinel.

## Requirements

- **R1.** `versions.distribution` and `version_tags.distribution` are nullable; all existing
  `'NA'` values become `NULL`; no `NOT NULL` and no `DEFAULT 'NA'` remain on either column.
- **R2.** Each table's unique constraint is recreated as
  `UNIQUE NULLS NOT DISTINCT (candidate, version, distribution, platform)` (versions) and
  `… (candidate, tag, distribution, platform)` (version_tags). The old constraint is dropped
  **by discovered name** (V7 renamed `versions.vendor → distribution` without renaming the
  constraint, so its name is unreliable).
- **R3.** Exactly one Flyway migration, `V16`, performs R1+R2 for both tables.
- **R4.** `NA_SENTINEL` (in `PostgresConnectivity.kt`) and the `distributionToDb` /
  `dbToDistribution` helper pairs (in both repositories) are deleted. `Option<Distribution>`
  maps directly to/from the nullable column.
- **R5.** Identity-match query sites read the absent case as `IS NULL`, not `= 'NA'`
  (`findUnique`, `findVersionId`, `findByTag`, `delete` in the version repo; `replaceTags`,
  `deleteTag` in the tag repo). `findByCandidate`'s `distribution` **filter** semantics
  (absent ⇒ no filter) are unchanged.
- **R6.** The existing single-path `upsert(...)` in `createOrUpdate` and `replaceTags` is
  retained unchanged; dedup of distribution-less rows continues to work via `NULLS NOT DISTINCT`.
- **R7.** Test support and specs that encode `'NA'` are updated to `NULL`. The dedup guarantee
  remains proven (see Testing). `./gradlew check` passes with no suppressions.
- **R8.** No domain-model change, no API change, no behaviour change other than the storage
  encoding of "absent distribution".

## Rules

**Before implementing, you MUST read and internalize:**
- `.claude/rules/kotlin.md` — Arrow `Option`, no nullables in the domain, expression bodies, immutability.
- `.claude/rules/hexagonal-architecture.md` — the `Option ↔ nullable` conversion stays in the adapter.
- `.claude/rules/kotest.md` — ShouldSpec, three-layer strategy, Testcontainers for DB tests.
- `.claude/rules/quality-gates.md` — no `@Suppress`, no `@Disabled`, no relaxing detekt/ktlint.

**If this spec conflicts with the rules, THE RULES WIN.**

## Domain

**Unchanged.** `Version.distribution` and `VersionTag`/`UniqueTag.distribution` remain
`Option<Distribution>` with `none()` for absent. The nullable column is an adapter-level
detail and must not leak a Kotlin nullable into the domain — convert at the boundary with
`.toOption()` / `getOrNull()`.

## Database Schema

Next available version is **V16**. One migration, both tables. The unique-constraint drop is
name-agnostic (R2); the column is made nullable **before** the `'NA' → NULL` update; the new
constraint is added **after** the update.

`src/main/resources/db/migration/V16__converge_distribution_to_null.sql`:

```sql
-- Replace the 'NA' distribution sentinel with SQL NULL on both tables.
-- The sentinel (versions: V15, version_tags: V12) only existed to keep ON CONFLICT
-- dedup working under Postgres' default NULLS DISTINCT uniqueness. Postgres 15's
-- UNIQUE NULLS NOT DISTINCT preserves that dedup with a nullable column, so the
-- sentinel is no longer needed. Constraints are dropped by discovered name because
-- V7 renamed versions.vendor -> distribution without renaming the constraint.

-- versions ---------------------------------------------------------------------
DO $$
DECLARE constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
      FROM pg_constraint
     WHERE conrelid = 'versions'::regclass AND contype = 'u';
    EXECUTE format('ALTER TABLE versions DROP CONSTRAINT %I', constraint_name);
END $$;

ALTER TABLE versions ALTER COLUMN distribution DROP DEFAULT;
ALTER TABLE versions ALTER COLUMN distribution DROP NOT NULL;
UPDATE versions SET distribution = NULL WHERE distribution = 'NA';

ALTER TABLE versions
    ADD CONSTRAINT versions_candidate_version_distribution_platform_key
    UNIQUE NULLS NOT DISTINCT (candidate, version, distribution, platform);

-- version_tags -----------------------------------------------------------------
DO $$
DECLARE constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
      FROM pg_constraint
     WHERE conrelid = 'version_tags'::regclass AND contype = 'u';
    EXECUTE format('ALTER TABLE version_tags DROP CONSTRAINT %I', constraint_name);
END $$;

ALTER TABLE version_tags ALTER COLUMN distribution DROP DEFAULT;
ALTER TABLE version_tags ALTER COLUMN distribution DROP NOT NULL;
UPDATE version_tags SET distribution = NULL WHERE distribution = 'NA';

ALTER TABLE version_tags
    ADD CONSTRAINT version_tags_candidate_tag_distribution_platform_key
    UNIQUE NULLS NOT DISTINCT (candidate, tag, distribution, platform);
```

**Safety note:** dropping the old unique constraint before the `UPDATE` avoids any transient
check; re-adding `NULLS NOT DISTINCT` afterwards cannot violate uniqueness, because the old
`NOT NULL` unique already forbade two `'NA'` rows in the same `(candidate, version, platform)`
scope, so at most one `NULL` row can exist per scope after conversion. The `DO $$` blocks
assume exactly one unique constraint per table (true: the only other constraint is the `id`
primary key, `contype = 'p'`). The regular `idx_version_tags_*` indexes are untouched.

## Persistence Adapter Changes

Both repositories drop the sentinel helpers and map `Option<Distribution>` straight to the
nullable column. Suggested boundary helpers (they do real work — null conversion and
null-aware matching — so they are not "default-holding" helpers):

```kotlin
// write: Option<Distribution> -> nullable column value
private fun Option<Distribution>.toDbValue(): String? = map { it.name }.getOrNull()

// read: nullable column value -> Option<Distribution>
private fun String?.toDistributionOption(): Option<Distribution> =
    toOption().map { Distribution.valueOf(it) }

// identity match in WHERE: None matches the NULL rows, Some matches the literal
private fun distributionEq(distribution: Option<Distribution>): Op<Boolean> =
    distribution.fold(
        { VersionsTable.distribution.isNull() },
        { VersionsTable.distribution eq it.name },
    )
```

### `PostgresVersionRepository.kt`

```kotlin
// before
internal object VersionsTable : IntIdTable(name = "versions") {
    val distribution = text("distribution")
    // …
}
private fun distributionToDb(distribution: Option<Distribution>): String =
    distribution.map { it.name }.getOrElse { NA_SENTINEL }
private fun dbToDistribution(value: String): Option<Distribution> =
    if (value == NA_SENTINEL) none() else Distribution.valueOf(value).some()

// after
internal object VersionsTable : IntIdTable(name = "versions") {
    val distribution = text("distribution").nullable()
    // …
}
// distributionToDb / dbToDistribution deleted; use toDbValue / toDistributionOption / distributionEq
```

- `toVersion()` — `distribution = this[VersionsTable.distribution].toDistributionOption()`.
- `createOrUpdate()` — `it[distribution] = version.distribution.toDbValue()`; `upsert(...)` keys
  and `onUpdateExclude` unchanged.
- `findUnique()`, `findVersionId()`, `findByTag()`, `delete()` — replace
  `(VersionsTable.distribution eq distributionToDb(x))` with `distributionEq(x)`.
- `findByCandidate()` — **unchanged**; its `distribution.map { … eq it.name }.getOrElse { Op.TRUE }`
  is filter semantics (absent ⇒ match all), which is already correct for a nullable column.

### `PostgresTagRepository.kt`

Same shape against `VersionTagsTable.distribution = text("distribution").nullable()`:

- `toVersionTag()` — null-aware read.
- `replaceTags()` — `val distDb = distribution.toDbValue()`; in the `deleteWhere` use the
  null-aware match for `distribution` (Exposed renders `eq null` as `IS NULL`, but prefer the
  explicit `fold(isNull, eq)` helper for clarity); `upsert(...)` writes `it[distribution] = distDb`.
- `deleteTag()` — null-aware match.
- The Exposed `uniqueIndex(candidate, tag, distribution, platform)` declaration in the table
  object is decorative (Flyway owns the real constraint; tests boot from Flyway, not
  `SchemaUtils`). Leave it; it does not need a `NULLS NOT DISTINCT` flag because Exposed never
  creates this schema.

### `PostgresConnectivity.kt`

- Delete `const val NA_SENTINEL = "NA"` (R4).

## Extra Considerations

- **Exposed upsert is already compatible (verified).** Exposed 1.3.0's Postgres dialect emits
  `INSERT … ON CONFLICT (candidate, version, distribution, platform) DO UPDATE SET …` —
  column-list inference, not a named-constraint target. Postgres infers the
  `NULLS NOT DISTINCT` unique index from the column set (the clause is an index property, not
  part of the set), and column nullability does not change the emitted SQL. So `createOrUpdate`
  and `replaceTags` keep working unchanged; the dedup acceptance test proves it at runtime.
- **`IS NULL` vs `IS NOT DISTINCT FROM`.** State uses explicit `None ⇒ IS NULL`, `Some ⇒ = literal`
  for identity matches. broker-2 uses `IS NOT DISTINCT FROM ?`. They are equivalent for these
  lookups; no need to align the syntax.
- **No nullable leak.** The nullable column value is converted to `Option` *inside* the adapter
  (`toDistributionOption`) and to a nullable *only* at the column write (`toDbValue`). The domain
  and all signatures stay on `Option<Distribution>` (kotlin.md RULE-001).
- **Migration is forward-only and data-safe** for the reasons in the schema safety note;
  Flyway runs it as part of the next `sdkman-state` deploy, after which broker-2 is correct.
- **detekt/ktlint:** expression bodies, `Option`, no nullables in the domain, no suppressions.

## Testing Considerations

**Framework:** Kotest `ShouldSpec`, Testcontainers Postgres (already in place; tests boot the
schema from the real Flyway migrations, so `V16` is exercised automatically).

- **Dedup guarantee — retarget the existing spec, do not add a new one.**
  `acceptance/ConcurrentPostVersionWithoutDistributionAcceptanceSpec` already proves that
  concurrent no-distribution POSTs collapse into a single row — currently asserting
  `VersionsTable.distribution eq NA_SENTINEL`. Update its assertion to
  `VersionsTable.distribution.isNull()` and refresh its header comment to reference
  `UNIQUE NULLS NOT DISTINCT` instead of V15's sentinel. This remains the canonical regression
  for R6, now proving the dedup works through `NULLS NOT DISTINCT`.
- **Test support — `support/Postgres.kt`.** Remove the `NA_SENTINEL` import and its six uses:
  seed writes (`it[distribution] = cv.distribution.toDbValue()`), the read mapping
  (`it[VersionsTable.distribution].toDistributionOption()`), and the two WHERE matches
  (null-aware). After this, `grep -RIn "NA" src/test` should find no sentinel references.
- **Round-trip coverage.** `PostgresVersionRepositoryIntegrationSpec` should cover (extend if
  absent): a `distribution = none()` version persists with `distribution IS NULL` and is read
  back as `none()`; `findUnique`/`findByTag` with `none()` match the `NULL` row; two rows that
  differ only by `Some(distribution)` (e.g. `TEMURIN` vs `ZULU`) for the same
  `(candidate, version, platform)` remain distinct.
- **Existing acceptance suite** (`PostVersion…`, `DeleteVersion…`, tag specs) must stay green
  unchanged — they exercise the API and prove no behavioural regression.
- **Do not** add a unit test that asserts the migration SQL text or re-states constants; rely
  on the Testcontainers-backed specs that boot the real schema.

## Specification by Example

**Persist a non-Java version (no distribution):**
```
POST /versions { "candidate": "groovy", "version": "4.0.0", "platform": "LINUX_X64", "url": "…" }
=> row: distribution IS NULL
GET  /versions/groovy … => distribution key absent (omitted under explicitNulls=false)
```

**Concurrent identical no-distribution POSTs (dedup):**
```
N concurrent POST /versions for (groovy, 4.0.0, LINUX_X64, no distribution)
=> all 204 NoContent; exactly ONE row exists; distribution IS NULL
```

**Java versions remain distinguishable by distribution:**
```
POST (java, 17.0.2, LINUX_X64, TEMURIN) and POST (java, 17.0.2, LINUX_X64, ZULU)
=> two distinct rows (NULLS NOT DISTINCT only collapses NULLs, not distinct literals)
```

## Verification

After implementation:

- [ ] `./gradlew check` passes cleanly (compile + detekt + ktlint + tests).
- [ ] `grep -RIn "NA_SENTINEL" src` returns no hits; `grep -RIn '"NA"' src/main src/test` returns no distribution-sentinel hits.
- [ ] `V16` migration present; on a freshly migrated DB, `\d versions` and `\d version_tags`
      show `distribution` nullable, no `DEFAULT`, and a `UNIQUE NULLS NOT DISTINCT` constraint.
- [ ] A `distribution = none()` version round-trips: stored `NULL`, read back `none()`.
- [ ] Concurrent identical no-distribution POSTs collapse to exactly one `NULL`-distribution row
      (`ConcurrentPostVersionWithoutDistributionAcceptanceSpec` green, asserting `isNull()`).
- [ ] Two `Some(distribution)` rows differing only by distribution remain distinct.
- [ ] Domain signatures still use `Option<Distribution>`; no Kotlin nullable escaped the adapter.
- [ ] OpenAPI and the `audit`/`vendor_audit` tables confirmed **unchanged**.
- [ ] Manual: against a DB previously holding `'NA'` rows, `SELECT count(*) FROM versions WHERE distribution = 'NA'` returns 0 post-migration.
