# Java Version Supersession on Write

Since the versions collection moved from MongoDB to Postgres, the java catalogue holds two generations of rows side by side. The migration carried every Mongo row across **verbatim**, in its legacy non-semverish spelling (`26.0.2.fx`, `17.0.19.crac`, `26.0.2`). DISCO has since been posting new builds in **semverish** form (`26.0.2-fx+1.1`, `17.0.20-crac+1.2`, `26.0.2+1.1`), all of them `visible=true`, and nothing retires the legacy row they replace. The result is a `sdk list java` view that grows a duplicate every time DISCO publishes: on `LINUX_X64` it currently advertises 122 versions where 91 should be, and Liberica alone shows 28 rows for what is really 14 artefacts.

This change makes `POST /versions` **retire the release series it supersedes**. When a java version is published, every other row in the same series — same candidate, distribution, platform, major line and variant — is flipped to `visible=false`. The superseded rows remain in the database and remain installable by explicit identifier; they simply stop being advertised. A one-off data migration applies the same rule retroactively to clear the accumulated backlog.

*Reference: closes out the versions Mongo→Postgres migration. The verbatim-java decision is recorded in [`../../../docs/specs/04-mongo-postgres-backfill.md`](../../../docs/specs/04-mongo-postgres-backfill.md); the semverish grammar this builds on, including the legacy→semverish mapping table, is [`semverish-version-validation.md`](semverish-version-validation.md). DISCO is an additive writer that only ever posts `visible=true` — it does not retire its own predecessors, which is why the rule belongs here.*

## Behaviour

`POST /versions` for the `java` candidate does two things in one transaction: it upserts the submitted version as today, and it flips every other row in the same **release series** to `visible=false`.

A release series is the set of rows sharing a candidate, distribution, platform, major version and variant. It is the unit within which exactly one version should be advertised — Liberica's JavaFX builds for major 26 on `LINUX_X64` are one series; Liberica's plain builds for major 26 on `LINUX_X64` are a different one; the same JavaFX builds on `MAC_ARM64` are a third.

Supersession is **positional, not comparative**. The posted version becomes the advertised one regardless of how it orders against what was there. This deliberately avoids depending on a semverish comparator, whose precedence semantics are still unspecified, and it matches how DISCO publishes: forward, one build at a time.

Retirement never deletes. A retired row keeps its URL, checksums and identifier, so `sdk install java 26.0.2.fx-librca` continues to resolve for anyone with it pinned in a `.sdkmanrc` or CI script. It is removed from listings only.

Supersession applies to `java` and to nothing else. Java is the only candidate with a distribution axis, the only one with a release-series notion, and the only one whose catalogue carries two generations of identifier — the conditions that make the rule meaningful do not arise elsewhere, and are not expected to. Every other candidate's `POST /versions` is untouched: each published version stays visible until something hides it explicitly. The candidate is fixed in code, not configured.

## The Release Series

A series key is derived from a stored or submitted version string as `(candidate, distribution, platform, major, variant)`.

Submitted versions are normally semverish: `java` is listed in `validation.semverish.candidates`, so write-time validation enforces the grammar. That is configuration, not a guarantee — `SEMVERISH_CANDIDATES` can switch it off — so supersession does not lean on it. It derives the series key from the submitted version by the same eligibility test it applies to stored rows, and a posted version that is ineligible retires nothing (Rule 11a). For an eligible submission, `major` is the first core component and `variant` is the `-` section, which [`semverish-version-validation.md`](semverish-version-validation.md) defines as a release *flavour* rather than a pre-release.

Stored rows may be either generation, so the derivation must also read the legacy spelling the migration preserved.

### Eligible shapes

Series membership is decided by an explicit grammar, **not** by the full legacy→semverish mapping in the sibling spec. That mapping is broader than what is wanted here: it maps `25.0.2.r25` → `25.0.2+r25` and `25.0.2.1` → `25.0.2+1`, which would fold runtime targets and rebuild counters into the plain series and make them retirable. Only these shapes take part in a series:

```
<major>.<minor>.<patch>                    plain
<major>.<minor>.<patch>.<variant>          legacy variant spelling
<major>.<minor>.<patch>-<variant>[+<build>]   semverish
<major>.<minor>.<patch>[+<build>]             semverish, no variant
```

