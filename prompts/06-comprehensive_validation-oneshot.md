# Comprehensive Request Validation with Error Accumulation

This feature implements comprehensive validation for the POST /versions endpoint using Arrow's Validated type to accumulate and return all validation errors in a single response, rather than failing fast on the first error encountered.

## Requirements

- Pre-deserialization validation that parses raw JSON and validates each field before constructing the Version object
- All validation errors must be accumulated and returned together in a structured error response
- Required field validation: candidate, version, platform, url must not be empty
- Candidate must match one of the allowed values: java, maven, gradle, kotlin, scala, groovy, sbt (exact match, case-sensitive)
- URL must be a well-formed HTTPS URL validated via regex
- Platform must be a valid Platform enum value (sent as enum name like LINUX_X64)
- Distribution, if present, must be a valid Distribution enum value
- Hash field validation (md5sum, sha256sum, sha512sum):
  - Fields can be completely omitted from JSON (results in None)
  - If present, must contain valid hash values (not null, not empty string)
  - MD5: 32 hexadecimal characters
  - SHA256: 64 hexadecimal characters
  - SHA512: 128 hexadecimal characters
  - Case-insensitive hex validation (accept both `abc123` and `ABC123`)
- Remove the existing distribution suffix validation (allow version strings like `1.0.0-RC1`)
- Error response structure should include all accumulated validation failures with field names and messages

## Rules

- rules/domain-driven-design.md
- rules/kotlin.md
- rules/kotest.md

## Domain

```kotlin
// Validation error types for accumulation
sealed class ValidationError {
    abstract val field: String
    abstract val message: String
}

data class EmptyFieldError(
    override val field: String
) : ValidationError() {
    override val message: String = "$field cannot be empty"
}

data class InvalidCandidateError(
    override val field: String = "candidate",
    val candidate: String,
    val allowedCandidates: List<String>
) : ValidationError() {
    override val message: String =
        "Candidate '$candidate' is not valid. Allowed values: ${allowedCandidates.joinToString(", ")}"
}

data class InvalidUrlError(
    override val field: String = "url",
    val url: String
) : ValidationError() {
    override val message: String = "URL '$url' must be a valid HTTPS URL"
}

data class InvalidPlatformError(
    override val field: String = "platform",
    val platform: String
) : ValidationError() {
    override val message: String = "Platform '$platform' is not valid"
}

data class InvalidDistributionError(
    override val field: String = "distribution",
    val distribution: String
) : ValidationError() {
    override val message: String = "Distribution '$distribution' is not valid"
}

data class InvalidHashFormatError(
    override val field: String,
    val value: String,
    val expectedLength: Int
) : ValidationError() {
    override val message: String =
        "$field must be a valid hexadecimal hash of $expectedLength characters, got: '$value'"
}

data class InvalidOptionalFieldError(
    override val field: String,
    val reason: String
) : ValidationError() {
    override val message: String = "$field is invalid: $reason"
}

data class DeserializationError(
    override val field: String,
    override val message: String
) : ValidationError()

// Error response for HTTP
@Serializable
data class ValidationFailure(
    val field: String,
    val message: String
)

@Serializable
data class ValidationErrorResponse(
    val error: String,
    val failures: List<ValidationFailure>
)
```

## Extra Considerations

- The validation should happen before Kotlin serialization attempts to deserialize into the Version data class
- Use kotlinx.serialization's JsonObject to parse raw JSON and extract fields manually
- Arrow's Validated type should be used to accumulate errors across all field validations
- The existing VersionValidator.validateVersion should be refactored or replaced to use the new accumulating approach
- Invalid enum values (Platform, Distribution) should produce clear error messages indicating which values are valid
- The URL regex should be comprehensive enough to catch common malformed URLs while accepting valid HTTPS URLs
- Hash validation should allow both uppercase and lowercase hexadecimal characters
- When optional hash fields are present but invalid (empty string, null, wrong format), they should generate validation errors
- The error response should maintain consistency with the existing ErrorResponse structure in Routing.kt
- Integration with the existing POST /versions endpoint at Routing.kt:103-121
- Introduce helpers/collaborators to implement validation logic to aid separation of concerns. **Don't implement validation logic inline in Routing!**

