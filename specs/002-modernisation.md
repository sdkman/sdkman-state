# SDKMAN State API Modernisation Specification

This specification describes the target state for modernising sdkman-state to follow hexagonal architecture patterns, using sdkman-broker-2 as the reference model.

## Scope

**In Scope:**
- Structural refactoring to adopt hexagonal architecture
- Migration from real PostgreSQL to Testcontainers (postgres:16) in tests
- Normalising timestamp types to `kotlin.time.Instant` (Kotlin stdlib)
- Adopting sealed class pattern for `DatabaseFailure`
- Stripping `@Serializable` annotations from domain models
- Separating JSON parsing (adapter) from validation logic (application)
- Package rename from `io.sdkman` to `io.sdkman.state`
- Eradicating nullable types (`?`) in favour of Arrow's `Option<A>` per `rules/kotlin.md`

**Out of Scope:**
- Adding new API endpoints
- Changing existing functionality
- Changing database schema
- Upgrading Kotlin/Ktor/Arrow versions
- Performance optimizations
- Adding new features

**All existing API behaviour must be preserved.**

**Implementation Note:** The spec originally referenced `kotlinx.datetime.Instant`, but Kotlin 2.3.10 includes `kotlin.time.Instant` in the stdlib, making the external dependency unnecessary. The `kotlinx-datetime:0.7.1-0.6.x-compat` library's `toKotlinInstant()` conversion is `internal`, so direct construction via `Instant.fromEpochSeconds()` is used at the persistence boundary instead.

---

## 1. Package Structure

### Current State

Flat structure mixing concerns:

```
src/main/kotlin/io/sdkman/
├── Application.kt
├── config/
│   └── ApplicationConfig.kt
├── domain/
│   └── Domain.kt                    # All domain types in one file
├── plugins/                          # Ktor plugins mixed with business logic
│   ├── Authentication.kt
│   ├── Databases.kt
│   ├── HTTP.kt
│   ├── Migration.kt
│   ├── Routing.kt                   # 377 lines mixing HTTP, validation, orchestration
│   └── Serialization.kt
├── repos/                            # Implementations without interfaces
│   ├── AuditRepositoryImpl.kt
│   ├── HealthRepositoryImpl.kt
│   ├── TagsRepositoryImpl.kt
│   └── VersionsRepository.kt
└── validation/
    ├── CandidateLoader.kt
    ├── UniqueTagValidator.kt
    ├── UniqueVersionValidator.kt
    ├── ValidationErrors.kt
    └── VersionRequestValidator.kt
```

**Problems:**
- No separation between domain, application, and infrastructure layers
- Business logic embedded in HTTP route handlers (377 lines in Routing.kt)
- Repository implementations without port interfaces (`VersionsRepository` has no interface)
- All domain models in a single file with `@Serializable` annotations
- Mixed blocking (`transaction {}`) and suspend (`newSuspendedTransaction`) patterns
- Duplicate table definitions across repositories and test support

### Target State

Hexagonal architecture with clear layer separation:

