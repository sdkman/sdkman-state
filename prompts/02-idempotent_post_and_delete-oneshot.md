# Idempotent POST and Enhanced DELETE Endpoints

*The SDKMAN State API currently returns NO_CONTENT (204) for both successful POSTs and DELETEs, regardless of whether data was created/deleted or already existed. This implementation needs to be changed to provide proper idempotent POST behavior with CREATED (201) status and enhanced DELETE behavior with NOT_FOUND (404) status when attempting to delete non-existent entries, following REST API best practices for data mutation operations.*

## Requirements

- POST /versions should always succeed and return CREATED (201) status, even if the version already exists (idempotent behavior)
- POST /versions should overwrite any existing version entry with the same unique key (candidate, version, vendor, platform)
- DELETE /versions should return NO_CONTENT (204) when successfully deleting an existing version
- DELETE /versions should return NOT_FOUND (404) when attempting to delete a non-existent version
- All validation rules must remain intact for both endpoints
- Authentication requirements must remain unchanged
- Database operations should handle upsert behavior for POST operations

## Rules

- rules/kotlin.md
- rules/kotest.md

## Domain

Core domain entities remain unchanged

## Testing Considerations

*All existing tests must pass with updated expectations for HTTP status codes. New tests should cover the enhanced behavior scenarios.*

*Test coverage should include:*
- Idempotent POST behavior (multiple POSTs of same version should succeed with 201)
- POST overwriting existing version data with different URL/visibility/checksums
- DELETE returning 404 for non-existent versions
- DELETE returning 204 for successful deletions
- Edge cases around vendor presence/absence in unique keys
- Concurrent operations and transaction handling

## Implementation Notes

*Prefer using database-level UPSERT mechanisms (PostgreSQL's ON CONFLICT clause) for efficient idempotent POST operations. The repository layer should be enhanced to support both create/update operations and return operation metadata to the API layer for appropriate status code determination.*

*Use Exposed ORM's insertIgnore or upsert capabilities where available, or raw SQL with ON CONFLICT clauses. Maintain separation of concerns between validation, business logic, and data access layers.*

## Specification by Example

### Idempotent POST Behavior

#### Vendored

```http
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "17.0.1", 
  "platform": "LINUX_X64",
  "url": "https://example.com/java-17.0.1.tar.gz",
  "visible": true,
  "vendor": "temurin"
}

Response: 201 CREATED
```

```http
# Same POST request repeated
POST /versions  
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "17.0.1",
  "platform": "LINUX_X64", 
  "url": "https://updated-url.com/java-17.0.1.tar.gz",
  "visible": false,
  "vendor": "temurin"
}

Response: 201 CREATED (overwrites existing entry)
```

#### Not vendored (null vendor in db)

```http
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "scala",
  "version": "3.1.0", 
  "platform": "UNIVERSAL",
  "url": "https://example.com/scala-3.1.0.tar.gz",
  "visible": true
}

Response: 201 CREATED
```

```http
# Same POST request repeated
POST /versions  
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "scala",
  "version": "3.1.0", 
  "platform": "UNIVERSAL",
  "url": "https://example.com/scala-3.1.0.tar.gz",
  "visible": true
}

Response: 201 CREATED (overwrites existing entry)
```

### Enhanced DELETE Behavior
```http
DELETE /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "17.0.1",
  "vendor": "temurin",
  "platform": "LINUX_X64"
}

Response: 204 NO_CONTENT (if version exists)
Response: 404 NOT_FOUND (if version does not exist)
```

### Cucumber Scenarios
```gherkin
Feature: Idempotent POST endpoint
  Scenario: POST creates new version with 201 status
    When I POST a version for java 17.0.1 on LINUX_X64 with temurin vendor
    Then the response status should be 201 CREATED
    And the version should be stored in the database

  Scenario: POST overwrites existing version with 201 status  
    Given a version exists for java 17.0.1 on LINUX_X64 with temurin vendor
    When I POST the same version with different URL
    Then the response status should be 201 CREATED
    And the version URL should be updated in the database

Feature: Enhanced DELETE endpoint
  Scenario: DELETE removes existing version with 204 status
    Given a version exists for java 17.0.1 on LINUX_X64 with temurin vendor
    When I DELETE the version
    Then the response status should be 204 NO_CONTENT
    And the version should not exist in the database

  Scenario: DELETE non-existent version returns 404 status
    Given no version exists for java 17.0.1 on LINUX_X64 with temurin vendor
    When I DELETE the version
    Then the response status should be 404 NOT_FOUND
```

## Extra Considerations

- Database constraints may prevent duplicate entries, requiring UPSERT or INSERT ON CONFLICT handling
- Existing validation logic must be preserved for both endpoints
- HTTP status code changes will affect API consumers and should be documented
- Transaction handling for upsert operations to ensure data consistency

## Verification

- [ ] POST /versions returns 201 CREATED for new versions
- [ ] POST /versions returns 201 CREATED for existing versions (idempotent)
- [ ] POST /versions overwrites existing version data completely
- [ ] DELETE /versions returns 204 NO_CONTENT when version exists and is deleted
- [ ] DELETE /versions returns 404 NOT_FOUND when version does not exist
- [ ] All existing validation rules still apply to both endpoints
- [ ] Authentication requirements unchanged for both endpoints
- [ ] Database operations handle concurrent requests safely
- [ ] All existing tests updated to reflect new status code expectations
- [ ] New tests cover idempotent POST and enhanced DELETE scenarios
- [ ] Performance testing shows acceptable response times for upsert operations