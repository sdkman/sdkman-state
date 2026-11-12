# Semverish Version Validation

SDKMAN currently accepts any non-empty string as a version when versions are created via `POST /versions`. As we move candidates onto a normalised three-part version scheme — referred to here as **semverish** — we need write-time validation that rejects malformed versions before they enter the database. To avoid disrupting candidates that have not yet been migrated, validation is opt-in per candidate via application configuration. The first candidate to opt in will be `java`.

This spec defines (a) what "semverish" means for SDKMAN and (b) how candidates opt in to enforcement.

## Behaviour

When a client submits a version through `POST /versions` for a candidate that has opted in to strict version validation, the version string is checked against the semverish grammar. A non-conforming version is rejected with `400 Bad Request` and a validation error describing the failure. A conforming version is accepted exactly as today.

For candidates that have **not** opted in, `POST /versions` continues to behave as it does today — no version-shape validation is performed beyond the existing checks (non-empty, etc.). The semverish rules and the opt-in flag are deliberately decoupled: opting in is the only thing that activates enforcement.

The set of opted-in candidates is determined by application configuration at startup. Changing the set requires a configuration change and a service restart; there is no runtime toggle.

## The Semverish Versioning Scheme

Semverish borrows SemVer 2.0.0's surface syntax (`M.N.P`, `-`, `+`) but reinterprets the meaning of the two optional sections in ways that matter:

- The `-...` section denotes a **variant** of a stable release (e.g. `-fx`, `-crac`) — *not* a pre-release as in SemVer. A semverish version with a `-` section is a fully-released, production artefact, just a different flavour of it. There is no notion of "less than the bare release" implied by its presence.
- Build metadata (`+...`) participates in **precedence** (deviating from SemVer, where it is ignored). Comparator semantics are out of scope for this spec — see *Ordering and precedence* below.

### Grammar

A semverish version has the shape:

```
<major>.<minor>.<patch>[-<variant>][+<build-metadata>]
```

where each component obeys the rules below.

#### Core version (`<major>.<minor>.<patch>`)

- Three numeric components separated by `.`.
- Each component is a non-negative integer.
- No leading zeros (e.g. `01.0.0` is invalid; `0.0.0` is valid).
- All three components are mandatory. A bare `26` or `26.0` is **not** semverish — it must be padded to `26.0.0` before submission.

#### Variant (optional, after `-`)

- Introduced by a single `-` immediately after `<patch>`.
- One or more identifiers separated by `.`.
- Each identifier is non-empty and contains only ASCII alphanumerics and `-`.
- Empty variant sections (`1.2.3-`) are invalid.

Used to denote release **variants** such as `fx` (JavaFX-bundled) and `crac` (CRaC-enabled). Variants are flavours of a stable release, not pre-releases — `25.0.2-fx` is *not* "less than" `25.0.2`; it is a different artefact at the same release level. SDKMAN does not currently restrict the variant vocabulary — any identifier matching the grammar is accepted.

Examples: `25.0.2-fx`, `26.0.0-crac`, `25.0.2-fx.crac` (combined variants, dot-separated).

#### Build metadata (optional, after `+`)

- Introduced by a single `+` after either `<patch>` or the variant section.
- One or more identifiers separated by `.`.
- Each identifier is non-empty and contains only ASCII alphanumerics and `-`.
- Empty metadata sections (`1.2.3+`) are invalid.

Used to express **rebuild counters**, **early-access numbers**, and **runtime targets**. Vocabulary is not restricted.

Examples: `25.0.2+1` (rebuild), `26.0.0+ea.13` (early access), `25.0.2+r25` (runtime target), `22.1.0+1.r17` (combined rebuild + runtime target).

### Mapping established Java patterns to semverish

These are illustrative and document the *target* normalised form. Validation does not consult this list; it only enforces the grammar above. Producers of version strings are expected to apply these conventions before calling the API.

| Pattern | Original | Normalised |
|---|---|---|
| Already three-part | `25.0.2` | `25.0.2` |
| Bare major | `26` | `26.0.0` |
| FX variant | `25.0.2.fx` | `25.0.2-fx` |
| CRaC variant | `21.0.10.crac` | `21.0.10-crac` |
| Combined variants | (synthetic) | `25.0.2-fx.crac` |
| Early-access build | `27.ea.16` | `27.0.0+ea.16` |
| Rebuild counter | `25.0.2.1` | `25.0.2+1` |
| Runtime target | `25.0.2.r25` | `25.0.2+r25` |
| Rebuild + runtime target | `22.1.0.1.r17` | `22.1.0+1.r17` |

### Examples — valid and invalid

**Valid:**
- `25.0.2`
- `8.0.472`
- `0.0.0`
- `26.0.0-fx`
- `25.0.2-fx.crac`
- `27.0.0+ea.16`
- `25.0.2+1`
- `22.1.0+1.r17`
- `25.0.2-fx+1` (variant and build metadata together)

**Invalid:**
- `26` — missing minor and patch
- `25.0` — missing patch
- `25.0.2.fx` — variant in the wrong section (must use `-`)
- `27.ea.16` — early-access fragment in the wrong section (must use `+ea.16`)
- `25.0.2.1` — rebuild counter in the wrong section (must use `+1`)
- `01.0.0` — leading zero in major
- `25.0.2-` — empty variant section
- `25.0.2+` — empty build metadata section
- `25.0.2-fx_crac` — `_` is not an allowed identifier character
- `25.0.2++1` — duplicate `+`
- `25.0.2--fx` — duplicate `-`
- `` — empty string

