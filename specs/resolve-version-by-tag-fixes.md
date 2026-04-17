# Resolve Version by Tag — Fixes

**Status:** Post-review fixes for PR #41
**Depends on:** `specs/resolve-version-by-tag.md`
**Service:** sdkman-state (Kotlin/Ktor)

Addresses four issues raised during code review of the `GET /versions/{candidate}/tags/{tag}` endpoint plus one stylistic preference around idiomatic Arrow usage.

Consider the following rules during execution of the tasks:
- .claude/rules/hexagonal-architecture.md
- .claude/rules/kotlin.md
- .claude/rules/kotest.md
- .claude/rules/quality-gates.md

---

### Task 1: Consolidate tag resolution into a single repository port

- [X] Replace the `findVersionIdByTag` + `findByVersionId` composition with a single `findByTag` port

**Prompt**: The current implementation resolves a tag in two steps: `VersionRepository.findVersionIdByTag` returns an `Option<Int>`, and `VersionRepository.findByVersionId` then fetches the full `Version`. `VersionServiceImpl.resolveByTag` glues the two together inside an `either { ... }` block with a nested `fold`. Both ports are used *only* by `resolveByTag` — they are speculative abstractions that leak a persistence concept (surrogate integer IDs) into the application layer.

Replace both ports with a single method on `VersionRepository`:

```kotlin
suspend fun findByTag(
    candidate: String,
    tag: String,
    distribution: Option<Distribution>,
    platform: Platform,
): Either<DatabaseFailure, Option<Version>>
```

Implement `findByTag` in `PostgresVersionRepository` with two queries inside a single `dbQuery` transaction:

1. A JOIN between `VersionTagsTable` and `VersionsTable` filtered by `(candidate, tag, distribution-or-NA_SENTINEL, platform)` to fetch the matching version row (at most one — the unique index guarantees it).
2. A follow-up fetch of all tag names for that version's id, so the response body includes every tag on the resolved version, not just the one the client searched on.

Using a single transaction makes the pair atomic — the "version id found but version missing" race case becomes unrepresentable, so the warning-log telemetry idea from the review is no longer needed.

Remove `findVersionIdByTag` and `findByVersionId` from the port and the adapter. Simplify `VersionServiceImpl.resolveByTag` to a thin wrapper:

```kotlin
override suspend fun resolveByTag(...) =
    versionsRepo.findByTag(...).mapLeft { DomainError.DatabaseError(it) }
```

The `either { ... }` block and the `arrow.core.None` import can go with it.

Update `PostgresVersionRepositoryIntegrationSpec`: collapse the existing `findVersionIdByTag` and `findByVersionId` contexts into a single `findByTag` context covering — tag resolves to a version with its full tag list; tag not found returns `None`; resolution is scoped to distribution; resolution is scoped to platform.

Update `VersionServiceUnitSpec.resolveByTag`: mock `versionsRepo.findByTag` directly. Drop the "version id found but version missing" test — it no longer represents a reachable state. Keep the "returns version on success", "returns None when tag does not exist", and "returns DatabaseError when repo fails" tests.

`ResolveVersionByTagAcceptanceSpec` must continue to pass unchanged — the user-visible behaviour is identical.

**Files affected**:
- `src/main/kotlin/io/sdkman/state/domain/repository/VersionRepository.kt`
- `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresVersionRepository.kt`
- `src/main/kotlin/io/sdkman/state/application/service/VersionServiceImpl.kt`
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresVersionRepositoryIntegrationSpec.kt`
- `src/test/kotlin/io/sdkman/state/application/service/VersionServiceUnitSpec.kt`

---

### Task 2: Align parameter ordering with `findUnique`

- [X] Reorder the last two parameters of `resolveByTag` / `findByTag` to match `findUnique`

**Prompt**: The existing `VersionRepository.findUnique` and `VersionService.findUnique` use the parameter order `(candidate, version, platform, distribution)`. The tag-resolution API — introduced on this branch — uses `(candidate, tag, distribution, platform)`. Swapping the last two between otherwise-parallel operations is a tab-completion foot-gun.

Align the tag-resolution API with the established convention. After Task 1, there are two signatures to change:

1. `VersionRepository.findByTag(candidate, tag, distribution, platform)` → `(candidate, tag, platform, distribution)`.
2. `VersionService.resolveByTag(candidate, tag, distribution, platform)` → `(candidate, tag, platform, distribution)`.

Update the adapter implementation, the service implementation, the route call-site in `resolveVersionByTagRoute`, and every test that references these methods (unit, integration, acceptance — though the acceptance tests invoke the HTTP API and won't change).

**Files affected**:
- `src/main/kotlin/io/sdkman/state/domain/repository/VersionRepository.kt`
- `src/main/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresVersionRepository.kt`
- `src/main/kotlin/io/sdkman/state/domain/service/VersionService.kt`
- `src/main/kotlin/io/sdkman/state/application/service/VersionServiceImpl.kt`
- `src/main/kotlin/io/sdkman/state/adapter/primary/rest/VersionRoutes.kt`
- `src/test/kotlin/io/sdkman/state/application/service/VersionServiceUnitSpec.kt`
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresVersionRepositoryIntegrationSpec.kt`

