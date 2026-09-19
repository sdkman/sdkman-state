# Candidate Registry: the Allow-List Cutover

Part 1, [`candidate-registry.md`](candidate-registry.md), built the `candidates` table and the three routes that read and write it, and deliberately stopped there. `POST /versions` still validates against `src/main/resources/candidates.txt`, a 79-line classpath resource loaded by `CandidateLoader` and enforced by `VersionRequestValidator`. Two candidate registries therefore exist side by side, which is the duplication the work set out to remove.

This part removes it. The allow-list moves onto the table and the file is deleted.

That is the entire feature: no new endpoint, no response shape changes, no schema change and no migration. What changes is where one check gets its answer. Everything else here follows from doing that safely. The registry has to be held in memory so validation stays synchronous, it has to refresh so a candidate registered through the API is publishable without a restart, and it has to tell "never loaded" apart from "loaded and genuinely empty" so that a database blip does not reach a publisher as "candidate is not valid".

**Precondition.** This must not ship until the registry is populated and verified. On the first request after deploy the table becomes load-bearing, and an empty or partial registry means `POST /versions` returns `400` for every candidate it is missing. See *Rollout*.

*Reference: phase 2 of the MongoDB→PostgreSQL move; scope and measurements in [`../../../docs/specs/candidates-end-game.md`](../../../docs/specs/candidates-end-game.md). Enforcing the registry in the application rather than with a foreign key is [`docs/decisions/0008`](../../../docs/decisions/0008-registry-enforced-in-application.md). The word `candidates` also names the vendor authorisation scope (`vendors.candidates`, the JWT claim); that is a different concept and this feature does not touch it.*

## Behaviour

**Publishing.** `POST /versions` continues to reject an unknown candidate with `400`, but the answer now comes from the table rather than from a file on the classpath. Registering a candidate through `POST /admin/candidates` is therefore sufficient to publish to it, with no deploy and no restart. Two candidates gain publishing rights by this alone: `jpx` and `ksrc`, which fell out of `candidates.txt` by accident and cannot be published to today.

**Registering and deleting become load-bearing.** In part 1 both admin routes wrote to a table nothing consulted. From here a registration grants publishing rights and a deletion revokes them, bounded by the refresh interval rather than instantaneous.

**Nothing else changes.** `GET /candidates`, the two admin routes, their validation, the schema and the derived `default` are exactly as part 1 shipped them.

## API Contract

No new endpoints, and no request or response body changes anywhere. `POST /versions` keeps every status code it has, with one change of source and one addition:

| Status | Body | When |
|---|---|---|
| `400` | `ValidationErrorResponse` | The candidate is not in the registry. Previously: not in `candidates.txt`. `InvalidCandidateError` carries the same message and enumerates the registered candidates, **sorted ascending** |
| `500` | `ErrorResponse` | The registry has never loaded successfully. Not a validation failure, and never reported as one |
| all others | (existing) | Unchanged |

## Business Rules

1. **The registry is the allow-list.** `POST /versions` accepts a candidate if and only if it is registered. There is no second list, no classpath resource, and no configuration override.
2. **The check is against an in-memory set, and it performs no database read.** Validation stays a synchronous, non-`suspend` computation.
3. **The set is refreshed, never loaded once.** A successful `POST /admin/candidates` or `DELETE /admin/candidates/{candidate}` refreshes the set in the process that served it, and a TTL refresh backs that up for siblings that did not.
4. **A refresh failure serves the last good set.** A briefly stale allow-list beats rejecting valid publishes.
5. **A registry that has never loaded answers `500`, not `400`.** The distinction between "not loaded" and "loaded and empty" is load-bearing and must be represented.
6. **`versions.candidate` is still not a foreign key, and the delete race is accepted.** Rule 3 of part 1's `409` version count is a check rather than a lock, so the concrete way an orphan can arise is a `DELETE /admin/candidates/{candidate}` racing a `POST /versions`: the count sees no versions, the delete commits, and the publish lands after it. **That race is accepted, not closed.** No isolation level closes it, because the publish path reads the in-memory registry and never touches the `candidates` table, so Postgres has no read/write conflict to detect; closing it would take either the foreign key [`0008`](../../../docs/decisions/0008-registry-enforced-in-application.md) declined or a registry read inside the publish transaction, which is the database read the in-memory allow-list exists to avoid. The window is also wider than a transaction: the publishing instance keeps accepting the deleted candidate until its cached set next refreshes. The trade is taken knowingly, because deletion is a rare administrative act and the outcome is an inert row rather than a broken read. No such row exists in production today, so this remains a consequence the design accepts rather than one it has observed. (`cuba` and `ktx` are the inverse case, not an example: Postgres holds none of their rows, while Mongo still lists the candidates.)
7. **The allow-list grows by two.** The registry is a superset of `candidates.txt`: 79 names in the file, 81 in the backfilled registry, adding `jpx` and `ksrc`. No candidate loses publishing rights at the cutover, and that containment is the property the rollout depends on.

