# Optional Vendor String Validation

Modify the SDKMAN State API to support optional vendor strings in Version creation while preventing vendor concatenation in version strings. This ensures clean separation of version and vendor data at the application boundary and throughout the system persistence layer.

## Requirements

- Accept POST requests for Versions with optional vendor field (can be null/empty)
- Validate that version strings do not contain vendor suffixes (e.g., reject `17.0.1-tem`, accept `17.0.1` with vendor `tem`)
- Return 400 Bad Request with appropriate error message when version contains vendor concatenation
- Make vendor field nullable in PostgreSQL database schema
- Ensure all application layers handle optional vendor correctly
- No need to maintain backward compatibility with existing data and GET endpoints

## Rules

- rules/domain-driven-design.md
- rules/hexagonal-architecture.md
- rules/kotest.md
- rules/kotlin.md

## Domain

```kotlin
// Updated domain model with optional vendor
data class Version(
    val candidate: String,
    val version: String,
    val platform: Platform,
    val url: String,
    val visible: Boolean,
    val vendor: Option<String>,
    val md5sum: Option<String> = None,
    val sha256sum: Option<String> = None,
    val sha512sum: Option<String> = None,
)
```

## Testing Considerations

- Unit tests for version/vendor validation logic with suffix pattern (version-vendor)
- Acceptance tests for POST /versions endpoint with valid and invalid payloads
- Integration tests to ensure repository layer and database compatibility
- Edge case testing: empty strings, whitespace, special characters in vendor names

## Implementation Notes

- Implement validation at the routing/controller layer before domain object creation
- Use Arrow's `Option` type throughout the application stack
- Database migration should use ALTER TABLE to modify existing vendor column
- Use Arrow's Either type for validation results
- Follow existing error response format for 400 Bad Request responses

## Specification by Example

### Valid POST Request
```json
POST /versions
{
  "candidate": "java",
  "version": "17.0.1",
  "vendor": "tem",
  "platform": "LINUX_X64",
  "url": "https://example.com/java-17.0.1.tar.gz",
  "visible": true
}
```

### Invalid POST Request (should return 400)
```json
POST /versions
{
  "candidate": "java",
  "version": "17.0.1-tem",
  "vendor": "tem",
  "platform": "LINUX_X64",
  "url": "https://example.com/java-17.0.1.tar.gz",
  "visible": true
}
```

### Valid POST Request with NO vendor
```json
POST /versions
{
  "candidate": "maven",
  "version": "3.9.0",
  "platform": "UNIVERSAL",
  "url": "https://example.com/maven-3.9.0.zip",
  "visible": true
}
```

### Error Response Example
```json
HTTP 400 Bad Request
{
  "error": "Validation failed",
  "message": "Version '17.0.1-tem' should not contain vendor 'tem' suffix"
}
```

## Extra Considerations

- Database migration must update existing schema to handle non-null vendor data gracefully
- Database migration can clear out any existing data
- API responses need not maintain existing JSON structure (vendor field may be ommitted if not present)
- Validation should be case-sensitive for vendor suffix detection

## Verification

- [ ] POST /versions accepts requests with null/empty/missing vendor field
- [ ] POST /versions rejects versions with vendor suffix concatenation (400 response)
- [ ] Database schema updated with nullable vendor column
- [ ] Database migration runs successfully with data-wipe
- [ ] GET endpoints need not return vendor field (ommitted when not present)
- [ ] All existing functionality remains intact
- [ ] Unit tests pass for validation logic
- [ ] Integration tests pass for persistence layer
- [ ] Acceptance tests pass for persistence layer
- [ ] Application builds and runs without errors
- [ ] All quality checks pass
- [ ] Manual testing confirms correct behavior for edge cases
