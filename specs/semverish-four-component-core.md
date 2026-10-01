# Semverish Four-Component Core

The semverish grammar introduced in [`semverish-version-validation.md`](semverish-version-validation.md) has a three-part numeric core, `M.N.P`. Java's own versioning scheme has four: [JEP 322 — Time-Based Release Versioning](https://openjdk.org/jeps/322) defines `$FEATURE.$INTERIM.$UPDATE.$PATCH`. Squeezing that fourth element into a three-part core forced producers to relocate it into build metadata (`25.0.2.1` → `25.0.2+1`), conflating JEP 322's patch counter with rebuild counters and runtime targets.

This change extends the semverish core to **four mandatory numeric components**, `M.N.P.Q`, loosely aligned with JEP 322. A three-part version is no longer semverish: `29.0.0.0+ea.10` is valid, `29.0.0+ea.10` is not.

*Reference: this spec amends the grammar defined in [`semverish-version-validation.md`](semverish-version-validation.md) and adjusts the eligibility grammar of [`java-version-supersession.md`](java-version-supersession.md). Both remain as the historic record of their own changes; where they conflict with this spec, this spec wins. Everything in the baseline spec not restated here — opt-in mechanism, configuration, error payload shape, scope of enforcement — is unchanged.*

## Behaviour

`POST /versions` for a candidate opted in to semverish validation (currently `java`) now requires a four-component core. A version with three components — whether bare, with a variant, or with build metadata — is rejected with `400 Bad Request` exactly as any other semverish violation is today. Candidates not opted in are unaffected.

Supersession of java release series continues to work for four-component publications, and those publications retire the three-component semverish rows already stored in their series.

## The Grammar

```
<major>.<minor>.<patch>.<q>[-<variant>][+<build-metadata>]
```

### Core version (`<major>.<minor>.<patch>.<q>`)

- Four numeric components separated by `.`.
- Each component is a non-negative integer with no leading zeros (`0` is valid; `00`, `01` are not).
- All four components are mandatory. `26`, `26.0` and `26.0.0` are **not** semverish — they must be padded to `26.0.0.0` before submission.

The components correspond to JEP 322's `$FEATURE.$INTERIM.$UPDATE.$PATCH`. Producers place the **fourth numeric element published upstream** in `<q>` — JEP 322's `$PATCH` where the vendor follows it, or whatever the vendor publishes in fourth position otherwise. Any numeric elements beyond the fourth go into build metadata. Validation is shape-only and does not attempt to verify which meaning a producer applied.

### Variant and build metadata

Build metadata is unchanged from the baseline spec. The variant keeps its syntax and semantics (a release flavour, not a pre-release), but its vocabulary is now **restricted**: the variant section is exactly one identifier, `fx` or `crac`. Any other variant — `-graal`, `-jfr`, `-FX`, combined `-fx.crac` — is rejected. This reverses the baseline's Business Rule 3 for variants only; build-metadata vocabulary stays unrestricted.

The shift in build-metadata *usage*: since the patch counter now lives in `<q>`, build metadata no longer carries a rebuild counter for four-part upstream versions — it carries early-access numbers, runtime targets, and numeric elements beyond the fourth.

### Mapping established Java patterns

Illustrative, as in the baseline spec — validation does not consult this table. It replaces the baseline mapping table.

| Pattern | Original | Normalised |
|---|---|---|
| Three-part | `25.0.2` | `25.0.2.0` |
| Four-part (JEP 322 patch) | `25.0.2.1` | `25.0.2.1` |
| Bare major | `26` | `26.0.0.0` |
| FX variant | `25.0.2.fx` | `25.0.2.0-fx` |
| CRaC variant | `21.0.10.crac` | `21.0.10.0-crac` |
| Early-access build | `27.ea.16` | `27.0.0.0+ea.16` |
| Runtime target | `25.0.2.r25` | `25.0.2.0+r25` |
| Patch + runtime target | `22.1.0.1.r17` | `22.1.0.1+r17` |
| Five-part vendor build | `21.0.5.11.1` | `21.0.5.11+1` |

Two rows change meaning relative to the baseline: `25.0.2.1` was previously normalised to `25.0.2+1` (rebuild counter) and `22.1.0.1.r17` to `22.1.0+1.r17`. Under this spec the `.1` is the patch component.

### Examples — valid and invalid

This list replaces the baseline list and drives parser-level unit tests.

**Valid:**
- `25.0.2.0`
- `25.0.2.1`
- `8.0.472.0`
- `0.0.0.0`
- `26.0.0.0-fx`
- `27.0.0.0+ea.16`
- `29.0.0.0+ea.10`
- `22.1.0.1+r17`
- `21.0.5.11+1`
- `25.0.2.0-fx+1` (variant and build metadata together)