```
src/main/kotlin/io/sdkman/state/
├── Application.kt                                    # Entry point with fun Application.module()
├── config/
│   ├── AppConfig.kt                                  # Interface + DefaultAppConfig implementation
│   ├── ConfigExtensions.kt                           # Arrow-based config helpers
│   ├── CandidateLoader.kt                            # Loads allowed candidates from classpath
│   ├── Authentication.kt                             # Ktor basic auth plugin
│   ├── Databases.kt                                  # Database connection setup
│   └── Migration.kt                                  # Flyway migration runner
├── domain/
│   ├── model/
│   │   ├── Audit.kt                                  # Auditable sealed interface, AuditOperation enum
│   │   ├── Distribution.kt                           # Distribution enum (no @Serializable)
│   │   ├── Platform.kt                               # Platform enum (no @Serializable)
│   │   ├── Version.kt                                # Version, UniqueVersion data classes (no @Serializable)
│   │   ├── VersionTag.kt                             # VersionTag (kotlin.time.Instant), UniqueTag
│   │   └── HealthCheckSuccess.kt                     # data object HealthCheckSuccess
│   ├── error/
│   │   ├── DatabaseFailure.kt                        # DatabaseFailure sealed class with variants
│   │   └── DomainError.kt                            # Unified sealed interface + FieldError type
│   ├── repository/                                   # Port interfaces
│   │   ├── AuditRepository.kt
│   │   ├── HealthRepository.kt
│   │   ├── TagRepository.kt
│   │   └── VersionRepository.kt
│   └── service/                                      # Domain service interfaces
│       ├── TagService.kt
│       └── VersionService.kt
├── application/
│   ├── service/                                      # Business logic orchestration
│   │   ├── TagServiceImpl.kt
│   │   └── VersionServiceImpl.kt
│   └── validation/                                   # Domain validation (no JSON parsing)
│       ├── UniqueTagValidator.kt
│       ├── UniqueVersionValidator.kt
│       ├── VersionRequestValidator.kt
│       └── ValidationErrors.kt                       # Validation error types
├── adapter/
│   ├── primary/
│   │   └── rest/                                     # HTTP adapters only
│   │       ├── HealthRoutes.kt
│   │       ├── TagRoutes.kt
│   │       ├── VersionRoutes.kt
│   │       ├── Routing.kt                            # 22-line composition function wiring routes
│   │       ├── RequestExtensions.kt                  # Shared HTTP helpers (authenticatedUsername, respondDomainError)
│   │       ├── HTTP.kt                               # Ktor compression + caching headers + Swagger
│   │       ├── Serialization.kt                      # Ktor content negotiation (explicitNulls = false)
│   │       └── dto/                                  # Request/Response DTOs (@Serializable)
│   │           ├── VersionRequest.kt                 # Unvalidated version create/update request
│   │           ├── VersionDto.kt                     # Version response DTO with toDto()/toDomain()
│   │           ├── UniqueVersionDto.kt               # Delete request/response DTO (serves both roles)
│   │           ├── UniqueTagDto.kt                   # Tag delete request/response DTO (serves both roles)
│   │           ├── ErrorResponse.kt                  # HTTP error response DTO
│   │           ├── TagConflictResponse.kt            # Tag conflict response DTO
│   │           ├── HealthCheckResponse.kt            # Health check response DTO
│   │           └── ValidationErrorResponse.kt        # Validation error response DTO + ValidationFailure
│   └── secondary/
│       └── persistence/                              # Database adapters
│           ├── PostgresConnectivity.kt               # dbQuery helper, NA_SENTINEL, toKotlinTimeInstant()
│           ├── PostgresAuditRepository.kt            # Contains internal AuditTable
│           ├── PostgresHealthRepository.kt
│           ├── PostgresTagRepository.kt              # Contains internal VersionTagsTable
│           └── PostgresVersionRepository.kt          # Contains internal VersionsTable
```

**Design Decision — No Separate Request DTOs for Delete:**
`UniqueVersionDto` and `UniqueTagDto` serve as both request and response DTOs. Creating separate `UniqueVersionRequest`/`UniqueTagRequest` types would be pure ceremony since the fields are identical.

---

## 2. Domain Layer

### 2.1 Domain Model Files

**Current:** All types in single `Domain.kt` file (159 lines), including `AuditRecord` and `HealthStatus` which are not needed in this service. Domain models have `@Serializable` annotations, violating separation of concerns.

**Target:** Split into separate files under `domain/model/`. Domain models must have **no `@Serializable` annotations** — serialization is handled at the adapter boundary via DTOs.

| File | Contents |
|------|----------|
| `Version.kt` | `Version` data class, `UniqueVersion` data class (no @Serializable) |
| `VersionTag.kt` | `VersionTag` data class (timestamps use `kotlin.time.Instant`, pure domain model), `UniqueTag` data class |
| `Platform.kt` | `Platform` enum with `platformId` property and `findByPlatformId` companion method |
| `Distribution.kt` | `Distribution` enum (no @Serializable) |
| `Audit.kt` | `Auditable` sealed interface (Version and UniqueTag implement it), `AuditOperation` enum |
| `HealthCheckSuccess.kt` | `data object HealthCheckSuccess` following broker pattern |

**Removed from main source:**
- `HealthStatus` enum - replaced by `Either<DatabaseFailure, HealthCheckSuccess>` pattern

**Moved to test sources:**
- `AuditRecord` → `VendorAuditRecord` in `src/test/kotlin/io/sdkman/state/support/Postgres.kt`
  - This type is only used for test assertions (reading back audit data from `vendor_audit` table)
  - Main source writes `Version`/`UniqueTag` directly as JSON via `AuditRepository`
  - Rename distinguishes it from broker's audit concept

