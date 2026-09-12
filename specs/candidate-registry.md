# Candidate Registry

The State API owns every `versions` row but has no idea what a candidate *is*. Candidate metadata — the name, description and website that `sdk list` renders — lives in a MongoDB `candidates` collection read directly by the Candidates Service. This feature moves that collection into Postgres and makes `candidates` a first-class State API resource.

It also resolves a duplication that already exists inside this service. `src/main/resources/candidates.txt` is a 79-line resource loaded by `CandidateLoader` and enforced by `VersionRequestValidator` as the allow-list for `POST /versions`. That file **is** a candidate registry, maintained by hand, deployed with the service, and independent of the 84-entry Mongo collection it shadows. The two disagreed: `cuba` and `ktx` were retired by deleting them from the file, while `jpx` and `ksrc` fell out of it by accident and cannot be published to at all. This feature collapses both into one table, and `candidates.txt` and `CandidateLoader` are deleted.

*Reference: this is phase 2 of the MongoDB→PostgreSQL move. Scope, measurements and the decisions behind it are in [`../../../docs/specs/candidates-end-game.md`](../../../docs/specs/candidates-end-game.md); the two hard-to-reverse calls are recorded as [`docs/decisions/0006`](../../../docs/decisions/0006-candidate-foreign-key.md) and [`0007`](../../../docs/decisions/0007-platform-classification-deleted.md). The word `candidates` also names the **vendor authorisation scope** (`vendors.candidates`, the JWT claim, `CreateVendorRequest.candidates`). That is a different concept and this feature does not touch it.*

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
| `description` | string | free text, up to 2000 characters, may contain non-ASCII |
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
| `description` | yes | free text, 1–2000 characters |
| `website_url` | yes | absolute `https` URL, up to 500 characters |

| Status | Body | When |
|---|---|---|
| `201 Created` | `CandidateAdminDto` | Candidate did not exist and was registered |
| `200 OK` | `CandidateAdminDto` | Candidate existed and its metadata was updated |
| `400 Bad Request` | `ValidationErrorResponse` | Validation failure or malformed JSON |
| `401 Unauthorized` | `ErrorResponse` | Missing, invalid, or non-admin token |
| `500 Internal Server Error` | `ErrorResponse` | Database error |

`CandidateAdminDto` is `CandidateDto` without the derived `default`, plus `created_at` and `updated_at` as ISO-8601 instants. The JSON field is `updated_at`, matching `VendorResponse`; the column behind it is `last_updated_at`, matching `versions` and `version_tags`. The two conventions already disagree in this codebase, so each layer follows its own.

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
3. **A candidate with versions cannot be deleted.** The service counts the candidate's versions and returns `409`; the foreign key is a backstop, not the mechanism. Catching the constraint violation instead would work, but produces a database error to translate rather than an intended branch, and gives no count for the response body. Delete the versions first.
4. **`default` is derived, never stored.** It is read from `version_tags` on every request. Nothing writes a default onto a candidate row, and there is no endpoint to set one — moving a default means moving the `lts` tag.
5. **`default` resolves `UNIVERSAL` first, then `LINUX_X64`.** No other platform is consulted — the resolution is restricted to those two, not merely ordered by them. A candidate whose `lts` sits only on, say, `MAC_ARM64` has no `default`.
6. **`default` only considers rows with no distribution**, matched on **`versions.distribution`**, not `version_tags.distribution`. Every non-java candidate stores `distribution` as `NULL` (see the `NA`→`NULL` convergence in [`distribution-na-to-null.md`](distribution-na-to-null.md)). `PostgresVersionRepository.findByTag` filters the same column, so the two agree by construction; filtering the other one would let `GET /candidates` and `GET /versions/{c}/tags/lts` disagree for the same candidate.
6a. **`default` does not filter on `visible`.** `findByTag` does not either, and `V18` knowingly leaves `lts` pointing at retired rows until DISCO next hydrates the tag (it even `RAISE NOTICE`s them). Filtering here would make the `sdk list` header and `sdk default` show different versions for exactly that window. The two reads must agree, even when what they agree on is temporarily stale.
7. **`java` never carries a `default`.** It is excluded by name. This is the one candidate-specific rule in the service and it exists because java is the one candidate with per-distribution tags.
8. **`website_url` must be `https`.** Enforced on write. The existing `HTTPS_URL_PATTERN` used for version download URLs is reused.
9. **Candidate identifiers are lowercase and case-sensitive.** `Java` is not `java`; it is rejected by the pattern.
10. **Ordering is ascending by `candidate`**, matching the sort the Mongo repository applied.
11. **The vendor authorisation scope is unaffected.** `vendors.candidates` remains a free-form `TEXT[]`; it is not foreign-keyed to this table and a vendor may still be scoped to a candidate that does not exist. Tightening that is separate work.