---

### Task 3: Add acceptance tests for 400 on blank candidate or tag

- [X] Cover the blank-guard branch of `resolveVersionByTagRoute` with acceptance tests

**Prompt**: `specs/resolve-version-by-tag.md` specifies that the endpoint returns `400 Bad Request` when either the `candidate` or `tag` path segment is missing or blank. The route handler implements this via `option { ... }.getOrElse { call.respond(HttpStatusCode.BadRequest) }`, filtering on `isNotBlank()`. No acceptance test currently exercises this branch — every existing test uses a populated, non-blank value.

Add two acceptance tests to `ResolveVersionByTagAcceptanceSpec`:

1. **Blank candidate** — `client.get("/versions/%20/tags/lts")` (URL-encoded space) must return `HttpStatusCode.BadRequest`. A blank path segment bypasses Ktor's path-matcher requirement (which rejects empty segments) while still hitting the `isNotBlank` guard.
2. **Blank tag** — `client.get("/versions/java/tags/%20")` must return `HttpStatusCode.BadRequest`.

Neither test needs a seeded database — the request never reaches the service. Use `withCleanDatabase { withTestApplication { ... } }` for consistency with the other tests in the file.

**Files affected**:
- `src/test/kotlin/io/sdkman/state/acceptance/ResolveVersionByTagAcceptanceSpec.kt`

---

### Task 4: Use `none()` instead of `None` in tag-resolution code

- [ ] Replace `arrow.core.None` with `arrow.core.none()` within files touched by the resolve-by-tag feature

**Prompt**: The code introduced for this feature uses `arrow.core.None` directly (the `Option<Nothing>` singleton). The more idiomatic Arrow style uses the `none<A>()` builder function, which carries a proper type parameter and reads as a verb alongside `some()`.

Within the files listed below — and **only** within those files — replace every `None` expression with `none()`, and swap the `import arrow.core.None` for `import arrow.core.none` where needed. Type arguments to `none()` are usually inferred from context; add an explicit type parameter only if the compiler requires it. Do not touch `None` usages elsewhere in the codebase — a wider refactor is out of scope for this spec.

After Task 1, `VersionServiceImpl.resolveByTag` no longer constructs `None` itself (the `either { ... fold(...) }` block is gone), so the main file may need no change. The test files still carry `None` references in setup and assertions (e.g. `Either.Right(None)`, `distribution = None`, `result shouldBe None`) — these are the primary targets.

**Files affected**:
- `src/main/kotlin/io/sdkman/state/application/service/VersionServiceImpl.kt` (only if a `None` reference survives Task 1)
- `src/test/kotlin/io/sdkman/state/acceptance/ResolveVersionByTagAcceptanceSpec.kt`
- `src/test/kotlin/io/sdkman/state/adapter/secondary/persistence/PostgresVersionRepositoryIntegrationSpec.kt` (only within the `findByTag` context)
- `src/test/kotlin/io/sdkman/state/application/service/VersionServiceUnitSpec.kt` (only within the `resolveByTag` context)

---

## Execution plan workflow

The following workflow applies when executing this TODO list:
- Execute only the **SPECIFIED TASK**
- Implement the task in **THE SIMPLEST WAY POSSIBLE**
- Run the tests, format and perform static analysis on the code:
    - ./gradlew ktlintFormat
    - ./gradlew test
    - ./gradlew detekt
- **Ask me to review the task once you have completed and then WAIT FOR ME**
- Mark the TODO item as complete with [X]
- Commit the change to Git when I've approved and/or amended the code
- **STOP and await further instructions**
