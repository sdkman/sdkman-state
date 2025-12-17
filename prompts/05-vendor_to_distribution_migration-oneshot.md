# Vendor to Distribution Field Migration

The SDKMAN State API currently uses a `vendor` field to distinguish between different providers of SDK candidates (e.g., different Java distributions). However, the term "distribution" more accurately describes what this field represents - the specific packaged variant of a candidate from a vendor. For example, BellSoft (vendor) provides both "liberica" and "liberica-nik" distributions. This migration replaces the `vendor` field with a `distribution` field throughout the system, including the domain model, database schema, API contracts, and all related code.

**Context:** This is a breaking change performed before production launch. A short outage due to this change is permitted.

## Requirements

- Replace `vendor` field with `distribution` field in the `Version` and `UniqueVersion` domain models
- Update database schema to rename `vendor` column to `distribution` keeping same characteristics of column (TEXT, nullable)
- Update unique constraint from `(candidate, version, vendor, platform)` to `(candidate, version, distribution, platform)`
- Replace `vendor` query parameter with `distribution` in GET endpoints
- Replace `vendor` field with `distribution` in POST/DELETE request/response payloads
- Update OpenAPI specification to reflect the field name change (spec generated via gradle task)
- Update all repository queries to use `distribution` instead of `vendor`
- Update all tests to use `distribution` terminology
- Field remains optional (`Option<String>`) for all candidates, though primarily used by Java

## Rules

- rules/kotlin.md
- rules/kotest.md

## Domain

**Current Domain Model:**

```kotlin
@Serializable
data class Version(
    val candidate: String,
    val version: String,
    val platform: Platform,
    val url: String,
    val visible: Option<Boolean> = None,
    val vendor: Option<String> = None,  // ← TO BE REPLACED
    val md5sum: Option<String> = None,
    val sha256sum: Option<String> = None,
    val sha512sum: Option<String> = None,
)

@Serializable
data class UniqueVersion(
    val candidate: String,
    val version: String,
    val vendor: Option<String>,  // ← TO BE REPLACED
    val platform: Platform,
)
```

**New Domain Model:**

```kotlin
@Serializable
data class Version(
    val candidate: String,
    val version: String,
    val platform: Platform,
    val url: String,
    val visible: Option<Boolean> = None,
    val distribution: Option<Distribution> = None,  // ← NEW
    val md5sum: Option<String> = None,
    val sha256sum: Option<String> = None,
    val sha512sum: Option<String> = None,
)

@Serializable
data class UniqueVersion(
    val candidate: String,
    val version: String,
    val distribution: Option<Distribution>,  // ← NEW
    val platform: Platform,
)

@Serializable
enum class Distribution {
  BISHENG
  CORETTO,
  GRAALCE,
  GRAALVM,
  JETBRAINS,
  KONA,
  LIBERICA,
  LIBERICA_NIK,
  MANDREL,
  MICROSOFT,
  OPENJDK,
  ORACLE,
  SAP_MACHINE,
  SEMERU,
  TEMURIN,
  ZULU
}
```

**Distribution Examples:**

- **Java candidate with Temurin vendor:**
  - `distribution: "temurin"`

- **Java candidate with BellSoft vendor:**
  - `distribution: "liberica"` (standard JDK)
  - `distribution: "liberica-nik"` (Native Image Kit variant)

- **Non-Java candidates:**
  - `distribution: None` (field omitted or null)

## Extra Considerations

- The `vendor` column in the database will be renamed to `distribution`
- The audit table also has a `vendor` column that needs to be renamed to `distribution`
- All indexes reference `vendor` and should be implicitly updated
- The **audit table is live** and should be **updated in a non-blocking way**
- Validation logic that checks for vendor suffix patterns should validate against `Distribution` enum
- Log messages mentioning "vendor" should be updated to "distribution"
- The field remains optional for all candidates - no candidate-specific validation is required
- Only Java candidates are expected to use this field in practice, and the API should now enforce this
- The distribution should now be validated against an enumeration of values
- The distribution is modelled in the `Distribution` enum in the domain model