where `<variant>` is `fx` or `crac`, and the three core components are exactly three. Anything else is **ineligible**.

| Stored version | Generation | Major | Variant | Note |
|---|---|---|---|---|
| `26.0.2+1.1` | DISCO | 26 | *(none)* | build metadata excluded from the key |
| `26.0.2-fx+1.1` | DISCO | 26 | `fx` | |
| `17.0.20-crac+1.2` | DISCO | 17 | `crac` | |
| `28.0.0+ea.11` | DISCO | 28 | *(none)* | early access is build metadata |
| `26.0.2` | migrated | 26 | *(none)* | |
| `26.0.2.fx` | migrated | 26 | `fx` | |
| `17.0.19.crac` | migrated | 17 | `crac` | |
| `25.0.4.r25` | — | — | — | **ineligible**: `r25` is a runtime target, not a variant |
| `11.0.14.1` | — | — | — | **ineligible**: four numeric components, a rebuild counter |
| `25.r25` | — | — | — | **ineligible**: two core components |

Build metadata is deliberately excluded from the key. It is what distinguishes the members of a series from one another — `26.0.2` and `26.0.2+1.1` are the same release at different rebuild levels, which is precisely the duplication being collapsed.

An ineligible version takes part in no series: it is never retired, and never retires anything. This fails safe — an unrecognised spelling stays advertised rather than being silently hidden. It is what keeps `mandrel`'s `25.r25`, the `.r25` rows of `nik`, and the four-component `sem`/`jbr` rows out of scope. Note this is a per-*version* test, not a per-distribution one: `nik` also has `25.0.4.fx`, which is eligible and would be retired if a semverish `nik` fx build ever arrived.

The legacy variant vocabulary is `fx` and `crac`. The sibling spec deliberately restricts no variant vocabulary for *validation*; this is a narrower, local list used only to read the frozen set of already-migrated legacy strings. It is fixed in code, and a new variant arriving in semverish form needs no change here at all, since the semverish `-` section is read directly.

### Generation

Several rules below distinguish a **migrated** row from a **DISCO** row. There is no provenance column, so generation is read from the spelling: a version carrying a variant (`-`) or build-metadata (`+`) section is DISCO-generation; a bare `<major>.<minor>.<patch>` is migrated-generation.

This is a proxy, and its limit should be stated plainly: were DISCO ever to publish a bare three-component version, it would be indistinguishable from a migrated row. That does not occur in the current catalogue — every DISCO row carries a rebuild counter, runtime target or EA number — and the proxy is used only by the backlog pass, never by the write-time rule.

## API Contract

`POST /versions` is unchanged on the wire — same request body, same status codes, same response. Supersession is a server-side effect.

| Status | Body | When |
|--------|------|------|
| `204 No Content` | empty | Version upserted; any superseded rows in its series retired |
| `400 Bad Request` | `ErrorResponse` | Existing validation failures, unchanged |
| `401 Unauthorized` | empty | Existing authentication failures, unchanged |
| `403 Forbidden` | `ErrorResponse` | Existing candidate authorization failures, unchanged |

Each retirement is written to the audit trail as a distinct entry under a new `AuditOperation` value, `RETIRE`, attributed to the same vendor id and email as the POST that triggered it, so a row that disappears from listings can be traced back to the publication that displaced it. The service has no correlation-id concept, and this change does not introduce one.

This change introduces no configuration. There is no toggle, no candidate list and no environment override — supersession is unconditional behaviour of `POST /versions` for `java`.

## Business Rules

### Scope

1. **Java only, fixed in code.** Supersession runs for `candidate=java` and no other. It is not configurable and there is no opt-out. Every other candidate's `POST /versions` behaviour is untouched.
2. **Writer-agnostic.** The rule fires for any authenticated consumer posting a `java` version — DISCO and vendor-release alike. Visibility semantics are a property of the catalogue, not of who wrote to it.

### Series membership