## Validation

Structural validation only, following the accumulated-error pattern (all failures returned in one `400` `ValidationErrorResponse`):

- `candidate` must be present, non-blank, at most 20 characters, and match `^[a-z][a-z0-9]*$`.
- `name` must be present, non-blank, at most 100 characters.
- `description` must be present, non-blank, at most 2000 characters.
- `website_url` must be present and match the existing HTTPS URL pattern, at most 500 characters.

`VersionRequestValidator`'s candidate check changes source, not shape. `InvalidCandidateError` still returns the same message and enumerates the allowed values, but reads them from the registry instead of `CandidateLoader.allowedCandidates`.

**The happy path reads one row, not the whole registry.** Validation asks only whether the candidate exists — a single primary-key lookup. The full list is read only when that lookup misses, to build the error message.

This is a matter of shape rather than throughput: `POST /versions` is low-volume (DISCO publishes on the order of once a day), so a full read would not hurt. But "does this candidate exist" is an existence question, and answering it by materialising every row and scanning in memory is the wrong shape for a primary-key lookup.

**A registry read failure is a `500`, never a `400`.** The allow-list used to be a classpath resource and could not fail; it is now a database read. A transient database error must not surface as "candidate is not valid", which reads as permanent to a retrying client.

## Database Schema

Next free version is **V19**, but it is reserved by [`java-version-supersession.md`](java-version-supersession.md)'s all-migrated-series pass (branch `spec/v19-all-migrated-series`, specced and unshipped). This feature therefore claims **V20** and **V21**. If that pass ships first, the numbers hold; if it never ships, V19 stays an intentional gap, which Flyway tolerates.

**Two migrations, one release.** `V20` creates the table *and* seeds it; `V21` then adds the foreign keys against populated data. Both apply in the same boot. See *Rollout*.

`src/main/resources/db/migration/V20__create_candidates_table.sql` — the DDL, followed in the same file by the seed:

```sql
-- Candidate metadata, migrated from the MongoDB `candidates` collection.
-- `candidate` is the natural primary key: `versions.candidate` and
-- `version_tags.candidate` reference it in V21. Both of those are TEXT --
-- `versions` was recreated with modern TEXT types in V5, superseding V2's
-- VARCHAR(20), and V12 declared version_tags.candidate TEXT -- so this column
-- is TEXT too, for an exact type match. The CHECK carries the shape the
-- request validator enforces, so the two cannot drift.
--
-- (Implementer note: PostgresVersionRepository maps this column as
-- varchar("candidate", length = 20). That is a pre-existing mismap of a TEXT
-- column; do not take it as evidence of the real type.)
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
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    last_updated_at TIMESTAMP    NOT NULL DEFAULT now()
);
```

### The seed

`V20` ends with an `INSERT` of the candidate set. The rows are **generated, not hand-written**: `candidates_migration/` in the parent workspace dumps the live Mongo `candidates` collection, drops the retired and fixture candidates, applies the `https` remediation, and emits the `INSERT` statement. The generated SQL is committed and reviewed like any other migration; the generator exists so the derivation is reproducible and the remediation table is curated config rather than inline edits.

```sql
INSERT INTO candidates (candidate, name, description, website_url) VALUES
    ('activemq', 'Apache ActiveMQ (Classic)', '...', 'https://activemq.apache.org/'),
    ...
    ('znai', 'Znai', '...', 'https://testingisdocumenting.org/znai/');
```

Which candidates are in the set, and why `cuba`, `ktx` and `test` are not, is [`../../../docs/specs/candidates-end-game.md`](../../../docs/specs/candidates-end-game.md) §6.

`src/main/resources/db/migration/V21__add_candidate_foreign_keys.sql`:

```sql
-- Make the registry structural. After this, no path -- API, migration or ad-hoc
-- SQL -- can create a version for an unregistered candidate, and a candidate
-- holding versions cannot be deleted.
--
-- PRECONDITION: every distinct candidate in `versions` and `version_tags` must
-- already exist in `candidates`. V20 seeds them in the same boot, so this holds
-- unless production carries a candidate outside the seeded set -- in which case
-- this migration fails and the service does not start. That is intended; verify
-- against production before shipping.

ALTER TABLE versions
    ADD CONSTRAINT versions_candidate_fkey
    FOREIGN KEY (candidate) REFERENCES candidates (candidate) ON DELETE RESTRICT;

ALTER TABLE version_tags
    ADD CONSTRAINT version_tags_candidate_fkey
    FOREIGN KEY (candidate) REFERENCES candidates (candidate) ON DELETE RESTRICT;
```