**Key Changes:**
- `VersionTag.createdAt` and `VersionTag.lastUpdatedAt` change from `java.time.Instant` to `kotlin.time.Instant`
- `VersionTag` remains a pure domain model with no @Serializable (internal use only)

### 2.2 Domain Error Types

**Current:**
- `DatabaseFailure` is a simple data class extending Throwable in `Domain.kt`
- `DeleteError` and `DeleteTagError` sealed interfaces are private in `Routing.kt` (lines 51-83)
- Validation errors in `validation/ValidationErrors.kt`
- Four different error patterns across the codebase

**Target:** Create error types in `domain/error/`:

| File | Contents |
|------|----------|
| `DatabaseFailure.kt` | `DatabaseFailure` sealed class with `ConnectionFailure` and `QueryExecutionFailure` variants |
| `DomainError.kt` | `FieldError` data class + unified `sealed interface DomainError` consolidating all domain error variants |

**DomainError.kt structure:**
```kotlin
data class FieldError(
    val field: String,
    val message: String,
)

sealed interface DomainError {
    // Version delete errors
    data class VersionNotFound(val candidate: String, val version: String) : DomainError
    data class TagConflict(val tags: List<String>) : DomainError

    // Tag delete errors
    data class TagNotFound(val tagName: String) : DomainError

    // Validation errors (wrapping domain-level field errors)
    data class ValidationFailed(val message: String) : DomainError
    data class ValidationFailures(val failures: List<FieldError>) : DomainError

    // Database errors (wrapping DatabaseFailure)
    data class DatabaseError(val failure: DatabaseFailure) : DomainError
}
```

**Design Decision — `FieldError` vs `ValidationFailure`:**
`DomainError.ValidationFailures` uses `List<FieldError>` (domain type) rather than `List<ValidationFailure>` (DTO type) to avoid a layering violation where the domain would import from the adapter layer. Conversion from `FieldError` to `ValidationFailure` DTO happens at the adapter boundary in `respondDomainError`.

**Design Decision — `sealed interface` vs `sealed class`:**
`DomainError` is a `sealed interface` (not `sealed class`) because variants don't share common state — only a type hierarchy. This is idiomatic Kotlin for marker-style hierarchies.

### 2.3 Repository Port Interfaces

**Current:** Repository interfaces mixed into `Domain.kt` or missing entirely. `VersionsRepository` is a concrete class with no interface.

**Target:** Create `domain/repository/` with port interfaces:

**VersionRepository.kt**
```kotlin
interface VersionRepository {
    suspend fun findByCandidate(
        candidate: String,
        platform: Option<Platform>,
        distribution: Option<Distribution>,
        visible: Option<Boolean>
    ): Either<DatabaseFailure, List<Version>>

    suspend fun findUnique(
        candidate: String,
        version: String,
        platform: Platform,
        distribution: Option<Distribution>
    ): Either<DatabaseFailure, Option<Version>>

    suspend fun createOrUpdate(version: Version): Either<DatabaseFailure, Int>

    suspend fun delete(uniqueVersion: UniqueVersion): Either<DatabaseFailure, Int>

    suspend fun findVersionId(uniqueVersion: UniqueVersion): Either<DatabaseFailure, Option<Int>>

    suspend fun findVersionIdByTag(
        candidate: String,
        tag: String,
        distribution: Option<Distribution>,
        platform: Platform
    ): Either<DatabaseFailure, Option<Int>>
}
```

Note:
- `createOrUpdate` returns `Either<DatabaseFailure, Int>` (version ID) for consistency with current behavior
- `findVersionId` exposed for service layer coordination
- `findVersionIdByTag` lives here (it looks up a version, belongs with version operations)

**TagRepository.kt**
```kotlin
interface TagRepository {
    suspend fun findTagsByVersionId(versionId: Int): Either<DatabaseFailure, List<VersionTag>>

    suspend fun findTagNamesByVersionId(versionId: Int): Either<DatabaseFailure, List<String>>

    suspend fun findTagNamesByVersionIds(versionIds: List<Int>): Either<DatabaseFailure, Map<Int, List<String>>>

    suspend fun replaceTags(
        versionId: Int,
        candidate: String,
        distribution: Option<Distribution>,
        platform: Platform,
        tags: List<String>
    ): Either<DatabaseFailure, Unit>

    suspend fun deleteTag(uniqueTag: UniqueTag): Either<DatabaseFailure, Int>

    suspend fun hasTagsForVersion(versionId: Int): Either<DatabaseFailure, Boolean>
}
```

