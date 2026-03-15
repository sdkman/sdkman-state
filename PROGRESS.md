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