**Invalid:**
- `25.0.2` — three-component core
- `29.0.0+ea.10` — three-component core with build metadata
- `26.0.0-fx` — three-component core with variant
- `26` — single component
- `25.0` — two components
- `25.0.2.1.1` — five numeric components (the fifth belongs in `+1`)
- `25.0.2.fx` — fourth component is not numeric (variant must use `-`)
- `27.ea.16` — early-access fragment in the wrong section
- `01.0.0.0` — leading zero in major
- `25.0.2.00` — leading zero in patch component
- `25.0.2.0-` — empty variant section
- `25.0.2.0+` — empty build metadata section
- `25.0.2.0-fx_crac` — `_` is not an allowed identifier character
- `25.0.2.0-fx.crac` — combined variants; the variant is exactly `fx` or `crac`
- `25.0.4.0-graal` — variant outside the `fx`/`crac` vocabulary
- `25.0.2.0-FX` — variant vocabulary is case-sensitive
- `25.0.2.0++1` — duplicate `+`
- `25.0.2.0--fx` — duplicate `-`
- `` — empty string

## Impact on Java Version Supersession

The supersession rule in [`java-version-supersession.md`](java-version-supersession.md) only acts on versions matching its eligibility grammar, which requires **exactly three** core components and explicitly treats four-component versions as ineligible (its Rules 8 and 9). Left unchanged, every four-component java publication would be ineligible and, under its Rule 11a, retire nothing — silently disabling supersession and reintroducing the duplicate-row backlog. Updating eligibility is therefore in scope.

### Eligible shapes

Eligibility is **extended, not replaced**. Validation decides what may be written from now on; eligibility decides what can be retired among everything already stored, and the store holds three-component semverish rows that four-component publications must be able to supersede.

```
<major>.<minor>.<patch>                                 legacy plain (the no-option case of the three-component row)
<major>.<minor>.<patch>.<variant>                       legacy variant spelling
<major>.<minor>.<patch>[-<variant>][+<build>]           three-component (pre-change spelling)
<major>.<minor>.<patch>.<q>[-<variant>][+<build>]       four-component semverish
```

Eligibility is one grammar, applied identically to the posted version and to stored rows.

For every eligible shape, `<variant>` is exactly `fx` or `crac`, in both the legacy dot spelling and the `-` spelling. Stored rows carrying any other variant (e.g. `25.0.4-graal`) are ineligible and are never retired. Everything else remains ineligible (`25.0.4.r25`, `25.r25`, five-component numerics).

### Series key

Unchanged: `(candidate, distribution, platform, major, variant)`. Every stored row the eligibility grammar admits into a series is found, locked and retired by a publication into that series, whatever its component count. The `<q>` component is excluded from the key, as `<minor>`, `<patch>` and build metadata already are. Posting `26.0.2.1` retires `26.0.2.0`, `26.0.2+1.1` and the legacy `26.0.2` in the same series; posting `26.0.2.0-fx+1` retires `26.0.2-fx+1.1` and the legacy `26.0.2.fx`.

### Consequence: bare four-component legacy rows become eligible

A bare `M.N.P.Q` is now a valid semverish shape, and there is no provenance column to tell a migrated row from a new one. Migrated rows such as `11.0.14.1` — previously excluded as "four numeric components, a rebuild counter" — therefore join their series and will be retired by the next visible publication into it. This affects, in particular, the `SEMERU` and `JETBRAINS` distributions.

This is accepted as correct: under this spec's reading, `11.0.14.1` is release 11.0.14 at patch 1, a legitimate member of the major-11 series, and superseding it is exactly what the rule is for.

The backlog migrations (`V17`, `V18`) have already run and are not revisited. The generation proxy in the supersession spec (used only by `V18`) is likewise unaffected.

## Storage

`versions.version` is `VARCHAR(25)` (`V1`, `V2`), mirrored by the Exposed mapping in `PostgresVersionRepository`. The grammar bounds neither component nor identifier length, and padding adds characters to every version (`25.0.2.0-crac+1.r25.ea` is already 23), so a grammatically valid version can exceed the column and fail as a `500` rather than a `400`. A new migration changes `versions.version` to `TEXT`, and the Exposed mapping follows. No length rule is added to validation.

## API Contract

No new endpoints and no request changes.

| Status | When |
|---|---|
| `204 No Content` | Version upserted; for opted-in candidates the version has a four-component semverish core |
| `400 Bad Request` | Candidate is opted in and the version does not conform — including any three-component version |