**Design Decision — `replaceTags` in Repository vs Service:**
The original spec proposed granular `create`/`deleteByVersionIdAndScope`/`deleteByTagAndScope` methods with orchestration in `TagService`. The implementation keeps `replaceTags` as a single repository method because the delete-existing + enforce-mutual-exclusivity + insert-new sequence must execute within a single database transaction for consistency. Splitting it across service-level calls would require distributed transaction coordination. `deleteTag` is a separate method because tag deletion is an independent operation with its own audit semantics.

**AuditRepository.kt**
```kotlin
interface AuditRepository {
    suspend fun recordAudit(
        username: String,
        operation: AuditOperation,
        data: Auditable
    ): Either<DatabaseFailure, Unit>
}
```

**HealthRepository.kt**
```kotlin
interface HealthRepository {
    suspend fun checkDatabaseConnection(): Either<DatabaseFailure, HealthCheckSuccess>
}
```

Note: Returns `HealthCheckSuccess` data object (following broker pattern) instead of `Unit`.

### 2.4 Domain Service Interfaces

**Current:** No service interfaces exist. Business logic is embedded in `Routing.kt`.

**Target:** Create `domain/service/` with interfaces defining business operations:

**VersionService.kt**
```kotlin
interface VersionService {
    suspend fun findByCandidate(
        candidate: String,
        platform: Option<Platform>,
        distribution: Option<Distribution>,
        visible: Option<Boolean>
    ): Either<DomainError, List<Version>>

    suspend fun findUnique(
        candidate: String,
        version: String,
        platform: Platform,
        distribution: Option<Distribution>
    ): Either<DomainError, Option<Version>>

    suspend fun createOrUpdate(
        username: String,
        version: Version
    ): Either<DomainError, Unit>

    suspend fun delete(
        username: String,
        uniqueVersion: UniqueVersion
    ): Either<DomainError, Unit>
}
```

**TagService.kt**
```kotlin
interface TagService {
    suspend fun replaceTags(
        versionId: Int,
        candidate: String,
        distribution: Option<Distribution>,
        platform: Platform,
        tags: List<String>
    ): Either<DomainError, Unit>

    suspend fun deleteTag(
        username: String,
        uniqueTag: UniqueTag
    ): Either<DomainError, Unit>

    suspend fun findTagNamesByVersionId(
        versionId: Int
    ): Either<DatabaseFailure, List<String>>
}
```

**Design Decision — `findTagNamesByVersionId` on `TagService`:**
This method was added to `TagService` (not in the original spec draft) so that `VersionServiceImpl` can check for tag conflicts during delete without needing a direct `TagRepository` dependency. This keeps the dependency graph clean: `VersionServiceImpl(VersionRepository, TagService, AuditRepository)`.

---

## 3. Application Layer

### 3.1 Application Services

**Current:** Business logic lives in `Routing.kt` (377 lines), mixing:
- HTTP parameter extraction (`call.parameters`, `queryParameters`)
- JSON deserialization (`call.receive<T>()`, `call.receiveText()`)
- Validation (`VersionRequestValidator.validateRequest()`)
- Repository coordination (version lookup, tag checking, delete)
- Audit recording (`logAudit()` helper)
- Tag processing (`processTags()` helper)
- Response mapping (`respondDeleteError()`, `respondDeleteTagError()`)

**Target:** Create `application/service/` with implementations that:

- Orchestrate domain operations
- Coordinate between repositories (e.g. version ID lookup for tag operations)
- Handle validation (receiving already-parsed DTOs, not raw JSON)
- Record audit events
- Contain **no HTTP or database concerns**

**VersionServiceImpl.kt** - implements `VersionService`

Constructor: `VersionServiceImpl(versionRepository: VersionRepository, tagService: TagService, auditRepository: AuditRepository)`

Key responsibilities:
- Delegate reads to `VersionRepository`
- Validate incoming requests using validators
- Check for tag conflicts before deletion (call `tagService.findTagNamesByVersionId`)
- Record audit events on create/delete
- Orchestrate tag replacement via `TagService` after version create/update

