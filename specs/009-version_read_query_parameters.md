# Strict Canonical Query Parameters for Version Reads

The State API expresses `platform` and `distribution` as enum values in every request and response **body**, but its GET **query parameters** are loosely validated and inconsistent: `platform` expects a legacy lowercase identifier (`linuxx64`) rather than the enum name (`LINUX_X64`); `distribution` silently drops any value that isn't an exact enum match (so a vendor shortcode like `open`, or a typo, just disappears); and `visible` silently coerces any unrecognised value to `true`. In every case an invalid input fails **silently** — the wrong filter is quietly applied or dropped, never reported. This change makes the three version-read query parameters — `platform`, `distribution`, `visible` — **strict and canonical**: they accept the same enum vocabulary used in the bodies, and they reject unknown, legacy, or malformed values with `400` instead of silently coercing or dropping them.

*Reference: surfaced during the Candidates Service ⇄ State API integration (step 2 of the versions Mongo→Postgres migration). No live consumers depend on the legacy/loose forms; the only writers (DISCO and the legacy vendor-release service) POST bodies, which already use enum names. The platform identifier `linuxx64`, distribution shortcodes, and unvalidated `visible` values are remnants of the legacy CLI contract being retired here.*

## Behaviour

The version-read endpoints now validate their query parameters strictly and speak a single, canonical vocabulary — the same enum values used everywhere else in the API.

- **`platform`** takes a canonical platform enum value (`LINUX_X64`, `MAC_ARM64`, `UNIVERSAL`, …). The legacy lowercase identifiers (`linuxx64`, `darwinarm64`, `universal`, …) are gone. An unrecognised value — legacy form or typo — is a client error (`400`), never a silent fall-through to `UNIVERSAL`. `platform` is **required** for single-version and tag resolution (the previous implicit `UNIVERSAL` default is removed) and **optional** for listing (absent means "all platforms"). `UNIVERSAL` is returned only when explicitly requested.

- **`distribution`** takes a canonical distribution enum value (`TEMURIN`, `OPENJDK`, `ZULU`, …). It is **optional** on every endpoint (absent means "no distribution"). When supplied, it must be a canonical enum value; an unrecognised value — a vendor shortcode such as `open`/`tem`, or a typo — is a client error (`400`), never silently dropped so that the filter vanishes.

- **`visible`** takes one of `true`, `false`, or `all`, and applies only to the list endpoint. Absent defaults to `true` (visible versions only). Any value outside that set is a client error (`400`), never silently coerced to `true`.

Request and response **body** representation is unchanged — `platform` and `distribution` were, and remain, enum names there.

## API Contract

This affects the three version **read** endpoints. The parameters under change are `platform`, `distribution`, and `visible`.

### Requests

| Endpoint | `platform` | `distribution` | `visible` |
|----------|-----------|----------------|-----------|
| `GET /versions/{candidate}` | optional (absent = all platforms) | optional (absent = no distribution filter) | optional (absent = `true`) |
| `GET /versions/{candidate}/{version}` | **required** | optional | — (not accepted) |
| `GET /versions/{candidate}/tags/{tag}` | **required** | optional | — (not accepted) |

Parameter vocabularies (all exact, case-sensitive):

| Parameter | Accepted values |
|-----------|-----------------|
| `platform` | `LINUX_X32`, `LINUX_X64`, `LINUX_ARM32HF`, `LINUX_ARM32SF`, `LINUX_ARM64`, `MAC_X64`, `MAC_ARM64`, `WINDOWS_X64`, `UNIVERSAL` |
| `distribution` | `BISHENG`, `CORRETTO`, `GRAALCE`, `GRAALVM`, `JETBRAINS`, `KONA`, `LIBERICA`, `LIBERICA_NIK`, `MANDREL`, `MICROSOFT`, `OPENJDK`, `ORACLE`, `SAP_MACHINE`, `SEMERU`, `TEMURIN`, `ZULU` |
| `visible` | `true`, `false`, `all` |

### Responses

| Status | Body | When |
|--------|------|------|
| `200 OK` | `Version` (single/tag) or `Version[]` (list) | Request is valid and a match is found |
| `400 Bad Request` | `ErrorResponse` | A required parameter is absent, or a supplied parameter value is not in its accepted vocabulary |
| `404 Not Found` | empty | (single/tag only) request is valid but no matching version exists |

### Response Body

The `400` body is the existing `ErrorResponse` shape, naming the offending parameter and value and the accepted set:

```json
{
  "error": "Bad Request",
  "message": "Invalid platform 'linuxx64'. Expected one of: LINUX_X32, LINUX_X64, LINUX_ARM32HF, LINUX_ARM32SF, LINUX_ARM64, MAC_X64, MAC_ARM64, WINDOWS_X64, UNIVERSAL."
}
```

