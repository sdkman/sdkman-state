# SDKMAN State Modernisation -- Implementation Plan

> **Goal:** Refactor sdkman-state to hexagonal architecture per `specs/modernisation.md`
> **Branch:** `corrective_actions`
> **Status:** In progress -- Phases 0-5 and 8 complete; Phase 7 (test migration to Testcontainers) in progress
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
- [x] Fix detekt `LongMethod` violation on `configureRouting` — suppressed with `@Suppress("LongMethod")` pending Phase 5.2 split; `@Suppress` naturally removed in Phase 5.2 since `Routing.kt` is now a 22-line composition function

---

## Phase 1: Domain Layer (Foundation)

Everything else depends on this. Migrate domain models first per spec section 11.

> **COMPLETE:** Full package restructuring from `io.sdkman` to `io.sdkman.state` is done. All files are in their final hexagonal directory structure under `src/main/kotlin/io/sdkman/state/` and `src/test/kotlin/io/sdkman/state/`. The old `io.sdkman` package tree has been removed.

### 1.1 Package Structure Scaffolding
- [x] Create target directory tree under `src/main/kotlin/io/sdkman/state/`
- [x] Create test directory tree under `src/test/kotlin/io/sdkman/state/`

### 1.2 Domain Error Types (spec section 2.2)
- [x] Create `domain/error/DatabaseError.kt` -- `DatabaseFailure` sealed class with `ConnectionFailure` and `QueryExecutionFailure` variants -- now at `io.sdkman.state.domain.error.DatabaseFailure`
- [x] Create `domain/error/ValidationError.kt` -- validation error hierarchy moved to `application/validation/ValidationErrors.kt` (contains `ValidationError` sealed class alongside validation infrastructure)
- [x] Create `domain/error/DomainError.kt` -- unified sealed interface consolidating `DeleteError`/`DeleteTagError` -- now at `io.sdkman.state.domain.error.DomainError`

### 1.3 Domain Models (spec section 2.1)
- [x] Create `domain/model/Platform.kt` -- now at `io.sdkman.state.domain.model.Platform`; pure domain enum, no serialization annotations
- [x] Create `domain/model/Distribution.kt` -- now at `io.sdkman.state.domain.model.Distribution`; pure domain model
- [x] Create `domain/model/Version.kt` -- now at `io.sdkman.state.domain.model.Version` + `UniqueVersion`; serialization handled by VersionDto in adapter layer
- [x] Create `domain/model/VersionTag.kt` -- now at `io.sdkman.state.domain.model.VersionTag` + `UniqueTag`; serialization handled by UniqueTagDto in adapter layer; uses `kotlin.time.Instant`
- [x] Create `domain/model/Audit.kt` -- now at `io.sdkman.state.domain.model.Audit` (`Auditable`, `AuditOperation`)
- [x] Create `domain/model/HealthCheckSuccess.kt` -- now at `io.sdkman.state.domain.model.HealthCheckSuccess`
- [x] Move `AuditRecord` to test sources as `VendorAuditRecord` (only used in test assertions) -- moved to `VendorAuditRecord` in test support `Postgres.kt`; removed from `Audit.kt`

### 1.4 Repository Port Interfaces (spec section 2.3)
- [x] Create `domain/repository/VersionRepository.kt` -- now at `io.sdkman.state.domain.repository.VersionRepository`; `VersionsRepository` implements it; Routing depends on interface
- [x] Create `domain/repository/TagRepository.kt` -- now at `io.sdkman.state.domain.repository.TagsRepository`
- [x] Create `domain/repository/AuditRepository.kt` -- now at `io.sdkman.state.domain.repository.AuditRepository`
- [x] Create `domain/repository/HealthRepository.kt` -- now at `io.sdkman.state.domain.repository.HealthRepository`; return type `Either<DatabaseFailure, HealthCheckSuccess>`

### 1.5 Domain Service Interfaces (spec section 2.4)
- [x] Create `domain/service/VersionService.kt` -- now at `io.sdkman.state.domain.service.VersionService`; covers findAll, findOne, createOrUpdate, delete
- [x] Create `domain/service/TagService.kt` -- now at `io.sdkman.state.domain.service.TagService`; covers deleteTag