**TagServiceImpl.kt** - implements `TagService`

Constructor: `TagServiceImpl(tagRepository: TagRepository, auditRepository: AuditRepository)`

Key responsibilities:
- Delegate `replaceTags` to `TagRepository` (transactional tag replacement)
- Delegate `findTagNamesByVersionId` to `TagRepository`
- Record audit events on tag deletion
- Coordinate version ID lookup via `VersionRepository` (for deleteTag)

### 3.2 Application Validation

**Current:** `VersionRequestValidator` does both JSON parsing AND validation in 273 lines.

**Target:** Move validators to `application/validation/`:
- `VersionRequestValidator.kt` - receives pre-parsed DTO (`VersionRequest`), validates domain rules
- `UniqueVersionValidator.kt` - validates UniqueVersion domain rules
- `UniqueTagValidator.kt` - validates UniqueTag domain rules
- `ValidationErrors.kt` - validation error types (sealed `ValidationError` hierarchy)

**Validation flow:**
1. REST adapter receives JSON, parses to DTO (e.g. `VersionRequest`)
2. REST adapter calls service with DTO
3. Service calls validator to convert DTO → Domain model (or validation errors)
4. Service proceeds with validated domain model

---

## 4. Adapter Layer

### 4.1 Primary Adapters (REST)

**Current:** Single `Routing.kt` (377 lines) handling all endpoints with embedded business logic.

**Target:** Create `adapter/primary/rest/` with focused route files:

| File | Endpoints |
|------|-----------|
| `VersionRoutes.kt` | `GET /versions/{candidate}`, `GET /versions/{candidate}/{version}`, `POST /versions`, `DELETE /versions` |
| `TagRoutes.kt` | `DELETE /versions/tags` |
| `HealthRoutes.kt` | `GET /meta/health` |
| `Routing.kt` | 22-line composition function wiring `healthRoutes`, `versionReadRoutes`, `versionWriteRoutes`, `tagRoutes` |

**Route responsibilities (thin adapters):**
- Extract HTTP parameters
- Deserialize request bodies to DTOs (JSON parsing happens here)
- Delegate to application services
- Map `Either` results to HTTP responses using `fold()`
- **No business logic**

**Design Decision — Version Routes Auth Split:**
Version routes are split into `versionReadRoutes()` (GET, unauthenticated) and `versionWriteRoutes()` (POST/DELETE, inside `authenticate("auth-basic")` block) because a single `versionRoutes()` function can't serve both authenticated and unauthenticated contexts.

**RequestExtensions.kt** - shared HTTP helper functions:
- `ApplicationCall.authenticatedUsername(): String`
- `ApplicationRequest.visibleQueryParam(): Option<Boolean>`
- `String.toDistribution(): Either<DomainError.ValidationFailed, Option<Distribution>>`
- `ApplicationCall.respondDomainError(error: DomainError)` — maps all `DomainError` variants to HTTP status codes, converting `FieldError` to `ValidationFailure` DTO at the boundary

**Ktor plugins in adapter:**
- `HTTP.kt` - HTTP compression, caching headers, Swagger UI at `/swagger`
- `Serialization.kt` - JSON content negotiation with `explicitNulls = false`

**Request/Response DTOs** in `dto/` sub-package (all `@Serializable`):
- `VersionRequest.kt` - unvalidated version create/update request (all `Option` fields defaulting to `None`)
- `VersionDto.kt` - version response DTO with `toDto()`/`toDomain()` mappers
- `UniqueVersionDto.kt` - version delete request/response DTO (serves both roles)
- `UniqueTagDto.kt` - tag delete request/response DTO (serves both roles)
- `ErrorResponse.kt` - generic error response
- `TagConflictResponse.kt` - tag conflict error with tag list
- `HealthCheckResponse.kt` - health check response (no HealthStatus enum, just message)
- `ValidationErrorResponse.kt` - validation error with failure list + `ValidationFailure` type

### 4.2 Secondary Adapters (Persistence)

**Current:** Repository implementations in `repos/` package with duplicated table definitions.

**Target:** Create `adapter/secondary/persistence/` with:

