# SDKMAN State Modernisation -- Implementation Plan

> **Goal:** Refactor sdkman-state to hexagonal architecture per `specs/modernisation.md`
> **Branch:** `corrective_actions`
> **Status:** Planning phase -- nothing implemented yet
> **Strategy:** Incremental migration (new structure alongside existing, then remove old)

---

## Phase 0: Prerequisites (Build & Dependency Fixes)

Resolve build configuration issues and known defects before any structural work begins.

### 0.1 Ktor Version Mismatch
- [x] Align Ktor plugin version with runtime: change `io.ktor.plugin` from `3.4.0` to `3.3.3` in `build.gradle.kts` (or upgrade `ktor_version` in `gradle.properties` to `3.4.0`) -- currently plugin is 3.4.0 but all runtime deps use `ktor_version=3.3.3`

### 0.2 Missing Test Dependencies
- [x] Add Testcontainers BOM: `testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))`
- [x] Add Testcontainers PostgreSQL module: `testImplementation("org.testcontainers:postgresql")`
- [x] Add Testcontainers core: `testImplementation("org.testcontainers:testcontainers")`
- [x] Add MockK: `testImplementation("io.mockk:mockk:1.13.13")`
- [x] Add Kotest Testcontainers extension: `testImplementation("io.kotest.extensions:kotest-extensions-testcontainers:2.0.2")`
- [x] Add `kotlinx-datetime` dependency (already present: `0.7.1-0.6.x-compat`) -- verify compatibility with `kotlinx.datetime.Instant` for domain models

### 0.3 Existing Code Defects
- [x] Fix `VendorAuditSpec` dead code: `insertVersions` call at line 73 outside `withCleanDatabase` block at line 85 (data inserted then immediately wiped by `withCleanDatabase`) -- `src/test/kotlin/io/sdkman/VendorAuditSpec.kt:73`
- [x] Fix `IdempotentPostVersionApiSpec` misleading test name: test says "succeed with 201" (line 28) but asserts `HttpStatusCode.NoContent` (204) at line 50 -- `src/test/kotlin/io/sdkman/IdempotentPostVersionApiSpec.kt:28`
- [x] Fix `UniqueTagValidator` return type inconsistency: returns `Either<List<ValidationFailure>, UniqueTag>` while `UniqueVersionValidator` returns `Either<NonEmptyList<ValidationError>, UniqueVersion>` -- normalise to consistent error type
- [x] Fix `VersionsRepository.create()` and `delete()` using blocking `transaction{}` instead of suspend `newSuspendedTransaction` -- all other repository methods are properly suspend -- `src/main/kotlin/io/sdkman/repos/VersionsRepository.kt:125-135` (create) and `188-201` (delete)
- [x] Fix duplicated `dbQuery` helper: identical private function defined in all 4 repository files (`VersionsRepository.kt:42`, `TagsRepositoryImpl.kt:45`, `AuditRepositoryImpl.kt:32`, `HealthRepositoryImpl.kt:11`) -- extract to shared utility
- [x] Fix duplicated JDBC URL construction: `"jdbc:postgresql://${config.host}:${config.port}/sdkman?sslMode=prefer&loglevel=2"` appears in both `Databases.kt:9` and `Migration.kt:11` -- extract to shared config
- [x] Fix duplicated `NA_SENTINEL` constant: defined in both `TagsRepositoryImpl.kt:28` and test support `Postgres.kt:43` -- extract to single location
- [x] Fix duplicate `VersionTags` table definition: consolidated to single `internal object VersionTags` in `PostgresConnectivity.kt`, shared by `VersionsRepository`, `TagsRepositoryImpl`, and test `Postgres.kt`
- [x] Fix `HealthCheckResponse.message` using nullable `String? = null` instead of `Option<String>` -- `src/main/kotlin/io/sdkman/plugins/Routing.kt:48`
- [x] Fix `HealthCheckApiSpec` SUCCESS test using nullable chain `contentType()?.withoutParameters()` at line 44 (inconsistent with FAILURE test which correctly uses `.toOption().map`) -- `src/test/kotlin/io/sdkman/HealthCheckApiSpec.kt:44`
- [x] Fix private sealed error types `DeleteError` and `DeleteTagError` in `Routing.kt:51-83` -- promoted to unified `DomainError` sealed interface in `Domain.kt`, used by both DELETE handlers with shared `respondDomainError` mapper
- [x] Fix business logic embedded in `TagsRepositoryImpl.replaceTags`: exclusive tag ownership rule (delete-from-other-versions-then-insert) at lines 128-134 is domain logic in the persistence layer -- orchestration now happens through VersionServiceImpl which calls replaceTags; the repo method itself still contains the DB-level tag replacement logic which is appropriate for the persistence layer
- [x] Fix business logic in `Routing.kt`: multi-step orchestration (validate, create, audit, process tags) inline in route handlers at lines 253-273 -- extracted to VersionServiceImpl and TagServiceImpl; Routing.kt now delegates to services
- [x] Fix `getOrNull()!!` pattern: ~14 instances in `TagsRepositorySpec` using unsafe unwrap instead of Arrow matchers

