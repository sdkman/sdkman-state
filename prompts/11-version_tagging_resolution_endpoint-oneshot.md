# Version Tagging — Tag Resolution Endpoint

Introduce the `GET /versions/{candidate}/tags/{tag}` endpoint that resolves a tag to a single version. This is the consumer-facing payoff — the CLI and website can use it to resolve aliases like `latest` or `27` to a concrete version. Includes fallback from a specific platform to `UNIVERSAL` before returning 404. Depends on Slices 1–3 (prompts 08–10).

See `specs/version-tagging.md` for the full feature specification.

## Requirements

- `GET /versions/{candidate}/tags/{tag}` resolves a tag to a single `Version` and returns it
- The `platform` query parameter is required
- The `distribution` query parameter is optional — omit for candidates without distributions
- The response is a single `Version` object (same shape as `GET /versions/{candidate}/{version}`) including the `tags` array
- Fallback behaviour: if the tag is not found for the requested platform, retry with `UNIVERSAL`. If still not found, return 404
- No authentication required (public endpoint, consistent with existing GET endpoints)
- Response status codes: `200 OK`, `400 Bad Request`, `404 Not Found`

## Rules

**Before implementing, you MUST read and internalize:**
- rules/kotlin.md
- rules/domain-driven-design.md
- rules/kotest.md

**If this prompt conflicts with the rules, THE RULES WIN.** Update this prompt to align with the rules.

## Domain

No new domain types required. The endpoint uses the existing `TagsRepository.findVersionIdByTag()` to locate the version ID, then fetches the full `Version` via `VersionsRepository`.

## Extra Considerations

### Fallback Logic
The fallback to `UNIVERSAL` is important for candidates that may have platform-specific tags for some platforms but fall back to a universal binary for others. The fallback only applies when the originally requested platform is **not** `UNIVERSAL` — if the caller explicitly requests `UNIVERSAL` and the tag doesn't exist, return 404 immediately without retrying.

### Distribution Handling
When `distribution` is omitted from the query parameters, the repository lookup should match tags with `NA` sentinel (candidates without distributions). When `distribution` is provided, it must be a valid `Distribution` enum value — return 400 for invalid values.

### Platform Parameter
The `platform` query parameter uses platform IDs (e.g., `linuxx64`, `darwinarm64`, `universal`) consistent with the existing GET endpoints. Use `Platform.findByPlatformId()` for resolution.

### Response Body
The response is a full `Version` object including `tags`. The resolved version's tags should reflect all tags on that version, not just the one used to resolve it. For example, resolving tag `27` might return a version that also has `latest` and `27.0` tags.

### Caching
This endpoint serves the same cache-control headers as other GET endpoints (configured in `HTTP.kt`). Tag resolution results are cacheable but may change when tags are reassigned.

## Testing Considerations

All tests should use Kotest's ShouldSpec style with real PostgreSQL.

### Happy Path Tests

- **Resolve tag**: create a version with tags, resolve one of its tags, verify correct version returned
- **Resolve with distribution**: create Java versions for different distributions, resolve `latest` for `TEMURIN`, verify correct version
- **Resolve without distribution**: create a Gradle version with tags, resolve without distribution parameter, verify correct version
- **Fallback to UNIVERSAL**: create a version tagged `latest` on `UNIVERSAL` only, request with `platform=linuxx64`, verify UNIVERSAL version returned
- **Platform-specific takes precedence**: create both a `UNIVERSAL` and `LINUX_X64` version tagged `latest`, request with `platform=linuxx64`, verify the platform-specific version is returned (not UNIVERSAL)
- **Response includes all tags**: create a version with `["latest", "27", "27.0"]`, resolve via `27`, verify response includes all three tags
- **Different distributions resolve independently**: create Temurin 27.0.2 tagged `latest` and Corretto 25.0.1 tagged `latest` on the same platform, resolve each, verify different versions returned

### Unhappy Path Tests

- **Tag not found**: resolve a non-existent tag, get 404
- **Tag not found after fallback**: resolve a tag that exists on neither the requested platform nor UNIVERSAL, get 404
- **Missing platform parameter**: call without `platform` query parameter, get 400
- **Blank candidate**: call with blank candidate path parameter, get 400
- **Blank tag**: call with blank tag path parameter, get 400
- **Invalid distribution**: call with `distribution=INVALID`, get 400
- **UNIVERSAL no fallback**: request `platform=universal` for a tag that doesn't exist on UNIVERSAL, get 404 (no further fallback)

## Implementation Notes

