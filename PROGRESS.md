# Progress Log

**Append-only log of iterative work. Never edit or remove previous entries.**

## Entry Template

Each entry must follow this structure exactly:

---

### [YYYY-MM-DD HH:mm] — [IMPLEMENTATION_PLAN.md item reference]

**Summary:** One-line description of what was done.

**Files changed:**
- `path/to/File.kt` — brief reason

**Test outcome:** PASS | FAIL (with detail if failed)

**Learnings:**
- _Patterns:_ e.g. "this codebase uses X for Y"
- _Gotchas:_ e.g. "don't forget to update Z when changing W"
- _Context:_ e.g. "the audit table relies on index X for this query pattern"

---

### [2026-03-15 12:00] — Phase 0.3: Corrective Actions (Batch 1)

**Summary:** Fixed six code defects: extracted shared dbQuery/JDBC URL/NA_SENTINEL, normalised UniqueTagValidator, replaced nullable types in HealthCheckResponse, and eliminated getOrNull()!! in tests.

**Files changed:**
- `src/main/kotlin/io/sdkman/repos/PostgresConnectivity.kt` — new shared dbQuery helper and NA_SENTINEL constant
- `src/main/kotlin/io/sdkman/config/ApplicationConfig.kt` — added jdbcUrl to DatabaseConfig
- `src/main/kotlin/io/sdkman/plugins/Databases.kt` — use config.jdbcUrl
- `src/main/kotlin/io/sdkman/plugins/Migration.kt` — use config.jdbcUrl
- `src/main/kotlin/io/sdkman/repos/VersionsRepository.kt` — removed private dbQuery
- `src/main/kotlin/io/sdkman/repos/TagsRepositoryImpl.kt` — removed private dbQuery and NA_SENTINEL
- `src/main/kotlin/io/sdkman/repos/AuditRepositoryImpl.kt` — removed private dbQuery
- `src/main/kotlin/io/sdkman/repos/HealthRepositoryImpl.kt` — removed private dbQuery
- `src/main/kotlin/io/sdkman/validation/UniqueTagValidator.kt` — normalised to NonEmptyList<ValidationError>
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — Option<String> for HealthCheckResponse.message, Option-based nullable replacements
- `src/test/kotlin/io/sdkman/HealthCheckApiSpec.kt` — removed JUnit import, consistent Option assertions
- `src/test/kotlin/io/sdkman/repos/TagsRepositorySpec.kt` — replaced getOrNull()!! with getOrElse
- `src/test/kotlin/io/sdkman/support/Postgres.kt` — use shared NA_SENTINEL and DatabaseConfig.jdbcUrl

**Test outcome:** PASS — all tests green

**Learnings:**
- _Patterns:_ File-level `@file:UseSerializers(OptionSerializer::class)` needed for Option serialization in any file with @Serializable data classes using Option fields
- _Gotchas:_ DatabaseFailure.message is non-nullable (overrides Throwable.message with String not String?), but Throwable.message from `Either.catch` is nullable at the Java boundary — handle both cases
- _Context:_ Test support Postgres.kt duplicates table definitions from main source — these will be consolidated in Phase 3 when table objects become `internal`

---

### [2026-03-15 13:00] — Phase 0.2 + Phase 1.4: Test Deps and VersionRepository Port

**Summary:** Added Testcontainers/MockK/Kotest extensions test deps; created VersionRepository port interface; normalised create return type to Either<DatabaseFailure, Int>.

**Files changed:**
- `build.gradle.kts` — added Testcontainers BOM, PostgreSQL, MockK, Kotest extensions
- `src/main/kotlin/io/sdkman/domain/Domain.kt` — added VersionRepository interface with 5 methods; changed create signature to Either<DatabaseFailure, Int>
- `src/main/kotlin/io/sdkman/repos/VersionsRepository.kt` — implements VersionRepository; create wrapped in Either.catch; private helpers return Int directly
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — depends on VersionRepository interface (not concrete class); uses error.message for DatabaseFailure

**Test outcome:** PASS — all tests green

**Learnings:**
- _Patterns:_ All 4 repository interfaces now follow the same error pattern: Either<DatabaseFailure, T>. The routing layer is the boundary where DatabaseFailure.message is extracted for HTTP responses.
- _Gotchas:_ When wrapping existing code in Either.catch, the private helper methods should return plain values (not Either) since the Either wrapping happens at the public method level. This avoids double-wrapping.
- _Context:_ VersionRepository interface created in Domain.kt alongside other port interfaces for consistency. Will move to its own file during package restructuring in later phases.

---

### [2026-03-15 14:00] — Phase 0.3: Consolidate duplicate VersionTags table definition

**Summary:** Consolidated three separate `VersionTags` table definitions (2-column in `VersionsRepository`, 7-column in `TagsRepositoryImpl`, 7-column in test `Postgres.kt`) into a single `internal object VersionTags` in `PostgresConnectivity.kt`.

**Files changed:**
- `src/main/kotlin/io/sdkman/repos/PostgresConnectivity.kt` — added single authoritative `VersionTags` table definition as `internal object`
- `src/main/kotlin/io/sdkman/repos/VersionsRepository.kt` — removed private 2-column `VersionTags`, now uses shared definition
- `src/main/kotlin/io/sdkman/repos/TagsRepositoryImpl.kt` — removed private 7-column `VersionTags`, now uses shared definition
- `src/test/kotlin/io/sdkman/support/Postgres.kt` — removed private 7-column `VersionTags`, imports from main source

