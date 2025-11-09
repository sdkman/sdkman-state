# Visible Flag Default Handling in POST /versions

*The SDKMAN State API currently has a bug where the `visible` boolean field in POST /versions requests is not properly handling the default value when omitted from the JSON payload. When `visible` is set to `false` explicitly in the payload, it is incorrectly being set to `true` in the database regardless of whether the version already existed or not. The correct behavior should be: if `visible` is omitted from the payload, it should default to `true`; if `visible` is explicitly provided (either `true` or `false`), that value should be used.*

## Requirements

- When `visible` field is **omitted** from the JSON payload, it should default to `true`
- When `visible` field is **explicitly set to `true`** in the JSON payload, it should be stored as `true`
- When `visible` field is **explicitly set to `false`** in the JSON payload, it should be stored as `false`
- This behavior should apply both when creating new versions and when updating existing versions
- All other validation rules, authentication, and functionality must remain intact

## Rules

- rules/kotlin.md
- rules/kotest.md

## Domain

The `Version` data class in `src/main/kotlin/io/sdkman/domain/Domain.kt` currently defines `visible` as a non-nullable `Boolean`:

```kotlin
@Serializable
data class Version(
    val candidate: String,
    val version: String,
    val platform: Platform,
    val url: String,
    val visible: Boolean,
    val vendor: Option<String> = None,
    val md5sum: Option<String> = None,
    val sha256sum: Option<String> = None,
    val sha512sum: Option<String> = None,
)
```

## Root Cause Analysis

The issue likely stems from Kotlin's `Boolean` serialization behavior. When a JSON field is omitted, Kotlin serialization may use the default value for the primitive type (`false` for Boolean), rather than allowing a custom default value. This needs to be addressed by using Arrow's `Option<Boolean>` type to distinguish between:
- `None` (field omitted) → should default to `true`
- `Some(true)` (explicitly set to true) → use `true`
- `Some(false)` (explicitly set to false) → use `false`

This approach is consistent with the existing codebase pattern where optional fields like `vendor`, `md5sum`, `sha256sum`, and `sha512sum` already use `Option<String>`.

## Testing Considerations

*A new dedicated test specification file must be created specifically for testing the visible field behavior. Do NOT add these tests to existing test specs.*

**Create a new file**: `src/test/kotlin/io/sdkman/PostVersionVisibilitySpec.kt`

*Test coverage in this new spec should include:*
- POST with `visible: Some(true)` explicitly → version should have `visible=true`
- POST with `visible: Some(false)` explicitly → version should have `visible=false`
- POST with `visible: None` (field omitted entirely) → version should default to `visible=true`
- Update existing version with `visible: Some(false)` explicitly → should update to `visible=false`
- Update existing version with `visible: None` (field omitted) → should default to `visible=true`
- Update existing version from `visible=false` to `visible=true` using explicit `Some(true)`

## Specification by Example

### POST with visible=true (explicit)

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

Expected: Version stored with visible=true
```

### POST with visible=false (explicit)

```http
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "17.0.2",
  "platform": "LINUX_X64",
  "url": "https://example.com/java-17.0.2.tar.gz",
  "visible": false,
  "vendor": "temurin"
}

Expected: Version stored with visible=false
```

### POST with visible omitted (default)

```http
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "scala",
  "version": "3.1.0",
  "platform": "UNIVERSAL",
  "url": "https://example.com/scala-3.1.0.tar.gz"
}

Expected: Version stored with visible=true (default)
```

### Update existing version with visible=false

```http
# First POST creates with visible=true
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "kotlin",
  "version": "1.9.0",
  "platform": "UNIVERSAL",
  "url": "https://example.com/kotlin-1.9.0.zip",
  "visible": true
}

# Second POST updates with visible=false
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "kotlin",
  "version": "1.9.0",
  "platform": "UNIVERSAL",
  "url": "https://example.com/kotlin-1.9.0.zip",
  "visible": false
}

Expected: Version updated with visible=false (not ignored)
```

### Cucumber Scenarios

```gherkin
Feature: Visible flag default handling in POST /versions

  Scenario: POST new version with explicit visible=true
    When I POST a version with visible explicitly set to true
    Then the version should be stored with visible=true

  Scenario: POST new version with explicit visible=false
    When I POST a version with visible explicitly set to false
    Then the version should be stored with visible=false

  Scenario: POST new version with visible field omitted
    When I POST a version without the visible field
    Then the version should be stored with visible=true as default

  Scenario: Update existing version setting visible to false
    Given a version exists with visible=true
    When I POST the same version with visible explicitly set to false
    Then the version should be updated to visible=false

  Scenario: Update existing version with visible omitted
    Given a version exists with visible=false
    When I POST the same version without the visible field
    Then the version should be updated to visible=true as default
```

## Implementation Notes

*Use Arrow's `Option<Boolean>` type to handle the visible field, following the existing pattern used for optional fields in the codebase:*

```kotlin
@Serializable
data class Version(
    val candidate: String,
    val version: String,
    val platform: Platform,
    val url: String,
    val visible: Option<Boolean> = None,  // Use Option to detect omission
    val vendor: Option<String> = None,
    val md5sum: Option<String> = None,
    val sha256sum: Option<String> = None,
    val sha512sum: Option<String> = None,
)
```

Then in the business logic (repository layer), apply the default using Arrow's functional operators:

```kotlin
val effectiveVisible = version.visible.getOrElse { true }
```

*This approach is consistent with the codebase's preference for Arrow types over nullable types. The `Option<Boolean>` clearly communicates that the field is optional and allows functional composition using Arrow's rich API (`.map`, `.fold`, `.getOrElse`, etc.).*

*Note: Since the database schema uses a non-nullable `visible` column, the conversion from `Option<Boolean>` to `Boolean` should happen in the repository layer when writing to the database.*

## Files Likely Affected

- `src/main/kotlin/io/sdkman/domain/Domain.kt` - Update Version data class to use `Option<Boolean>` for visible field
- `src/main/kotlin/io/sdkman/repos/VersionsRepository.kt` - Handle `Option<Boolean>` and apply default value `true` when `None`
- **`src/test/kotlin/io/sdkman/PostVersionVisibilitySpec.kt`** - **NEW FILE**: Create dedicated test spec for visible field behavior
- `src/test/kotlin/io/sdkman/PostVersionApiSpec.kt` - May need minor updates if existing tests are affected by domain model changes
- `src/test/kotlin/io/sdkman/IdempotentPostVersionApiSpec.kt` - May need minor updates if existing tests are affected by domain model changes
- Test support utilities may need updates to handle `Option<Boolean>` for the visible field

## Verification

- [ ] New test spec file `PostVersionVisibilitySpec.kt` has been created
- [ ] POST /versions with `visible: Some(true)` stores version with visible=true
- [ ] POST /versions with `visible: Some(false)` stores version with visible=false
- [ ] POST /versions with `visible: None` (omitted) stores version with visible=true (default)
- [ ] Updating existing version with `visible: Some(false)` correctly sets visible=false
- [ ] Updating existing version with `visible: None` defaults to visible=true
- [ ] Updating existing version from visible=false to visible=true using `Some(true)` works correctly
- [ ] All existing tests continue to pass (after updating for domain model changes)
- [ ] New dedicated test spec covers all visibility scenarios comprehensively
- [ ] Domain model uses `Option<Boolean>` for visible field
- [ ] Repository layer correctly applies `getOrElse { true }` default