---

## Phase 1: Domain Layer (Foundation)

Everything else depends on this. Migrate domain models first per spec section 11.

### 1.1 Package Structure Scaffolding
- [ ] Create target directory tree under `src/main/kotlin/io/sdkman/state/`
- [ ] Create test directory tree under `src/test/kotlin/io/sdkman/state/`

### 1.2 Domain Error Types (spec section 2.2)
- [x] Create `domain/error/DatabaseError.kt` -- `DatabaseFailure` sealed class with `ConnectionFailure` and `QueryExecutionFailure` variants (currently a simple data class in Domain.kt) -- done in `Domain.kt`; will move to separate file during package restructuring
- [ ] Create `domain/error/ValidationError.kt` -- move validation error hierarchy from `validation/ValidationErrors.kt`
- [ ] Create `domain/error/DomainError.kt` -- unified sealed class consolidating `DeleteError`/`DeleteTagError` from Routing.kt (currently private sealed interfaces at lines 51-83)

### 1.3 Domain Models (spec section 2.1)
- [ ] Create `domain/model/Platform.kt` -- extract Platform enum from Domain.kt, **remove @Serializable**
- [ ] Create `domain/model/Distribution.kt` -- extract Distribution enum from Domain.kt, **remove @Serializable**
- [ ] Create `domain/model/Version.kt` -- extract Version + UniqueVersion from Domain.kt, **remove @Serializable**
- [ ] Create `domain/model/VersionTag.kt` -- extract VersionTag + UniqueTag, **remove @Serializable**, change `java.time.Instant` to `kotlinx.datetime.Instant` (spec section 9)
- [ ] Create `domain/model/Audit.kt` -- extract Auditable sealed interface + AuditOperation enum, **remove @Serializable**
- [~] Create `domain/model/HealthCheckSuccess.kt` -- new `data object HealthCheckSuccess` (replaces HealthStatus enum) -- `HealthCheckSuccess` data object created in `Domain.kt`; `HealthStatus` enum kept temporarily for DTO layer compatibility
- [ ] Move `AuditRecord` to test sources as `VendorAuditRecord` (only used in test assertions)

### 1.4 Repository Port Interfaces (spec section 2.3)
- [x] Create `domain/repository/VersionRepository.kt` -- **new interface** (created in Domain.kt alongside other port interfaces; VersionsRepository now implements it; Routing depends on interface)
- [ ] Create `domain/repository/TagRepository.kt` -- simplified from current `TagsRepository` (complex orchestration moves to service layer)
- [ ] Create `domain/repository/AuditRepository.kt` -- refine existing interface from Domain.kt
- [x] Create `domain/repository/HealthRepository.kt` -- change return type to `Either<DatabaseFailure, HealthCheckSuccess>` -- return type updated in existing `HealthRepository` interface in `Domain.kt`

### 1.5 Domain Service Interfaces (spec section 2.4)
- [x] Create `domain/service/VersionService.kt` -- created in `Domain.kt` alongside other port interfaces; covers findAll, findOne, createOrUpdate, delete
- [x] Create `domain/service/TagService.kt` -- created in `Domain.kt`; covers deleteTag