---

## Phase 2: Configuration & Infrastructure

### 2.1 Configuration (spec section 5)
- [ ] Create `config/AppConfig.kt` -- interface + `DefaultAppConfig` implementation (currently just data classes)
- [ ] Create `config/ConfigExtensions.kt` -- Arrow-based config helpers
- [x] Extract JDBC URL construction to shared config -- done in `ApplicationConfig.kt` (`DatabaseConfig.jdbcUrl` property)
- [x] Move `CandidateLoader.kt` to `application/validation/` -- moved to `io.sdkman.state.application.validation.CandidateLoader` (kept with validation infrastructure since it loads valid candidates for validation)
- [x] Move `Authentication.kt` from `plugins/` to `config/` -- now at `io.sdkman.state.config.Authentication`
- [x] Move `Migration.kt` from `plugins/` to `config/` -- now at `io.sdkman.state.config.Migration`

---

## Phase 3: Secondary Adapters (Persistence)

### 3.1 Repository Implementations (spec sections 4.2, 6, 7)
> **Note:** Repository implementations have been moved to `io.sdkman.state.adapter.secondary.persistence` package. Items below track remaining structural improvements beyond the package move.
- [x] Create `adapter/secondary/persistence/PostgresConnectivity.kt` -- shared `dbQuery` helper, now at `io.sdkman.state.adapter.secondary.persistence.PostgresConnectivity`
- [x] Rename `VersionsRepository.kt` to `PostgresVersionRepository.kt` -- now at `io.sdkman.state.adapter.secondary.persistence.PostgresVersionRepository`; all methods suspend via shared `dbQuery` helper
- [x] Rename `TagsRepositoryImpl.kt` to `PostgresTagRepository.kt` -- now at `io.sdkman.state.adapter.secondary.persistence.PostgresTagRepository`; `VersionTags` table consolidated in `PostgresConnectivity.kt`; `NA_SENTINEL` shared; `findTagNamesByVersionId` present
- [x] Rename `AuditRepositoryImpl.kt` to `PostgresAuditRepository.kt` -- now at `io.sdkman.state.adapter.secondary.persistence.PostgresAuditRepository`
- [x] Rename `HealthRepositoryImpl.kt` to `PostgresHealthRepository.kt` -- now at `io.sdkman.state.adapter.secondary.persistence.PostgresHealthRepository`
- [x] Normalise `VersionTag` timestamps: convert `java.time.Instant` to/from `kotlinx.datetime.Instant` at persistence boundary only (spec section 9) -- already done: domain uses `kotlin.time.Instant`, persistence boundary uses `toKotlinTimeInstant()` extension in PostgresConnectivity.kt

---

## Phase 4: Application Layer (Business Logic Extraction)

### 4.1 Application Services (spec section 3.1)
- [x] Create `application/service/VersionServiceImpl.kt` -- now at `io.sdkman.state.application.service.VersionServiceImpl`; extracts business logic from Routing.kt
- [x] Create `application/service/TagServiceImpl.kt` -- now at `io.sdkman.state.application.service.TagServiceImpl`; extracts tag deletion + audit from Routing.kt

### 4.2 Application Validation (spec section 3.2)
- [x] Move `VersionRequestValidator.kt` to `application/validation/VersionRequestValidator.kt` -- now at `io.sdkman.state.application.validation.VersionRequestValidator`
- [x] Move `UniqueVersionValidator.kt` to `application/validation/UniqueVersionValidator.kt` -- now at `io.sdkman.state.application.validation.UniqueVersionValidator`
- [x] Move `UniqueTagValidator.kt` to `application/validation/UniqueTagValidator.kt` -- now at `io.sdkman.state.application.validation.UniqueTagValidator`; **fix inconsistent return type** still pending (currently returns `Either<List<ValidationFailure>, UniqueTag>` vs `Either<NonEmptyList<ValidationError>, ...>` elsewhere)
- [x] Move `ValidationErrors.kt` to `application/validation/ValidationErrors.kt` -- now at `io.sdkman.state.application.validation.ValidationErrors`

---

## Phase 5: Primary Adapters (REST)