```json
{
  "error": "Bad Request",
  "message": "Invalid distribution 'open'. Expected one of: BISHENG, CORRETTO, GRAALCE, GRAALVM, JETBRAINS, KONA, LIBERICA, LIBERICA_NIK, MANDREL, MICROSOFT, OPENJDK, ORACLE, SAP_MACHINE, SEMERU, TEMURIN, ZULU."
}
```

```json
{
  "error": "Bad Request",
  "message": "Invalid visible 'yes'. Expected one of: true, false, all."
}
```

A successful `Version` response is unchanged, e.g.:

```json
{
  "candidate": "java",
  "version": "21.0.3",
  "platform": "LINUX_X64",
  "url": "https://example.com/java-21.0.3.tar.gz",
  "visible": true,
  "distribution": "TEMURIN",
  "tags": ["lts"]
}
```

## Business Rules

### Platform

1. **Canonical values only.** Accepted `platform` values are exactly the platform enum names: `LINUX_X32`, `LINUX_X64`, `LINUX_ARM32HF`, `LINUX_ARM32SF`, `LINUX_ARM64`, `MAC_X64`, `MAC_ARM64`, `WINDOWS_X64`, `UNIVERSAL`. Matching is exact and case-sensitive.
2. **Legacy identifiers retired.** The former lowercase platform identifiers (`linuxx64`, `darwinarm64`, `universal`, …) are no longer accepted and are treated as invalid (Rule 3).
3. **Unknown platform is a client error.** A `platform` value not in the canonical set yields `400`. It is never silently coerced to `UNIVERSAL`.
4. **Platform required for single-version resolution.** `GET /versions/{candidate}/{version}` requires `platform`; absent → `400`.
5. **Platform required for tag resolution.** `GET /versions/{candidate}/tags/{tag}` requires `platform`; absent → `400`. The previous implicit `UNIVERSAL` default is removed.
6. **Platform optional for listing.** `GET /versions/{candidate}` treats an absent `platform` as "no platform filter" (all platforms). A supplied value must be valid (Rule 3).
7. **`UNIVERSAL` is explicit-only.** A `UNIVERSAL` version is returned only when `platform=UNIVERSAL` is explicitly supplied, or — on the list endpoint — when no platform filter is given. There is no automatic fallback from a specific platform to `UNIVERSAL`.

### Distribution

8. **Canonical values only.** Accepted `distribution` values are exactly the distribution enum names (`BISHENG` … `ZULU`, per the vocabulary table). Matching is exact and case-sensitive.
9. **Shortcodes and legacy forms retired.** Vendor shortcodes (e.g. `open`, `tem`, `zulu` lower-case, `amzn`) are not accepted on the query parameter and are treated as invalid (Rule 10).
10. **Unknown distribution is a client error.** A supplied `distribution` value not in the canonical set yields `400`. It is never silently dropped (which would discard the filter and broaden the result).
11. **Distribution is optional everywhere.** Absent `distribution` means "no distribution specified": on the list and single-version endpoints it applies no distribution filter; on the tag endpoint it resolves the version tagged for the candidate with no distribution. Absence is never an error.

### Visible

12. **Fixed vocabulary.** `visible` accepts exactly `true`, `false`, or `all` (case-sensitive). `true` returns visible versions only; `false` returns hidden versions only; `all` applies no visibility filter.
13. **Default is visible-only.** Absent `visible` is equivalent to `true`.
14. **Unknown visible is a client error.** A supplied `visible` value outside `{true, false, all}` yields `400`. It is never silently coerced to `true`.
15. **Visible applies to listing only.** `visible` is accepted only on `GET /versions/{candidate}`; the single-version and tag endpoints do not take it.

### Cross-cutting

16. **Descriptive error body.** Every `400` from these rules returns the `ErrorResponse` JSON shape `{ "error", "message" }`, where the message names the offending parameter and value (or the missing required parameter) and the accepted set.
17. **Body representation unchanged.** Request and response bodies continue to express `platform` and `distribution` as enum names; only the query-parameter inputs are affected.

## Examples

