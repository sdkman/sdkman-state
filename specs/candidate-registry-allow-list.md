# Candidate Registry: the Allow-List Cutover

Part 1, [`candidate-registry.md`](candidate-registry.md), built the `candidates` table and the three routes that read and write it, and deliberately stopped there. `POST /versions` still validates against `src/main/resources/candidates.txt`, an 80-line classpath resource deployed with the service. Two candidate registries therefore exist side by side, which is the duplication the work set out to remove.

This part removes it. The allow-list moves onto the table and the file is deleted.

That is the entire feature: no new endpoint, no response shape changes, no schema change and no migration. What changes is where one check gets its answer. Everything else here follows from doing that safely. The answer has to stay fast enough to sit on every publish, it has to follow the table closely enough that a candidate registered through the API is publishable without a restart, and "never loaded" has to be distinguishable from "loaded and genuinely empty" so that a database blip does not reach a publisher as "candidate is not valid".

**Precondition.** This must not ship until the registry is populated and verified. On the first request after deploy the table becomes load-bearing, and an empty or partial registry means `POST /versions` returns `400` for every candidate it is missing. See *Rollout*.

*Reference: phase 2 of the MongoDB→PostgreSQL move; scope and measurements in [`../../../docs/specs/candidates-end-game.md`](../../../docs/specs/candidates-end-game.md). Enforcing the registry in the application rather than with a foreign key is [`docs/decisions/0008`](../../../docs/decisions/0008-registry-enforced-in-application.md). The word `candidates` also names the vendor authorisation scope (`vendors.candidates`, the JWT claim); that is a different concept and this feature does not touch it.*

## Behaviour

**Publishing.** `POST /versions` continues to reject an unknown candidate with `400`, but the answer now comes from the table rather than from a file on the classpath. Registering a candidate through `POST /admin/candidates` is therefore sufficient to publish to it, with no deploy and no restart. That is the repair this cutover exists to make unnecessary: `jpx` fell out of `candidates.txt` by accident and was put back by hand, and `ksrc` fell out the same way and was never put back, being retired instead.

**Two candidates lose publishing rights, and that is the intent.** `coursier` and `infrastructor` are named in `candidates.txt` and are deliberately absent from the registry, both retired on 2026-09-20 ([`candidates-end-game.md`](../../../docs/specs/candidates-end-game.md) §6). At the cutover the file stops being consulted and they become unpublishable, which is what retiring a candidate means. See rule 7.

**Registering and deleting become load-bearing.** In part 1 both admin routes wrote to a table nothing consulted. From here a registration grants publishing rights and a deletion revokes them, bounded by the refresh interval rather than instantaneous.

**Nothing else changes.** `GET /candidates`, the two admin routes, their validation, the schema and the derived `default` are exactly as part 1 shipped them.

## API Contract

No new endpoints, and no request or response body changes anywhere. `POST /versions` keeps every status code it has, with one change of source and one addition:

| Status | Body | When |
|---|---|---|
| `400` | `ValidationErrorResponse` | The candidate is not in the registry. Previously: not in `candidates.txt`. The same message, enumerating the registered candidates, **sorted ascending** |
| `500` | `ErrorResponse` | The registry has never loaded successfully. Not a validation failure, and never reported as one |
| all others | (existing) | Unchanged |

## Business Rules

Part 1's rules carry over except where stated here: its rule 1 (the registry is not yet the allow-list) and its rule 13 (an empty registry is the normal state) are superseded by this part, and the accepted-window clause of its rule 3 is restated as rule 6 below.