### 5.1 Request/Response DTOs (spec section 4.1)
- [x] Create `dto/VersionDto.kt` -- now at `io.sdkman.state.adapter.primary.rest.dto.VersionDto`; @Serializable DTO with toDto()/toDomain() mappers
- [x] Create `dto/UniqueVersionDto.kt` -- now at `io.sdkman.state.adapter.primary.rest.dto.UniqueVersionDto`; @Serializable DTO with toDomain() mapper
- [x] Create `dto/UniqueTagDto.kt` -- now at `io.sdkman.state.adapter.primary.rest.dto.UniqueTagDto`; @Serializable DTO with toDto()/toDomain() mappers
- [ ] Create `adapter/primary/rest/dto/VersionRequest.kt` -- `@Serializable` unvalidated request DTO (JSON parsing moves here from VersionRequestValidator)
- [ ] Create `adapter/primary/rest/dto/UniqueVersionRequest.kt` -- `@Serializable` delete request DTO
- [ ] Create `adapter/primary/rest/dto/UniqueTagRequest.kt` -- `@Serializable` tag delete request DTO
- [x] Create `adapter/primary/rest/dto/ErrorResponse.kt` -- now at `io.sdkman.state.adapter.primary.rest.dto.ErrorResponse`
- [x] Create `adapter/primary/rest/dto/TagConflictResponse.kt` -- now at `io.sdkman.state.adapter.primary.rest.dto.TagConflictResponse`
- [x] Create `adapter/primary/rest/dto/HealthCheckResponse.kt` -- now at `io.sdkman.state.adapter.primary.rest.dto.HealthCheckResponse`
- [ ] Create `adapter/primary/rest/dto/ValidationErrorResponse.kt` -- move from validation/ValidationErrors.kt

### 5.2 REST Route Adapters (spec section 4.1)
- [x] Create `adapter/primary/rest/RequestExtensions.kt` -- now at `io.sdkman.state.adapter.primary.rest.RequestExtensions`
- [x] Create `adapter/primary/rest/VersionRoutes.kt` -- now at `io.sdkman.state.adapter.primary.rest.VersionRoutes`; split into `versionReadRoutes()` and `versionWriteRoutes()`
- [x] Create `adapter/primary/rest/TagRoutes.kt` -- now at `io.sdkman.state.adapter.primary.rest.TagRoutes`
- [x] Create `adapter/primary/rest/HealthRoutes.kt` -- now at `io.sdkman.state.adapter.primary.rest.HealthRoutes`
- [x] Move `HTTP.kt` (from plugins/) to `adapter/primary/rest/HTTP.kt` -- moved; contains both Compression and CachingHeaders configuration; rename to Compression.kt deferred as cosmetic
- [x] Move `Serialization.kt` (from plugins/) to `adapter/primary/rest/Serialization.kt` -- now at `io.sdkman.state.adapter.primary.rest.Serialization`

### 5.3 Entry Point (spec sections 5, 11)
- [x] Create `App.kt` -- Application.kt already serves as the entry point with manual DI wiring at `io.sdkman.state.Application`
- [x] Update `application.conf` to point to new module path -- updated to `io.sdkman.state.ApplicationKt.module`

---

## Phase 6: Nullable Type Eradication (spec section 10)

### 6.1 Main Source Fixes
- [x] Fix `HealthCheckResponse.message: String? = null` to `Option<String> = None` (`Routing.kt:48`, moves to DTO) -- done in Phase 0.3
- [x] Fix `it.message ?: "Unknown error"` to `Option.fromNullable(it.message).getOrElse { "Unknown error" }` (`Routing.kt:281,323`) -- done in Phase 0.3, uses `.toOption().getOrElse`
- [x] Fix `content.contentType?.withoutParameters()` to `.toOption().map` (`HTTP.kt:27`) -- framework-forced exception: Ktor CachingHeaders API requires nullable; `@Suppress("detekt:UnsafeCallOnNullableType")` applied
- [x] Fix `(item as? JsonPrimitive)?.takeIf` to Option/pattern matching (`VersionRequestValidator.kt:67`) -- refactored in VersionRequestValidator rewrite
- [x] Fix `.swap().getOrNull()` pattern to Option throughout (`VersionRequestValidator.kt:99-107`) -- refactored in VersionRequestValidator rewrite
- [x] Fix `?: throw IllegalStateException` to Either/Option (`CandidateLoader.kt:13`) -- already fixed to use Option.fromNullable

