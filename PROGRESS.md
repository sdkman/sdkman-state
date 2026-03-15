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