1. **The registry is the allow-list.** `POST /versions` accepts a candidate if and only if it is registered. There is no second list, no classpath resource, and no configuration override.
2. **The check performs no database read.** A publish is validated against a copy the service already holds, not against a query.
3. **That copy is refreshed, never loaded once.** A successful `POST /admin/candidates` or `DELETE /admin/candidates/{candidate}` refreshes it in the instance that served the write, and a periodic refresh backs that up for siblings that did not.
4. **A refresh failure keeps serving the last good copy.** A briefly stale allow-list beats rejecting valid publishes.
5. **A registry that has never loaded answers `500`, not `400`.** The distinction between "not loaded" and "loaded and empty" is load-bearing and must be represented.
6. **`versions.candidate` is still not a foreign key, and the delete race is accepted.** Rule 3 of part 1's `409` version count is a check rather than a lock, so the concrete way an orphan can arise is a `DELETE /admin/candidates/{candidate}` racing a `POST /versions`: the count sees no versions, the delete commits, and the publish lands after it. **That race is accepted, not closed.** No isolation level closes it, because the publish path never touches the `candidates` table, so Postgres has no read/write conflict to detect; closing it would take either the foreign key [`0008`](../../../docs/decisions/0008-registry-enforced-in-application.md) declined or a registry read inside the publish transaction, which is the per-publish read rule 2 rules out. The window is also wider than a transaction: the publishing instance keeps accepting the deleted candidate until it next refreshes. The trade is taken knowingly, because deletion is a rare administrative act and the outcome is an inert row rather than a broken read. No row has yet arrived this way, so the race remains a consequence the design accepts rather than one it has observed. Orphaned rows themselves are not hypothetical: the backfill excluded `infrastructor`, whose 7 `versions` rows are in Postgres and unregistered from the moment this ships. They behave exactly as rule 13 of part 1 describes, resolving by exact identifier and absent from every listing. (`cuba` and `ktx` are the inverse case, not an example: Postgres holds none of their rows, while Mongo still lists the candidates.)
7. **The registry is not a superset of the file, and the difference is a decision.** 80 names in `candidates.txt`, 78 in the backfilled registry, and the registry adds nothing the file does not already have. The two names it drops, `coursier` and `infrastructor`, are retired candidates excluded by the backfill's own remediation table, so the cutover revokes their publishing rights along with the file. The property the rollout depends on is therefore containment *modulo that exclusion list*: every name in the file is either registered or explicitly retired, and any third case is an accident and blocks the release. Checking it is rollout step 1.

## Freshness and Failure

The candidate check changes source, not shape. The rejection still carries the same message and still enumerates the allowed values, but takes them from the registry, **sorted ascending**: the file order it inherits today disappears with the file.

**The publish path does not read the database for it.** The registry is order-of-100 rows, static in nature, and consulted on every `POST /versions`. A read per publish would be the wrong trade for data that changes a few times a year. It also matters that validation is a plain synchronous computation today: the version write path is built around that, and making the candidate check asynchronous ripples outward well beyond this feature. The *Findings* record why the obvious way to hold the set instead does not work.

**The set must not be loaded once for the process lifetime.** The classpath allow-list is loaded lazily and then never again, which is right for a file baked into the deployment and wrong for a table `POST /admin/candidates` mutates. Inheriting that would mean a candidate registered through the API is rejected by `POST /versions` until the service restarts, which is the `jpx` / `ksrc` failure the merged registry exists to remove, surviving the migration in a new form.

**Freshness is bounded, not instantaneous.** A successful `POST /admin/candidates` **or `DELETE /admin/candidates/{candidate}`** refreshes the copy held by the instance that served it. Both writes have to, not only the register: otherwise a deleted candidate stays publishable on the very instance that removed it. Eager refresh is only ever complete on one instance, though, since a sibling that did not serve the write keeps its own copy, so a periodic refresh has to back it up. The observable contract is therefore *a newly registered candidate becomes publishable within the TTL, a deleted one stops being publishable within the TTL, and neither needs a restart*. `sdkman-candidates` warms and refreshes the same registry on the same pattern ([`candidates-end-game.md`](../../../docs/specs/candidates-end-game.md) §10).

**The refresh interval is five minutes, and it is configuration**, declared once alongside the service's other defaults and overridable per environment.

Five minutes is chosen against the write path, which is the only thing this interval governs: how long after a `POST /admin/candidates` a sibling instance starts accepting that candidate on `POST /versions`. It bounds rule 6's delete window on the same path. It is deliberately not derived from the HTTP cache lifetime, and it must not be read as bounding how long the registry takes to reach `sdk list` — that is a different chain with three hops:

| Hop | Mechanism | Today |
|---|---|---|
| `sdkman-state` → the wire | the HTTP `max-age` every JSON response in the service carries, `GET /candidates` included | 600s |
| the wire → `sdkman-candidates` | Play WS response cache (`play.ws.cache.enabled=true`) honours that `max-age` | 600s |
| `sdkman-candidates` → the listing | that service's own candidate cache ([`candidate-registry-read-flip.md`](../../../candidates/sdkman-candidates/specs/candidate-registry-read-flip.md)) | 300s |

Worst case a newly registered candidate is **900 seconds** from appearing in `sdk list`, because the in-process refresh can re-fetch and be served the still-cached HTTP body. That is the read chain's number, not this one, and nothing here changes it. Aligning the two would mean either raising this interval to 600s or giving `GET /candidates` its own `max-age`; both are out of scope, and the second runs into the caching-plugin trap recorded under *Findings*.

**A refresh failure serves the last good copy; a cold failure is a `500`, never a `400`.** If a refresh fails, the previous copy keeps serving — the registry changes rarely enough that a briefly stale allow-list beats rejecting valid publishes. If the *first* load fails there is nothing to fall back to, and `POST /versions` answers `500`. The allow-list used to be a classpath resource and could not fail; a transient database error must not now surface as "candidate is not valid", which reads as permanent to a retrying client.

