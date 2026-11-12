# Resolve Version by Tag

There is currently no way to resolve a tag to the version it points to. Tags like `lts` and `latest` are returned in version responses, but a client cannot look up which version a tag refers to. This feature adds a dedicated tag resolution endpoint that returns the single version a tag points to within a given candidate, distribution, and platform scope.

Reference: [sdkman/sdkman-state#41](https://github.com/sdkman/sdkman-state/issues/41)

## Behaviour

A client can resolve a tag to its version by requesting a specific candidate and tag combination. For example, to find which Java version is currently the `lts` release for Temurin on Linux x64, the client requests the tag and receives back the single version it points to.

The endpoint mirrors the existing single-version lookup (`GET /versions/{candidate}/{version}`) in its response shape and semantics — it returns one version object on success, or 404 when no matching tag exists. The tag acts as an alternative identifier: instead of looking up a version by its version string, the client looks it up by its tag name.

Distribution is optional because some candidates (e.g. `scala`, `gradle`) do not have distributions. Platform defaults to UNIVERSAL when omitted, consistent with the existing single-version endpoint.

This is a public endpoint — no authentication required. It follows the same HTTP caching strategy as the other public version read endpoints.

## API Contract

### Request

```
GET /versions/{candidate}/tags/{tag}?distribution={distribution}&platform={platform}
```

| Parameter      | In    | Required | Default   | Description                                        |
|----------------|-------|----------|-----------|----------------------------------------------------|
| `candidate`    | path  | yes      |           | The candidate name (e.g. `java`, `scala`)          |
| `tag`          | path  | yes      |           | The tag to resolve (e.g. `lts`, `latest`)          |
| `distribution` | query | no       |           | Distribution name (e.g. `TEMURIN`, `CORRETTO`)     |
| `platform`     | query | no       | UNIVERSAL | Platform ID (e.g. `linuxx64`, `darwinarm64`)       |

### Response

| Status            | Body                   | When                                         |
|-------------------|------------------------|----------------------------------------------|
| `200 OK`          | Single version object  | Tag resolved successfully                    |
| `404 Not Found`   |                        | No version carries this tag in the given scope|
| `400 Bad Request`  |                        | Missing or blank candidate or tag            |

### Response Body

The response body uses the same version schema as `GET /versions/{candidate}/{version}` — a single version object, not an array.

```json
{
  "candidate": "java",
  "version": "25.0.2",
  "platform": "LINUX_X64",
  "url": "https://example.com/java-25.0.2-temurin-linux-x64.tar.gz",
  "visible": true,
  "distribution": "TEMURIN",
  "md5sum": null,
  "sha256sum": null,
  "sha512sum": null,
  "tags": ["lts"]
}
```

Fields `distribution`, `md5sum`, `sha256sum`, and `sha512sum` are nullable. The `tags` field contains all tags associated with the resolved version, not just the one used for the lookup.

## Business Rules

1. **A tag resolves to exactly one version.** Tags are unique within `(candidate, tag, distribution, platform)` scope. The endpoint returns a single version object, never a collection.
2. **Tags are case-sensitive.** `lts` and `LTS` are distinct tags. No normalisation is applied.
3. **Distribution is optional.** Candidates without distributions (e.g. `scala`, `gradle`) are resolved without a distribution parameter. Candidates with distributions (e.g. `java`) require distribution to identify the correct tag scope.
4. **Platform defaults to UNIVERSAL.** When the platform query parameter is omitted, the lookup assumes UNIVERSAL, consistent with the existing single-version endpoint.
5. **Visibility does not affect tag resolution.** A tagged version is returned regardless of its visibility flag, consistent with how the existing single-version endpoint behaves. Visibility controls listing, not direct lookups.
6. **404 means the tag does not exist in the given scope.** It does not distinguish between "tag never existed" and "tag existed but was removed."

## Examples

```gherkin
Feature: Resolve version by tag

  Scenario: Resolve a fully-scoped tag for a candidate with distribution
    Given a version "25.0.2" of "java" exists for "TEMURIN" on "LINUX_X64"
      And it is tagged "lts"
    When the client sends GET /versions/java/tags/lts?distribution=TEMURIN&platform=linuxx64
    Then the response status is 200
      And the response body is the version "25.0.2" for "TEMURIN" on "LINUX_X64"

  Scenario: Tag does not exist in the given scope
    Given no version of "java" is tagged "lts" for "TEMURIN" on "LINUX_X64"
    When the client sends GET /versions/java/tags/lts?distribution=TEMURIN&platform=linuxx64
    Then the response status is 404

  Scenario: Resolve tag for a candidate without distribution
    Given a version "3.6.4" of "scala" exists without distribution on "UNIVERSAL"
      And it is tagged "latest"
    When the client sends GET /versions/scala/tags/latest
    Then the response status is 200
      And the response body is the version "3.6.4" on "UNIVERSAL"

  Scenario: Platform defaults to UNIVERSAL when omitted
    Given a version "8.12" of "gradle" exists without distribution on "UNIVERSAL"
      And it is tagged "latest"
    When the client sends GET /versions/gradle/tags/latest
    Then the response status is 200
      And the response body is the version "8.12" on "UNIVERSAL"

  Scenario: Tag is scoped to distribution
    Given a version "25.0.2" of "java" for "TEMURIN" on "LINUX_X64" is tagged "lts"
      And a version "25.0.2" of "java" for "CORRETTO" on "LINUX_X64" is tagged "lts"
    When the client sends GET /versions/java/tags/lts?distribution=TEMURIN&platform=linuxx64
    Then the response status is 200
      And the response body contains distribution "TEMURIN"

  Scenario: Tags are case-sensitive
    Given a version "25.0.2" of "java" for "TEMURIN" on "LINUX_X64" is tagged "lts"
    When the client sends GET /versions/java/tags/LTS?distribution=TEMURIN&platform=linuxx64
    Then the response status is 404

  Scenario: Tagged version is returned regardless of visibility
    Given a version "25.0.2" of "java" for "TEMURIN" on "LINUX_X64" is tagged "lts"
      And the version is not visible
    When the client sends GET /versions/java/tags/lts?distribution=TEMURIN&platform=linuxx64
    Then the response status is 200
      And the response body is the version "25.0.2"
```

## Out of Scope

- No changes to the existing `GET /versions/{candidate}` collection endpoint
- No changes to the existing `GET /versions/{candidate}/{version}` single-version endpoint
- No changes to tag write operations (POST/DELETE tag endpoints)
- No tag validation or enumeration of allowed tag values — any string is accepted
- No visibility filtering on this endpoint

## Acceptance Criteria

- [ ] `GET /versions/java/tags/lts?distribution=TEMURIN&platform=linuxx64` returns the tagged version as a single object
- [ ] `GET /versions/java/tags/nonexistent?distribution=TEMURIN&platform=linuxx64` returns `404 Not Found`
- [ ] `GET /versions/scala/tags/latest` resolves without distribution, defaulting platform to UNIVERSAL
- [ ] `GET /versions/java/tags/LTS?distribution=TEMURIN&platform=linuxx64` returns 404 (case-sensitive)
- [ ] Same tag for different distributions resolves to the correct version per distribution
- [ ] Tagged version is returned regardless of its visibility flag
- [ ] OpenAPI documentation is updated with the new endpoint
- [ ] All quality gates pass (build, lint, tests)