**Repository Implementations (each containing its table definition as `internal object`):**
- `PostgresVersionRepository.kt` - implements `VersionRepository`, contains `internal object VersionsTable`
- `PostgresTagRepository.kt` - implements `TagRepository`, contains `internal object VersionTagsTable`
- `PostgresAuditRepository.kt` - implements `AuditRepository`, contains `internal object AuditTable`
- `PostgresHealthRepository.kt` - implements `HealthRepository`

**PostgresConnectivity.kt** - shared infrastructure:
- `dbQuery` helper wrapping `newSuspendedTransaction(Dispatchers.IO)`
- `NA_SENTINEL = "NA"` constant for absent distribution in non-nullable DB columns
- `java.time.Instant.toKotlinTimeInstant()` extension for persistence boundary conversion

**Table visibility:** Tables are `internal object` (not private) so tests in the same module can import them. `VersionTagsTable` is referenced by both `PostgresTagRepository` (primary owner) and `PostgresVersionRepository` (for batch tag fetching).

---

## 5. Configuration

### Current State

Basic `ApplicationConfig` data class with no interface. Manual `.getString()` and `.toInt()` conversions.

### Target State

Interface-based configuration with Arrow extensions:

**AppConfig.kt**
```kotlin
interface AppConfig {
    val databaseHost: String
    val databasePort: Int
    val databaseName: String
    val databaseUsername: Option<String>
    val databasePassword: Option<String>
    val authUsername: String
    val authPassword: String
    val cacheMaxAge: Int
}

class DefaultAppConfig(config: ApplicationConfig) : AppConfig { ... }
```

**ConfigExtensions.kt**
Arrow-based helper functions for configuration loading (e.g., `getStringOrDefault`, `getIntOrDefault`, `getOptionString`). Includes `AppConfig.jdbcUrl` extension property.

**CandidateLoader.kt** - moved to `config/` (loads allowed candidates from `/candidates.txt` at startup using `Option.fromNullable` for resource loading)

**Ktor plugins kept in config/:**
- `Authentication.kt` - Ktor basic auth plugin
- `Databases.kt` - Database connection setup
- `Migration.kt` - Flyway migration runner

**Dependency Injection:** Manual constructor injection in `Application.kt` (no DI framework).

---

## 6. Repository Async Consistency

### Current State

Mixed blocking and suspend patterns in repositories:
- `VersionsRepository.create()` uses blocking `transaction { }` (line 126)
- `VersionsRepository.delete()` uses blocking `transaction { }` (line 188)
- `VersionsRepository.find*()` uses `suspend` with `newSuspendedTransaction` (line 42)
- All `TagsRepositoryImpl` methods use consistent `suspend` with `newSuspendedTransaction`

### Target State

All repository methods use consistent `suspend` functions via a shared `dbQuery` helper that wraps `newSuspendedTransaction(Dispatchers.IO)`. No bare `transaction {}` calls anywhere in the codebase.

Service layer methods are suspend where they call repositories, non-suspend for simple operations.

---

## 7. Table Definition Consolidation

### Current State

`VersionTags` table defined three times with different schemas:
- `VersionsRepository.kt` (lines 37-40) - minimal `Table("version_tags")` with only `versionId` and `tag`
- `TagsRepositoryImpl.kt` (lines 31-43) - full `IntIdTable("version_tags")` with all columns
- Test `Postgres.kt` - full `IntIdTable("version_tags")` with all columns

This duplication risks inconsistency.

### Target State

Each table defined **once**, co-located with its repository implementation as `internal object`:
- `VersionsTable` in `PostgresVersionRepository.kt`
- `VersionTagsTable` in `PostgresTagRepository.kt`
- `AuditTable` in `PostgresAuditRepository.kt`

Test support code imports table definitions from main source rather than re-declaring them.

For batch tag fetching: `TagRepository.findTagNamesByVersionIds(List<Int>)` method added, called by `VersionService` when enriching versions with tags.

---

## 8. Testing Structure

### Current State

Single layer of tests (mostly API-level integration tests) using a real PostgreSQL instance (hardcoded `localhost:5432`):

