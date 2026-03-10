# Version Tagging — Delete Tag Endpoint

Introduce the `DELETE /versions/tags` endpoint for removing a tag without moving it to another version. This completes the tag lifecycle — tags can now be created (POST /versions), resolved (GET /versions/{candidate}/tags/{tag}), and removed (DELETE /versions/tags), unblocking version deletion when needed. Depends on Slices 1–5 (prompts 08–12).

See `specs/version-tagging.md` for the full feature specification.

## Requirements

- `DELETE /versions/tags` removes a tag by its unique scope `(candidate, tag, distribution, platform)`
- Request body uses `UniqueTag` shape (consistent with `DELETE /versions` using `UniqueVersion`)
- Authentication required (same as existing DELETE /versions)
- Returns `204 No Content` on successful removal
- Returns `404 Not Found` if the tag does not exist for the given scope
- Returns `400 Bad Request` for validation failures (blank candidate/tag, invalid distribution/platform)
- Returns `401 Unauthorized` for missing or invalid authentication
- Tag removal is audited via the existing audit system

## Rules

**Before implementing, you MUST read and internalize:**
- rules/kotlin.md
- rules/domain-driven-design.md
- rules/kotest.md

**If this prompt conflicts with the rules, THE RULES WIN.** Update this prompt to align with the rules.

## Domain

The `UniqueTag` data class was introduced in Slice 1:

```kotlin
@Serializable
data class UniqueTag(
    val candidate: String,
    val tag: String,
    val distribution: Option<Distribution> = None,
    val platform: Platform
)
```

## Extra Considerations

### Consistency with DELETE /versions
This endpoint follows the same pattern as the existing `DELETE /versions`:
- Authenticated via the same mechanism
- Request body identifies what to delete
- Returns 204 on success, 404 when not found, 400 for validation errors
- Audited

### Validation Rules
The `UniqueTag` request must be validated:
- `candidate` must not be blank
- `tag` must not be blank
- `platform` must be a valid `Platform` enum value
- `distribution`, if provided, must be a valid `Distribution` enum value

Validation should follow the same accumulated error pattern used by `UniqueVersionValidator`, returning all failures in a single response.

### Audit Trail
Tag deletion should be recorded in the `vendor_audit` table. The audit entry should capture:
- The authenticated username
- Operation type: `DELETE`
- The tag details as JSON in the `version_data` field (the `UniqueTag` serialized, or the resolved version data — choose whichever is most useful for audit forensics)

## Testing Considerations

All tests should use Kotest's ShouldSpec style with real PostgreSQL.

### Happy Path Tests

- **Delete a tag**: create a version with tags, delete one tag, verify 204 and tag is gone
- **Delete tag preserves other tags**: version has `["latest", "27", "27.0"]`, delete `27.0`, verify `latest` and `27` remain
- **Delete tag preserves the version**: delete a tag, verify the version itself still exists
- **Delete tag unblocks version deletion**: create a tagged version, delete all its tags, then delete the version, verify both succeed
- **Delete tag without distribution**: delete a tag for a candidate without distributions (e.g., Gradle), verify 204

### Unhappy Path Tests

- **Tag not found**: delete a non-existent tag, get 404
- **Blank candidate**: delete with blank candidate, get 400 with validation error
- **Blank tag name**: delete with blank tag, get 400
- **Invalid distribution**: delete with invalid distribution enum value, get 400
- **Invalid platform**: delete with invalid platform enum value, get 400
- **Multiple validation failures**: delete with blank candidate and blank tag, get accumulated 400 errors
- **Unauthenticated**: delete without auth credentials, get 401
- **Malformed JSON**: send invalid JSON body, get 400

### Regression Tests

- **Existing DELETE /versions unchanged**: all existing `DeleteVersionApiSpec` tests pass — the new endpoint does not interfere with the existing one

## Implementation Notes

### Order of Implementation
1. Read and internalize rules files
2. Create `UniqueTagValidator` following the pattern of `UniqueVersionValidator`
3. Add the DELETE route in `Routing.kt`
4. Wire up audit logging for tag deletions
5. Update OpenAPI documentation with the new endpoint
6. Write integration tests

### Route Registration