**A failed load at startup does not stop the service booting, and the TTL is also the retry.** No read path consults the registry, so an instance that cannot build its allow-list still serves every read route correctly and degrades publishing alone; refusing to boot would trade a publishing stall for an outage. The ordinary TTL refresh doubles as the cold-load retry, so an unready holder becomes ready within one interval of the database recovering, with no restart and no second backoff to configure and test. Part 1's property that nothing here can refuse to boot therefore survives the cutover.

## Rollout

**One release, and it has a hard precondition.** The registry must already hold every candidate before this deploys, because the first `POST /versions` after the deploy is validated against it.

1. **Verify the registry** — the live registry holds the full backfilled set, and every name in `candidates.txt` is either in it or on the exclusion list. Rule 7 is the thing being checked, and it is asserted mechanically rather than eyeballed: the backfill's `verify` compares the registry against the Mongo snapshot and does not look at `candidates.txt` at all, so the containment that makes this release safe needs a check of its own. `candidates_migration`'s `verify` gains an `--allow-list` option taking the path to `candidates.txt`. It reconciles the file against the live registry and the exclusion list its own remediation table already carries, reporting the retired names it tolerates and failing on any other name the registry does not hold. As it stands that is `coursier` and `infrastructor` tolerated and nothing failed; a third name appearing means the backfill missed a row. It must run against the live registry rather than a fixture, which is why it cannot be a test in this repo, and it must keep `verify`'s cache-buster: `GET /candidates` is served `max-age=600` behind Cloudflare, so a plain `curl` can report a registry ten minutes out of date, as it did during the backfill ([`candidates-end-game.md`](../../../docs/specs/candidates-end-game.md) §9). That option is the only backfill-tool change this part depends on; see *Out of Scope*.
2. **Deploy** — the publish check switches to the registry, and `candidates.txt` and the code that loaded it are deleted.
3. The Candidates Service read flip follows, specced separately.

**If the precondition is missed.** Reads are unaffected in every case: `sdk list` and `sdk install` keep working, because no read path consults the registry. The damage is confined to publishing, where a missing candidate returns `400` until it is registered. DISCO retries the next day. This is a publishing stall rather than an outage, which is why the precondition is a verification step rather than a startup assertion.

**The health check is unchanged.** `/meta/health` stays a `checkDatabaseConnection()` probe and deliberately does not gate on registry readiness. The common cause of a cold-load failure already surfaces there as `503`, because the same connection fails both. The residual case is a database that answers the probe while the registry load failed, and that breaks publishing only: pulling the instance out of rotation would stop reads that are serving correctly, to signal a write-path fault. Part 1 relied on the health check enumerating no tables so an empty registry could not fail a deploy gate; that stays true, and a registry that never loads is visible as a `500` on `POST /versions` rather than as an unhealthy instance.

**Rollback.** Redeploy part 1: the publish check returns to `candidates.txt` and the table is left in place, unread. Safe in both directions, because no constraint was ever added and the file is a superset of the registry: every candidate publishable after the cutover was publishable before it. The two names the registry lacks, `coursier` and `infrastructor`, simply regain publishing rights they are retired from, which is inert in practice because `coursier` holds no versions in either datastore and `infrastructor`'s upstream has been archived since 2021.

**Ordering note for the test suite.** Once the publish check reads the registry, a candidate must be registered before any version is posted to it. That applies to the acceptance suite as much as to production; see *Findings*.

## Findings

Observations made while surveying the code this feature lands in, recorded so they are not rediscovered the hard way. They constrain what a correct solution looks like; none of them chooses one.

- **Holding the allow-list as a value captured at construction does not work.** The tempting move is to mirror the existing set of semverish-opted-in candidates, which is read once at startup and handed to the validator. It cannot satisfy this feature. A set captured at construction can be neither refreshed on write nor expired on an interval, and it cannot represent "never loaded" as distinct from "loaded and genuinely empty", which is the distinction the `500`-versus-`400` rule rests on. Whatever holds the registry has to expose a live view and a readiness state rather than a value.

- **Readiness has to reach the response without passing through validation.** The write route maps validation failures to `400`, so an unready registry reported as one would surface as "candidate is not valid", which is precisely what rule 5 forbids.

- **The validator has six construction sites today, all single-argument**: the application itself, the test application support, two acceptance specs, and the two validator unit specs. The last two also lean on `candidates.txt` through the allow-list constant, using `java` heavily plus `gradle`, `kotlin`, `maven` and `scala`, so every case with a valid candidate needs one supplied. The cost is signature churn, which is cheap; what would not be cheap is making validation asynchronous, and nothing here requires that.

