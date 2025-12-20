# Comprehensive Request Validation with Error Accumulation

This feature implements comprehensive validation for the POST /versions endpoint using Arrow's Either type to accumulate and return all validation errors in a single response, rather than failing fast on the first error encountered.

## Rules

**Before implementing, you MUST read and internalize:**
- rules/kotlin.md
- rules/domain-driven-design.md
- rules/kotest.md

**If this prompt conflicts with the rules, THE RULES WIN.** Update this prompt to align with the rules.

## Requirements

- Pre-deserialization validation that parses raw JSON and validates each field before constructing the Version object
- **Convert JSON null/missing values to `Option<A>` immediately at the JSON boundary** (RULE-001 from kotlin.md)
- All validation errors must be accumulated and returned together in a structured error response
- Required field validation: candidate, version, platform, url must not be empty
- Candidate must match one of the allowed values: java, maven, gradle, kotlin, scala, groovy, sbt (exact match, case-sensitive)
- URL must be a well-formed HTTPS URL validated via regex
- Platform must be a valid Platform enum value (sent as enum name like LINUX_X64)
- Distribution, if present, must be a valid Distribution enum value (convert to `Option<Distribution>`)
- Hash field validation (md5sum, sha256sum, sha512sum):
  - Fields can be completely omitted from JSON (results in `None`)
  - If present in JSON, convert to `Option<String>` immediately
  - If `Some(hash)`, validate that hash is not empty and matches format
  - MD5: 32 hexadecimal characters
  - SHA256: 64 hexadecimal characters
  - SHA512: 128 hexadecimal characters
  - Case-insensitive hex validation (accept both `abc123` and `ABC123`)
- Remove the existing distribution suffix validation (allow version strings like `1.0.0-RC1`)
- Error response structure should include all accumulated validation failures with field names and messages

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

### Null Safety at JSON Boundary (CRITICAL!)
- **NEVER use nullable types** (`String?`, `Distribution?`) in validator return types
- Convert JSON extraction to Option immediately:
  ```kotlin
  // ❌ WRONG - Don't use nullable types
  val candidate: String? = jsonObject["candidate"]?.jsonPrimitive?.content

  // ✅ CORRECT - Convert to Option at boundary
  val candidate: Option<String> = jsonObject["candidate"]?.jsonPrimitive?.content.toOption()
  ```
- Field validators should accept `Option<String>` not `String?`
- Return types should be `Either<NonEmptyList<ValidationError>, T>` not `Either<NonEmptyList<ValidationError>, T?>`

### Arrow Library Current Best Practices
- **Check Arrow documentation for current patterns** - some APIs may be deprecated
- If `Validated` functionality is being merged into `Either`, use `Either`-based accumulation instead
- Use `Either.zipOrAccumulate` or similar for error accumulation (verify current Arrow API)
- Follow Arrow's recommended migration paths for any deprecated features

### Other Considerations
- The validation should happen before Kotlin serialization attempts to deserialize into the Version data class
- Use kotlinx.serialization's JsonObject to parse raw JSON and extract fields manually
- Invalid enum values (Platform, Distribution) should produce clear error messages indicating which values are valid
- The URL regex should be comprehensive enough to catch common malformed URLs while accepting valid HTTPS URLs
- Hash validation should allow both uppercase and lowercase hexadecimal characters
- The error response should maintain consistency with the existing ErrorResponse structure in Routing.kt
- Integration with the existing POST /versions endpoint
- Introduce helpers/collaborators to implement validation logic to aid separation of concerns. **Don't implement validation logic inline in Routing!**

## Testing Considerations

All tests should be written using Kotest's ShouldSpec style to match the existing test structure.

### Happy Path Tests
- Valid Version with all required fields and no optional fields
- Valid Version with all required fields and valid optional hash fields (as `Some(hash)`)
- Valid Version with distribution field (as `Some(distribution)`)
- Valid Version with all possible hash formats (md5sum, sha256sum, sha512sum)