**Test outcome:** PASS — all 131 tests green

**Learnings:**
- _Patterns:_ `internal` visibility in Kotlin allows same-module access (both main and test source sets), making it ideal for table objects that need sharing without public exposure
- _Gotchas:_ The 2-column definition in `VersionsRepository` was a subset of the full schema — using the full 7-column `IntIdTable` definition works identically since Exposed only queries the columns you explicitly select
- _Context:_ In Phase 3 the table will move to `PostgresTagRepository.kt` as its co-located `internal object VersionTagsTable` — for now `PostgresConnectivity.kt` is the shared infrastructure home

---

### [2026-03-15 15:00] — Phase 0.3: Promote private sealed error types to domain-level DomainError

**Summary:** Consolidated private `DeleteError` and `DeleteTagError` sealed interfaces from `Routing.kt` into a unified `DomainError` sealed interface in `Domain.kt`, shared by both DELETE handlers via a single `respondDomainError` mapper.

**Files changed:**
- `src/main/kotlin/io/sdkman/domain/Domain.kt` — added `DomainError` sealed interface with 6 variants: `VersionNotFound`, `TagConflict`, `TagNotFound`, `ValidationFailed`, `ValidationFailures`, `DatabaseError`
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — removed private `DeleteError` and `DeleteTagError` sealed interfaces; removed `respondDeleteError` and `respondDeleteTagError`; added unified `respondDomainError`; updated both DELETE handlers to use `DomainError` variants

**Test outcome:** PASS — all 131 tests green

**Learnings:**
- _Patterns:_ `DomainError.DatabaseError` wraps the full `DatabaseFailure` object rather than just extracting the message, preserving the original error for logging/debugging
- _Gotchas:_ The DELETE /versions handler used label "Validation failed" while DELETE /versions/tags used "Bad Request" — unified to "Bad Request" since no tests assert the exact label for delete version errors
- _Context:_ `DomainError.ValidationFailures` depends on `ValidationFailure` from the validation package — this cross-package dependency will be resolved when `ValidationFailure` moves to the domain layer in Phase 1.2

---

### [2026-03-15 16:00] — Phase 1.5 + 4.1: Service Layer Extraction

**Summary:** Created `VersionService` and `TagService` domain interfaces with `VersionServiceImpl` and `TagServiceImpl` implementations. Extracted business logic from `Routing.kt` into services — routing now handles only HTTP concerns (deserialization, validation, response mapping) while services handle orchestration (create/delete, audit, tag processing).

**Files changed:**
- `src/main/kotlin/io/sdkman/domain/Domain.kt` — added `VersionService` interface (findAll, findOne, createOrUpdate, delete) and `TagService` interface (deleteTag)
- `src/main/kotlin/io/sdkman/service/VersionServiceImpl.kt` — new file; implements VersionService with repo delegation for reads and orchestration logic for writes (create → audit → tags, delete → check tags → audit → delete)
- `src/main/kotlin/io/sdkman/service/TagServiceImpl.kt` — new file; implements TagService with tag deletion + audit recording
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — simplified to thin adapter; removed `logAudit`, `processTags` private functions; changed `configureRouting` signature from 4 repos to (VersionService, TagService, HealthRepository)
- `src/main/kotlin/io/sdkman/Application.kt` — updated DI wiring to construct services from repos
- `src/test/kotlin/io/sdkman/support/Application.kt` — updated test wiring to match new signature
- `src/test/kotlin/io/sdkman/HealthCheckApiSpec.kt` — updated inline app wiring to match new signature

**Test outcome:** PASS — all 131 tests green

