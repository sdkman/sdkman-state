# Version Tagging — Tags in GET Responses

Wire tags into the existing GET endpoints so that every version in the response includes its `tags` field as an array of strings. This is a read-only, non-breaking, additive change that depends on the schema and repository layer from Slice 1 (prompt 08).

See `specs/version-tagging.md` for the full feature specification.

## Requirements

- Add a `tags` field to the `Version` data class as `Option<List<String>>` defaulting to `None`
- `GET /versions/{candidate}` must include the `tags` array on each version in the response
- `GET /versions/{candidate}/{version}` must include the `tags` array on the single version in the response
- Versions with no tags must return `"tags": []` in the JSON response
- Versions with tags must return them as `"tags": ["latest", "27", "27.0"]`
- The `tags` field must not affect POST or DELETE request deserialization — it is output-only on responses and ignored on input for this slice
- No changes to query parameters or filtering logic — tags are purely informational in list/detail responses
- Performance: tags should be fetched efficiently, avoiding N+1 queries where possible

## Rules

**Before implementing, you MUST read and internalize:**
- rules/kotlin.md
- rules/domain-driven-design.md
- rules/kotest.md

**If this prompt conflicts with the rules, THE RULES WIN.** Update this prompt to align with the rules.

## Domain

The `Version` data class gains a `tags` field:

```kotlin
@Serializable
data class Version(
    val candidate: String,
    val version: String,
    val platform: Platform,
    val url: String,
    val visible: Option<Boolean> = None,
    val distribution: Option<Distribution> = None,
    val md5sum: Option<String> = None,
    val sha256sum: Option<String> = None,
    val sha512sum: Option<String> = None,
    val tags: Option<List<String>> = None
)
```

## Extra Considerations

### Serialization Behaviour
The `tags` field is `Option<List<String>>`. The existing serialization config uses `explicitNulls = false` which omits `Option.None` fields. To ensure `"tags": []` is always present in GET responses (not omitted), the repository layer must always populate tags as `Some(tagList)` — even when the list is empty. This guarantees consumers can always expect the field to exist. The `None` default is only relevant for POST request deserialization (handled in Slice 3), where absent means "don't touch existing tags".

### POST/DELETE Request Compatibility
The `tags` field defaults to `emptyList()`, so existing POST and DELETE payloads that don't include `tags` will continue to work without modification. The field is simply ignored during deserialization if absent. Tag *management* via POST is handled in Slice 3 — this slice only adds tags to responses.

### N+1 Query Prevention
When listing versions via `GET /versions/{candidate}`, avoid making a separate database call per version to fetch tags. Options:
- Join `versions` and `version_tags` in a single query and group results in the repository
- Fetch all versions first, collect their IDs, then batch-fetch all tags in a single query and merge in-memory

Choose whichever approach keeps the repository code cleanest while avoiding N+1 queries.

### Single Version Endpoint
For `GET /versions/{candidate}/{version}`, a simple query by version ID to the tags repository is sufficient since there's only one version.

## Testing Considerations

All tests should use Kotest's ShouldSpec style with real PostgreSQL.

### Happy Path Tests

- **Version list with tags**: insert versions with tags, call `GET /versions/{candidate}`, verify each version in response has correct `tags` array
- **Version list mixed tags**: insert some versions with tags and some without, verify tagged versions have their tags and untagged versions have `"tags": []`
- **Single version with tags**: insert a version with tags, call `GET /versions/{candidate}/{version}`, verify response includes correct `tags` array
- **Single version without tags**: insert a version with no tags, call `GET /versions/{candidate}/{version}`, verify response includes `"tags": []`
- **Multiple tags per version**: insert a version with `["latest", "27", "27.0"]`, verify all three appear in the response

### Regression Tests

- **Existing GET tests still pass**: all existing `GetVersionsApiSpec` and `GetVersionApiSpec` tests must continue to pass — versions without tags simply have `"tags": []`
- **POST without tags field**: verify existing POST payloads without `tags` still work (deserialization is backwards-compatible)