---

## Phase 2: Configuration & Infrastructure

### 2.1 Configuration (spec section 5)
- [ ] Create `config/AppConfig.kt` -- interface + `DefaultAppConfig` implementation (currently just data classes)
- [ ] Create `config/ConfigExtensions.kt` -- Arrow-based config helpers
- [ ] Extract JDBC URL construction to shared config (currently duplicated in `Databases.kt:9` and `Migration.kt:11`)
- [ ] Move `CandidateLoader.kt` from `validation/` to `config/`
- [ ] Move `Authentication.kt` from `plugins/` to `config/`
- [ ] Move `Migration.kt` from `plugins/` to `config/`

---

## Phase 3: Secondary Adapters (Persistence)

### 3.1 Repository Implementations (spec sections 4.2, 6, 7)
- [ ] Create `adapter/secondary/persistence/PostgresConnectivity.kt` -- shared `dbQuery` helper (currently duplicated in all 4 repository files)
- [ ] Create `adapter/secondary/persistence/PostgresVersionRepository.kt` -- implements new `VersionRepository` port; contains `internal object VersionsTable` (single definition); **all methods suspend** (currently `create()` and `delete()` are blocking via `transaction{}` -- `VersionsRepository.kt:125,188`)
- [ ] Create `adapter/secondary/persistence/PostgresTagRepository.kt` -- implements new `TagRepository` port; contains `internal object VersionTagsTable` (**single definition** -- currently defined in `VersionsRepository.kt:37-40` as 2-col, `TagsRepositoryImpl.kt:31-43` as 7-col, and `Postgres.kt:45-53` as 7-col); add `findTagNamesByVersionIds` batch method; extract `NA_SENTINEL` to single location
- [ ] Create `adapter/secondary/persistence/PostgresAuditRepository.kt` -- implements `AuditRepository`; contains `internal object AuditTable`
- [ ] Create `adapter/secondary/persistence/PostgresHealthRepository.kt` -- implements `HealthRepository`; returns `HealthCheckSuccess`
- [ ] Normalise `VersionTag` timestamps: convert `java.time.Instant` to/from `kotlinx.datetime.Instant` at persistence boundary only (spec section 9)

---

## Phase 4: Application Layer (Business Logic Extraction)

### 4.1 Application Services (spec section 3.1)
- [x] Create `application/service/VersionServiceImpl.kt` -- created in `io.sdkman.service`; extracts business logic from Routing.kt (validation stays in routing, orchestration/audit/tags in service)
- [x] Create `application/service/TagServiceImpl.kt` -- created in `io.sdkman.service`; extracts tag deletion + audit from Routing.kt

### 4.2 Application Validation (spec section 3.2)
- [ ] Refactor `VersionRequestValidator.kt` to `application/validation/VersionRequestValidator.kt` -- receives pre-parsed DTO (not raw JSON), validates domain rules only
- [ ] Move `UniqueVersionValidator.kt` to `application/validation/UniqueVersionValidator.kt`
- [ ] Move `UniqueTagValidator.kt` to `application/validation/UniqueTagValidator.kt` -- **fix inconsistent return type** (currently returns `Either<List<ValidationFailure>, UniqueTag>` vs `Either<NonEmptyList<ValidationError>, ...>` elsewhere)
- [ ] Move `ValidationErrors.kt` to `application/validation/ValidationErrors.kt`

---

## Phase 5: Primary Adapters (REST)