### Ordering and precedence

The semverish ordering rules deviate from strict SemVer (build metadata participates in precedence). Comparator implementation is **out of scope** for this spec; this section exists only so that downstream work can find this anchor.

## Configuration

Each candidate that should be subject to semverish validation must be explicitly opted in via application configuration.

### What

Application configuration exposes a single setting — a set of candidate names. Membership in this set means *strict semverish validation is enforced for `POST /versions` writes against this candidate*. Candidates not in the set are unaffected.

The initial production value is the set `{ "java" }`.

### When and where it takes effect

- The set is read once at application startup. Restart is required to apply changes.
- The set is consulted on every `POST /versions` request to determine whether to validate the incoming version string.
- The set has no effect on read endpoints, on tag write endpoints, on the existing candidates listing, or on any other surface.

### Future direction (informational)

Once a `candidates` table exists, this flag will move there as a per-row column. The application-config form is a pragmatic interim placement and the spec expects it to be replaced; consumers of this configuration should not assume permanence.

## API Contract

No new endpoints. `POST /versions` gains an additional validation step.

### Request

Unchanged. The request body is the same as today.

### Response

| Status | Body | When |
|---|---|---|
| `204 No Content` | (existing) | Request body is valid and (for opted-in candidates) the version string conforms to the semverish grammar |
| `400 Bad Request` | Validation error payload | The candidate is opted in **and** the version string does not conform to the semverish grammar |
| Other existing statuses | (existing) | Unchanged |

### Validation error payload

The error response uses the same shape as the existing validation errors emitted by `POST /versions`. The error identifies the offending field (`version`) and a human-readable reason. The exact wording of the reason is left to implementation, but it must indicate that the value violates the semverish format.

## Business Rules

1. **Opt-in is per candidate, not global.** A candidate not in the configured set is unaffected. There is no global "strict mode" switch.
2. **Validation applies to writes only.** Reads (`GET /versions/...`) and tag operations are unchanged.
3. **Validation is shape-only.** The validator enforces the grammar described above. It does not enforce a vocabulary of allowed variant or metadata identifiers; `25.0.2-anyword` is accepted as long as it parses.
4. **Validation is case-sensitive in identifiers.** `25.0.2-FX` and `25.0.2-fx` are both syntactically valid and considered distinct strings.
5. **Existing data is not re-validated.** Versions already stored when a candidate is opted in remain in place untouched. Re-validating or normalising legacy data is out of scope.
6. **Tag write endpoints are unaffected directly.** They reference versions that already exist; if a non-conforming version cannot be created, it cannot be tagged. No additional check is added at the tag layer.
7. **Configuration is read once at startup.** Changes to the opted-in set require a restart. There is no runtime toggle, admin endpoint, or hot-reload behaviour.
8. **Default set is `{ "java" }`** in production configuration. Other environments may override.

## Examples

These scenarios cover endpoint-level behaviour (opt-in routing, HTTP status, error shape). The exhaustive enumeration of valid and invalid version strings is the *Examples — valid and invalid* list above; that list drives parser-level unit tests, not acceptance scenarios.

```gherkin
Feature: Semverish version validation on POST /versions

  Background:
    Given the application is configured with strict semverish validation for candidate "java"
      And no strict validation is configured for candidate "scala"

  Scenario: Conforming version is accepted for an opted-in candidate
    When the client sends POST /versions with candidate "java" and a semverish-conforming version
    Then the response status is 201

  Scenario: Non-conforming version is rejected for an opted-in candidate
    When the client sends POST /versions with candidate "java" and a non-conforming version
    Then the response status is 400
      And the validation error identifies the "version" field

  Scenario: Validation does not apply to a candidate that has not opted in
    When the client sends POST /versions with candidate "scala" and a version that would fail semverish validation
    Then the response status is 201
```

## Out of Scope

- Backfill or normalisation of versions already stored in the database before a candidate is opted in.
- Migration of the opt-in flag from application configuration to a `candidates` table column.
- A custom precedence/comparator implementation for ordering semverish versions (specifically the deviation from SemVer where build metadata participates in ordering).
- Validation of tag write endpoints (`POST /versions/{candidate}/{version}/tags`); enforcement is transitive via the version-existence check.
- Any change to read endpoints (`GET /versions/...`).
- Any vocabulary restrictions on variant or build-metadata identifiers (e.g. whitelisting `fx`, `crac`, `ea`, `rN`).
- An admin endpoint for toggling the opt-in set at runtime.
- Application of semverish validation to candidates other than `java` in the initial rollout.

## Acceptance Criteria

- [ ] `POST /versions` for candidate `java` accepts `25.0.2`, `26.0.0-fx`, `25.0.2+1`, `27.0.0+ea.16`, `22.1.0+1.r17`
- [ ] `POST /versions` for candidate `java` rejects `26`, `25.0.2.fx`, `27.ea.16`, `25.0.2.1`, `25.0.2-`, `25.0.2+`, `01.0.0` with `400 Bad Request`
- [ ] `POST /versions` for a candidate not in the opted-in set accepts version strings that would fail semverish validation (current behaviour preserved)
- [ ] The set of opted-in candidates is sourced from application configuration and is `{ "java" }` by default in production
- [ ] Validation errors identify the `version` field and indicate a semverish format violation
- [ ] OpenAPI documentation is updated to describe the new `400` failure mode for `POST /versions` and the semverish format
- [ ] All quality gates pass (build, lint, tests)