```gherkin
Feature: Strict canonical query parameters for version reads

  # --- platform ---

  Scenario: List filtered by a canonical platform value
    Given a java LINUX_X64 version exists
    When I GET /versions/java?platform=LINUX_X64
    Then the response is 200
      And every returned version has platform "LINUX_X64"

  Scenario: Legacy lowercase platform identifier is rejected
    When I GET /versions/java?platform=linuxx64
    Then the response is 400
      And the body names "linuxx64" as invalid and lists the accepted platform values

  Scenario: List without a platform returns all platforms
    Given java versions exist on LINUX_X64 and MAC_ARM64
    When I GET /versions/java
    Then the response is 200
      And versions for both LINUX_X64 and MAC_ARM64 are returned

  Scenario: Single-version resolution requires a platform
    When I GET /versions/java/21.0.3 without a platform parameter
    Then the response is 400

  Scenario: Tag resolution requires a platform
    When I GET /versions/java/tags/lts without a platform parameter
    Then the response is 400

  # --- distribution ---

  Scenario: Filter by a canonical distribution value
    Given a java 21.0.3 LINUX_X64 TEMURIN version exists
    When I GET /versions/java/21.0.3?platform=LINUX_X64&distribution=TEMURIN
    Then the response is 200
      And the returned version has distribution "TEMURIN"

  Scenario: Vendor shortcode is rejected, not silently dropped
    Given a java 21.0.3 LINUX_X64 TEMURIN version exists
    When I GET /versions/java/21.0.3?platform=LINUX_X64&distribution=open
    Then the response is 400
      And the body names "open" as invalid and lists the accepted distribution values

  Scenario: Distribution omitted is allowed
    Given a gradle 8.8 UNIVERSAL version exists with no distribution
    When I GET /versions/gradle?platform=UNIVERSAL
    Then the response is 200

  Scenario: Tag resolution for a candidate with no distribution
    Given a gradle 8.8 UNIVERSAL version is tagged "lts" with no distribution
    When I GET /versions/gradle/tags/lts?platform=UNIVERSAL
    Then the response is 200
      And the returned version is 8.8

  # --- visible ---

  Scenario: List defaults to visible-only
    Given a java LINUX_X64 visible version and a java LINUX_X64 hidden version exist
    When I GET /versions/java?platform=LINUX_X64
    Then the response is 200
      And only the visible version is returned

  Scenario: List all visibilities
    Given a java LINUX_X64 visible version and a java LINUX_X64 hidden version exist
    When I GET /versions/java?platform=LINUX_X64&visible=all
    Then the response is 200
      And both versions are returned

  Scenario: Unknown visible value is rejected, not coerced to true
    When I GET /versions/java?platform=LINUX_X64&visible=yes
    Then the response is 400
      And the body names "yes" as invalid and lists the accepted visible values
```

## Out of Scope

- Request/response **body** representation of `platform` and `distribution` — already enum names; not touched.
- The internal `NA` sentinel used to store "no distribution" on tagged versions — an implementation detail, not a wire concern; unchanged.
- Introducing any platform→`UNIVERSAL` resolution fallback — none exists today and none is added.
- Stored data — persisted `platform` and `distribution` values are already enum names; no data migration is implied.
- Downstream consumers — they adopt this contract in their own repositories; no changes to them here. In particular:
  - The **Candidates Service** already sends canonical `platform` enum names and canonical `distribution` on its default-version path, but its **validation path sends vendor shortcodes** (e.g. `open`, `tem`). Under this contract those become `400`; the Candidates Service must translate vendor shortcodes to distribution enum names (`open→OPENJDK`, `tem→TEMURIN`, …) before calling. This is a consumer-side fix, tracked as coordination — not part of this change.
- **Defunct platforms.** The canonical platform set deliberately omits `FREE_BSD` and `SUN_OS` (Solaris): these have been out of service for years and host zero versions. Callers must only send canonical platform values; a consumer that still classifies a client as FreeBSD/Solaris should collapse it to `UNIVERSAL` on its side rather than expecting the State API to recognise it.

## Acceptance Criteria

- [ ] `GET /versions/{candidate}?platform=LINUX_X64` returns only `LINUX_X64` versions; `?platform=linuxx64` (and other legacy identifiers) returns `400` with a descriptive body.
- [ ] `GET /versions/{candidate}` with no `platform` returns versions across all platforms.
- [ ] `GET /versions/{candidate}/{version}` and `GET /versions/{candidate}/tags/{tag}` each return `400` when `platform` is absent.
- [ ] No request path coerces an unrecognised `platform` to `UNIVERSAL`; `UNIVERSAL` is returned only when explicitly requested (or, on list, with no platform filter).
- [ ] `GET /versions/{candidate}/{version}?platform=LINUX_X64&distribution=TEMURIN` returns the matching version; `&distribution=open` (a shortcode) returns `400` with a descriptive body.
- [ ] A request that omits `distribution` is accepted and applies no distribution filter (list/single) or resolves the no-distribution tag (tag endpoint).
- [ ] `GET /versions/{candidate}?platform=LINUX_X64` returns visible versions only by default; `&visible=all` returns all; `&visible=false` returns hidden only.
- [ ] `GET /versions/{candidate}?visible=yes` (or any value outside `{true,false,all}`) returns `400` with a descriptive body.
- [ ] Every rejection returns the `ErrorResponse` JSON shape naming the offending parameter/value and the accepted set.
- [ ] `src/main/resources/openapi/documentation.yaml` is regenerated: `platform`, `distribution`, and `visible` query parameters are documented with their canonical enum vocabularies; the legacy "Platform ID (e.g. linuxx64)" wording is removed.
- [ ] All quality gates pass (`./gradlew check`: build, detekt, ktlint, tests).
