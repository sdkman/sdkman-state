# Semverish Open-Ended Core

[`semverish-four-component-core.md`](semverish-four-component-core.md) fixed the semverish core at exactly four numeric components and sent anything beyond the fourth into build metadata (`21.0.5.11.1` → `21.0.5.11+1`). That was stricter than Java's own scheme: [JEP 322](https://openjdk.org/jeps/322) assigns meaning to the first four elements, `$FEATURE.$INTERIM.$UPDATE.$PATCH`, and allows the sequence to be of arbitrary length.

This change keeps four components as the **minimum** and lifts the maximum. `25.0.0.1.1.2` becomes valid; `25.0.1` stays invalid. The core components take their JEP 322 names. Semverish also becomes **java's rule, fixed in code**, rather than an opt-in list in configuration.

The change only widens what is accepted. Every version that validates today still validates, so DISCO's current posts are unaffected.

*Reference: amends the grammar of [`semverish-four-component-core.md`](semverish-four-component-core.md) and the opt-in mechanism of [`semverish-version-validation.md`](semverish-version-validation.md). Both remain as historic records; where they conflict with this spec, this spec wins. Covers step 13 ("semverish java-only") of [`../../../docs/specs/application-execution-plan.md`](../../../docs/specs/application-execution-plan.md).*

## Behaviour

`POST /versions` for `java` accepts a version whose core has four or more numeric components, with the existing optional variant and build-metadata sections. Anything else is rejected with `400 Bad Request`, as today.

Semverish applies to `java` and to no other candidate. There is no configuration that adds a candidate or removes `java`.

## The Grammar

```
<feature>.<interim>.<update>.<patch>[.<trailing>...][-<variant>][+<build-metadata>]
```

### Core version

- Four or more numeric components separated by `.`. There is no upper bound.
- The first four are named after JEP 322: **FEATURE**, **INTERIM**, **UPDATE**, **PATCH**. Components after PATCH are **trailing elements** and have no individual names.
- Every component, trailing elements included, is a non-negative integer with no leading zeros (`0` is valid; `00`, `01` are not).
- FEATURE, INTERIM, UPDATE and PATCH are mandatory. `26`, `26.0` and `26.0.0` must be padded to `26.0.0.0` before submission.

Validation is shape-only. It does not check that a producer used each element in the JEP 322 sense.

### Variant (after `-`)

Unchanged: exactly one identifier, `fx` or `crac`, case-sensitive. The `-` section carries nothing else. Early-access numbers, rebuild counters and runtime targets belong in build metadata.

### Build metadata (after `+`)

Unchanged: one or more dot-separated identifiers of ASCII alphanumerics and `-`, vocabulary unrestricted. It carries early-access numbers (`+ea.25`), rebuild counters (`+1`, `+116386`) and runtime targets (`+r17`).

### Examples: valid and invalid

This list replaces the four-component list and drives parser-level unit tests.

**Valid:**
- `25.0.2.0`
- `25.0.2.1`
- `0.0.0.0`
- `21.0.5.11.1` (one trailing element)
- `25.0.0.1.1.2` (two trailing elements)
- `26.0.0.0-fx`
- `21.0.10.0-crac`
- `27.0.0.0+ea.16`
- `21.0.9.0+116386`
- `22.1.0.1+r17`
- `25.0.2.0-fx+1`
- `25.0.0.1.1.2-crac+ea.3`

**Invalid:**
- `25.0.1`: three components
- `29.0.0+ea.10`: three components with build metadata
- `26.0.0-fx`: three components with a variant
- `26`, `25.0`: too few components
- `01.0.0.0`: leading zero in FEATURE
- `25.0.2.00`: leading zero in PATCH
- `25.0.2.0.01`: leading zero in a trailing element
- `25.0.2.0.`: empty trailing element
- `25.0.2..0`: empty component
- `25.0.2.fx`: variant must use `-`
- `27.ea.16`: early access must use `+`
- `25.0.2.0-ea.25`: early access must use `+`, not `-`
- `25.0.2.0-fx.crac`: combined variants
- `25.0.4.0-graal`: variant outside `fx`/`crac`
- `25.0.2.0-FX`: variant is case-sensitive
- `25.0.2.0-`, `25.0.2.0+`: empty section
- `25.0.2.0--fx`, `25.0.2.0++1`: duplicate separator
- `25.0.2.0-fx_crac`: `_` is not an identifier character
- `` (empty string)