## Testing Considerations

All tests should be written using Kotest's ShouldSpec style to match the existing test structure.

### Happy Path Tests
- Valid Version with all required fields and no optional fields
- Valid Version with all required fields and valid optional hash fields
- Valid Version with distribution field
- Valid Version with all possible hash formats (md5sum, sha256sum, sha512sum)

### Unhappy Path Tests - Required Fields
- Missing candidate field
- Missing version field
- Missing platform field
- Missing url field
- Empty candidate field
- Empty version field
- Empty url field

### Unhappy Path Tests - Field Validation
- Invalid candidate (not in allowed list)
- Invalid URL format (not HTTPS, malformed URL)
- Invalid platform enum value
- Invalid distribution enum value
- Invalid MD5 hash format (wrong length, non-hex characters)
- Invalid SHA256 hash format
- Invalid SHA512 hash format
- Hash fields present but null
- Hash fields present but empty strings

### Unhappy Path Tests - Multiple Errors
- Multiple required fields missing simultaneously
- Multiple field validation errors (e.g., invalid candidate + invalid URL + invalid hash)
- All required fields missing
- Combination of missing fields and invalid field values

### Edge Cases
- Version string with suffix like `4.0.0-beta-1` or `1.0.0-RC1` should be accepted
- Hash values with mixed case hexadecimal characters
- Hash values with all uppercase or all lowercase
- Valid JSON but wrong field types (should produce deserialization errors)

## Implementation Notes

### Order of Implementation
1. Create new validation error domain types in a new file or extend existing validation package
2. Implement individual field validators using Arrow's Validated
3. Create a JSON-based request validator that extracts fields from JsonObject
4. Implement error accumulation logic using Validated.zipOrAccumulate or similar
5. Update POST /versions endpoint to use new validation
6. Update error response mapping to convert ValidationError list to ValidationErrorResponse
7. Write comprehensive Kotest specifications

### Technology Preferences
- Use Arrow's `Validated` type for error accumulation (Arrow 1.2.0)
- Use kotlinx.serialization's JsonObject for pre-deserialization JSON parsing
- Keep individual field validators as separate, testable functions
- Use functional composition to combine validators
- Maintain consistency with existing Arrow usage (Either, Option) in the codebase

### Code Organization
- Validation logic should live in the `io.sdkman.validation` package
- Consider creating a `VersionRequestValidator` object similar to existing `VersionValidator`
- Error types should be organized in the same package as validators
- Keep routing logic minimal - delegate validation to collaborator and/or validator objects

### Validation Patterns
- Each field validator should return `Validated<NonEmptyList<ValidationError>, T>`
- Use `zipOrAccumulate` or `zip` with `Validated` to combine multiple field validations
- Convert the final `Validated` to `Either` for integration with existing routing error handling
- Hash validation regex: `^[0-9a-fA-F]{n}$` where n is the expected length

### Constants
- Allowed candidates: `listOf("java", "maven", "gradle", "kotlin", "scala", "groovy", "sbt")`
- HTTPS URL regex pattern (example): `^https://[a-zA-Z0-9.-]+(/.*)?$` (adjust as needed for comprehensive validation)
- Hash lengths: MD5=32, SHA256=64, SHA512=128

## Specification by Example

### Example: Valid Request with Optional Fields
```json
POST /versions
{
  "candidate": "java",
  "version": "17.0.1",
  "platform": "LINUX_X64",
  "url": "https://example.com/java-17.0.1.tar.gz",
  "visible": true,
  "distribution": "TEMURIN",
  "sha256sum": "abc123def456abc123def456abc123def456abc123def456abc123def456abc1"
}
```
Response: `204 No Content`