The validation error identifies the `version` field and its message must describe the four-component format (`M.N.P.Q[-variant][+build-metadata]`).

## Business Rules

1. **Four core components are mandatory** for opted-in candidates. Three-component versions are rejected, regardless of variant or build metadata.
2. **`<q>` follows the same rules as the other core components:** required, non-negative integer, no leading zeros.
3. **Numeric elements beyond the fourth belong in build metadata.** Validation rejects a five-component core.
4. **Opt-in, configuration and scope are unchanged** from the baseline spec. The opted-in set remains `{ "java" }`.
5. **Existing data is not re-validated or normalised.** Stored three-component rows remain as they are.
6. **Supersession eligibility is extended** to four-component semverish while retaining all previously eligible shapes, including three-component semverish.
7. **`<q>` is not part of the series key.**
8. **Bare four-component legacy rows are eligible** for supersession under the extended grammar.
9. **Version storage is unbounded.** `versions.version` is `TEXT`; no grammatically valid version fails on length.
10. **The variant vocabulary is `fx` or `crac`, exactly one, case-sensitive.** Any other variant is rejected at validation and is ineligible for supersession. Build-metadata vocabulary remains unrestricted.

## Examples

```gherkin
Feature: Four-component semverish validation on POST /versions

  Background:
    Given the application is configured with strict semverish validation for candidate "java"
      And no strict validation is configured for candidate "scala"

  Scenario: Four-component version is accepted for an opted-in candidate
    When the client sends POST /versions with candidate "java" and version "29.0.0.0+ea.10"
    Then the response status is 204

  Scenario: Three-component version is rejected for an opted-in candidate
    When the client sends POST /versions with candidate "java" and version "29.0.0+ea.10"
    Then the response status is 400
      And the validation error identifies the "version" field

  Scenario: Candidates not opted in are unaffected
    When the client sends POST /versions with candidate "scala" and version "3.5"
    Then the response status is 204

  Scenario: A four-component publication retires a stored three-component semverish row
    Given a visible java version "26.0.2-fx+1.1" for distribution "LIBERICA" on "LINUX_X64"
    When the client publishes java version "26.0.2.0-fx+1" for distribution "LIBERICA" on "LINUX_X64"
    Then "26.0.2-fx+1.1" is no longer visible

  Scenario: A bare four-component legacy row is retired by a publication into its series
    Given a visible java version "11.0.14.1" for distribution "SEMERU" on "LINUX_X64"
    When the client publishes java version "11.0.25.0" for distribution "SEMERU" on "LINUX_X64"
    Then "11.0.14.1" is no longer visible
```

## Out of Scope

- Any transition period accepting both three- and four-component versions.
- Coordinating DISCO's switch to four-component versions; until it switches, its three-component posts are rejected.
- Normalising or rewriting stored versions to four components.
- Re-running or amending the `V17` / `V18` backlog migrations.
- A semverish comparator; precedence remains unspecified.
- Editing the baseline or supersession specs; they stand as historic records.
- Changes to configuration, read endpoints, or tag endpoints.

## Acceptance Criteria

- [ ] `POST /versions` for `java` accepts `25.0.2.0`, `25.0.2.1`, `26.0.0.0-fx`, `27.0.0.0+ea.16`, `29.0.0.0+ea.10`, `22.1.0.1+r17`, `25.0.2.0-fx+1`
- [ ] `POST /versions` for `java` rejects `25.0.2`, `29.0.0+ea.10`, `26.0.0-fx`, `26`, `25.0.2.1.1`, `25.0.2.fx`, `01.0.0.0`, `25.0.2.00`, `25.0.2.0-`, `25.0.2.0+`, `25.0.2.0-fx.crac`, `25.0.4.0-graal`, `25.0.2.0-FX` with `400 Bad Request`
- [ ] `POST /versions` for a candidate not opted in accepts versions that fail four-component validation
- [ ] The validation error identifies the `version` field and describes the `M.N.P.Q[-variant][+build-metadata]` format
- [ ] A visible four-component java publication retires every other row in its `(candidate, distribution, platform, major, variant)` series, including stored three-component semverish rows and legacy rows
- [ ] Bare four-component legacy rows (e.g. `11.0.14.1`) are eligible for supersession
- [ ] `r`-suffixed and sub-three-component shapes (`25.0.4.r25`, `25.r25`) and five-component numerics remain ineligible
- [ ] `versions.version` is `TEXT`, and a valid version longer than 25 characters is stored and returns `204`
- [ ] OpenAPI documentation describes the four-component format for `POST /versions`, including the supersession eligibility description
- [ ] All quality gates pass (build, lint, tests)