- **The rejection message has no defined order once the file goes.** The allowed values are rendered into the message as a list, and today's order is the order of lines in `candidates.txt`. A set has no such order, so the message has to sort. Nothing currently catches a scrambled list: the existing validator spec asserts only that the message contains `"Allowed values:"`.

- **Nothing in the test suite seeds an allow-list.** The acceptance specs rely on `candidates.txt` being on the classpath with `java`, `gradle` and friends already in it, so once the validator reads the registry, a spec posting a version against a fresh Testcontainers database finds it empty and gets a `400`. Measured on `main` at part 1's merge: 18 spec files post versions and are affected; 42 of 65 test files touch `versions`, but a direct insert still meets no constraint and so is untouched. Two things soften this relative to a foreign-key design: the validator's own unit tests are unaffected, because they are handed an explicit set, and a test that wants an orphan version row can still create one, since the database will not stop it.

- **The caching plugin appends rather than replaces**, and its options block fires for any JSON body, so a route that also sets its own `Cache-Control` emits two values plus an `Expires`. That is why the table above notes that giving `GET /candidates` its own `max-age` is not a one-line change. Part 1 carries the same finding, where it applies to the admin routes. Nothing in this part touches the plugin.

- **Deleting `candidates.txt` and its loader is part of the deliverable, not a tidy-up.** Leaving the file as a fallback would restore the two-lists-that-can-disagree defect this work exists to remove, and would mask exactly the cold-load failure rule 5 turns into a `500`.

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

  Scenario: An unready registry serves reads and recovers without a restart
    Given the registry has never loaded successfully
      And GET /versions/gradle still succeeds
    When the database becomes available and the refresh interval elapses
    Then a vendor publishing a version of "gradle" succeeds
```

## Out of Scope

- Anything part 1 delivered: the table, the migration, `GET /candidates`, the two admin routes and their validation.
- The Candidates Service's read flip. Specced separately in [`../../../candidates/sdkman-candidates/specs/candidate-registry-read-flip.md`](../../../candidates/sdkman-candidates/specs/candidate-registry-read-flip.md).
- The backfill tool, with one carve-out. It is an operator-side script driving `POST /admin/candidates`, and it lives in the parent workspace, not in this repo. The `verify --allow-list` containment check of *Rollout* step 1 is the single change to it this part depends on.
- Foreign-keying either `versions.candidate` or the vendor authorisation scope to the registry.
- Closing the delete-versus-publish race of rule 6.
- Any change to `DELETE /versions`, the tag routes, or the version read routes.
- Auditing of candidate writes.

## Acceptance Criteria

- [ ] `POST /versions` accepts a candidate registered through the API without a restart, and rejects an unregistered one with `400`
- [ ] `src/main/resources/candidates.txt` and the code that loaded it no longer exist
- [ ] Every existing acceptance spec that writes a version registers its candidate first; none relies on a classpath allow-list
- [ ] `POST /versions` performs no database read of the registry
- [ ] A candidate registered through `POST /admin/candidates` is publishable without a restart
- [ ] The registry refreshes periodically as well as on write, so an instance that did not serve the write picks the candidate up within the interval
- [ ] `DELETE /admin/candidates/{candidate}` refreshes the registry in the instance that served it, so the deleted candidate stops being publishable there without waiting for the interval
- [ ] The refresh interval is configuration, defaults to five minutes, and is overridable per environment
- [ ] No test asserts that the delete `409` serialises against a concurrent `POST /versions`; rule 6 records that window as accepted
- [ ] A registry refresh failure keeps serving the last good copy rather than rejecting valid publishes
- [ ] `POST /versions` returns `500`, not `400`, when the registry has never loaded successfully
- [ ] A failed load at startup does not stop the service booting, the read routes keep serving while the holder is unready, and the TTL refresh recovers it without a restart
- [ ] `/meta/health` is unchanged and does not gate on registry readiness
- [ ] The rejection message enumerates the registry sorted ascending, asserted on the order rather than only on the `"Allowed values:"` prefix
- [ ] Every name in `candidates.txt` is either registered or on the documented exclusion list before this ships, proven by `candidates_migration verify --allow-list` against the live registry rather than by inspection, and no name falls outside both
- [ ] No read path consults the registry: `GET /candidates`, the version read routes and the tag routes are unchanged
- [ ] `CandidateRegistryPublishingAcceptanceSpec` is deleted rather than adapted: all four of its cases assert the coexistence this part ends, and two of them read `CandidateLoader.allowedCandidates` directly
- [ ] This part adds no Flyway migration and alters no existing table
- [ ] OpenAPI: `POST /versions`'s existing `500` description covers a registry that has never loaded, not only a database error
- [ ] All quality gates pass (`./gradlew check`)