### 5.1 Request/Response DTOs (spec section 4.1)
- [ ] Create `adapter/primary/rest/dto/VersionRequest.kt` -- `@Serializable` unvalidated request DTO (JSON parsing moves here from VersionRequestValidator)
- [ ] Create `adapter/primary/rest/dto/UniqueVersionRequest.kt` -- `@Serializable` delete request DTO
- [ ] Create `adapter/primary/rest/dto/UniqueTagRequest.kt` -- `@Serializable` tag delete request DTO
- [ ] Create `adapter/primary/rest/dto/ErrorResponse.kt` -- move from Routing.kt (currently defined at lines 33-36)
- [ ] Create `adapter/primary/rest/dto/TagConflictResponse.kt` -- move from Routing.kt (currently at lines 39-43)
- [ ] Create `adapter/primary/rest/dto/HealthCheckResponse.kt` -- move from Routing.kt (fix nullable `message: String?` to `Option<String>`, currently at lines 46-49)
- [ ] Create `adapter/primary/rest/dto/ValidationErrorResponse.kt` -- move from validation/ValidationErrors.kt

### 5.2 REST Route Adapters (spec section 4.1)
- [ ] Create `adapter/primary/rest/RequestExtensions.kt` -- extract `authenticatedUsername()`, `visibleQueryParam()` helpers
- [ ] Create `adapter/primary/rest/VersionRoutes.kt` -- thin adapter for GET/POST/DELETE /versions (delegates to VersionService)
- [ ] Create `adapter/primary/rest/TagRoutes.kt` -- thin adapter for DELETE /versions/tags (delegates to TagService)
- [ ] Create `adapter/primary/rest/HealthRoutes.kt` -- thin adapter for GET /meta/health
- [ ] Move `Compression.kt` (from plugins/HTTP.kt) to `adapter/primary/rest/Compression.kt`
- [ ] Move `Serialization.kt` (from plugins/) to `adapter/primary/rest/Serialization.kt`

### 5.3 Entry Point (spec sections 5, 11)
- [ ] Create `App.kt` -- new entry point with manual DI wiring (repos to services to routes)
- [ ] Update `application.conf` to point to new module path

---

## Phase 6: Nullable Type Eradication (spec section 10)

### 6.1 Main Source Fixes
- [ ] Fix `HealthCheckResponse.message: String? = null` to `Option<String> = None` (`Routing.kt:48`, moves to DTO)
- [ ] Fix `it.message ?: "Unknown error"` to `Option.fromNullable(it.message).getOrElse { "Unknown error" }` (`Routing.kt:281,323`)
- [ ] Fix `content.contentType?.withoutParameters()` to `.toOption().map` (`HTTP.kt:27`)
- [ ] Fix `(item as? JsonPrimitive)?.takeIf` to Option/pattern matching (`VersionRequestValidator.kt:67`)
- [ ] Fix `.swap().getOrNull()` pattern to Option throughout (`VersionRequestValidator.kt:99-107`)
- [ ] Fix `?: throw IllegalStateException` to Either/Option (`CandidateLoader.kt:13`)

### 6.2 Test Source Fixes
- [ ] Fix `body["key"]?.jsonPrimitive?.content` patterns in DeleteTagApiSpec, DeleteTaggedVersionApiSpec
- [ ] Fix `contentType()?.withoutParameters()` in HealthCheckApiSpec SUCCESS test (line 44 -- inconsistent with FAILURE test at lines 69-72 which correctly uses `.toOption().map`)
- [ ] Fix `getOrNull()!!` pattern (14 instances) in TagsRepositorySpec to use Arrow `shouldBeRight()` matchers

### 6.3 Detekt Nullable Enforcement
- [ ] Add JitPack repository to `settings.gradle.kts`: `maven("https://jitpack.io")`
- [ ] Add detekt plugin dependency to `build.gradle.kts`: `detektPlugins("com.github.marc0der:detekt-rules:1.0.0")`
- [ ] Run `./gradlew detekt` to verify no nullable violations remain
- [ ] Add `@AllowNullableTypes` annotation only where external interop requires it (document each case)

---

## Phase 7: Test Migration (spec section 8)

### 7.1 Test Infrastructure
- [ ] Create `support/PostgresTestListener.kt` -- Testcontainers lifecycle with `postgres:16` (replaces hardcoded `localhost:5432` in `Postgres.kt:25-28`)
- [ ] Create `support/TestDependencyInjection.kt` -- test DI container
- [ ] Create `support/EitherMatchers.kt` -- Arrow `shouldBeRight`/`shouldBeLeft` matchers
- [ ] Create `support/OptionMatchers.kt` -- Arrow `shouldBeSome`/`shouldBeNone` matchers
- [ ] Create `support/KotestConfig.kt` -- parallelism, extensions, test tagging configuration
- [ ] Create `support/VendorAuditRecord.kt` -- test-only type (moved from domain AuditRecord)