## Testing Considerations

All existing test specifications that reference `vendor` must be updated to use `distribution`:

- `GetVersionsApiSpec.kt` - Update tests filtering by vendor to filter by distribution
- `GetVersionApiSpec.kt` - Update tests that query specific vendor to use distribution
- `PostVersionApiSpec.kt` - Update tests that POST with vendor field to use distribution
- `IdempotentPostVersionApiSpec.kt` - Update idempotency tests using vendor
- `DeleteVersionApiSpec.kt` - Update tests deleting by vendor to use distribution
- `PostVersionVisibilitySpec.kt` - Update any vendor references in visibility tests
- `VersionsRepositorySpec.kt` - Update repository-level tests
- `VersionValidatorSpec.kt` - Update validation tests referencing vendor suffixes

Test coverage should verify:

- GET `/versions/{candidate}?distribution=temurin` returns only versions with that distribution
- GET `/versions/{candidate}/{version}?distribution=liberica` returns specific version for that distribution
- POST `/versions` with `distribution: "temurin"` creates version with correct distribution
- POST `/versions` without `distribution` field creates version with None
- DELETE `/versions` with `distribution: "liberica-nik"` deletes correct version
- Unique constraint enforces uniqueness on (candidate, version, distribution, platform)
- Idempotent POST with same distribution updates existing version
- Validation prevents version strings with distribution suffixes (e.g., "21.0.1-temurin")

## Implementation Notes

### Database Migration Strategy

Create a new Flyway migration `V7__rename_vendor_columns_to_distribution.sql`:

```sql
ALTER TABLE versions RENAME COLUMN vendor TO distribution;
ALTER TABLE audit RENAME COLUMN vendor TO distribution;
```

### Repository Layer Changes

In `VersionsRepository.kt`, update the Exposed table definition:

```kotlin
private object Versions : IntIdTable(name = "versions") {
    val candidate = varchar("candidate", length = 20)
    val version = varchar("version", length = 25)
    val distribution = varchar("distribution", length = 20).nullable()  // ← RENAMED
    val platform = varchar("platform", length = 15)
    val url = varchar("url", length = 500)
    val visible = bool("visible")
    val md5sum = varchar("md5_sum", length = 32).nullable()
    val sha256sum = varchar("sha_256_sum", length = 64).nullable()
    val sha512sum = varchar("sha_512_sum", length = 128).nullable()
    val lastUpdatedAt = timestamp("last_updated_at")
}
```

Update all queries to use `distribution` instead of `vendor`:

- `read(candidate, platform, distribution, visible)` parameter name change
- `read(candidate, version, platform, distribution)` parameter name change
- All WHERE clauses: `Versions.distribution` instead of `Versions.vendor`
- All result mapping: `distribution = it[Versions.distribution].toOption()`
- Sorting: `it.distribution.getOrNull()` instead of `it.vendor.getOrNull()`

### API Route Changes

In `Routing.kt`, update query parameter handling:

```kotlin
// Change from:
val vendor = call.request.queryParameters["vendor"].toOption()

// To:
val distribution = call.request.queryParameters["distribution"].toOption()
```

Update logging:

```kotlin
// Change from:
"vendor=${version.vendor.getOrElse { "none" }}"

// To:
"distribution=${version.distribution.getOrElse { "none" }}"
```

### Validation Layer Changes

In `VersionValidator.kt`, update error messages:

```kotlin
// Change from:
data class VendorSuffixError(val version: String, val vendor: String) : ValidationError(
    "Version '$version' should not contain vendor '$vendor' suffix"
)

// To:
data class DistributionSuffixError(val version: String, val distribution: String) : ValidationError(
    "Version '$version' should not contain distribution '$distribution' suffix"
)
```

### OpenAPI Specification Changes

The `openapi/documentation.yaml` should be regenerated with Gradle and show:

- Replace `vendor` property with `distribution` in `Version` schema
- Replace `vendor` property with `distribution` in `UniqueVersion` schema
- Update query parameter from `vendor` to `distribution` in GET endpoints
- Update all descriptions mentioning "vendor" to say "distribution"