```
src/test/kotlin/io/sdkman/
├── DeleteTagApiSpec.kt
├── DeleteTaggedVersionApiSpec.kt
├── DeleteVersionApiSpec.kt
├── GetVersionApiSpec.kt
├── GetVersionsApiSpec.kt
├── GetVersionTagsApiSpec.kt
├── HealthCheckApiSpec.kt
├── HealthRepositorySpec.kt
├── IdempotentPostVersionApiSpec.kt
├── PostVersionApiSpec.kt
├── PostVersionTagsApiSpec.kt
├── PostVersionVisibilitySpec.kt
├── TagsRepositorySpec.kt
├── UniqueVersionValidatorSpec.kt
├── VendorAuditSpec.kt
├── VersionRequestValidatorSpec.kt
├── VersionsRepositorySpec.kt
└── support/
    ├── Application.kt
    ├── Json.kt
    └── Postgres.kt
```

**Problems:**
- No separation between acceptance, integration, and unit tests
- No test tagging for selective execution
- Missing test infrastructure (listeners, matchers)
- No Testcontainers — relies on real PostgreSQL instance
- Test support re-declares table definitions from main source (duplication)

### Target State

Three-layer testing structure with Testcontainers (postgres:16):

```
src/test/kotlin/io/sdkman/state/
├── acceptance/                                       # E2E API tests (12 focused specs)
│   ├── GetVersionsAcceptanceSpec.kt
│   ├── GetVersionAcceptanceSpec.kt
│   ├── GetVersionTagsAcceptanceSpec.kt
│   ├── PostVersionAcceptanceSpec.kt
│   ├── PostVersionTagsAcceptanceSpec.kt
│   ├── PostVersionVisibilityAcceptanceSpec.kt
│   ├── IdempotentPostVersionAcceptanceSpec.kt
│   ├── DeleteVersionAcceptanceSpec.kt
│   ├── DeleteTagAcceptanceSpec.kt
│   ├── DeleteTaggedVersionAcceptanceSpec.kt
│   ├── HealthCheckAcceptanceSpec.kt
│   └── VendorAuditAcceptanceSpec.kt
├── adapter/
│   └── secondary/
│       └── persistence/                              # Repository integration tests
│           ├── PostgresVersionRepositoryIntegrationSpec.kt
│           ├── PostgresTagRepositoryIntegrationSpec.kt
│           ├── PostgresAuditRepositoryIntegrationSpec.kt
│           └── PostgresHealthRepositoryIntegrationSpec.kt
├── application/
│   ├── service/                                      # Service unit tests (MockK)
│   │   ├── VersionServiceUnitSpec.kt
│   │   └── TagServiceUnitSpec.kt
│   └── validation/                                   # Validator unit tests
│       ├── VersionRequestValidatorSpec.kt
│       ├── UniqueVersionValidatorSpec.kt
│       └── UniqueTagValidatorSpec.kt
└── support/
    ├── PostgresTestContainer.kt                      # Singleton Testcontainer (postgres:16, withReuse)
    ├── Application.kt                                # Test app wiring with MapApplicationConfig
    ├── Postgres.kt                                   # DB helpers, VendorAuditRecord, withCleanDatabase
    ├── Json.kt                                       # Serialisation helpers (toJson, toJsonString, etc.)
    ├── EitherMatchers.kt                             # shouldBeRight(), shouldBeLeft() Arrow matchers
    └── OptionMatchers.kt                             # shouldBeSome(), shouldBeNone() Arrow matchers
```

**Design Decision — 12 Focused Acceptance Specs:**
Acceptance specs are kept as 12 separate focused files rather than consolidating into 3 mega-files. Each spec tests a cohesive feature area (GET versions, POST versions, DELETE tags, etc.) and consolidation would create files with 70+ tests that are harder to navigate and maintain.

**Design Decision — `PostgresTestContainer` vs `PostgresTestListener`/`KotestConfig`/`TestDependencyInjection`:**
The original spec proposed separate `PostgresTestListener.kt`, `KotestConfig.kt`, and `TestDependencyInjection.kt` files. The implementation uses a simpler `PostgresTestContainer` singleton + `withCleanDatabase` approach which is functionally equivalent with less ceremony. The `withTestApplication` helper in `Application.kt` handles test DI wiring.

**Layer Responsibilities:**

| Layer | Purpose | Tools |
|-------|---------|-------|
| `acceptance/` | E2E API tests | Full app, Testcontainers (postgres:16) |
| `adapter/.../persistence/` | Repository integration tests | Testcontainers (postgres:16) |
| `application/service/` | Service unit tests | MockK |
| `application/validation/` | Validator unit tests | None (pure functions) |

**Test Tagging:**
- Slow tests tagged with `@Tags("acceptance")` or `@Tags("integration")`
- Fast unit tests untagged (always run)