## The Registry in Memory

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

Five minutes is chosen against the write path, which is the only thing this TTL governs: how long after a `POST /admin/candidates` a sibling instance starts accepting that candidate on `POST /versions`. It bounds rule 6's delete window on the same path. It is deliberately not derived from `api.cache.control`, and it must not be read as bounding how long the registry takes to reach `sdk list` — that is a different chain with three hops:

| Hop | Mechanism | Today |
|---|---|---|
| `sdkman-state` → the wire | HTTP `max-age` from `api.cache.control`, inherited by `GET /candidates` because `CachingHeaders` installs on the root route | 600s |
| the wire → `sdkman-candidates` | Play WS response cache (`play.ws.cache.enabled=true`) honours that `max-age` | 600s |
| `sdkman-candidates` → the listing | that service's own candidate cache ([`candidate-registry-read-flip.md`](../../../candidates/sdkman-candidates/specs/candidate-registry-read-flip.md)) | 300s |

Worst case a newly registered candidate is **900 seconds** from appearing in `sdk list`, because the in-process refresh can re-fetch and be served the still-cached HTTP body. That is the read chain's number, not this one, and nothing here changes it. Aligning the two would mean either raising this TTL to 600s or giving `GET /candidates` its own `max-age`; both are out of scope, and the second is entangled with the `CachingHeaders` fix in *Domain & Implementation Notes*.

**A refresh failure serves the last good set; a cold failure is a `500`, never a `400`.** If a refresh fails, the previous set keeps serving — the registry changes rarely enough that a briefly stale allow-list beats rejecting valid publishes. If the *first* load fails there is nothing to fall back to, and `POST /versions` must answer `500`. The allow-list used to be a classpath resource and could not fail; a transient database error must not now surface as "candidate is not valid", which reads as permanent to a retrying client.

## Rollout

**One release, and it has a hard precondition.** The registry must already hold every candidate before this deploys, because the first `POST /versions` after the deploy is validated against it.

1. **Verify the registry** — `GET /candidates` returns the full backfilled set, and every name in `candidates.txt` appears in it. Rule 7 is the thing being checked, and it should be asserted mechanically rather than eyeballed: the backfill's `verify` compares the registry against the Mongo snapshot and does not look at `candidates.txt` at all, so the containment that makes this release safe is otherwise unasserted.
2. **Deploy** — the validator switches to the registry; `candidates.txt`, `CandidateLoader` and the `ALLOWED_CANDIDATES` companion constant are deleted.
3. The Candidates Service read flip follows, specced separately.

**If the precondition is missed.** Reads are unaffected in every case: `sdk list` and `sdk install` keep working, because no read path consults the registry. The damage is confined to publishing, where a missing candidate returns `400` until it is registered. DISCO retries the next day. This is a publishing stall rather than an outage, which is why the precondition is a verification step rather than a startup assertion.

**Rollback.** Redeploy part 1: the validator returns to `candidates.txt` and the table is left in place, unread. Safe in both directions, because no constraint was ever added and the registry is a superset of the file. The two candidates the file lacks, `jpx` and `ksrc`, simply become unpublishable again, which is the state they are in today.

**Ordering note for the test suite.** Once the validator reads the registry, a candidate must be registered before any version is posted to it. That applies to the acceptance suite as much as to production; see *Domain & Implementation Notes*.

## Domain & Implementation Notes

- **Validator: a frozen `Set<String>` at construction does not work.** The tempting move is to mirror `semverishCandidates: Set<String>`, which `Application.kt` reads once at startup. It cannot satisfy this feature. An immutable set captured at construction can be neither refreshed on write nor expired on a TTL, and it cannot represent "never loaded" as distinct from "loaded and genuinely empty" — which is the distinction the `500`-vs-`400` rule depends on. Whatever shape is chosen must therefore carry a *live* view plus a readiness state, not a value. Candidates for the implementation plan: inject the holder itself; inject a `() -> Set<String>` supplier; pass the set per call; or rebuild the validator on each refresh. Each trades purity against signature churn differently, and the choice is out of scope here. What the spec fixes is the behaviour: synchronous, no database read on the happy path, refreshable, and readiness-aware.

- **Registry holder:** a new component owns the set — startup load, configurable TTL refresh, eager refresh after a successful `POST /admin/candidates`. A failed refresh leaves the previous set in place. A failed *first* load leaves the holder unready, and `POST /versions` must answer `500` while it stays that way. That readiness check has to sit somewhere validation cannot swallow into a `ValidationError`, since the route maps those to `400`: either the route consults the holder before validating, or a distinct error type carries `500` out of it.

- **Every construction site gains an argument.** There are six, all currently single-argument: `Application.kt`, the test `support/Application.kt`, `HealthCheckAcceptanceSpec`, `LoginRateLimitDisabledAcceptanceSpec`, `VersionRequestValidatorSpec` and `VersionRequestSemverishValidatorSpec`. The last two also depend on `candidates.txt` today through `ALLOWED_CANDIDATES` — they use `java` heavily, plus `gradle`, `kotlin`, `maven` and `scala` — so each case with a valid candidate must now be handed a set containing it. This is signature churn, but it is not `suspend` churn, which is the expensive kind.

