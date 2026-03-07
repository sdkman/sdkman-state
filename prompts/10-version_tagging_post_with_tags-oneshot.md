# Version Tagging — POST /versions with Tag Support

Add tag management to the existing `POST /versions` endpoint. Tags are declared as part of the version payload and managed declaratively — the tags list in the payload fully replaces any previous tags on that version, and mutual exclusivity is enforced within the `(candidate, distribution, platform)` scope. This is the core write path for the tagging feature and depends on Slices 1 and 2 (prompts 08, 09).

See `specs/version-tagging.md` for the full feature specification.

## Requirements

- The `POST /versions` payload accepts an optional `tags` field as a list of strings
- When `tags` is present in the payload: all existing tags on that version are replaced with the new list (declarative)
- When `tags` is absent/null in the payload: existing tags on the version are left untouched (no-op for tags)
- When `tags` is an empty list `[]`: all tags are removed from the version
- Mutual exclusivity: when a tag is assigned to a version, it is automatically removed from any other version in the same `(candidate, distribution, platform)` scope
- Tag names must be validated before processing
- The response remains `204 No Content` (unchanged)
- Tag operations are audited as part of the existing `CREATE` audit entry — the `version_data` JSONB now includes the `tags` array

## Rules

**Before implementing, you MUST read and internalize:**
- rules/kotlin.md
- rules/domain-driven-design.md
- rules/kotest.md

**If this prompt conflicts with the rules, THE RULES WIN.** Update this prompt to align with the rules.

## Domain

The `Version` data class `tags` field must be changed from `List<String> = emptyList()` (Slice 2) to `Option<List<String>> = None`. See "Absent vs Empty" section under Extra Considerations for rationale. The GET response repository layer must always populate this as `Some(tagList)` so the field is never omitted from responses.

No other new domain types are required. The key behaviour lives in the routing/service layer that coordinates between `VersionsRepository` and `TagsRepository`.

## Extra Considerations

### Tag Processing Order
Tag processing must happen within the same transaction as the version upsert, or at minimum after the version upsert succeeds. The version must exist in the database (with a valid ID) before tags can reference it. The processing order is:

1. Validate the full request (including tag names)
2. Upsert the version (existing behaviour)
3. If `tags` field is present in the payload: call `TagsRepository.replaceTags()` with the version ID

### Distinguishing Absent vs Empty Tags
This is a critical distinction in the deserialization:
- **Absent** (`{"candidate":"java", ...}` — no `tags` key): leave existing tags alone
- **Present and empty** (`{"candidate":"java", ..., "tags":[]}` — explicit empty array): remove all tags
- **Present with values** (`{"candidate":"java", ..., "tags":["latest","27"]}` — populated array): replace tags

Use `Option<List<String>>` or a similar mechanism in the deserialization layer to distinguish between absent and present-but-empty. The default `emptyList()` on the `Version` data class may need adjustment to support this distinction — consider using `Option<List<String>>` for the `tags` field specifically in the request model, or a separate flag to track whether the field was present.

### Tag Name Validation
Applied to each tag in the list before any processing:
- Must not be blank/empty string
- Maximum length: 50 characters
- Allowed characters: alphanumeric, dots (`.`), hyphens (`-`), underscores (`_`)
- Must not start or end with a dot, hyphen, or underscore
- Regex: `^[a-zA-Z0-9]([a-zA-Z0-9._-]{0,48}[a-zA-Z0-9])?$`

Validation errors should accumulate (report all invalid tags, not just the first) and return `400 Bad Request` with the existing `ValidationErrorResponse` format:
```json
{
  "error": "Validation Error",
  "failures": [
    {"field": "tags[0]", "message": "Tag must contain only alphanumeric characters, dots, hyphens, and underscores"},
    {"field": "tags[2]", "message": "Tag must not exceed 50 characters"}
  ]
}
```

### Audit Trail
The audit record's `version_data` JSONB field should now include the `tags` array. Since the `Version` object already contains `tags`, this happens naturally when serializing to JSON for the audit entry.

## Testing Considerations

All tests should use Kotest's ShouldSpec style with real PostgreSQL.

### Happy Path Tests

- **POST version with tags**: POST a version with `tags: ["latest", "27"]`, verify tags are stored and returned in subsequent GET
- **Tags absent preserves existing**: POST a version with tags, then POST the same version again without `tags` field, verify original tags are unchanged
- **Empty tags clears all**: POST a version with tags, then POST again with `tags: []`, verify all tags are removed
- **Declarative replacement**: POST a version with `tags: ["latest", "27", "lts"]`, then POST again with `tags: ["latest", "27"]`, verify `lts` is gone
- **Mutual exclusivity**: POST version A with `tags: ["latest"]`, then POST version B (same candidate, distribution, platform) with `tags: ["latest"]`, verify `latest` moved from A to B
- **Mutual exclusivity across versions preserves other tags**: version A has `["latest", "27"]`, POST version B with `["latest"]`, verify A retains `"27"` but loses `"latest"`
- **Multiple tags**: POST a version with `tags: ["latest", "27", "27.0", "lts"]`, verify all four are stored
- **Tags on version without distribution**: POST a Gradle version with `tags: ["latest", "8"]`, verify tags stored with `NA` sentinel

### Unhappy Path Tests

- **Invalid tag characters**: POST with `tags: ["latest!"]`, get 400 with validation error
- **Blank tag**: POST with `tags: [""]`, get 400
- **Tag too long**: POST with tag exceeding 50 characters, get 400
- **Tag starting with dot**: POST with `tags: [".latest"]`, get 400
- **Multiple invalid tags**: POST with multiple invalid tags, get accumulated validation errors
- **Mixed valid and invalid tags**: POST with `tags: ["latest", ""]`, get 400 — no tags should be created (atomic)