3. **Series key.** A row belongs to the series `(candidate, distribution, platform, major, variant)`.
4. **Platform is part of the key.** Retirement never crosses platforms. DISCO publishes one row per platform, so a partially rolled-out release retires the old row only on the platforms where its replacement has actually landed.
5. **Distribution is part of the key.** A Temurin publication never retires a Zulu row, and a row with no distribution never shares a series with one that has a distribution.
6. **Variant is part of the key.** A plain publication does not retire the `fx` or `crac` rows of the same major, and vice versa. They are distinct artefacts at the same release level.
7. **Build metadata is excluded from the key.** `26.0.2` and `26.0.2+1.1` are the same series; the second supersedes the first.
8. **Eligibility is an explicit grammar.** A version takes part in a series only if it matches one of the shapes under *Eligible shapes*: exactly three core components, optionally an `fx`/`crac` variant in either the legacy dot spelling or the semverish `-` spelling, optionally build metadata. The broader legacy→semverish mapping in [`semverish-version-validation.md`](semverish-version-validation.md) is deliberately **not** applied — it would fold runtime targets and rebuild counters into the plain series.
9. **Ineligible versions take part in no series.** A version outside that grammar — four-component rebuild counters (`11.0.14.1`), `r`-suffixed runtime targets (`25.0.4.r25`), two-component cores (`25.r25`) — is never retired and never retires another row. Eligibility is a property of the version, not the distribution.

### Retirement

10. **The posted version wins, on its own platform only.** On a successful upsert, every *other* row in the series is set `visible=false` — and the series is platform-scoped (Rule 4), so only rows on the **same platform as the posted row** are touched. The same version on another platform is a different series and is left alone. No comparison is made between the posted version and the rows it displaces.
11. **Only a visible publication supersedes.** A POST carrying `visible=false` upserts as today and retires nothing. Publishing a hidden row must not empty the series. An absent `visible` field persists as `true` and therefore does supersede.
11a. **An ineligible publication supersedes nothing.** If the posted version does not match the eligibility grammar, it upserts as today and retires nothing. This is the fail-safe if semverish validation is ever switched off for `java`.
12. **Retirement is not deletion.** Superseded rows keep their URL, checksums, identifier and tags. `GET /versions/{candidate}/{version}` and the broker's download path continue to resolve them; only listings filter them out.
13. **Retirements are atomic with the upsert.** The upsert, its tag replacement and the retirements commit in one transaction. A failed retirement fails the POST and leaves no partial state.
14. **Idempotent.** Re-posting the current version retires nothing further — the row is the posted row and is excluded — and leaves the already-retired rows as they are.
15. **Audited per row, best-effort.** Each retired row produces its own `RETIRE` audit entry carrying the vendor id and email of the triggering POST. Audit writes stay **outside** the transaction, matching the existing R6 decision for `CREATE` (`VersionServiceImpl.kt:61-63`): an audit failure is logged and must not roll back a valid publication. The trail is therefore best-effort, exactly as it already is for creates.
15a. **Concurrent posts to one series serialize.** Two simultaneous publications into the same series must not both survive visible. The retirement acquires a transaction-scoped advisory lock keyed on the series — `pg_advisory_xact_lock` over the series-key fields — before its locking select, so the later transaction waits, then observes the earlier one's committed row and retires it. Under the existing read-committed isolation an unlocked implementation can interleave and leave two visible rows. *Decision (planning, 2026-08-20): an earlier draft relied on `SELECT … FOR UPDATE` row locks alone, but a locking select cannot observe a concurrent transaction's uncommitted insert, so two posts of different versions could interleave and both survive. The advisory lock closes that window; the row-level `FOR UPDATE` select is kept beneath it to pin the rows being flipped. That select is scoped to the series in SQL — the eligibility grammar is also emitted as a regex over the version string, so the locked rows are the candidate/distribution/platform rows that could be members, not every visible row under that triple. Publications into different major lines or variants then neither share an advisory lock nor contend on row locks.*
16. **Tags are untouched.** Retirement neither moves, copies nor clears a tag. Tag ownership is already correct without help: `version_tags` is unique on `(candidate, tag, distribution, platform)`, and `replaceTags` upserts on that index without excluding `version_id`, so applying `lts`, `latest` or a major alias to a new version atomically re-points the tag off whichever version held it. That happens whether the tag arrives through the tag endpoints or in the `tags` field of the `POST /versions` body — the latter runs `replaceTags` inside the same transaction as the upsert, so a publication that carries its own tags supersedes and re-tags in one atomic step, leaving no window at all.

