# Assign Tag to Version

There is currently no way to add a tag to an existing version without re-posting the entire version through `POST /versions`. That endpoint applies *replace* semantics to the tag set, so a client wanting to move or add a single tag must resend the full version payload and the complete desired tag list. This feature adds a dedicated, focused endpoint that assigns a single tag to an existing version, leaving the version's other tags untouched.

It is the write-side counterpart to the existing tag resolution (`GET /versions/{candidate}/tags/{tag}`) and tag removal (`DELETE /versions/tags`) endpoints, completing the targeted tag lifecycle: assign, resolve, remove — none of which require touching the version payload.

## Behaviour

A client assigns a tag to a version by identifying the target version coordinates `(candidate, version, distribution, platform)` and the `tag` to apply. The tag is added to that version's existing tag set.

Tags are mutually exclusive within a `(candidate, distribution, platform)` scope — a given tag points to exactly one version at a time. Assignment therefore behaves as "assert that this version holds this tag":

- If the tag is not currently assigned anywhere in the scope, it is added to the target version.
- If the tag currently points to a **different** version in the same scope, it is **moved** to the target version. This is identical to the move behaviour already exhibited by `POST /versions`.
- If the tag already points to the target version, the operation is a **no-op** and still succeeds (idempotent).

In all three cases the target version's *other* tags are preserved — this is an append/assign operation, never a replace.

The endpoint manages tags only; it never creates a version. If the target version does not exist, the request fails with `404 Not Found`.

This is an authenticated write endpoint. It uses the same authorization model as `POST /versions` and `DELETE /versions/tags`: a valid JWT is required; admin tokens bypass candidate checks; vendor tokens must be authorized for the request's `candidate`.

## API Contract

### Request

```
POST /versions/tags
Authorization: Bearer <token>
Content-Type: application/json
```

Request body (`TagAssignment`):

| Field          | Required | Description                                          |
|----------------|----------|------------------------------------------------------|
| `candidate`    | yes      | The candidate name (e.g. `java`, `gradle`)           |
| `version`      | yes      | The version string of the target version             |
| `platform`     | yes      | Platform ID (must be a valid `Platform` enum value)  |
| `distribution` | no       | Distribution (must be a valid `Distribution` if set) |
| `tag`          | yes      | The tag to assign (e.g. `latest`, `lts`, `27`)       |

```json
{
  "candidate": "java",
  "version": "27.0.2",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64",
  "tag": "latest"
}
```

### Response

| Status                      | Body                       | When                                                        |
|-----------------------------|----------------------------|-------------------------------------------------------------|
| `204 No Content`            |                            | Tag assigned, moved, or already present (no-op)             |
| `400 Bad Request`           | `ValidationErrorResponse`  | Validation failure or malformed JSON                        |
| `401 Unauthorized`          |                            | Missing or invalid token                                    |
| `403 Forbidden`             |                            | Vendor not authorized for the candidate                     |
| `404 Not Found`             |                            | Target version does not exist for the given coordinates     |
| `500 Internal Server Error` | `ErrorResponse`            | Database error                                              |

Success returns no body — `204` is honest across all three success paths (new, moved, no-op), and consistent with every other write endpoint in the API.

The `404` is returned with no body, mirroring the existing `DomainError.VersionNotFound → 404` mapping.

## Business Rules

1. **Single tag per request.** The endpoint assigns exactly one tag. There is no list form. Assigning multiple tags is multiple requests.
2. **Append, not replace.** The target version's other tags are never removed. Only `POST /versions` applies replace semantics to a version's tag set.
3. **A tag points to exactly one version.** Tags are unique within `(candidate, tag, distribution, platform)` scope. Assigning a tag that lives on another version in the same scope moves it; the source version keeps its remaining tags.
4. **Idempotent.** Assigning a tag the version already holds succeeds with `204` and changes nothing.
5. **The version must exist.** Assignment never creates a version. A non-existent target yields `404`; the version is identified by the exact `(candidate, version, distribution, platform)` tuple.
6. **No semverish validation.** Unlike `POST /versions`, this endpoint does not apply semverish format validation to the `version` field. Semverish is a creation-time gate; a version that exists has already passed it, and a malformed/non-existent version simply falls through to `404`.
7. **Distribution is optional.** Candidates without distributions (e.g. `gradle`, `scala`) assign tags without a distribution; candidates with distributions (e.g. `java`) require it to identify the correct scope — consistent with the rest of the API.
8. **Tags are case-sensitive.** `latest` and `LATEST` are distinct. No normalisation is applied.

## Validation

Structural validation only, following the accumulated-error pattern used elsewhere (all failures returned in one `400` `ValidationErrorResponse`):

- `candidate` must not be blank.
- `version` must not be blank.
- `platform` must be a valid `Platform` enum value.
- `distribution`, if provided, must be a valid `Distribution` enum value.
- `tag` must satisfy the existing tag rules: 1–50 characters, matching `^[a-zA-Z0-9]([a-zA-Z0-9._-]{0,48}[a-zA-Z0-9])?$` (alphanumeric start and end; `.`, `-`, `_` permitted in the middle). The tag pattern is reused from the existing version-tag validation rather than redefined.

## Auditing

Tag assignment is recorded in the `vendor_audit` table, written **after** the transaction commits — an audit failure does not roll back the assignment, consistent with `POST /versions` and `DELETE /versions`.

- A new `AuditOperation.TAG` value distinguishes tag assignment from version `CREATE`/`DELETE` in the audit trail.
- The audit payload captures the assigned `tag` together with the target version coordinates `(candidate, version, distribution, platform)`.
- "Moved from" provenance is **not** materialised into the record. It is reconstructable from history: the prior audit entry for the same tag scope identifies where the tag previously lived. This avoids an extra pre-write lookup and cross-layer plumbing for no information gain.