- **The rejection message needs a defined order.** `InvalidCandidateError.allowedCandidates` is a `List<String>` rendered with `joinToString(", ")`, and today's order is `candidates.txt` file order. A `Set` has no defined iteration order, so the message must enumerate the registry **sorted ascending**, matching `GET /candidates`. `VersionRequestValidatorSpec` asserts only that the message contains `"Allowed values:"`, so nothing currently catches a scrambled list.

- **Existing tests need a registered candidate.** Nothing in the suite seeds an allow-list today: the acceptance specs rely on `candidates.txt` being on the classpath with `java`, `gradle` and friends already in it. Once the validator reads the registry, an acceptance spec posting a version against a fresh Testcontainers database finds it empty and gets a `400`. The ~14 specs that post versions need a shared fixture registering the candidate first — one helper in `support/`, not fourteen ad-hoc inserts. (26 of 55 test files touch `versions`; only those going through `POST /versions` are affected, because a direct insert no longer meets a constraint.) Two things soften this relative to a foreign-key design: unit tests of the validator are untouched, because they construct it with an explicit set, and a test that wants an orphan version row can still insert one directly, since the database will not stop it.

- **Deletions:** `src/main/resources/candidates.txt` and `io/sdkman/state/config/CandidateLoader.kt` are removed, along with the `ALLOWED_CANDIDATES` companion constant. They are deleted rather than left as a fallback: two allow-lists that can disagree is the defect this work exists to remove, and a fallback would hide exactly the cold-load failure rule 5 turns into a `500`.

## Examples

```gherkin
Feature: Candidate registry as the allow-list

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

  Scenario: Deleting a candidate stops it being publishable, without a restart
    Given the candidate "jbang" is registered
      And "jbang" has no versions
    When an admin sends DELETE /admin/candidates/jbang
    Then posting a version of "jbang" to the same instance returns 400

  Scenario: A registry refresh failure keeps serving the last good set
    Given the candidate "gradle" is registered and the registry has loaded
      And the database then becomes unavailable
    When a vendor publishes a version of "gradle"
    Then the version is accepted

  Scenario: A registry that has never loaded rejects with 500, not 400
    Given the registry has never loaded successfully
    When a vendor publishes a version of "gradle"
    Then the response status is 500
```

## Out of Scope

- Anything part 1 delivered: the table, the migration, `GET /candidates`, the two admin routes and their validation.
- The Candidates Service's read flip. Specced separately in [`../../../candidates/sdkman-candidates/specs/candidate-registry-read-flip.md`](../../../candidates/sdkman-candidates/specs/candidate-registry-read-flip.md).
- The backfill tool. It is an operator-side script driving `POST /admin/candidates`; it lives in the parent workspace, not in this repo.
- Foreign-keying either `versions.candidate` or the vendor authorisation scope to the registry.
- Closing the delete-versus-publish race of rule 6.
- Any change to `DELETE /versions`, the tag routes, or the version read routes.
- Auditing of candidate writes.

## Acceptance Criteria

- [ ] `POST /versions` accepts a candidate registered through the API without a restart, and rejects an unregistered one with `400`
- [ ] `src/main/resources/candidates.txt` and `CandidateLoader` no longer exist
- [ ] Every existing acceptance spec that writes a version registers its candidate first; none relies on a classpath allow-list
- [ ] `POST /versions` performs no database read of the registry; the check is against the in-memory set
- [ ] `VersionRequestValidator.validate` and `validateRequest` remain synchronous
- [ ] The registry is not loaded once for the process lifetime: a candidate registered through `POST /admin/candidates` is publishable without a restart
- [ ] The registry refreshes on a TTL as well as on write, so an instance that did not serve the write picks the candidate up within the TTL
- [ ] `DELETE /admin/candidates/{candidate}` refreshes the registry in the serving process, so the deleted candidate stops being publishable there without waiting for the TTL
- [ ] The refresh interval is read from `application.conf` and defaults to five minutes; no Kotlin literal duplicates it
- [ ] No test asserts that the delete `409` serialises against a concurrent `POST /versions`; rule 6 records that window as accepted
- [ ] A registry refresh failure keeps serving the last good set rather than rejecting valid publishes
- [ ] `POST /versions` returns `500`, not `400`, when the registry has never loaded successfully
- [ ] `InvalidCandidateError` enumerates the registry sorted ascending, asserted on the order rather than only on the `"Allowed values:"` prefix
- [ ] Every name in `candidates.txt` is present in the registry before this ships, asserted mechanically rather than by inspection
- [ ] No read path consults the registry: `GET /candidates`, the version read routes and the tag routes are unchanged
- [ ] All quality gates pass (`./gradlew check`)