## Clearing the Backlog

Two Flyway migrations apply the rule to the rows already in the database. Order matters.

**`V17` — hide the incorrect GraalVM rows.** Oracle GraalVM was published with a `+r<N>` runtime-target suffix carrying the *GraalVM product* version rather than the JDK version, so `25.2.4+r25-graal` is not a newer build of `25.0.4-graal`. These rows are wrong and are hidden explicitly. DISCO is dropping the suffix in upcoming posts, at which point correctly-versioned rows supersede through the normal rule. Running this first is essential: left visible, `25.2.4+r25-graal` would be read as the head of the series and would retire the correct `25.0.4-graal`.

*Hide, not rewrite.* Rewriting these rows to a corrected identifier was considered and rejected. The correct target cannot be derived from the version string — stripping the suffix yields `25.2.4-graal`, which asserts a JDK patch level that does not exist if `25.2.4` is a GraalVM product version — and deriving it from the artifact URL is a data question, not a schema one. Hiding leaves no hole: `25.0.4-graal` is present and stays visible on every platform that carries a `+r<N>` row (`LINUX_X64`, `LINUX_ARM64`, `MAC_ARM64`, `WINDOWS_X64`), so GraalVM Oracle continues to advertise 17 / 21 / 25 throughout. What is accepted is a *currency* gap: if the `+r<N>` builds are newer than `25.0.4`, they are unreachable until DISCO republishes without the suffix.

**`V18` — retire superseded rows.** For every eligible series containing at least one **DISCO-generation** row (per *Generation* above: the version carries a `-` or `+` section), retire all but the surviving member. A series composed entirely of migrated rows is left completely untouched, honouring the rule that a version not yet superseded stays as it was. This is why Corretto's major-8 line keeps `8.0.504`, `8.0.472` and `8.0.232` — DISCO has published nothing into it, so nothing there is superseded.

Because the backlog pass has no triggering POST, it selects the survivor by ordering rather than position: **highest core version wins; at equal core version, a DISCO row beats a migrated one.** That second clause is what retires `26.0.2-librca` in favour of `26.0.2+1.1-librca`, the commonest case in the backlog.

**Tied series are skipped, not guessed.** If the two highest rows share a core version and are *both* DISCO-generation, the ordering rule cannot separate them without comparing build metadata — the semverish comparator this spec declines to define. `V18` retires nothing in such a series and reports it. Two series qualify today, both `open`: `27.0.0+ea.34` against `27.0.0+ea.35`, and `28.0.0+ea.10` against `28.0.0+ea.11`. They resolve on their own when DISCO next publishes into those majors and the write-time rule fires positionally.

Like the write-time rule, the backlog pass leaves `version_tags` alone. Unlike the write-time rule it has no accompanying tag POST to re-point anything, so a retired row still holding `lts`, `latest` or a major alias will keep resolving through `findByTag` until DISCO next hydrates that tag. Report the tags landing on retired rows as part of the migration rather than moving them — it is a short list, it self-corrects on DISCO's next sweep, and a surprise there is worth seeing before it is papered over.

Modelled against live `LINUX_X64`: `V17` hides 2 rows, `V18` retires 29, and the listing goes from 122 to **91**. Liberica goes from 28 rows to 14, resolving to a plain and an `fx` row per major line, plus `crac` where it exists. The migrations are data-driven and apply across every platform, so the full row count is larger.

## Examples