### 7.2 Acceptance Tests (E2E via full app + Testcontainers)
- [ ] Migrate/consolidate API specs into `acceptance/VersionAcceptanceSpec.kt`
- [ ] Migrate/consolidate tag API specs into `acceptance/TagAcceptanceSpec.kt`
- [ ] Migrate health check spec into `acceptance/HealthAcceptanceSpec.kt`
- [ ] Add `@Tag("acceptance")` to all acceptance specs

### 7.3 Integration Tests (Repository + Testcontainers)
- [ ] Migrate `VersionsRepositorySpec` to `adapter/secondary/persistence/PostgresVersionRepositoryIntegrationSpec.kt`
- [ ] Migrate `TagsRepositorySpec` to `adapter/secondary/persistence/PostgresTagRepositoryIntegrationSpec.kt`
- [ ] Create `adapter/secondary/persistence/PostgresAuditRepositoryIntegrationSpec.kt` (audit repo currently only tested indirectly via API specs)
- [ ] Migrate `HealthRepositorySpec` to `adapter/secondary/persistence/PostgresHealthRepositoryIntegrationSpec.kt`
- [ ] Add `@Tag("integration")` to all integration specs
- [ ] Remove duplicate table definitions from test support (import `internal object` tables from main source)

### 7.4 Unit Tests (Services with MockK)
- [ ] Create `application/service/VersionServiceUnitSpec.kt` -- test service orchestration with mocked repos
- [ ] Create `application/service/TagServiceUnitSpec.kt` -- test tag orchestration with mocked repos
- [ ] Migrate validator specs to new package paths

### 7.5 Cleanup
- [ ] Remove old test files once all new tests pass
- [ ] Verify no test uses `localhost:5432` directly

---

## Phase 8: Cleanup & Removal

- [ ] Remove old `io.sdkman` package tree (all files migrated to `io.sdkman.state`)
- [ ] Remove `plugins/Routing.kt` (logic distributed to services + route adapters)
- [ ] Remove `plugins/HTTP.kt` (split into Compression.kt + caching config)
- [ ] Remove `plugins/Serialization.kt` (moved to adapter)
- [ ] Remove `plugins/Databases.kt` (moved to PostgresConnectivity.kt)
- [ ] Remove `repos/` package (replaced by adapter/secondary/persistence/)
- [ ] Remove `validation/` package (moved to application/validation/)
- [ ] Remove `domain/Domain.kt` (split into domain/model/ files)
- [ ] Verify all existing API behaviour preserved (all tests green)
- [ ] Run `./gradlew build` (compile + ktlint + tests)

---

## Priority Rationale

Phases are ordered by dependency:
0. **Prerequisites** -- fix build config and known defects before any structural work
1. **Domain first** -- everything depends on domain types, ports, and service interfaces
2. **Config** -- needed by adapters and App.kt
3. **Secondary adapters** -- implement domain ports, needed by services
4. **Application services** -- consume ports, needed by REST adapters
5. **Primary adapters** -- consume services, complete the hexagonal wiring
6. **Nullable eradication** -- cross-cutting fix, easier once structure is final
7. **Test migration** -- validate everything works, switch to Testcontainers
8. **Cleanup** -- remove old code only after all tests pass

---

## Notes

- **No new features** -- this is purely structural (spec: "All existing API behaviour must be preserved")
- **Incremental commits** -- one test path + implementation per commit per CLAUDE.md
- **ktlint before commit** -- `./gradlew ktlintFormat` per project convention
- **No specs missing** -- the single `specs/modernisation.md` comprehensively covers all planned changes
- **Known version mismatch** -- Ktor plugin 3.4.0 vs runtime 3.3.3 must be resolved in Phase 0 before any code changes