## Domain & Implementation Notes

- **DTO:** a new serializable `TagAssignment` request DTO with the five fields above (`distribution` as `Option`).
- **Service / repository:** a new `assignTag(versionId, candidate, distribution, platform, tag)` on `TagService` / `TagRepository`. It performs the single race-safe `UPSERT` already used inside `replaceTags` — **without** the delete-not-in-list step. This naturally re-points the tag (move), is a no-op when already present (idempotent), and leaves sibling tags intact (append).
- **Auditable:** a new `AuditOperation.TAG` enum value and a new `Auditable` subtype (e.g. `TagAssignment`) serialized into the existing `version_data` JSON column via a new branch in `toJsonElement()`.
- **Transaction boundary:** the tag write runs inside the transaction; audit is recorded post-commit; a `DatabaseError` maps to `500`.
- **No database migration required.** `version_tags` already exists, and the enriched audit detail rides in the existing `version_data` JSON column.

## Access Matrix

| Endpoint            | Anonymous | Vendor (own candidate) | Vendor (other candidate) | Admin |
|---------------------|-----------|------------------------|--------------------------|-------|
| `POST /versions/tags` | No (401)  | Yes                    | No (403)                 | Yes   |

## Examples

```gherkin
Feature: Assign tag to version

  Scenario: Assign a new tag to an existing version
    Given a version "27.0.2" of "java" exists for "TEMURIN" on "LINUX_X64"
      And the tag "latest" is not assigned in that scope
    When an authorized client sends POST /versions/tags
      | candidate | java     |
      | version   | 27.0.2   |
      | distribution | TEMURIN |
      | platform  | LINUX_X64 |
      | tag       | latest   |
    Then the response status is 204
      And version "27.0.2" carries the tag "latest"

  Scenario: Assigning a tag moves it from another version
    Given version "27.0.1" of "java" for "TEMURIN" on "LINUX_X64" is tagged "latest"
      And version "27.0.2" of "java" for "TEMURIN" on "LINUX_X64" exists
    When an authorized client assigns "latest" to "27.0.2"
    Then the response status is 204
      And version "27.0.2" carries the tag "latest"
      And version "27.0.1" no longer carries the tag "latest"

  Scenario: Moving a tag preserves the source version's other tags
    Given version "27.0.1" of "java" for "TEMURIN" on "LINUX_X64" is tagged "latest" and "27"
      And version "27.0.2" of "java" for "TEMURIN" on "LINUX_X64" exists
    When an authorized client assigns "latest" to "27.0.2"
    Then version "27.0.1" still carries the tag "27"

  Scenario: Assigning a tag preserves the target version's existing tags
    Given version "27.0.2" of "java" for "TEMURIN" on "LINUX_X64" is tagged "27"
    When an authorized client assigns "latest" to "27.0.2"
    Then version "27.0.2" carries both "27" and "latest"

  Scenario: Re-assigning a tag the version already has is a no-op
    Given version "27.0.2" of "java" for "TEMURIN" on "LINUX_X64" is tagged "latest"
    When an authorized client assigns "latest" to "27.0.2"
    Then the response status is 204
      And version "27.0.2" still carries exactly its existing tags

  Scenario: Assign a tag for a candidate without distribution
    Given a version "8.12" of "gradle" exists on "UNIVERSAL"
    When an authorized client sends POST /versions/tags
      | candidate | gradle    |
      | version   | 8.12      |
      | platform  | UNIVERSAL |
      | tag       | latest    |
    Then the response status is 204

  Scenario: Target version does not exist
    Given no version "99.0.0" of "java" exists for "TEMURIN" on "LINUX_X64"
    When an authorized client assigns "latest" to that version
    Then the response status is 404

  Scenario: Invalid tag format
    Given a version "27.0.2" of "java" exists for "TEMURIN" on "LINUX_X64"
    When an authorized client assigns the tag "-bad-" to it
    Then the response status is 400

  Scenario: Unauthenticated request
    When an unauthenticated client sends POST /versions/tags
    Then the response status is 401

  Scenario: Vendor not authorized for the candidate
    Given a vendor token authorized only for "scala"
    When the vendor assigns a tag to a "java" version
    Then the response status is 403
```

## Out of Scope

- No changes to `POST /versions` and its replace semantics for the full tag set.
- No changes to `DELETE /versions/tags` or `GET /versions/{candidate}/tags/{tag}`.
- No multi-tag (list) assignment form.
- No materialised "moved from" provenance in the audit record (reconstructable from history).
- No database migration.

## Acceptance Criteria

- [ ] `POST /versions/tags` assigns a tag to an existing version and returns `204`
- [ ] Assigning a tag that lives on another version moves it; the source version keeps its other tags
- [ ] Assigning a tag preserves the target version's existing tags (append, not replace)
- [ ] Re-assigning a tag the version already has returns `204` and changes nothing
- [ ] Works for candidates without distributions
- [ ] Returns `404` (no body) when the target version does not exist
- [ ] Returns `400` with accumulated validation failures for blank fields, invalid enums, or invalid tag format
- [ ] Returns `401` for unauthenticated requests and `403` for vendors not authorized for the candidate
- [ ] Returns `500` on database error
- [ ] No semverish validation is applied to the `version` field
- [ ] Tag assignment is recorded under `AuditOperation.TAG` with tag + version coordinates; audit failure does not roll back the assignment
- [ ] OpenAPI documentation updated with the new endpoint and `TagAssignment` schema
- [ ] No nullable types used — follows the Arrow `Option` pattern
- [ ] All quality gates pass (`./gradlew check`)
```