## Java Only

The opt-in list from the baseline spec is removed. The `validation.semverish.candidates` key leaves `application.conf` and `SEMVERISH_CANDIDATES` is no longer read; a deployment that still sets it is unaffected. Production holds only `java` in that list today, so java and every other candidate behave exactly as before.

Every candidate other than `java` gets no version-shape validation beyond the existing checks, and nothing can switch it on.

## API Contract

No new endpoints and no request changes.

| Status | When |
|---|---|
| `204 No Content` | Version upserted; for `java`, the version matches the grammar above |
| `400 Bad Request` | Candidate is `java` and the version does not match the grammar |

The validation error identifies the `version` field. Its message describes the format as `FEATURE.INTERIM.UPDATE.PATCH[.N...][-fx|-crac][+build-metadata]`.

## Business Rules

1. **Four core components minimum, no maximum.** Three-component versions are rejected, regardless of variant or build metadata.
2. **Trailing elements follow the core rules:** non-negative integer, no leading zeros, never empty.
3. **The variant is exactly `fx` or `crac`.** Unchanged.
4. **Build metadata vocabulary is unrestricted.** Unchanged.
5. **Strictly a widening.** Every version accepted under the four-component grammar is still accepted.
6. **Java only, fixed in code.** No configuration, no toggle, no other candidate.
7. **Existing data is not re-validated or normalised.**

## Examples

```gherkin
Feature: Open-ended semverish core on POST /versions

  Scenario: A version with trailing elements is accepted for java
    When the client sends POST /versions with candidate "java" and version "25.0.0.1.1.2"
    Then the response status is 204

  Scenario: A four-component version is still accepted for java
    When the client sends POST /versions with candidate "java" and version "21.0.9.0+116386"
    Then the response status is 204

  Scenario: A three-component version is rejected for java
    When the client sends POST /versions with candidate "java" and version "25.0.1"
    Then the response status is 400
      And the validation error identifies the "version" field

  Scenario: Early access in the variant section is rejected
    When the client sends POST /versions with candidate "java" and version "25.0.2.0-ea.25"
    Then the response status is 400

  Scenario: Other candidates are never semverish-validated
    When the client sends POST /versions with candidate "scala" and version "3.5"
    Then the response status is 204
```

## Out of Scope

- **Supersession.** This spec changes the validation grammar only. Supersession eligibility and the series key are untouched.
- Renaming anything outside the validation grammar and its error message, including the series key's `major`.
- Normalising or rewriting stored versions.
- DISCO changes. Its current posts keep validating; whether it ever posts trailing elements is its own call.
- A semverish comparator; precedence remains unspecified.
- Read endpoints and tag endpoints.

## Acceptance Criteria

- [ ] `POST /versions` for `java` accepts every version in the *Valid* list
- [ ] `POST /versions` for `java` rejects every version in the *Invalid* list with `400 Bad Request`
- [ ] Every version listed as valid in [`semverish-four-component-core.md`](semverish-four-component-core.md) is still accepted
- [ ] `POST /versions` for any candidate other than `java` accepts versions that fail the grammar
- [ ] `validation.semverish.candidates` is gone from `application.conf` and `SEMVERISH_CANDIDATES` is no longer read; java validation does not depend on configuration
- [ ] The validation error identifies the `version` field and describes the `FEATURE.INTERIM.UPDATE.PATCH[.N...][-fx|-crac][+build-metadata]` format
- [ ] OpenAPI documentation describes the open-ended core for `POST /versions` and drops the opt-in wording
- [ ] All quality gates pass (build, lint, tests)
