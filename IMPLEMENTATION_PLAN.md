# SDKMAN State Modernisation -- Implementation Plan

> **Goal:** Refactor sdkman-state to hexagonal architecture per `specs/modernisation.md`
> **Branch:** `corrective_actions`
> **Status:** Complete -- All phases (0-9) complete
> **Strategy:** Incremental migration (new structure alongside existing, then remove old)

---

## Completed Phases (Summary)

All phases below are fully implemented and verified. See `PROGRESS.md` for the detailed chronological log of each change.

### Phase 0: Prerequisites (Build & Dependency Fixes) -- COMPLETE
- Ktor version aligned (plugin + runtime at 3.3.3)
- Test dependencies added (Testcontainers, MockK, Kotest extensions)
- 14 existing code defects fixed (duplicate helpers, nullable types, dead code, misleading names, business logic in wrong layers)

### Phase 1: Domain Layer (Foundation) -- COMPLETE
- Package restructured from `io.sdkman` to `io.sdkman.state` with hexagonal subdirectories
- Domain models split into per-type files, `@Serializable` removed (RULE-004)
- Repository port interfaces created in `domain/repository/`
- Service interfaces created in `domain/service/`
- Error hierarchy: `DatabaseFailure` (sealed class), `DomainError` (sealed interface)

### Phase 2: Configuration & Infrastructure -- COMPLETE
- `AppConfig` interface with `DefaultAppConfig` implementation (flat properties)
- `ConfigExtensions.kt` with Arrow-based config helpers
- `CandidateLoader`, `Authentication`, `Migration` moved to `config/` package

### Phase 3: Secondary Adapters (Persistence) -- COMPLETE
- All repositories renamed to `Postgres*` convention
- Shared `dbQuery` helper in `PostgresConnectivity.kt`
- `kotlin.time.Instant` used in domain; `toKotlinTimeInstant()` at persistence boundary

### Phase 4: Application Layer (Business Logic Extraction) -- COMPLETE
- `VersionServiceImpl` and `TagServiceImpl` extract orchestration from routing
- Validators moved to `application/validation/`

### Phase 5: Primary Adapters (REST) -- COMPLETE
- DTOs created in `adapter/primary/rest/dto/`
- Routes split into `VersionRoutes`, `TagRoutes`, `HealthRoutes`
- `Routing.kt` reduced to 22-line composition function

### Phase 6: Nullable Type Eradication -- COMPLETE
- All nullable types replaced with `Option<A>` or non-nullable alternatives
- Custom detekt rule `NoNullableTypes` enforced via `com.github.marc0der:detekt-rules:1.0.0`

### Phase 7: Test Migration -- COMPLETE
- Testcontainers (`postgres:16`) replaces hardcoded `localhost:5432`
- Three-layer test structure: acceptance (12 specs), integration (4 specs), unit (5 specs)
- All tests tagged: `@Tags("acceptance")`, `@Tags("integration")`, or untagged (unit)
- Arrow test matchers (`EitherMatchers.kt`, `OptionMatchers.kt`) for expressive assertions

### Phase 8: Cleanup & Removal -- COMPLETE
- Old `io.sdkman` package tree fully removed
- All 151 tests green with Testcontainers
- Full build passes (compile + ktlint + detekt + test)

### Phase 9: Spec Alignment (Post-Migration Corrections) -- COMPLETE
- Table definitions co-located per repository (`VersionsTable`, `VersionTagsTable`, `AuditTable`)
- Repository interfaces aligned: `findByCandidate`/`findUnique`, `Either`-wrapped returns
- Service interfaces aligned: `VersionService` and `TagService` per spec
- `VersionServiceImpl` depends on `TagService` (not `TagRepository`) for proper layering

---

## Post-Migration Corrective Actions

### Cross-Adapter Dependency Fix -- COMPLETE
- `PostgresAuditRepository` (secondary adapter) was importing `toDto()` from the primary adapter (REST DTO layer), violating hexagonal RULE-003
- Created `AuditSerialization.kt` in the persistence package with audit-specific `@Serializable` types (`AuditVersionData`, `AuditTagData`)
- Each adapter now owns its own serialization concern -- no cross-adapter dependencies remain

---

## Architecture Reference

```
io.sdkman.state/
├── domain/          -- models, errors, ports (repository + service interfaces)
├── application/     -- service implementations, validation
├── adapter/
│   ├── primary/rest/    -- Ktor routes, DTOs
│   └── secondary/persistence/  -- Exposed ORM repositories
└── config/          -- AppConfig, Authentication, Migration, CandidateLoader
```

## Notes

- **No new features** -- this was purely structural (spec: "All existing API behaviour must be preserved")
- **ktlint before commit** -- `./gradlew ktlintFormat` per project convention
- **Single spec** -- `specs/modernisation.md` comprehensively covers all planned changes