Every text column is `TEXT`, matching what V5 established for `versions`. The longest live description is 966 characters and 7 carry non-ASCII. The lengths in *Validation* are application-level guards, not column widths; the only shape the database enforces is the `candidate` `CHECK`, which exists because that column is a foreign-key target and a malformed value there is unrecoverable rather than merely wrong.

## Rollout

**One release.** The candidate rows arrive in a Flyway migration, not over HTTP, so there is no moment at which the table exists but is empty, and no intermediate deploy to sequence.

1. **`V20`** creates the table and inserts the candidate set in the same migration.
2. **`V21`** adds the foreign keys. Flyway has already applied `V20` in the same boot, so the rows are present and the constraint applies.
3. The validator switches to the table, and `candidates.txt` and `CandidateLoader` are deleted, in that same release.

An earlier draft had the rows arriving through `POST /admin/candidates` between two releases. That is what forced a split: Flyway runs every pending migration at startup, before routing is configured (`Application.kt`), so an artefact carrying both migrations would apply `V21` against an empty table and fail to boot. Seeding in the migration removes the ordering problem entirely.

**The write path then ships without a production exercise.** That was the backfill's secondary benefit and it is lost here. It is replaced by `seed/seed.sh` in the parent workspace, which registers the local stack's candidates through `POST /admin/candidates` on every bring-up, plus the acceptance specs below.

**`V21` takes `ACCESS EXCLUSIVE` on `versions`.** Broker 2 reads that table directly on the download path ([`../../../docs/contracts.md`](../../../docs/contracts.md) §4.2). The table is small so the scan is brief, and the plain blocking form is used deliberately: `NOT VALID` plus a later `VALIDATE CONSTRAINT` would avoid the lock but accept existing violating rows, forfeiting the fail-fast property this design depends on. The residual risk is lock *queueing* — if a broker read or an idle-in-transaction session is in flight, the `ALTER` waits and new reads queue behind it. Deploy at a quiet moment.

**`V21` is the pre-flight, and it now fires on the first deploy.** If any distinct `versions.candidate` or `version_tags.candidate` in production falls outside the seeded set, `V21` fails and the service does not start. Flyway runs migrations transactionally on Postgres, so a failure leaves no half-applied state, but the deploy fails. Run that comparison against production before shipping rather than discovering it at boot.

**Rollback** is a redeploy of the prior release, which restores `candidates.txt` while leaving the table and foreign keys in place. Safe, because the seeded registry is a superset of the file: every candidate the restored file accepts exists in the table, so no accepted write can violate the constraint.

## Auditing

**None.** Candidate writes are recorded by `created_at` and `last_updated_at` on the row and nothing else. No `vendor_audit` entries, no `AuditOperation` value, no new table. Registration is a rare administrative act against a table already protected by `ON DELETE RESTRICT`, and the audit machinery exists for the high-volume vendor-driven version writes.

## Domain & Implementation Notes