### Regression Tests

- **Existing POST tests pass**: all existing `PostVersionApiSpec`, `PostVersionVisibilitySpec`, and `IdempotentPostVersionApiSpec` tests must continue to pass without modification
- **Audit includes tags**: POST a version with tags, verify the audit record's `version_data` JSON includes the `tags` array

## Implementation Notes

### Order of Implementation
1. Read and internalize rules files
2. Decide on the absent vs empty deserialization strategy for the `tags` field
3. Add tag name validation to `VersionRequestValidator`
4. Update the POST route handler to process tags after version upsert
5. Verify existing POST tests still pass
6. Write new integration tests for tag behaviour

### Absent vs Empty: Use `Option<List<String>>`

The `tags` field on `Version` must be `Option<List<String>>` — not `List<String>`. This follows the same pattern as every other optional field on the data class (`visible`, `distribution`, `md5sum`, etc.) and leverages the existing `OptionSerializer` and `explicitNulls = false` serialization config.

The three states map cleanly:
- `None` → field absent from JSON → don't touch existing tags
- `Some(emptyList())` → `"tags": []` in JSON → clear all tags
- `Some(listOf("latest", "27"))` → `"tags": ["latest", "27"]` in JSON → replace with these tags

**GET response handling:** The repository layer must always return `Some(tagList)` when building `Version` objects for responses — even when the list is empty. This ensures the `tags` field is always present in GET responses as `"tags": []"` and never omitted.

**POST request handling:** Deserialization naturally produces `None` when the field is absent, making the "don't touch tags" case automatic.

This means the `Version` data class from Slice 2 needs to be adjusted:
```kotlin
val tags: Option<List<String>> = None
```
instead of:
```kotlin
val tags: List<String> = emptyList()
```

### Routing Layer Pseudocode

```kotlin
post("/versions") {
    // ... existing validation and authentication ...

    val version = call.receive<Version>()

    // Validate tags if present
    version.tags.onSome { tagList ->
        validateTagNames(tagList).onLeft { failures ->
            return@post call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse(...))
        }
    }

    // Upsert version (existing behaviour)
    val versionId = versionsRepository.create(version).getOrElse { ... }

    // Process tags if present
    version.tags.onSome { tagList ->
        tagsRepository.replaceTags(
            versionId = versionId,
            candidate = version.candidate,
            distribution = version.distribution,
            platform = version.platform,
            tags = tagList
        ).onLeft { error ->
            // Handle tag processing failure
        }
    }

    // Audit (existing behaviour — version_data now includes tags)
    auditRepository.recordAudit(username, AuditOperation.CREATE, version)

    call.respond(HttpStatusCode.NoContent)
}
```

Note: the `create` method in `VersionsRepository` currently returns `Either<String, Unit>`. It may need to be updated to return the version ID so that tags can reference it.

## Specification by Example

### POST with tags
```http
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "27.0.2",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64",
  "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
  "tags": ["latest", "27", "27.0"]
}

Response: 204 No Content
```

Subsequent GET returns:
```json
{
  "candidate": "java",
  "version": "27.0.2",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64",
  "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
  "visible": true,
  "tags": ["latest", "27", "27.0"]
}
```

### POST without tags (preserves existing)
```http
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "27.0.2",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64",
  "url": "https://cdn.example.com/java-27.0.2-updated-url.tar.gz"
}

Response: 204 No Content
```

Tags remain `["latest", "27", "27.0"]` — URL is updated but tags are untouched.

### POST with empty tags (removes all)
```http
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "27.0.2",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64",
  "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
  "tags": []
}

Response: 204 No Content
```

Tags are now `[]`.

### Mutual exclusivity
```http
# Version 27.0.1 currently has tags: ["latest"]

POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "27.0.2",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64",
  "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
  "tags": ["latest"]
}

Response: 204 No Content
```

After this POST:
- `java 27.0.2 (TEMURIN, LINUX_X64)` → `tags: ["latest"]`
- `java 27.0.1 (TEMURIN, LINUX_X64)` → `tags: []`

### Invalid tag names
```http
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "27.0.2",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64",
  "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
  "tags": ["latest", "", ".bad"]
}

Response: 400 Bad Request
{
  "error": "Validation Error",
  "failures": [
    {"field": "tags[1]", "message": "Tag must not be blank"},
    {"field": "tags[2]", "message": "Tag must start and end with an alphanumeric character"}
  ]
}
```

No version is created and no tags are set.

## Verification

- [ ] POST with `tags` field stores tags and they appear in subsequent GET responses
- [ ] POST without `tags` field leaves existing tags untouched
- [ ] POST with `tags: []` removes all tags from the version
- [ ] Declarative replacement works — new tag list fully replaces old tag list
- [ ] Mutual exclusivity enforced — tag moves from old version to new version within scope
- [ ] Other tags on the old version are preserved when only some tags move
- [ ] Tag name validation rejects blank, too-long, and invalid-character tags
- [ ] Validation errors accumulate and report all invalid tags
- [ ] Invalid tags cause 400 response — no version or tag changes made (atomic)
- [ ] Tags work correctly for candidates without distributions (NA sentinel)
- [ ] Audit record includes tags in the `version_data` JSON
- [ ] All existing POST tests pass without modification
- [ ] Response remains `204 No Content`
- [ ] OpenAPI documentation updated with `tags` field on POST request schema and tag validation error responses
- [ ] No nullable types used — follows Arrow Option pattern
- [ ] Code formatted and ktlint clean