**Learnings:**
- _Patterns:_ Service methods return `Either<DomainError, Unit>` which the routing layer can `.bind()` inside its own `either` block, allowing clean composition of adapter-level errors (deserialization) with service-level errors (not found, conflict)
- _Gotchas:_ HealthCheckApiSpec has its own inline app configuration (doesn't use `withTestApplication`) — must be updated separately when `configureRouting` signature changes
- _Context:_ `TagsRepositoryImpl.replaceTags` still contains the DB-level tag mutual exclusivity logic (delete-from-other-versions-then-insert) — this is appropriate for the persistence layer as it's transactional DB work, not business orchestration

---

### [2026-03-15 17:00] — Phase 1.2 + 1.3 + 1.4: DatabaseFailure Sealed Class and HealthCheckSuccess

**Summary:** Enhanced `DatabaseFailure` from a simple data class to a sealed class with `ConnectionFailure` and `QueryExecutionFailure` variants; added `data object HealthCheckSuccess`; updated `HealthRepository` return type to `Either<DatabaseFailure, HealthCheckSuccess>`.

**Files changed:**
- `src/main/kotlin/io/sdkman/domain/Domain.kt` — DatabaseFailure becomes sealed class with two variants; added HealthCheckSuccess data object; updated HealthRepository return type
- `src/main/kotlin/io/sdkman/repos/HealthRepositoryImpl.kt` — returns HealthCheckSuccess; uses DatabaseFailure.ConnectionFailure
- `src/main/kotlin/io/sdkman/repos/AuditRepositoryImpl.kt` — uses DatabaseFailure.QueryExecutionFailure
- `src/main/kotlin/io/sdkman/repos/VersionsRepository.kt` — uses DatabaseFailure.QueryExecutionFailure
- `src/main/kotlin/io/sdkman/repos/TagsRepositoryImpl.kt` — uses DatabaseFailure.QueryExecutionFailure (5 occurrences)

**Test outcome:** PASS — all tests green, ktlint clean

**Learnings:**
- _Patterns:_ DatabaseFailure variants allow distinguishing connection-level failures (health check) from query-level failures (CRUD operations) — this enables more precise error handling in consumers
- _Gotchas:_ Sealed class constructors pass (message, cause) to Throwable superclass, preserving stack traces and message accessibility through the parent type
- _Context:_ HealthStatus enum retained temporarily for DTO serialization compatibility; will be removed when HealthCheckResponse DTO moves to adapter layer (Phase 5.1)

---

### [2026-03-15 18:00] — Phase 6.1 + 6.2: Nullable Type Eradication (Remaining Items)

**Summary:** Fixed remaining nullable type violations in main and test sources: replaced `toNonEmptyListOrNull()?.left()` with Arrow-idiomatic `toNonEmptyListOrNone().fold()` in UniqueTagValidator; replaced `getOrNull()` sort key in VersionsRepository with `map/getOrElse`; replaced all `body["key"]?.jsonPrimitive?.content` patterns with `body.getValue("key").jsonPrimitive.content` in DeleteTagApiSpec and DeleteTaggedVersionApiSpec.

**Files changed:**
- `src/main/kotlin/io/sdkman/validation/UniqueTagValidator.kt` — replaced `toNonEmptyListOrNull()?.left() ?: right()` with `toNonEmptyListOrNone().fold()`
- `src/main/kotlin/io/sdkman/repos/VersionsRepository.kt` — replaced `getOrNull()` in sort comparator with `.map { it.name }.getOrElse { "" }`
- `src/test/kotlin/io/sdkman/DeleteTaggedVersionApiSpec.kt` — replaced 4 `?.jsonPrimitive?.content` patterns with `getValue().jsonPrimitive.content`
- `src/test/kotlin/io/sdkman/DeleteTagApiSpec.kt` — replaced 10 `?.jsonPrimitive?.content` and `?.jsonArray` patterns with `getValue()` equivalents

**Test outcome:** PASS — all tests green, ktlint clean

**Learnings:**
- _Patterns:_ `JsonObject.getValue(key)` returns non-nullable `JsonElement` and throws `NoSuchElementException` if absent — appropriate for test assertions where missing keys should fail the test
- _Gotchas:_ ktlint enforces newlines before `.` in chained calls inside lambdas — use `ktlintFormat` after edits
- _Context:_ Remaining nullable violations at persistence boundary (`getOrNull()` in Exposed ORM column writes) are acceptable per the architecture — Exposed requires actual `null` for nullable columns

---

### [2026-03-15 19:00] — Phase 1.2 + 1.3 + 1.4 + 1.5: Split Domain.kt into focused files

**Summary:** Split the monolithic `Domain.kt` (254 lines, 19+ types) into 14 focused files in the `io.sdkman.domain` package. All types remain in the same package so zero import changes were needed across the codebase. Domain.kt was deleted.

**Files changed:**
- `src/main/kotlin/io/sdkman/domain/Domain.kt` — **deleted** (replaced by 14 focused files below)
- `src/main/kotlin/io/sdkman/domain/Platform.kt` — Platform enum
- `src/main/kotlin/io/sdkman/domain/Distribution.kt` — Distribution enum
- `src/main/kotlin/io/sdkman/domain/Version.kt` — Version, UniqueVersion
- `src/main/kotlin/io/sdkman/domain/VersionTag.kt` — VersionTag, UniqueTag
- `src/main/kotlin/io/sdkman/domain/Audit.kt` — Auditable, AuditOperation, AuditRecord
- `src/main/kotlin/io/sdkman/domain/HealthCheck.kt` — HealthCheckSuccess, HealthStatus
- `src/main/kotlin/io/sdkman/domain/DatabaseFailure.kt` — DatabaseFailure sealed class
- `src/main/kotlin/io/sdkman/domain/DomainError.kt` — DomainError sealed interface
- `src/main/kotlin/io/sdkman/domain/VersionRepository.kt` — VersionRepository interface
- `src/main/kotlin/io/sdkman/domain/TagsRepository.kt` — TagsRepository interface
- `src/main/kotlin/io/sdkman/domain/AuditRepository.kt` — AuditRepository interface
- `src/main/kotlin/io/sdkman/domain/HealthRepository.kt` — HealthRepository interface
- `src/main/kotlin/io/sdkman/domain/VersionService.kt` — VersionService interface
- `src/main/kotlin/io/sdkman/domain/TagService.kt` — TagService interface

**Test outcome:** PASS — all tests green, ktlint clean

**Learnings:**
- _Patterns:_ Kotlin allows splitting a package across multiple files with no import changes — types in the same package are automatically visible to each other. This makes incremental refactoring safe.
- _Gotchas:_ ktlint enforces that single-class files are named after the class — `DatabaseError.kt` was rejected, had to rename to `DatabaseFailure.kt`
- _Context:_ `@file:UseSerializers(OptionSerializer::class)` must be added to each file that has `@Serializable` data classes with `Option` fields (Version.kt, VersionTag.kt). Files without Option fields don't need it.

---

### [2026-03-15 20:00] — Phase 1.3: Domain Model Cleanup (AuditRecord, HealthStatus, VersionTag timestamps)

**Summary:** Moved `AuditRecord` to test sources as `VendorAuditRecord`, removed `HealthStatus` enum (replaced with String in HealthCheckResponse), and converted `VersionTag` timestamps from `java.time.Instant` to `kotlin.time.Instant` with persistence boundary conversion.

**Files changed:**
- `src/main/kotlin/io/sdkman/domain/Audit.kt` — removed `AuditRecord` data class (only `Auditable` interface and `AuditOperation` enum remain)
- `src/main/kotlin/io/sdkman/domain/HealthCheck.kt` → renamed to `HealthCheckSuccess.kt` — removed `HealthStatus` enum, only `HealthCheckSuccess` data object remains
- `src/main/kotlin/io/sdkman/domain/VersionTag.kt` — changed `createdAt`/`lastUpdatedAt` from `java.time.Instant` to `kotlin.time.Instant` with `kotlin.time.Clock.System.now()` defaults
- `src/main/kotlin/io/sdkman/repos/PostgresConnectivity.kt` — added `toKotlinTimeInstant()` extension for `java.time.Instant` → `kotlin.time.Instant` conversion at persistence boundary
- `src/main/kotlin/io/sdkman/repos/TagsRepositoryImpl.kt` — uses `toKotlinTimeInstant()` when mapping DB rows to `VersionTag` domain objects
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — `HealthCheckResponse.status` changed from `HealthStatus` to `String`; added `@Suppress("LongMethod")` for pre-existing detekt violation
- `src/test/kotlin/io/sdkman/support/Postgres.kt` — added `VendorAuditRecord` data class (moved from domain); updated all `selectAuditRecords*` helpers
- `src/test/kotlin/io/sdkman/HealthCheckApiSpec.kt` — assertions changed from `HealthStatus.SUCCESS`/`FAILURE` to string literals `"SUCCESS"`/`"FAILURE"`

**Test outcome:** PASS — all tests green, full build passes (compile + detekt + ktlint + test)

**Learnings:**
- _Patterns:_ `kotlinx.datetime.toKotlinInstant()` is internal in `kotlinx-datetime:0.7.1-0.6.x-compat` with Kotlin 2.3.10 — use `kotlin.time.Instant` (stdlib) instead, which is the native Kotlin approach already used in the codebase
- _Gotchas:_ When using `replace_all` in the Edit tool, the replacement string can match substrings of previously inserted text — e.g. replacing `AuditRecord(` in a file that already contains `VendorAuditRecord(` creates `VendorVendorAuditRecord(`
- _Context:_ `configureRouting` in Routing.kt is 130 lines vs detekt max of 60 — pre-existing violation suppressed with annotation; will be naturally resolved in Phase 5.2 when routes split into VersionRoutes/TagRoutes/HealthRoutes

---

### [2026-03-15 21:00] — Phase 1.3 + 5.1: Remove @Serializable from Domain Models

**Summary:** Removed `@Serializable` from all domain models (`Version`, `UniqueVersion`, `UniqueTag`, `Distribution`, `AuditOperation`) to enforce hexagonal RULE-004 (domain entities must not reference infrastructure annotations). Created `@Serializable` DTOs (`VersionDto`, `UniqueVersionDto`, `UniqueTagDto`) in `io.sdkman.dto` package for serialization at adapter boundaries.

**Files changed:**
- `src/main/kotlin/io/sdkman/dto/VersionDto.kt` — new file; @Serializable DTO mirroring Version with toDto()/toDomain() mappers
- `src/main/kotlin/io/sdkman/dto/UniqueVersionDto.kt` — new file; @Serializable DTO mirroring UniqueVersion with toDomain() mapper
- `src/main/kotlin/io/sdkman/dto/UniqueTagDto.kt` — new file; @Serializable DTO mirroring UniqueTag with toDto()/toDomain() mappers
- `src/main/kotlin/io/sdkman/domain/Version.kt` — removed @Serializable, @file:UseSerializers, and serialization imports
- `src/main/kotlin/io/sdkman/domain/VersionTag.kt` — removed @Serializable from UniqueTag, removed serialization imports
- `src/main/kotlin/io/sdkman/domain/Distribution.kt` — removed @Serializable and serialization import
- `src/main/kotlin/io/sdkman/domain/Audit.kt` — removed @Serializable from AuditOperation and serialization import
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — GET responses now map Version→VersionDto; DELETE handlers receive UniqueVersionDto/UniqueTagDto and map to domain
- `src/main/kotlin/io/sdkman/repos/AuditRepositoryImpl.kt` — toJsonElement() now converts domain→DTO before Json.encodeToJsonElement
- `src/test/kotlin/io/sdkman/support/Json.kt` — toJson()/toJsonString() now go through DTOs
- `src/test/kotlin/io/sdkman/support/Postgres.kt` — deserializeVersionData() decodes to VersionDto then maps toDomain()

**Test outcome:** PASS — all 131 tests green, full build passes (compile + detekt + ktlint + test)

**Learnings:**
- _Patterns:_ kotlinx.serialization compiler plugin auto-generates serializers for enums used as fields in @Serializable classes, even when the enum itself lacks @Serializable — Platform (never annotated) and Distribution (annotation removed) both work as fields in @Serializable DTOs
- _Gotchas:_ Test support `deserializeVersionData` used `Version.serializer()` directly — needed intermediate DTO deserialization + toDomain() conversion to maintain test assertion compatibility (tests compare deserialized audit data against domain `Version` objects)
- _Context:_ DTOs currently live in `io.sdkman.dto` package — will move to `io.sdkman.state.adapter.primary.rest.dto` during Phase 1.1 package restructuring

---

### [2026-03-15 22:00] — Phase 5.1 + 5.2: Split Routing.kt into Focused Route Files

**Summary:** Extracted response DTOs (ErrorResponse, TagConflictResponse, HealthCheckResponse) from Routing.kt to `io.sdkman.dto` package. Split monolithic `configureRouting` (233 lines) into four focused files: `VersionRoutes.kt` (read/write split for auth boundary), `TagRoutes.kt`, `HealthRoutes.kt`, and `RequestExtensions.kt`. Routing.kt reduced to 22-line composition function, naturally removing the `@Suppress("LongMethod")` annotation.

**Files changed:**
- `src/main/kotlin/io/sdkman/dto/ErrorResponse.kt` — new file; @Serializable response DTO extracted from Routing.kt
- `src/main/kotlin/io/sdkman/dto/TagConflictResponse.kt` — new file; @Serializable response DTO extracted from Routing.kt
- `src/main/kotlin/io/sdkman/dto/HealthCheckResponse.kt` — new file; @Serializable response DTO with Option<String> message field
- `src/main/kotlin/io/sdkman/plugins/RequestExtensions.kt` — new file; shared helpers: authenticatedUsername(), visibleQueryParam(), toDistribution(), respondDomainError()
- `src/main/kotlin/io/sdkman/plugins/VersionRoutes.kt` — new file; Route.versionReadRoutes() for GETs, Route.versionWriteRoutes() for POST/DELETE
- `src/main/kotlin/io/sdkman/plugins/TagRoutes.kt` — new file; Route.tagRoutes() for DELETE /versions/tags
- `src/main/kotlin/io/sdkman/plugins/HealthRoutes.kt` — new file; Route.healthRoutes() for GET /meta/health
- `src/main/kotlin/io/sdkman/plugins/Routing.kt` — simplified to 22-line composition; removed inline DTOs, error mappers, and route handlers
- `src/test/kotlin/io/sdkman/HealthCheckApiSpec.kt` — updated import from io.sdkman.plugins.HealthCheckResponse to io.sdkman.dto.HealthCheckResponse

**Test outcome:** PASS — all tests green, full build passes (compile + detekt + ktlint + test)

**Learnings:**
- _Patterns:_ Version routes need auth-boundary split: `versionReadRoutes()` (GET, unauthenticated) and `versionWriteRoutes()` (POST/DELETE, inside `authenticate("auth-basic")` block) — a single `versionRoutes()` function can't serve both contexts
- _Gotchas:_ Arrow extension functions like `getOrElse`, `firstOrNone`, `flatMap` need explicit imports in each file — they're not auto-imported when code is moved to new files
- _Context:_ Remaining Phase 5.2 items (move Compression.kt from HTTP.kt, move Serialization.kt) are purely file relocations and can be done during Phase 8 cleanup

---

### [2026-03-15 23:00] — Phase 1.1 + 1.2 + 1.4 + 1.5 + 4.2 + 5.1 + 5.2: Package Restructure to io.sdkman.state

**Summary:** Restructured the entire codebase from flat `io.sdkman` package to `io.sdkman.state` with proper hexagonal architecture subdirectories. Moved all 44 main source files and 20 test source files to their target locations.

**Files changed:**
- All 44 main source files moved from `io.sdkman.*` to `io.sdkman.state.*` with updated package declarations and imports
- All 20 test source files moved from `io.sdkman.*` to `io.sdkman.state.*`
- `src/main/resources/application.conf` — updated module reference from `io.sdkman.ApplicationKt.module` to `io.sdkman.state.ApplicationKt.module`
- Key directory mappings:
  - `io.sdkman.domain` → split into `domain/model/`, `domain/error/`, `domain/repository/`, `domain/service/`
  - `io.sdkman.dto` → `adapter/primary/rest/dto/`
  - `io.sdkman.service` → `application/service/`
  - `io.sdkman.repos` → `adapter/secondary/persistence/`
  - `io.sdkman.plugins` (routes) → `adapter/primary/rest/`
  - `io.sdkman.plugins` (framework) → `plugins/`
  - `io.sdkman.validation` → `application/validation/`

**Test outcome:** PASS — all tests green, full build passes (compile + detekt + ktlint + test)

**Learnings:**
- _Patterns:_ Moving all domain types from a single package (`io.sdkman.domain`) into subpackages (`domain/model`, `domain/error`, `domain/repository`, `domain/service`) requires explicit imports for every cross-subpackage reference — no more implicit same-package visibility
- _Gotchas:_ `application.conf` module path must be updated when the Application.kt package changes, or Ktor won't find the entry point at runtime
- _Context:_ Old directories are fully removed — no stale files remain. The restructure completes Phase 1.1 scaffolding and resolves all "will move during package restructuring" notes from Phases 1.2-1.5

---

### [2026-03-15 24:00] — Phase 2.1 + 3.1: Repository Renames and Config Moves

**Summary:** Renamed all four repository implementations to `Postgres*` naming convention (`PostgresVersionRepository`, `PostgresTagRepository`, `PostgresAuditRepository`, `PostgresHealthRepository`) and moved `Authentication.kt` and `Migration.kt` from `plugins/` to `config/` package.

**Files changed:**
- `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresVersionRepository.kt` — renamed from `VersionsRepository.kt`
- `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresTagRepository.kt` — renamed from `TagsRepositoryImpl.kt`
- `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresAuditRepository.kt` — renamed from `AuditRepositoryImpl.kt`
- `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresHealthRepository.kt` — renamed from `HealthRepositoryImpl.kt`
- `src/main/kotlin/io/sdkman/state/config/Authentication.kt` — moved from `plugins/Authentication.kt`, package changed to `io.sdkman.state.config`
- `src/main/kotlin/io/sdkman/state/config/Migration.kt` — moved from `plugins/Migration.kt`, package changed to `io.sdkman.state.config`
- `src/main/kotlin/io/sdkman/state/Application.kt` — updated imports and instantiation to new class names
- `src/test/kotlin/io/sdkman/state/support/Application.kt` — updated imports and instantiation
- `src/test/kotlin/io/sdkman/state/HealthCheckApiSpec.kt` — updated imports and instantiation
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/VersionsRepositorySpec.kt` — updated class instantiation
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/TagsRepositorySpec.kt` — updated class instantiation
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/HealthRepositorySpec.kt` — updated class instantiation
- Old files deleted: `VersionsRepository.kt`, `TagsRepositoryImpl.kt`, `AuditRepositoryImpl.kt`, `HealthRepositoryImpl.kt`, `plugins/Authentication.kt`, `plugins/Migration.kt`

**Test outcome:** PASS — all tests green, full build passes (compile + detekt + ktlint + test)

**Learnings:**
- _Patterns:_ Repository implementations in same package as their test specs don't need import changes when renamed — test files use the class name directly since they share the `io.sdkman.state.adapter.secondary.persistence` package
- _Gotchas:_ `Application.kt` used a wildcard import `io.sdkman.state.plugins.*` which covered both `configureBasicAuthentication` and `configureDatabaseMigration` — after moving those functions to `config/`, the wildcard `io.sdkman.state.config.*` now covers them along with `configureAppConfig`
- _Context:_ Phase 3.1 still has one remaining item: normalise `VersionTag` timestamps (already using `kotlin.time.Instant` in domain, `toKotlinTimeInstant()` at persistence boundary — may already be complete, needs verification)

---

### [2026-03-15 25:00] — Phase 7.1: Testcontainers Infrastructure Migration

**Summary:** Replaced hardcoded `localhost:5432` PostgreSQL dependency with Testcontainers (`postgres:16`) across all test infrastructure. Created `PostgresTestContainer` singleton with `withReuse(true)` for fast test execution. Updated test `Application.kt` to use `MapApplicationConfig` with container-provided values instead of `ApplicationConfig("application.conf")`, making test configuration explicit. Updated `HealthCheckApiSpec` to use the same pattern with explicit plugin installation.

**Files changed:**
- `src/test/kotlin/io/sdkman/state/support/PostgresTestContainer.kt` — new file; singleton Testcontainers lifecycle object providing `host`, `port`, `username`, `password`, `jdbcUrl` from a `postgres:16` container
- `src/test/kotlin/io/sdkman/state/support/Postgres.kt` — replaced `const val DB_HOST/DB_PORT/DB_USERNAME/DB_PASSWORD` with computed properties delegating to `PostgresTestContainer`
- `src/test/kotlin/io/sdkman/state/support/Application.kt` — replaced `ApplicationConfig("application.conf")` with `MapApplicationConfig` using container values; added explicit `configureHTTP`, `configureSerialization`, `configureBasicAuthentication` calls (previously auto-loaded from main module); extracted `testApplicationConfig()` for reuse
- `src/test/kotlin/io/sdkman/state/HealthCheckApiSpec.kt` — switched from `ApplicationConfig("application.conf")` to `testApplicationConfig()`; added explicit `configureSerialization()` and `configureBasicAuthentication()` calls

**Test outcome:** PASS — all 151 tests green, full build passes (compile + detekt + ktlint + test)

**Learnings:**
- _Patterns:_ Using `MapApplicationConfig` instead of `ApplicationConfig("application.conf")` prevents Ktor's auto-module loading (from `ktor.application.modules` config key), which means the test `application {}` block must explicitly install all plugins (Serialization, Authentication) — this is better for test clarity since dependencies are explicit
- _Gotchas:_ `configureRouting` calls `authenticate("auth-basic")` which requires the Authentication plugin to be installed — forgetting `configureBasicAuthentication()` in the test's `application {}` block causes `MissingApplicationPluginException` at route registration time, not at request time
- _Context:_ Tests no longer require a pre-running PostgreSQL instance — Testcontainers starts/manages the container automatically, enabling CI/CD without external database dependencies

---

### [2026-03-15 26:00] — Phase 7.3 + 7.4: Integration Test Migration and UniqueTagValidatorSpec

**Summary:** Renamed three repository integration specs to `Postgres*IntegrationSpec` convention with `@Tags("integration")` annotation. Created new `PostgresAuditRepositoryIntegrationSpec` (5 tests covering CREATE/DELETE audit, multiple usernames, operation filtering, no-distribution serialization). Created `UniqueTagValidatorSpec` unit test (5 tests: valid with/without distribution, blank candidate/tag, accumulated errors). Deleted old spec files.

**Files changed:**
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresVersionRepositoryIntegrationSpec.kt` — renamed from `VersionsRepositorySpec.kt`, added `@Tags("integration")`
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresTagRepositoryIntegrationSpec.kt` — renamed from `TagsRepositorySpec.kt`, added `@Tags("integration")`
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresHealthRepositoryIntegrationSpec.kt` — renamed from `HealthRepositorySpec.kt`, added `@Tags("integration")`
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresAuditRepositoryIntegrationSpec.kt` — new file; 5 integration tests for `PostgresAuditRepository.recordAudit`
- `src/test/kotlin/io/sdkman/state/application/validation/UniqueTagValidatorSpec.kt` — new file; 5 unit tests for `UniqueTagValidator.validate`
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/VersionsRepositorySpec.kt` — **deleted** (replaced by renamed file)
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/TagsRepositorySpec.kt` — **deleted** (replaced by renamed file)
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/HealthRepositorySpec.kt` — **deleted** (replaced by renamed file)

**Test outcome:** PASS — all tests green, full build passes (compile + detekt + ktlint + test)

**Learnings:**
- _Patterns:_ Kotest `@Tags("integration")` annotation is from `io.kotest.core.annotation.Tags` — applied at class level, enables selective test execution via `--include-tags` in CI pipelines
- _Gotchas:_ `PostgresAuditRepository` serializes `Auditable` via the DTO layer (Version.toDto(), UniqueTag.toDto()) — integration tests verify the full serialization path including DTO mapping, which is why `shouldContain` on the JSON string is sufficient to verify correctness
- _Context:_ The audit repository was previously only tested indirectly through API acceptance specs — direct integration tests improve coverage of the serialization boundary and error handling

---

### [2026-03-15 27:00] — Phase 7.2: Acceptance Test Migration to acceptance/ Subdirectory

**Summary:** Migrated all 12 acceptance (E2E API) test specs from root `io.sdkman.state` package to `io.sdkman.state.acceptance` subdirectory. Applied `@Tags("acceptance")` annotation and renamed classes to `*AcceptanceSpec` convention. Deleted old spec files.

**Files changed:**
- `src/test/kotlin/io/sdkman/state/acceptance/GetVersionsAcceptanceSpec.kt` — new file (migrated from `GetVersionsApiSpec.kt`)
- `src/test/kotlin/io/sdkman/state/acceptance/GetVersionAcceptanceSpec.kt` — new file (migrated from `GetVersionApiSpec.kt`)
- `src/test/kotlin/io/sdkman/state/acceptance/GetVersionTagsAcceptanceSpec.kt` — new file (migrated from `GetVersionTagsApiSpec.kt`)
- `src/test/kotlin/io/sdkman/state/acceptance/PostVersionAcceptanceSpec.kt` — new file (migrated from `PostVersionApiSpec.kt`)
- `src/test/kotlin/io/sdkman/state/acceptance/PostVersionTagsAcceptanceSpec.kt` — new file (migrated from `PostVersionTagsApiSpec.kt`)
- `src/test/kotlin/io/sdkman/state/acceptance/PostVersionVisibilityAcceptanceSpec.kt` — new file (migrated from `PostVersionVisibilitySpec.kt`)
- `src/test/kotlin/io/sdkman/state/acceptance/IdempotentPostVersionAcceptanceSpec.kt` — new file (migrated from `IdempotentPostVersionApiSpec.kt`)
- `src/test/kotlin/io/sdkman/state/acceptance/DeleteVersionAcceptanceSpec.kt` — new file (migrated from `DeleteVersionApiSpec.kt`)
- `src/test/kotlin/io/sdkman/state/acceptance/DeleteTagAcceptanceSpec.kt` — new file (migrated from `DeleteTagApiSpec.kt`)
- `src/test/kotlin/io/sdkman/state/acceptance/DeleteTaggedVersionAcceptanceSpec.kt` — new file (migrated from `DeleteTaggedVersionApiSpec.kt`)
- `src/test/kotlin/io/sdkman/state/acceptance/HealthCheckAcceptanceSpec.kt` — new file (migrated from `HealthCheckApiSpec.kt`)
- `src/test/kotlin/io/sdkman/state/acceptance/VendorAuditAcceptanceSpec.kt` — new file (migrated from `VendorAuditSpec.kt`)
- 12 old spec files in `src/test/kotlin/io/sdkman/state/` — **deleted**

**Test outcome:** PASS — all tests green, full build passes (compile + ktlint + detekt + test)

**Learnings:**
- _Patterns:_ Kept acceptance specs as 12 separate focused files rather than consolidating into 3 mega-files — each spec tests a cohesive feature area (GET versions, POST versions, DELETE tags, etc.) and consolidation would create files with 70+ tests that are harder to navigate
- _Gotchas:_ `@Tags("acceptance")` uses `io.kotest.core.annotation.Tags` (not `io.kotest.core.Tag`) — the annotation form enables class-level tagging for selective CI execution via `--include-tags`
- _Context:_ All 3 test layers now follow consistent tagging: `@Tags("acceptance")` for E2E API tests, `@Tags("integration")` for repository tests, no tag for unit tests (they're fast enough to always run)

---

### [2026-03-15 28:00] — Phase 7.3: Remove duplicate table definitions from test support

**Summary:** Promoted `Versions` and `VendorAuditTable` from `private object` inside their respective repository classes to `internal object` in `PostgresConnectivity.kt`. Test support `Postgres.kt` now imports these shared definitions instead of maintaining duplicate copies, following the established `VersionTags` pattern.

**Files changed:**
- `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresConnectivity.kt` — added `internal object Versions` and `internal object VendorAuditTable` alongside existing `internal object VersionTags`
- `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresVersionRepository.kt` — removed `private object Versions` (now imports shared definition)
- `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresAuditRepository.kt` — removed `private object VendorAuditTable` (now imports shared definition)
- `src/test/kotlin/io/sdkman/state/support/Postgres.kt` — removed duplicate `private object Versions` and `private object VendorAuditTable`; now imports from main source

**Test outcome:** PASS — all tests green, full build passes (compile + detekt + ktlint + test)

**Learnings:**
- _Patterns:_ All three table objects (`Versions`, `VersionTags`, `VendorAuditTable`) now follow the same `internal object` pattern in `PostgresConnectivity.kt`, providing a single source of truth for Exposed table definitions accessible by both production repositories and test support code
- _Gotchas:_ Removing the `private object` from inside the repository class means the table object is no longer scoped to the class — but since all repositories are in the same package, unqualified access works identically
- _Context:_ This completes Phase 7.3 of the implementation plan; the only remaining test infrastructure items are Phase 7.1 helpers (EitherMatchers, OptionMatchers, KotestConfig, TestDependencyInjection)

---

### [2026-03-15 29:00] — Phase 2.1: AppConfig Interface and ConfigExtensions

**Summary:** Replaced nested config data classes (`DatabaseConfig`, `ApiAuthenticationConfig`, `ApiCacheConfig`, `ApplicationConfig`) with a flat `AppConfig` interface and `DefaultAppConfig` implementation per spec section 5. Created `ConfigExtensions.kt` with Arrow-based config helpers (`getStringOrDefault`, `getIntOrDefault`, `getOptionString`) and `AppConfig.jdbcUrl` extension property. Updated all consumers (Databases, Migration, Authentication, HTTP, Application, tests) to use `AppConfig` interface. Deleted `ApplicationConfig.kt`.

**Files changed:**
- `src/main/kotlin/io/sdkman/state/config/AppConfig.kt` — new file; `interface AppConfig` with 8 flat properties + `DefaultAppConfig(config: ApplicationConfig)` implementation
- `src/main/kotlin/io/sdkman/state/config/ConfigExtensions.kt` — new file; Arrow-based config loading helpers and `AppConfig.jdbcUrl` extension
- `src/main/kotlin/io/sdkman/state/config/ApplicationConfig.kt` — **deleted** (replaced by AppConfig.kt)
- `src/main/kotlin/io/sdkman/state/config/Databases.kt` — accepts `AppConfig` instead of `DatabaseConfig`; uses `databaseUsername.getOrElse` pattern
- `src/main/kotlin/io/sdkman/state/config/Migration.kt` — accepts `AppConfig` instead of `DatabaseConfig`; uses `databaseUsername.getOrElse` pattern
- `src/main/kotlin/io/sdkman/state/config/Authentication.kt` — accepts `AppConfig` instead of `ApiAuthenticationConfig`; uses `authUsername`/`authPassword` properties
- `src/main/kotlin/io/sdkman/state/adapter/primary/rest/HTTP.kt` — accepts `AppConfig` instead of `ApiCacheConfig`; uses `cacheMaxAge` property
- `src/main/kotlin/io/sdkman/state/Application.kt` — uses `DefaultAppConfig(environment.config)` instead of `configureAppConfig(environment)`; passes single `appConfig` to all configure functions
- `src/test/kotlin/io/sdkman/state/support/Application.kt` — uses `DefaultAppConfig(environment.config)` instead of `configureAppConfig`
- `src/test/kotlin/io/sdkman/state/support/Postgres.kt` — uses `DefaultAppConfig(testApplicationConfig())` with `jdbcUrl` extension instead of `DatabaseConfig`
- `src/test/kotlin/io/sdkman/state/acceptance/HealthCheckAcceptanceSpec.kt` — uses `DefaultAppConfig`; FAILURE test uses Kotlin delegation (`object : AppConfig by appConfig { override val databasePort = 9999 }`) instead of `data class .copy()`

**Test outcome:** PASS — all tests green, full build passes (compile + detekt + ktlint + test)

**Learnings:**
- _Patterns:_ Kotlin interface delegation (`object : AppConfig by appConfig { override val ... }`) cleanly replaces `data class .copy()` for test scenarios that need to override one property — avoids making the config implementation a data class
- _Gotchas:_ Extension properties defined in the same package are visible without explicit imports for files in that package, but test files in other packages must explicitly import them (e.g., `import io.sdkman.state.config.jdbcUrl`)
- _Context:_ `databaseUsername` and `databasePassword` are `Option<String>` per the spec, even though `application.conf` always provides defaults — this models the real-world case where credentials might come from external secret stores and might be absent in some configurations

---
