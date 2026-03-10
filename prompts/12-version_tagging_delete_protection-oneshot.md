# Version Tagging — Version Delete Protection

Modify the existing `DELETE /versions` endpoint to prevent deletion of versions that have active tags. Tagged versions must have their tags removed or moved to another version before they can be deleted. This is a small, focused safety net that depends on the tag repository from Slice 1 (prompt 08).

See `specs/version-tagging.md` for the full feature specification.

## Requirements

- `DELETE /versions` must check if the version has any tags before proceeding with deletion
- If the version has tags, return `409 Conflict` with an error listing the active tags
- If the version has no tags, proceed with deletion as before (existing behaviour unchanged)
- The database-level `ON DELETE RESTRICT` constraint on `version_tags.version_id` acts as a safety net, but the application should check first and return a meaningful error
- All existing delete behaviour (404 for non-existent versions, 400 for validation errors, 401 for unauthenticated requests) remains unchanged

## Rules

**Before implementing, you MUST read and internalize:**
- rules/kotlin.md
- rules/domain-driven-design.md
- rules/kotest.md

**If this prompt conflicts with the rules, THE RULES WIN.** Update this prompt to align with the rules.

## Domain

No new domain types required. Uses the existing `TagsRepository.hasTagsForVersion()` and `TagsRepository.findTagNamesByVersionId()` methods defined in Slice 1.

A new error response shape is needed for the 409 Conflict:

```kotlin
@Serializable
data class TagConflictResponse(
    val error: String,
    val message: String,
    val tags: List<String>
)
```

## Extra Considerations

### Check Order
The tag check must happen after the version is found but before the delete is executed. The flow is:

1. Validate the request (existing)
2. Look up the version by unique key (existing)
3. If not found → 404 (existing)
4. **Check for tags → if tagged, return 409 with tag list (new)**
5. Delete the version (existing)
6. Audit the deletion (existing)

### Version ID Lookup
The existing delete flow uses `UniqueVersion` (candidate, version, distribution, platform) to identify the version. To check for tags, we need the version's database ID. The repository may need a method to resolve a `UniqueVersion` to a version ID, or the tag check can be done by looking up tags via the version's unique key rather than ID.

### Database Safety Net
Even if the application-level check is bypassed (e.g., race condition between check and delete), the `ON DELETE RESTRICT` foreign key constraint on `version_tags.version_id` will prevent the deletion at the database level. The application check provides a user-friendly error message; the database constraint provides the hard guarantee.

## Testing Considerations

All tests should use Kotest's ShouldSpec style with real PostgreSQL.

### Happy Path Tests

- **Delete untagged version succeeds**: create a version with no tags, delete it, verify 204
- **Delete version after tags removed**: create a tagged version, remove its tags, delete it, verify 204

### Unhappy Path Tests

- **Delete tagged version blocked**: create a version with tags `["latest", "27"]`, attempt delete, verify 409 response with both tags listed
- **Delete version with single tag blocked**: create a version with tag `["latest"]`, attempt delete, verify 409

### Regression Tests

- **All existing DELETE tests pass**: existing `DeleteVersionApiSpec` tests must continue to pass without modification — they operate on untagged versions
- **404 for non-existent version unchanged**: delete request for non-existent version still returns 404
- **400 for invalid request unchanged**: delete request with blank candidate still returns 400
- **401 for unauthenticated unchanged**: delete request without auth still returns 401

## Implementation Notes

### Order of Implementation
1. Read and internalize rules files
2. Add `TagConflictResponse` data class
3. Update the DELETE route handler to check for tags before deletion
4. Update OpenAPI documentation with the 409 response
5. Write integration tests

### Route Handler Update

```kotlin
// In the DELETE /versions handler, after version lookup succeeds
delete("/versions") {
    // ... existing validation and authentication ...
    // ... existing version lookup ...

    // Check for tags before deletion
    tagsRepository.findTagNamesByVersionId(versionId)
        .map { tagNames ->
            when {
                tagNames.isNotEmpty() -> call.respond(
                    HttpStatusCode.Conflict,
                    TagConflictResponse(
                        error = "Conflict",
                        message = "Cannot delete version with active tags. Remove or reassign the following tags first.",
                        tags = tagNames
                    )
                )
                else -> {
                    // Proceed with existing delete logic
                }
            }
        }
        .getOrElse { error ->
            // Handle database failure
        }
}
```

## Specification by Example

### Delete tagged version — blocked
```http
DELETE /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "27.0.2",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64"
}

Response: 409 Conflict
{
  "error": "Conflict",
  "message": "Cannot delete version with active tags. Remove or reassign the following tags first.",
  "tags": ["latest", "27", "27.0"]
}
```

### Delete untagged version — succeeds
```http
DELETE /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "26.0.3",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64"
}

Response: 204 No Content
```

### Delete after tags moved — succeeds
```
Given java 27.0.2 (TEMURIN, LINUX_X64) has tags ["latest", "27"]

Step 1: Move tags to new version
POST /versions {"candidate":"java", "version":"27.0.3", "distribution":"TEMURIN", "platform":"LINUX_X64", "url":"...", "tags":["latest","27"]}
→ 204 No Content (tags move from 27.0.2 to 27.0.3)

Step 2: Delete old version (now untagged)
DELETE /versions {"candidate":"java", "version":"27.0.2", "distribution":"TEMURIN", "platform":"LINUX_X64"}
→ 204 No Content
```

## Verification

- [ ] Delete of tagged version returns 409 Conflict
- [ ] 409 response includes the list of active tag names
- [ ] 409 response body matches `TagConflictResponse` shape
- [ ] Delete of untagged version returns 204 (existing behaviour preserved)
- [ ] Delete after tags removed/moved returns 204
- [ ] Database `ON DELETE RESTRICT` acts as safety net even if application check is bypassed
- [ ] All existing DELETE tests pass without modification
- [ ] 404, 400, and 401 responses unchanged for their respective scenarios
- [ ] OpenAPI documentation updated with 409 Conflict response on DELETE /versions
- [ ] No nullable types used — follows Arrow Option pattern
- [ ] Code formatted and ktlint clean