- **Domain model:** a new `Candidate(candidate, name, description, websiteUrl, createdAt, lastUpdatedAt)`, and a read model carrying the derived `default` as an `Option<String>`.
- **Repository:** a new `CandidateRepository` / `PostgresCandidateRepository` with `findAll()`, `findAllWithDefaults()`, `upsert(...)`, and `delete(candidate)`. `findAllWithDefaults` is a single `LEFT JOIN` against `version_tags` and `versions` — the derived default must not become a per-row query.
- **Platform preference in SQL:** one row per candidate, restricted to the two platforms. `DISTINCT ON (candidate) ... WHERE platform IN ('UNIVERSAL','LINUX_X64') ORDER BY candidate, CASE platform WHEN 'UNIVERSAL' THEN 0 ELSE 1 END` is the shape. A bare `LIMIT 1` is global, not per candidate, and returns a single row for the whole registry; an `ELSE 1` ordering without the `IN` restriction admits every platform and leaks a `MAC_ARM64`-only `lts` into `sdk list`. Both were reproduced against a real database.
- **Delete conflict:** count the candidate's versions inside the transaction and return `409` on a non-zero count, rather than catching the foreign-key violation. A new `DomainError` (e.g. `CandidateInUse`) carries it to the route layer.
- **Validator:** `VersionRequestValidator` takes the registry as an injected dependency rather than reading a companion-object constant. Because the check becomes a database read, `validate` and `validateRequest` become `suspend` — they are not today, and every repository method is — which ripples through `VersionRoutes.kt` and the validator's test construction sites. Budget for that; it is a signature change, not a dependency swap. It must not cache the registry for the process lifetime: a candidate registered through the API has to be publishable immediately, without a restart.
- **Routes:** `versionReadRoutes` installs `CachingHeaders` on the **root** route (`Routing.kt` calls it on the `routing { }` receiver), so every JSON response already carries `max-age=appConfig.cacheMaxAge`. `GET /candidates` inherits it by existing, not by joining a group, and that is intended: the registry is static in nature and every consumer should cache it. The two admin routes join the `authenticate("auth-jwt")` block alongside the vendor admin routes and set `Cache-Control: no-store`, which overrides the inherited header rather than adding to it.
- **Non-admin handling:** `/admin/candidates` returns `401` for a valid non-admin token, matching `/admin/vendors` rather than the `403` used by the version write routes. Consistency with the neighbouring admin routes wins.
- **No nullable types.** Arrow `Option` throughout, per the existing convention.
- **Existing tests need a registered candidate.** Nothing in the suite seeds an allow-list today: the acceptance specs rely on `candidates.txt` being on the classpath with `java`, `gradle` and friends already in it. Once the validator reads the table, every `POST /versions` against a fresh Testcontainers database hits an empty `candidates` table and returns `400`. The 15-odd specs that write versions need a shared fixture that registers the candidate first — one helper in `support/`, not fifteen ad-hoc inserts.
- **Deletions:** `src/main/resources/candidates.txt` and `io/sdkman/state/config/CandidateLoader.kt` are removed in release 2, along with the `ALLOWED_CANDIDATES` companion constant.

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

  Scenario: Deleting a candidate is refused before the foreign key exists
    Given the release has applied V20 but not V21
      And the candidate "gradle" is registered and has versions
    When an admin sends DELETE /admin/candidates/gradle
    Then the response status is 409
      And no version rows are orphaned

  Scenario: Deleting a candidate that has versions is refused
    Given the candidate "gradle" is registered
      And "gradle" has versions
    When an admin sends DELETE /admin/candidates/gradle
    Then the response status is 409
      And "gradle" still appears in GET /candidates

  Scenario: Deleting an unknown candidate
    When an admin sends DELETE /admin/candidates/nonesuch
    Then the response status is 404

  Scenario: A vendor cannot register a candidate
    Given a vendor token authorized for "gradle"
    When the vendor sends POST /admin/candidates
    Then the response status is 401

  Scenario: An unauthenticated client cannot register a candidate
    When an unauthenticated client sends POST /admin/candidates
    Then the response status is 401

  Scenario: The registry is enforced by the database
    Given the candidate "gradle" is registered and has versions
    When a direct SQL delete of the "gradle" candidate row is attempted
    Then the delete is refused by a foreign key constraint
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
- [ ] `DELETE /admin/candidates/{candidate}` returns `200` and removes a candidate with no versions
- [ ] `DELETE /admin/candidates/{candidate}` returns `409` when the candidate has versions or version tags, and `404` when it does not exist
- [ ] Both admin routes return `401` for anonymous, vendor, and expired tokens
- [ ] `POST /versions` accepts a candidate registered through the API without a restart, and rejects an unregistered one with `400`
- [ ] `src/main/resources/candidates.txt` and `CandidateLoader` no longer exist
- [ ] `V20` creates the `candidates` table and seeds it with the candidate set in the same migration
- [ ] `V21` adds both foreign keys with `ON DELETE RESTRICT`, and applies successfully against a database where only `V20` has run
- [ ] A single boot of the service applies `V20` and `V21` in order and starts cleanly
- [ ] Every existing acceptance spec that writes a version registers its candidate first; none relies on a classpath allow-list
- [ ] `V21` fails when a `versions` or `version_tags` row references an unregistered candidate
- [ ] A direct SQL insert into `versions` for an unregistered candidate is refused by the database
- [ ] No candidate write produces a `vendor_audit` row
- [ ] The derived `default` is restricted to `UNIVERSAL` and `LINUX_X64`; a candidate whose only `lts` tag is on another platform has no `default`
- [ ] The derived `default` does not filter on `visible`, and agrees with `GET /versions/{candidate}/tags/lts` for the same candidate
- [ ] `DELETE /admin/candidates/{candidate}` returns `409` from the service's own version count, not from a translated database error
- [ ] `POST /versions` for a registered candidate performs no full read of the registry; the full list is read only to build a rejection message
- [ ] A registry read failure during validation yields `500`, not `400`
- [ ] Both admin routes respond with `Cache-Control: no-store`
- [ ] `POST /admin/candidates` never mutates an existing candidate's identifier
- [ ] A `500` is returned, with `ErrorResponse`, on database error for all three endpoints
- [ ] OpenAPI documentation updated with all three endpoints and the `CandidateDto`, `CandidateAdminDto` and `CreateCandidateRequest` schemas
- [ ] No nullable types used — follows the Arrow `Option` pattern
- [ ] All quality gates pass (`./gradlew check`)