### Unhappy Path Tests - Required Fields
- Missing candidate field (JSON doesn't contain key)
- Missing version field
- Missing platform field
- Missing url field
- Empty candidate field (JSON contains `"candidate": ""`)
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
- Hash fields present but empty strings (`"md5sum": ""`)

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
- JSON null values should convert to `None`, not cause null pointer exceptions

## Implementation Notes

### Order of Implementation
1. **Read and internalize rules/kotlin.md** - Understand Option usage and null safety requirements
2. Create new validation error domain types in a new file or extend existing validation package
3. Implement individual field validators that accept `Option<String>` and return `Either<NonEmptyList<ValidationError>, T>`
4. Create a JSON-based request validator that extracts fields from JsonObject and converts to Option immediately
5. Implement error accumulation logic using Either-based patterns (check Arrow docs for current API)
6. Update POST /versions endpoint to use new validation
7. Update error response mapping to convert ValidationError list to ValidationErrorResponse
8. Write comprehensive Kotest specifications

### Technology Preferences
- **Use Arrow's current error accumulation patterns** (verify whether to use Either.zipOrAccumulate or newer API)
- Use kotlinx.serialization's JsonObject for pre-deserialization JSON parsing
- **NEVER use nullable types** - always use `Option<A>` for optional values (RULE-001)
- Keep individual field validators as separate, testable functions
- Use functional composition to combine validators
- Maintain consistency with existing Arrow usage (Either, Option) in the codebase

### Code Organization
- Validation logic should live in the `io.sdkman.validation` package
- Consider creating a `VersionRequestValidator` object similar to existing `VersionValidator`
- Error types should be organized in the same package as validators
- Keep routing logic minimal - delegate validation to collaborator and/or validator objects

### Validation Patterns

**CRITICAL: These examples may need updating based on current Arrow API**

```kotlin
// Convert JSON to Option at boundary
val candidateOpt: Option<String> = jsonObject["candidate"]?.jsonPrimitive?.content.toOption()
val md5sumOpt: Option<String> = jsonObject["md5sum"]?.jsonPrimitive?.content.toOption()

// Field validator signature - accepts Option, returns Either
fun validateCandidate(candidate: Option<String>): Either<NonEmptyList<ValidationError>, String> =
    candidate.fold(
        { EmptyFieldError("candidate").nel().left() },
        { value ->
            when {
                value.isBlank() -> EmptyFieldError("candidate").nel().left()
                value !in ALLOWED_CANDIDATES -> InvalidCandidateError(
                    candidate = value,
                    allowedCandidates = ALLOWED_CANDIDATES
                ).nel().left()
                else -> value.right()
            }
        }
    )

// Optional field validator - None is valid, Some(empty) is not
fun validateHash(
    field: String,
    hashOpt: Option<String>,
    expectedLength: Int
): Either<NonEmptyList<ValidationError>, Option<String>> =
    hashOpt.fold(
        { None.right() },  // Missing field is OK
        { hash ->
            when {
                hash.isBlank() -> InvalidOptionalFieldError(field, "field cannot be empty").nel().left()
                hash.length != expectedLength -> InvalidHashFormatError(field, hash, expectedLength).nel().left()
                !HEX_PATTERN.matches(hash) -> InvalidHashFormatError(field, hash, expectedLength).nel().left()
                else -> hash.some().right()
            }
        }
    )

// Error accumulation - verify current Arrow API!
// This example may need updating if zipOrAccumulate is deprecated
Either.zipOrAccumulate(
    validateCandidate(candidateOpt),
    validateVersion(versionOpt),
    validatePlatform(platformOpt),
    validateUrl(urlOpt)
) { candidate, version, platform, url ->
    Version(candidate, version, platform, url, /* ... */)
}
```

### Constants
- Allowed candidates: `listOf("java", "maven", "gradle", "kotlin", "scala", "groovy", "sbt")`
- HTTPS URL regex pattern (example): `^https://[a-zA-Z0-9.-]+(/.*)?$` (adjust as needed for comprehensive validation)
- Hash lengths: MD5=32, SHA256=64, SHA512=128
- Hash validation regex: `^[0-9a-fA-F]{n}$` where n is the expected length

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

Internal representation: `distribution = Some(Distribution.TEMURIN)`, `sha256sum = Some("abc123...")`

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

Internal representation: `distribution = None`, `md5sum = None`, etc.

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

Internal: `candidateOpt = None`, triggers validation error

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

Internal: `md5sumOpt = Some("tooshort")`, triggers validation of the Some value

## Verification Checklist

### Architectural Compliance
- [ ] **NO nullable types used anywhere** - all nullable JSON values converted to `Option<A>` at boundary
- [ ] All field validators accept `Option<T>` parameters, not `T?`
- [ ] All return types use `Either` and `Option`, never nullable types
- [ ] Arrow library deprecation warnings addressed (checked current documentation)
- [ ] Code follows RULE-001 through RULE-006 from rules/kotlin.md

### Functional Requirements
- [ ] All required fields (candidate, version, platform, url) are validated for presence and non-emptiness
- [ ] Candidate field validates against static list of allowed candidates
- [ ] URL field validates as well-formed HTTPS URL via regex
- [ ] Platform field validates against Platform enum values
- [ ] Distribution field validates against Distribution enum values when present
- [ ] MD5 hash validates as 32 hexadecimal characters (case-insensitive)
- [ ] SHA256 hash validates as 64 hexadecimal characters (case-insensitive)
- [ ] SHA512 hash validates as 128 hexadecimal characters (case-insensitive)
- [ ] Optional hash fields can be completely omitted from request (results in None)
- [ ] Optional hash fields when present as Some(value) validate the value properly
- [ ] Version strings with suffixes like `-beta-1` or `-RC1` are accepted
- [ ] Multiple validation errors are accumulated and returned together
- [ ] Validation error response follows the specified JSON structure

### Integration & Quality
- [ ] POST /versions endpoint integrates with new validation logic
- [ ] Existing VersionValidator distribution suffix validation is removed
- [ ] All tests pass using Kotest framework
- [ ] Tests cover all happy paths, unhappy paths, and edge cases
- [ ] Error messages are clear and indicate what values are valid
- [ ] Code follows existing Kotlin and Arrow patterns in the codebase
- [ ] No deprecation warnings from Arrow library