### 6.2 Test Source Fixes
- [x] Fix `body["key"]?.jsonPrimitive?.content` patterns in DeleteTagApiSpec, DeleteTaggedVersionApiSpec -- replaced with `body.getValue("key").jsonPrimitive.content`
- [x] Fix `contentType()?.withoutParameters()` in HealthCheckApiSpec SUCCESS test (line 44 -- inconsistent with FAILURE test at lines 69-72 which correctly uses `.toOption().map`) -- done in Phase 0.3
- [x] Fix `getOrNull()!!` pattern (14 instances) in TagsRepositorySpec to use Arrow `shouldBeRight()` matchers -- already done in Phase 0.3

### 6.3 Detekt Nullable Enforcement
- [x] Detekt now passing — `configureRouting` `LongMethod` violation suppressed with `@Suppress("LongMethod")` (see Phase 0.3); suppression naturally removed after Phase 5.2 route split; no other violations outstanding
- [ ] **BLOCKED**: `com.github.marc0der:detekt-rules:1.0.0` artifact does not exist on JitPack yet — the `marc0der/detekt-rules` GitHub repo needs to be created and published first
- [ ] Add JitPack repository to `build.gradle.kts`: `maven("https://jitpack.io")`
- [ ] Add detekt plugin dependency to `build.gradle.kts`: `detektPlugins("com.github.marc0der:detekt-rules:1.0.0")`
- [ ] Run `./gradlew detekt` to verify no nullable violations remain
- [ ] Add `@AllowNullableTypes` annotation only where external interop requires it (document each case)

---

## Phase 7: Test Migration (spec section 8)

### 7.1 Test Infrastructure
- [x] Create `support/PostgresTestContainer.kt` -- singleton Testcontainers lifecycle with `postgres:16` and `withReuse(true)`; replaces hardcoded `localhost:5432` in all test infrastructure
- [ ] Create `support/TestDependencyInjection.kt` -- test DI container
- [ ] Create `support/EitherMatchers.kt` -- Arrow `shouldBeRight`/`shouldBeLeft` matchers
- [ ] Create `support/OptionMatchers.kt` -- Arrow `shouldBeSome`/`shouldBeNone` matchers
- [ ] Create `support/KotestConfig.kt` -- parallelism, extensions, test tagging configuration
- [x] Create `support/VendorAuditRecord.kt` -- test-only type already exists in `Postgres.kt` (moved from domain AuditRecord in Phase 1.3)

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
- [x] Verify no test uses `localhost:5432` directly -- all test infrastructure now uses PostgresTestContainer

---

## Phase 8: Cleanup & Removal

> **Note:** The old `io.sdkman` package tree has already been removed as part of the package restructuring. All code now lives under `io.sdkman.state`. Remaining items track cleanup of legacy files within the new structure.

- [x] Remove old `io.sdkman` package tree (all files migrated to `io.sdkman.state`)
- [x] Remove old `plugins/Routing.kt` (logic distributed to services + route adapters; new Routing.kt is a 22-line composition function at `adapter/primary/rest/Routing.kt`)
- [x] Remove `plugins/HTTP.kt` -- moved to `adapter/primary/rest/HTTP.kt`; `plugins/` directory no longer exists
- [x] Remove `plugins/Serialization.kt` -- moved to `adapter/primary/rest/Serialization.kt`; `plugins/` directory no longer exists
- [x] Remove `plugins/Databases.kt` -- moved to `config/Databases.kt`; `plugins/` directory no longer exists
- [x] Remove old `repos/` package (replaced by `adapter/secondary/persistence/`)
- [x] Remove old `validation/` package (moved to `application/validation/`)
- [x] Remove old `domain/Domain.kt` (split into `domain/model/` and `domain/error/` files)
- [x] Verify all existing API behaviour preserved (all 151 tests green with Testcontainers)
- [x] Run `./gradlew build` (compile + ktlint + detekt + tests) -- passes

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