```kotlin
// In Routing.kt, alongside existing authenticated routes
authenticate("auth") {
    delete("/versions/tags") {
        val principal = call.principal<UserIdPrincipal>()
        val username = principal?.name.toOption().getOrElse { "unknown" }

        val uniqueTag = call.receive<UniqueTag>()

        // Validate
        validateUniqueTag(uniqueTag)
            .onLeft { failures ->
                return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ValidationErrorResponse(error = "Validation Error", failures = failures)
                )
            }

        // Delete tag
        tagsRepository.deleteTag(uniqueTag)
            .map { deletedCount ->
                when {
                    deletedCount > 0 -> {
                        // Audit the deletion
                        auditRepository.recordAudit(username, AuditOperation.DELETE, uniqueTag)
                            .onLeft { error ->
                                logger.warn("Audit logging failed for DELETE /versions/tags: ${error.message}")
                            }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    else -> call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(error = "Not Found", message = "Tag '${uniqueTag.tag}' not found")
                    )
                }
            }
            .getOrElse { error ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(error = "Database Error", message = error.message)
                )
            }
    }
}
```

### Validator

```kotlin
// UniqueTagValidator — follows UniqueVersionValidator pattern
fun validateUniqueTag(uniqueTag: UniqueTag): Either<List<ValidationFailure>, UniqueTag> {
    val failures = buildList {
        if (uniqueTag.candidate.isBlank()) add(ValidationFailure("candidate", "Candidate must not be blank"))
        if (uniqueTag.tag.isBlank()) add(ValidationFailure("tag", "Tag must not be blank"))
        // platform and distribution validated during deserialization
    }
    return when {
        failures.isNotEmpty() -> failures.left()
        else -> uniqueTag.right()
    }
}
```

## Specification by Example

### Delete a tag — success
```http
DELETE /versions/tags
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "tag": "latest",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64"
}

Response: 204 No Content
```

### Delete a tag — without distribution
```http
DELETE /versions/tags
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "gradle",
  "tag": "latest",
  "platform": "UNIVERSAL"
}

Response: 204 No Content
```

### Delete a tag — not found
```http
DELETE /versions/tags
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "tag": "nonexistent",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64"
}

Response: 404 Not Found
{
  "error": "Not Found",
  "message": "Tag 'nonexistent' not found"
}
```

### Delete a tag — validation error
```http
DELETE /versions/tags
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "",
  "tag": "",
  "platform": "LINUX_X64"
}

Response: 400 Bad Request
{
  "error": "Validation Error",
  "failures": [
    {"field": "candidate", "message": "Candidate must not be blank"},
    {"field": "tag", "message": "Tag must not be blank"}
  ]
}
```

### Delete a tag — unauthenticated
```http
DELETE /versions/tags
Content-Type: application/json

{
  "candidate": "java",
  "tag": "latest",
  "distribution": "TEMURIN",
  "platform": "LINUX_X64"
}

Response: 401 Unauthorized
```

### Full lifecycle — delete tags then delete version
```
Given java 27.0.2 (TEMURIN, LINUX_X64) has tags ["latest", "27"]

Step 1: Delete "latest" tag
DELETE /versions/tags {"candidate":"java", "tag":"latest", "distribution":"TEMURIN", "platform":"LINUX_X64"}
→ 204 No Content

Step 2: Delete "27" tag
DELETE /versions/tags {"candidate":"java", "tag":"27", "distribution":"TEMURIN", "platform":"LINUX_X64"}
→ 204 No Content

Step 3: Delete the now-untagged version
DELETE /versions {"candidate":"java", "version":"27.0.2", "distribution":"TEMURIN", "platform":"LINUX_X64"}
→ 204 No Content
```

## Verification

- [ ] `DELETE /versions/tags` removes a tag and returns 204
- [ ] Deleting a tag preserves other tags on the same version
- [ ] Deleting a tag preserves the version itself
- [ ] Deleting all tags unblocks version deletion (integration with Slice 5)
- [ ] Works for candidates without distributions (NA sentinel)
- [ ] Returns 404 for non-existent tags
- [ ] Returns 400 for blank candidate or blank tag
- [ ] Returns 400 for invalid distribution or platform
- [ ] Validation errors accumulate and report all failures
- [ ] Returns 401 for unauthenticated requests
- [ ] Tag deletion is audited in the `vendor_audit` table
- [ ] Audit failure does not block the delete operation
- [ ] Existing `DELETE /versions` endpoint and tests unchanged
- [ ] OpenAPI documentation updated with the new endpoint, request body, and response schemas
- [ ] No nullable types used — follows Arrow Option pattern
- [ ] Code formatted and ktlint clean