**Table imports:** Test support imports `internal object` table definitions from main source (no re-declaration).

---

## 9. Timestamp Normalisation

### Current State

Two different Instant types in use:
- `AuditRecord.timestamp` uses `kotlinx.datetime.Instant` (Domain.kt line 13, 97)
- `VersionTag.createdAt/lastUpdatedAt` use `java.time.Instant` (Domain.kt lines 123-124)
- Repositories use `java.time.Instant` throughout
- Test code converts between types (Postgres.kt lines 209-213)

### Target State

Domain timestamps use `kotlin.time.Instant` (Kotlin 2.3.10 stdlib):
- `VersionTag.createdAt` and `VersionTag.lastUpdatedAt` use `kotlin.time.Instant` with `kotlin.time.Clock.System.now()` defaults
- Repository implementations convert via `java.time.Instant.toKotlinTimeInstant()` extension at the persistence boundary
- Persistence adapters use `java.time.Instant` internally (required by Exposed's `timestamp()` column type)
- No timestamp type conversions needed in test code

**Why `kotlin.time.Instant` instead of `kotlinx.datetime.Instant`:**
Kotlin 2.3.10 includes `kotlin.time.Instant` in the standard library, making the `kotlinx-datetime` dependency unnecessary for timestamp representation. The `kotlinx-datetime:0.7.1-0.6.x-compat` library's `toKotlinInstant()` conversion function is `internal`, so a manual `toKotlinTimeInstant()` extension using `Instant.fromEpochSeconds()` handles the conversion at the persistence boundary.

---

## 10. Nullable Type Eradication

Per `rules/kotlin.md` RULE-001: NEVER use nullable types. Convert to `Option<A>` at boundaries.

### Target State

- All main source code uses `Option<A>` for optional values
- All test assertions use Arrow matchers (`shouldBeRight`, `shouldBeSome`) instead of `getOrNull()!!`
- Exception messages wrapped with `Option.fromNullable()` at catch boundaries
- JSON parsing uses `Option` chains with `.map` and `.getOrElse` instead of nullable safe calls
- Custom detekt rule `NoNullableTypes` (from `com.github.marc0der:detekt-rules:1.0.0`) enforces no explicit nullable types in source

### Acceptable Boundary Nullables

The following are **acceptable** as they exist at the persistence boundary where Exposed ORM requires nullable columns:

- `varchar().nullable()` in table definitions
- `getOrNull()` when writing `Option` values to nullable DB columns (converting Arrow → JDBC)
- `toOption()` when reading nullable DB columns (converting JDBC → Arrow)

### Target Patterns

Per `rules/kotlin.md` RULE-107, prefer `.map` and `.getOrElse` over `fold` when working with `Option<A>`:

```kotlin
// ✅ Do this
option.map { process(it) }.getOrElse { defaultValue }

// ✅ Or for side effects
option.onSome { doSomething(it) }

// ❌ Don't do this
option?.let { process(it) } ?: defaultValue
```

---

## 11. Implementation Notes

### Dependency Injection

Manual constructor injection in `Application.kt`:
```kotlin
fun Application.module() {
    val appConfig = DefaultAppConfig(environment.config)

    // Repositories
    val versionRepository = PostgresVersionRepository()
    val tagRepository = PostgresTagRepository()
    val auditRepository = PostgresAuditRepository()
    val healthRepository = PostgresHealthRepository()

    // Services (tagService first — VersionServiceImpl depends on it)
    val tagService = TagServiceImpl(tagRepository, auditRepository)
    val versionService = VersionServiceImpl(
        versionRepository, tagService, auditRepository
    )

    // Configure adapters
    configureRouting(versionService, tagService, healthRepository)
}
```

### Migration Strategy

1. Create new package structure alongside existing code
2. Migrate domain models first (remove @Serializable, normalise timestamps)
3. Create repository interfaces and implementations
4. Create service layer with business logic extracted from Routing.kt
5. Create REST adapters with DTOs
6. Migrate tests to new structure with Testcontainers
7. Remove old code once all tests pass

---

## Reference

This specification follows the patterns documented in:
- `rules/hexagonal-architecture.md`
- `rules/domain-driven-design.md`
- `rules/kotlin.md`
- `rules/kotest.md`

Reference implementation: `sdkman-broker-2`