## Implementation Notes

### Order of Implementation
1. Read and internalize rules files
2. Add `tags: Option<List<String>> = None` to `Version` data class
3. Verify existing tests still compile and pass (backwards-compatible default)
4. Update `VersionsRepository.read` (list) to join or batch-fetch tags
5. Update `VersionsRepository.read` (single) to fetch tags for the version
6. Update OpenAPI documentation (`openapi/documentation.yaml`) to include the `tags` field on version response schemas
7. Write integration tests for tags in GET responses

### Repository Changes

The repository methods that return `Version` objects need to populate the `tags` field. The cleanest approach is likely:

```kotlin
// For list endpoint — batch approach
suspend fun read(candidate: String, ...): List<Version> {
    // 1. Fetch versions as before
    // 2. Collect version IDs
    // 3. Batch-fetch tags for all IDs from version_tags
    // 4. Merge tags into version objects
}

// For single endpoint — simple lookup
suspend fun read(candidate: String, version: String, ...): Option<Version> {
    // 1. Fetch version as before
    // 2. Fetch tags by version ID
    // 3. Copy version with tags
}
```

### Serialization

No special serialization configuration needed. `List<String>` serializes naturally with kotlinx.serialization. The `@Serializable` annotation on `Version` will pick it up automatically.

## Specification by Example

### GET /versions/java — list with tags

```http
GET /versions/java?platform=linuxx64

Response: 200 OK
[
  {
    "candidate": "java",
    "version": "27.0.1",
    "distribution": "TEMURIN",
    "platform": "LINUX_X64",
    "url": "https://cdn.example.com/java-27.0.1-temurin-linux-x64.tar.gz",
    "visible": true,
    "tags": ["27.0"],
    "sha256sum": "abc123..."
  },
  {
    "candidate": "java",
    "version": "27.0.2",
    "distribution": "TEMURIN",
    "platform": "LINUX_X64",
    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
    "visible": true,
    "tags": ["latest", "27", "27.0"],
    "sha256sum": "def456..."
  },
  {
    "candidate": "java",
    "version": "26.0.5",
    "distribution": "CORRETTO",
    "platform": "LINUX_X64",
    "url": "https://cdn.example.com/java-26.0.5-corretto-linux-x64.tar.gz",
    "visible": true,
    "tags": []
  }
]
```

### GET /versions/java/27.0.2 — single version with tags

```http
GET /versions/java/27.0.2?platform=linuxx64&distribution=TEMURIN

Response: 200 OK
{
  "candidate": "java",
  "version": "27.0.2",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64",
  "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
  "visible": true,
  "tags": ["latest", "27", "27.0"],
  "sha256sum": "def456..."
}
```

### GET /versions/gradle/8.10 — version without tags

```http
GET /versions/gradle/8.10?platform=universal

Response: 200 OK
{
  "candidate": "gradle",
  "version": "8.10",
  "platform": "UNIVERSAL",
  "url": "https://cdn.example.com/gradle-8.10.zip",
  "visible": true,
  "tags": []
}
```

## Verification

- [ ] `Version` data class has `tags: Option<List<String>> = None` field
- [ ] All existing tests compile and pass without modification (backwards-compatible default)
- [ ] `GET /versions/{candidate}` includes `tags` array on each version
- [ ] `GET /versions/{candidate}/{version}` includes `tags` array on the version
- [ ] Versions with no tags return `"tags": []`
- [ ] Versions with tags return the correct tag names
- [ ] No N+1 queries — tags are fetched efficiently for list endpoints
- [ ] POST payloads without `tags` field still deserialize correctly (`tags` defaults to `None`)
- [ ] Repository always returns `Some(tagList)` so `tags` is never omitted from GET responses
- [ ] OpenAPI documentation updated with `tags` field on version response schemas
- [ ] No nullable types used — follows Arrow Option pattern
- [ ] Code formatted and ktlint clean