```gherkin
Feature: Java version supersession on write

  # --- core rule ---

  Scenario: A semverish publication retires its legacy counterpart
    Given a java 26.0.2 LINUX_X64 LIBERICA version is visible
    When DISCO posts java 26.0.2+1.1 LINUX_X64 LIBERICA
    Then 26.0.2+1.1 is visible
      And 26.0.2 is not visible
      And 26.0.2 still resolves by explicit identifier

  Scenario: A publication retires an older build in the same major line
    Given a java 17.0.19-crac LINUX_X64 LIBERICA version is visible
    When DISCO posts java 17.0.20-crac+1.2 LINUX_X64 LIBERICA
    Then 17.0.20-crac+1.2 is visible
      And 17.0.19-crac is not visible

  Scenario: The whole major series is retired, not just an exact match
    Given java 25.0.1+1, 25.0.2+1 and 25.0.3+1 LINUX_X64 KONA versions are visible
    When DISCO posts java 25.0.4+1 LINUX_X64 KONA
    Then only 25.0.4+1 is visible for KONA major 25 on LINUX_X64

  # --- series boundaries ---

  Scenario: A plain publication leaves the fx variant alone
    Given a java 26.0.2-fx+1.1 LINUX_X64 LIBERICA version is visible
    When DISCO posts java 26.0.2+1.1 LINUX_X64 LIBERICA
    Then 26.0.2-fx+1.1 is still visible

  Scenario: Retirement does not cross platforms
    Given a java 26.0.2 version is visible on LINUX_X64 and on MAC_ARM64
    When DISCO posts java 26.0.2+1.1 LINUX_X64 LIBERICA
    Then 26.0.2 is not visible on LINUX_X64
      And 26.0.2 is still visible on MAC_ARM64

  Scenario: Retirement does not cross distributions
    Given a java 26.0.2 LINUX_X64 ZULU version is visible
    When DISCO posts java 26.0.2+1.1 LINUX_X64 LIBERICA
    Then 26.0.2 ZULU is still visible

  Scenario: Retirement does not cross major lines
    Given a java 21.0.2 LINUX_X64 OPENJDK version is visible
    When DISCO posts java 26.0.2+1.1 LINUX_X64 OPENJDK
    Then 21.0.2 is still visible

  # --- eligibility ---

  Scenario: An r-suffixed legacy version is ineligible
    Given a java 25.0.4.r25 LINUX_X64 LIBERICA_NIK version is visible
    When DISCO posts java 25.0.4+1 LINUX_X64 LIBERICA_NIK
    Then 25.0.4.r25 is still visible

  Scenario: A four-component legacy version is ineligible
    Given a java 11.0.14.1 LINUX_X64 JETBRAINS version is visible
    When DISCO posts java 11.0.32+1 LINUX_X64 JETBRAINS
    Then 11.0.14.1 is still visible

  # --- guards ---

  Scenario: A hidden publication retires nothing
    Given a java 26.0.2 LINUX_X64 LIBERICA version is visible
    When a consumer posts java 26.0.2+1.1 LINUX_X64 LIBERICA with visible false
    Then 26.0.2 is still visible

  Scenario: Re-posting the current version is idempotent
    Given java 26.0.2+1.1 LINUX_X64 LIBERICA is visible and 26.0.2 is retired
    When DISCO posts java 26.0.2+1.1 LINUX_X64 LIBERICA again
    Then 26.0.2+1.1 is still visible
      And no further rows are retired

  Scenario: A candidate other than java is unaffected
    Given a gradle 8.13 UNIVERSAL version is visible
    When a consumer posts gradle 8.14 UNIVERSAL
    Then 8.13 is still visible

  # --- tags ---

  Scenario: Retirement leaves tags alone
    Given a java 21.0.12 LINUX_X64 TEMURIN version is visible and tagged "lts"
    When DISCO posts java 21.0.12+1.1 LINUX_X64 TEMURIN
    Then 21.0.12 is not visible
      And 21.0.12 still carries the "lts" tag

  Scenario: Tagging the new version re-points the tag off the retired one
    Given a java 21.0.12 LINUX_X64 TEMURIN version is retired and tagged "lts"
      And a java 21.0.12+1.1 LINUX_X64 TEMURIN version is visible
    When DISCO applies the "lts" tag to 21.0.12+1.1
    Then 21.0.12+1.1 carries the "lts" tag
      And 21.0.12 no longer carries it

  # --- audit ---

  Scenario: Each retirement is audited
    Given java 25.0.1+1 and 25.0.2+1 LINUX_X64 KONA versions are visible
    When DISCO posts java 25.0.4+1 LINUX_X64 KONA
    Then an audit entry exists for each retired version
      And each is a RETIRE entry carrying the vendor id of the triggering POST
```