### Example: Valid Request with Version Suffix
```json
POST /versions
{
  "candidate": "kotlin",
  "version": "1.9.0-RC1",
  "platform": "UNIVERSAL",
  "url": "https://github.com/JetBrains/kotlin/releases/download/1.9.0-RC1/kotlin.zip"
}
```
Response: `204 No Content`

### Example: Multiple Validation Errors
```json
POST /versions
{
  "candidate": "invalid-candidate",
  "version": "",
  "platform": "INVALID_PLATFORM",
  "url": "http://not-https.com/file.zip",
  "sha256sum": ""
}
```
Response: `400 Bad Request`
```json
{
  "error": "Validation failed",
  "failures": [
    {
      "field": "candidate",
      "message": "Candidate 'invalid-candidate' is not valid. Allowed values: java, maven, gradle, kotlin, scala, groovy, sbt"
    },
    {
      "field": "version",
      "message": "version cannot be empty"
    },
    {
      "field": "platform",
      "message": "Platform 'INVALID_PLATFORM' is not valid"
    },
    {
      "field": "url",
      "message": "URL 'http://not-https.com/file.zip' must be a valid HTTPS URL"
    },
    {
      "field": "sha256sum",
      "message": "sha256sum is invalid: field cannot be empty"
    }
  ]
}
```

### Example: Missing Required Fields
```json
POST /versions
{
  "visible": true
}
```
Response: `400 Bad Request`
```json
{
  "error": "Validation failed",
  "failures": [
    {
      "field": "candidate",
      "message": "candidate cannot be empty"
    },
    {
      "field": "version",
      "message": "version cannot be empty"
    },
    {
      "field": "platform",
      "message": "platform cannot be empty"
    },
    {
      "field": "url",
      "message": "url cannot be empty"
    }
  ]
}
```

### Example: Invalid Hash Formats
```json
POST /versions
{
  "candidate": "java",
  "version": "17.0.1",
  "platform": "LINUX_X64",
  "url": "https://example.com/java.tar.gz",
  "md5sum": "tooshort",
  "sha256sum": "not-a-hex-value-!!!",
  "sha512sum": "ABC123"
}
```
Response: `400 Bad Request`
```json
{
  "error": "Validation failed",
  "failures": [
    {
      "field": "md5sum",
      "message": "md5sum must be a valid hexadecimal hash of 32 characters, got: 'tooshort'"
    },
    {
      "field": "sha256sum",
      "message": "sha256sum must be a valid hexadecimal hash of 64 characters, got: 'not-a-hex-value-!!!'"
    },
    {
      "field": "sha512sum",
      "message": "sha512sum must be a valid hexadecimal hash of 128 characters, got: 'ABC123'"
    }
  ]
}
```

## Verification

- [ ] All required fields (candidate, version, platform, url) are validated for presence and non-emptiness
- [ ] Candidate field validates against static list of allowed candidates
- [ ] URL field validates as well-formed HTTPS URL via regex
- [ ] Platform field validates against Platform enum values
- [ ] Distribution field validates against Distribution enum values when present
- [ ] MD5 hash validates as 32 hexadecimal characters (case-insensitive)
- [ ] SHA256 hash validates as 64 hexadecimal characters (case-insensitive)
- [ ] SHA512 hash validates as 128 hexadecimal characters (case-insensitive)
- [ ] Optional hash fields can be completely omitted from request (results in None)
- [ ] Optional hash fields cannot be null or empty strings if present
- [ ] Version strings with suffixes like `-tem` or `-RC1` are accepted
- [ ] Multiple validation errors are accumulated and returned together
- [ ] Validation error response follows the specified JSON structure
- [ ] POST /versions endpoint integrates with new validation logic
- [ ] Existing VersionValidator distribution suffix validation is removed
- [ ] All tests pass using Kotest framework
- [ ] Tests cover all happy paths, unhappy paths, and edge cases
- [ ] Error messages are clear and indicate what values are valid
- [ ] Code follows existing Kotlin and Arrow patterns in the codebase