## Specification by Example

### GET versions filtered by distribution

**Before:**
```http
GET /versions/java?platform=linuxx64&vendor=temurin
```

**After:**
```http
GET /versions/java?platform=linuxx64&distribution=temurin
```

**Response:**
```json
[
  {
    "candidate": "java",
    "version": "21.0.1",
    "platform": "LINUX_X64",
    "url": "https://example.com/java-21.0.1.tar.gz",
    "visible": true,
    "distribution": "temurin"
  }
]
```

### GET specific version with distribution

**Before:**
```http
GET /versions/java/21.0.1?platform=linuxx64&vendor=liberica
```

**After:**
```http
GET /versions/java/21.0.1?platform=linuxx64&distribution=liberica
```

**Response:**
```json
{
  "candidate": "java",
  "version": "21.0.1",
  "platform": "LINUX_X64",
  "url": "https://example.com/liberica-21.0.1.tar.gz",
  "visible": true,
  "distribution": "liberica"
}
```

### POST new version with distribution

**Before:**
```http
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "21.0.1",
  "platform": "LINUX_X64",
  "url": "https://example.com/java-21.0.1.tar.gz",
  "vendor": "temurin",
  "visible": true
}
```

**After:**
```http
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "21.0.1",
  "platform": "LINUX_X64",
  "url": "https://example.com/java-21.0.1.tar.gz",
  "distribution": "temurin",
  "visible": true
}
```

### POST version without distribution (non-Java candidate)

```http
POST /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "gradle",
  "version": "8.5",
  "platform": "UNIVERSAL",
  "url": "https://example.com/gradle-8.5.zip",
  "visible": true
}
```

**Note:** No `distribution` field needed for non-Java candidates.

### DELETE version by distribution

**Before:**
```http
DELETE /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "21.0.1",
  "vendor": "liberica-nik",
  "platform": "LINUX_X64"
}
```

**After:**
```http
DELETE /versions
Authorization: Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=
Content-Type: application/json

{
  "candidate": "java",
  "version": "21.0.1",
  "distribution": "liberica-nik",
  "platform": "LINUX_X64"
}
```

### Gherkin Scenarios

```gherkin
Feature: Distribution field replaces vendor field

  Scenario: GET versions filtered by distribution
    Given versions exist for Java with distributions "temurin", "liberica", and "liberica-nik"
    When I GET /versions/java?distribution=temurin
    Then only versions with distribution "temurin" are returned

  Scenario: POST version with distribution
    When I POST a Java version with distribution "temurin"
    Then the version is stored with distribution "temurin"

  Scenario: POST version without distribution
    When I POST a Gradle version without distribution field
    Then the version is stored with distribution None

  Scenario: DELETE version by distribution
    Given a Java version exists with distribution "liberica-nik"
    When I DELETE that version specifying distribution "liberica-nik"
    Then only that specific distribution variant is deleted

  Scenario: Unique constraint includes distribution
    Given a Java 21.0.1 version exists with distribution "temurin" on LINUX_X64
    When I POST the same candidate, version, and platform with distribution "liberica"
    Then both versions coexist (different distributions)

  Scenario: Idempotent POST updates distribution
    Given a Java 21.0.1 version exists with distribution "temurin"
    When I POST the same version again with distribution "temurin" but different URL
    Then the existing version is updated (not duplicated)
```

## Verification

- [ ] Database migrations rename `vendor` column to `distribution` in `versions` table
- [ ] Database migrations rename `vendor` column to `distribution` in `audit` table
- [ ] Domain models (`Version`, `UniqueVersion`) use `distribution` field
- [ ] Repository layer queries use `distribution` parameter and column
- [ ] API routes accept `distribution` query parameter and request body field
- [ ] Validation layer references `distribution` in error messages
- [ ] OpenAPI spec updated with `distribution` property
- [ ] All tests updated to use `distribution` terminology
- [ ] Integration tests verify distribution filtering, creation, and deletion
- [ ] No references to "vendor" remain in code, logs, or API responses