## Out of Scope

- **A semverish comparator.** The write-time rule is positional and needs no ordering. The backlog migration orders by core version only, which is sufficient for the data it runs against; general semverish precedence remains unspecified, per [`semverish-version-validation.md`](semverish-version-validation.md).
- **Re-publishing an older build.** Rule 10 means a consumer that posts `26.0.1` after `26.0.2` retires the newer row. This follows from "the posted version wins" and is accepted: DISCO publishes forward, and the remedy is to re-post the correct head. No guard is added.
- **Normalising legacy identifiers to semverish.** Migrated rows keep their spelling. The catalogue will carry both generations for as long as DISCO has not republished a series, and untwinned distributions (`amzn`, `tem`, `sem`, `kona`, `ms`, `oracle`, `jbr`, `graalce`) stay legacy-spelled indefinitely.
- **Collapsing major lines.** Every major line a distribution publishes keeps a visible row. Retiring end-of-life majors — java 6 and 7 still appear under Zulu — is a separate catalogue-curation question.
- **Tag movement.** Retirement does not touch tags (Rule 16); the existing tag API already re-points a tag off the version that held it. `findByTag` continues to ignore `visible`, so between a version POST and its tag POST a tag can briefly resolve to a just-retired row. That window is transient, self-correcting, and no wider than it is today — closing it would mean changing the tag read contract, which belongs in its own change.
- **Consumer changes.** The candidates service already defaults to `visible=true` on its listing read, so retired rows drop out of `sdk list java` with no change on its side.
- **DISCO changes.** DISCO stays additive and keeps posting `visible=true`. Its GraalVM suffix fix is upstream work this spec only accommodates.

## Acceptance Criteria

- [ ] `POST /versions` for `java` retires every other row in the posted row's `(candidate, distribution, platform, major, variant)` series, in the same transaction as the upsert, with no configuration required to enable it.
- [ ] Retirement sets `visible=false` and changes nothing else; the row still resolves via `GET /versions/{candidate}/{version}` and the broker download path.
- [ ] Retirement does not cross platform, distribution, major or variant boundaries.
- [ ] Build metadata does not separate series: posting `26.0.2+1.1` retires `26.0.2`.
- [ ] A stored legacy version that is neither semverish nor covered by the mapping table is never retired and never retires another row.
- [ ] The legacy variant vocabulary is `fx` and `crac`.
- [ ] A POST carrying `visible=false` retires nothing.
- [ ] Re-posting the current version retires nothing further and leaves already-retired rows unchanged.
- [ ] A candidate other than `java` sees no behaviour change on `POST /versions`.
- [ ] Retirement leaves `version_tags` untouched; no tag is moved, copied or cleared as a side effect of a POST.
- [ ] Each retired row produces a `RETIRE` audit entry carrying the vendor id and email of the triggering POST, written outside the transaction so an audit failure cannot roll back the publication.
- [ ] Two concurrent posts into the same series cannot both end up visible.
- [ ] A posted version that fails the eligibility grammar upserts normally and retires nothing.
- [ ] `V17` hides every `java` row whose version carries a `+r<N>` build-metadata suffix under distribution `GRAALVM`, and runs before `V18`.
- [ ] `V18` retires rows only in series containing at least one DISCO-generation row; series composed entirely of migrated rows are untouched — specifically, Corretto major 8 keeps `8.0.504`, `8.0.472` and `8.0.232`.
- [ ] `V18` skips and reports any series whose two highest rows are both DISCO-generation at the same core version; the two `open` EA series are untouched.
- [ ] After both migrations, the `LINUX_X64` java listing advertises 91 versions where it advertised 122, and `25.0.4-graal` is among them — i.e. `V17` ran first and the correct GraalVM row survived.
- [ ] After both migrations, no series composed entirely of migrated legacy rows has lost a visible version.
- [ ] `src/main/resources/openapi/documentation.yaml` documents the supersession side effect on `POST /versions`.
- [ ] All quality gates pass (`./gradlew check`: build, detekt, ktlint, tests).
