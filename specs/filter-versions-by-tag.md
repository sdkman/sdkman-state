# Filter Versions by Tag

Clients currently have no way to look up a version by its tag. Tags like `latest` or `lts` are returned in version responses, but cannot be used as search criteria. This feature adds a `tag` query parameter to the existing `GET /versions/{candidate}` endpoint, consistent with how `platform` and `distribution` are already accepted as filters.

Reference: [sdkman/sdkman-state#41](https://github.com/sdkman/sdkman-state/issues/41)

## Behaviour

A client can query for a specific tagged version by adding `?tag=latest` (or any tag name) to the existing versions-by-candidate endpoint. The `tag` parameter works alongside the existing `platform`, `distribution`, and `visible` filters — all filters compose together.

Because tags are unique within the scope of `(candidate, tag, distribution, platform)`, a fully-qualified query — one that specifies tag, distribution, and platform — returns at most one version. A broader query (tag only, or tag with just one of distribution/platform) may return multiple versions across the unfiltered dimensions.

The response shape is always a JSON array, consistent with the existing endpoint. No match produces an empty array, not a 404.

This is a public endpoint — no authentication required. Existing HTTP caching behaviour applies unchanged.

## API Contract

### Request

```
GET /versions/{candidate}?tag={tag}&distribution={distribution}&platform={platform}&visible={visible}
```

| Parameter      | In    | Required | Description                                          |
|----------------|-------|----------|------------------------------------------------------|
| `candidate`    | path  | yes      | The candidate name (e.g. `java`, `scala`)            |
| `tag`          | query | no       | Tag to filter by (e.g. `latest`, `lts`)              |
| `platform`     | query | no       | Platform ID (e.g. `linuxx64`, `darwinarm64`)         |
| `distribution` | query | no       | Distribution name (e.g. `TEMURIN`, `CORRETTO`)       |
| `visible`      | query | no       | Visibility filter: `true` (default), `false`, `all`  |

### Response

| Status            | Body                        | When                                  |
|-------------------|-----------------------------|---------------------------------------|
| `200 OK`          | JSON array of versions      | Always, including empty results       |
| `400 Bad Request` | Error object                | Missing or blank candidate            |

The response body schema is identical to the existing `GET /versions/{candidate}` response — an array of version objects. No changes to the response shape.

## Business Rules

1. **Tag is an additive filter.** When `tag` is absent, the endpoint behaves exactly as it does today — no change to existing queries.
2. **Tag matches against tag associations**, not against the `tags` array on the version itself. A version is returned when a tag record exists linking that tag name to the version for the given candidate, distribution, and platform scope.
3. **All filters compose with AND semantics.** A query with `tag=latest&distribution=TEMURIN&platform=linuxx64&visible=true` must satisfy all four conditions simultaneously.
4. **Tags are case-sensitive.** `latest` and `Latest` are distinct tags. No normalisation is applied.
5. **Empty results return 200 OK with `[]`.** A tag that matches nothing is not an error.
6. **Tag without distribution or platform is valid.** The query returns all versions carrying that tag across all unfiltered dimensions. For example, `?tag=latest` returns every version tagged `latest` for any distribution and platform under the given candidate.
7. **Visibility still applies.** A version that is tagged but not visible must be excluded when `visible=true` (the default). A tagged hidden version is only returned when `visible=false` or `visible=all`.

## Examples

### Fully-qualified tag lookup

**Given** these versions exist:

| candidate | version | distribution | platform  | visible | tags       |
|-----------|---------|-------------|-----------|---------|------------|
| java      | 21.0.5  | TEMURIN     | LINUX_X64 | true    | [`latest`] |
| java      | 17.0.13 | TEMURIN     | LINUX_X64 | true    | []         |
| java      | 21.0.5  | CORRETTO    | LINUX_X64 | true    | [`latest`] |

**When** `GET /versions/java?tag=latest&distribution=TEMURIN&platform=linuxx64`

**Then** `200 OK` with:

```json
[
  {
    "candidate": "java",
    "version": "21.0.5",
    "platform": "LINUX_X64",
    "url": "https://temurin-21.0.5.tgz",
    "visible": true,
    "distribution": "TEMURIN",
    "md5sum": null,
    "sha256sum": null,
    "sha512sum": null,
    "tags": ["latest"]
  }
]
```

### Tag with no match

**When** `GET /versions/java?tag=lts&distribution=TEMURIN&platform=linuxx64`

**Then** `200 OK` with:

```json
[]
```

### Broad tag lookup (no distribution or platform filter)

**Given** the same data as the first example.

**When** `GET /versions/java?tag=latest`

**Then** `200 OK` with both TEMURIN and CORRETTO versions tagged `latest`, as an array with two elements.

### Tagged version that is not visible

**Given** a version `java 21.0.5 TEMURIN LINUX_X64` tagged `latest` with `visible = false`.

**When** `GET /versions/java?tag=latest&distribution=TEMURIN&platform=linuxx64`

**Then** `200 OK` with `[]` (default visibility filter is `true`, excluding the hidden version).

**When** `GET /versions/java?tag=latest&distribution=TEMURIN&platform=linuxx64&visible=all`

**Then** `200 OK` with the version included in the array.

### No tag parameter — existing behaviour unchanged

**When** `GET /versions/java`

**Then** behaves exactly as today — returns all visible versions for the candidate. The absence of `tag` has no effect on existing queries.

## Out of Scope

- No new endpoints — this extends an existing one
- No changes to the response schema
- No tag validation or enumeration of allowed tag values — any string is accepted
- No changes to tag write operations (POST/DELETE tag endpoints)
- No changes to the `GET /versions/{candidate}/{version}` single-version endpoint

## Acceptance Criteria

- [ ] `GET /versions/java?tag=latest&distribution=TEMURIN&platform=linuxx64` returns the single tagged version
- [ ] `GET /versions/java?tag=nonexistent` returns `200 OK` with `[]`
- [ ] `GET /versions/java?tag=latest` without distribution/platform returns all `latest`-tagged java versions
- [ ] `GET /versions/java?tag=latest&visible=false` returns only hidden tagged versions
- [ ] `GET /versions/java?tag=latest&visible=all` returns tagged versions regardless of visibility
- [ ] `GET /versions/java` (no tag) behaviour is unchanged
- [ ] `GET /versions/java?platform=linuxx64&distribution=TEMURIN` (no tag) behaviour is unchanged
- [ ] OpenAPI documentation is updated with the `tag` query parameter
- [ ] `./gradlew check` passes cleanly