### Order of Implementation
1. Read and internalize rules files
2. Add the new route in `Routing.kt` under the existing version routes
3. Implement the fallback resolution logic
4. Update OpenAPI documentation with the new endpoint
5. Write integration tests

### Route Registration

```kotlin
// In Routing.kt, alongside existing version routes
get("/versions/{candidate}/tags/{tag}") {
    val candidate = call.parameters["candidate"] ?: ""
    val tag = call.parameters["tag"] ?: ""
    val platformId = call.request.queryParameters["platform"] ?: ""
    val distributionName = call.request.queryParameters["distribution"]

    // Validate required parameters
    // ...

    val platform = Platform.findByPlatformId(platformId)
    val distribution = distributionName?.let { /* parse and validate */ }

    // Resolve tag with fallback — never use null, always Option
    val resolvedVersionId: Option<Int> = tagsRepository
        .findVersionIdByTag(candidate, tag, distribution, platform)
        .getOrElse { return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse(...)) }
        .orElse {
            // Fallback to UNIVERSAL if not found and platform is not already UNIVERSAL
            when (platform) {
                Platform.UNIVERSAL -> None
                else -> tagsRepository
                    .findVersionIdByTag(candidate, tag, distribution, Platform.UNIVERSAL)
                    .getOrElse { return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse(...)) }
            }
        }

    resolvedVersionId
        .map { versionId ->
            // Fetch full version with tags and respond
            call.respond(HttpStatusCode.OK, version)
        }
        .getOrElse {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(...))
        }
}
```

### URL Routing Consideration
The new route `/versions/{candidate}/tags/{tag}` must not conflict with the existing `/versions/{candidate}/{version}` route. Since Ktor matches routes by specificity, the literal `tags` segment should take precedence over the `{version}` parameter. Verify this during implementation and add a test to confirm that both routes work correctly side by side.

## Specification by Example

### Resolve tag — happy path
```http
GET /versions/java/tags/latest?platform=linuxx64&distribution=TEMURIN

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

### Resolve tag — without distribution
```http
GET /versions/gradle/tags/latest?platform=universal

Response: 200 OK
{
  "candidate": "gradle",
  "version": "8.12",
  "platform": "UNIVERSAL",
  "url": "https://cdn.example.com/gradle-8.12.zip",
  "visible": true,
  "tags": ["latest", "8", "8.12"]
}
```

### Resolve tag — fallback to UNIVERSAL
```http
# No "latest" tag exists for java on linuxarm32hf,
# but a UNIVERSAL version is tagged "latest"

GET /versions/java/tags/latest?platform=linuxarm32hf&distribution=TEMURIN

Response: 200 OK
{
  "candidate": "java",
  "version": "27.0.2",
  "distribution": "TEMURIN",
  "platform": "UNIVERSAL",
  "url": "https://cdn.example.com/java-27.0.2-temurin-universal.tar.gz",
  "visible": true,
  "tags": ["latest"]
}
```

### Tag not found
```http
GET /versions/java/tags/nonexistent?platform=linuxx64&distribution=TEMURIN

Response: 404 Not Found
{
  "error": "Not Found",
  "message": "Tag 'nonexistent' not found for java (TEMURIN) on linuxx64"
}
```

### Missing platform parameter
```http
GET /versions/java/tags/latest

Response: 400 Bad Request
{
  "error": "Bad Request",
  "message": "Query parameter 'platform' is required"
}
```

### Invalid distribution
```http
GET /versions/java/tags/latest?platform=linuxx64&distribution=INVALID

Response: 400 Bad Request
{
  "error": "Bad Request",
  "message": "Invalid distribution: INVALID"
}
```

## Verification

- [ ] `GET /versions/{candidate}/tags/{tag}` returns the correct version for a resolved tag
- [ ] `platform` query parameter is required — 400 if missing
- [ ] `distribution` query parameter filters correctly when provided
- [ ] Omitting `distribution` resolves tags for candidates without distributions
- [ ] Fallback to UNIVERSAL works when tag not found on requested platform
- [ ] No fallback when requested platform is already UNIVERSAL — returns 404
- [ ] Platform-specific tag takes precedence over UNIVERSAL (no unnecessary fallback)
- [ ] Response includes all tags on the resolved version, not just the queried tag
- [ ] Returns 404 for non-existent tags (after fallback attempt)
- [ ] Returns 400 for blank candidate, blank tag, or invalid distribution
- [ ] No authentication required (public endpoint)
- [ ] Does not conflict with existing `GET /versions/{candidate}/{version}` route
- [ ] OpenAPI documentation updated with the new endpoint, parameters, and response schemas
- [ ] No nullable types used — follows Arrow Option pattern
- [ ] Code formatted and ktlint clean
