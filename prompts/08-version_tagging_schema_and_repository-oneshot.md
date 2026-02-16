# Version Tagging — Database Schema and Repository Layer

Introduce the foundational infrastructure for version tagging in the SDKMAN State API. This slice creates the `version_tags` database table and the repository layer for tag CRUD operations. No API changes are included — this is purely internal plumbing that subsequent slices will build upon.

See `specs/version-tagging.md` for the full feature specification.

## Requirements

- Create a Flyway migration for the `version_tags` table with a unique constraint on `(candidate, tag, distribution, platform)`
- The `version_id` foreign key must use `ON DELETE RESTRICT` to prevent deletion of tagged versions at the database level
- Create a `TagsRepository` interface in the domain layer with methods for: reading tags by version ID, reading a version by tag scope, creating/replacing tags for a version, deleting a tag by scope, and checking if a version has tags
- Implement the repository using Jetbrains Exposed ORM, consistent with the existing `VersionsRepository`
- All repository methods must use Arrow `Either<DatabaseFailure, T>` for error handling
- The `distribution` column must be `NOT NULL` and use the sentinel value `NA` for candidates without distributions (e.g., Gradle). The repository translates between `Option<Distribution>` and the sentinel at the boundary

## Rules

**Before implementing, you MUST read and internalize:**
- rules/kotlin.md
- rules/domain-driven-design.md
- rules/kotest.md

**If this prompt conflicts with the rules, THE RULES WIN.** Update this prompt to align with the rules.

## Domain

```kotlin
/**
 * Represents a tag assignment — a named alias pointing to a specific version
 * within a (candidate, distribution, platform) scope.
 */
data class VersionTag(
    val id: Int = 0,
    val candidate: String,
    val tag: String,
    val distribution: Option<Distribution>,
    val platform: Platform,
    val versionId: Int,
    val createdAt: Instant = Instant.now(),
    val lastUpdatedAt: Instant = Instant.now()
)

/**
 * Identifies a tag within its uniqueness scope.
 * Used for tag resolution and deletion.
 */
@Serializable
data class UniqueTag(
    val candidate: String,
    val tag: String,
    val distribution: Option<Distribution> = None,
    val platform: Platform
)

/**
 * Repository interface for tag operations.
 */
interface TagsRepository {
    /** Find all tags for a given version ID */
    suspend fun findTagsByVersionId(versionId: Int): Either<DatabaseFailure, List<VersionTag>>

    /** Find the version ID that a tag points to within its scope */
    suspend fun findVersionIdByTag(
        candidate: String,
        tag: String,
        distribution: Option<Distribution>,
        platform: Platform
    ): Either<DatabaseFailure, Option<Int>>

    /** Declaratively replace all tags for a version within its scope */
    suspend fun replaceTags(
        versionId: Int,
        candidate: String,
        distribution: Option<Distribution>,
        platform: Platform,
        tags: List<String>
    ): Either<DatabaseFailure, Unit>

    /** Remove a specific tag by its unique scope */
    suspend fun deleteTag(uniqueTag: UniqueTag): Either<DatabaseFailure, Int>

    /** Check if a version has any tags pointing to it */
    suspend fun hasTagsForVersion(versionId: Int): Either<DatabaseFailure, Boolean>

    /** Find all tag names for a given version ID (convenience for response building) */
    suspend fun findTagNamesByVersionId(versionId: Int): Either<DatabaseFailure, List<String>>
}
```

## Extra Considerations

### Mutual Exclusivity
The `replaceTags` method must enforce mutual exclusivity: when assigning a tag to a version, it must first remove that tag from any other version in the same `(candidate, distribution, platform)` scope. The `UNIQUE (candidate, tag, distribution, platform)` constraint enforces this at the database level, but the repository should handle the upsert logic to avoid constraint violations.

### Distribution Sentinel Value
The `version_tags.distribution` column is `NOT NULL` and uses the sentinel value `NA` (not applicable) for candidates without distributions (e.g., Gradle, Maven). This avoids PostgreSQL's `NULL != NULL` behaviour in unique constraints, making `UNIQUE (candidate, tag, distribution, platform)` work correctly without `COALESCE` tricks. The repository layer translates between `Option<Distribution>` in the domain and the `NA` sentinel at the database boundary.

### Transaction Safety
The `replaceTags` operation involves multiple steps (delete old tags, remove tags from other versions, insert new tags). These must all happen within a single database transaction to maintain consistency.

### Exposed ORM Table Definition
```kotlin
private object VersionTags : IntIdTable("version_tags") {
    val candidate = text("candidate")
    val tag = text("tag")
    val distribution = text("distribution")  // Uses "NA" sentinel for candidates without distributions
    val platform = text("platform")
    val versionId = integer("version_id").references(Versions.id)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val lastUpdatedAt = timestamp("last_updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(candidate, tag, distribution, platform)
    }
}
```

## Testing Considerations

All tests should use Kotest's ShouldSpec style and real PostgreSQL via the existing test support infrastructure.

### Repository Integration Tests

- **Create tags for a version**: insert a version, call `replaceTags` with `["latest", "27"]`, verify tags are stored
- **Replace tags declaratively**: set tags to `["latest", "27"]`, then `["latest", "28"]`, verify `27` is gone and `28` is present
- **Mutual exclusivity**: create two versions, tag the first as `latest`, then tag the second as `latest`, verify the first no longer has the tag
- **Find tags by version ID**: insert tags, query by version ID, verify correct tags returned
- **Find version by tag**: insert a tagged version, resolve the tag, verify correct version ID returned
- **Delete a tag**: create a tag, delete it, verify it's gone
- **Has tags check**: verify `hasTagsForVersion` returns true/false correctly
- **Empty tags clears all**: call `replaceTags` with empty list, verify all tags removed
- **NA distribution handling**: verify tags work correctly for candidates without distributions (distribution stored as `NA`)
- **NA distribution uniqueness**: verify two tags with `NA` distribution in the same scope correctly conflict via the unique constraint

### Test Support Utilities
Add helper methods to the existing test support infrastructure for:
- Inserting tags directly into the database
- Querying tags from the database for assertions
- Cleaning the `version_tags` table between tests

## Implementation Notes

### Order of Implementation
1. Read and internalize rules files
2. Create Flyway migration `V12__create_version_tags_table.sql`
3. Define `VersionTag` and `UniqueTag` domain types in `Domain.kt`
4. Create `TagsRepository` interface in domain layer
5. Implement `TagsRepositoryImpl` using Jetbrains Exposed
6. Add test support utilities for tag operations
7. Write repository integration tests

### Database Migration

```sql
CREATE TABLE version_tags (
    id SERIAL PRIMARY KEY,
    candidate TEXT NOT NULL,
    tag TEXT NOT NULL,
    distribution TEXT NOT NULL DEFAULT 'NA',
    platform TEXT NOT NULL,
    version_id INTEGER NOT NULL REFERENCES versions(id) ON DELETE RESTRICT,
    created_at TIMESTAMP DEFAULT now(),
    last_updated_at TIMESTAMP DEFAULT now(),
    UNIQUE (candidate, tag, distribution, platform)
);

CREATE INDEX idx_version_tags_version_id ON version_tags(version_id);
CREATE INDEX idx_version_tags_candidate ON version_tags(candidate);
CREATE INDEX idx_version_tags_lookup ON version_tags(candidate, tag, platform);
```

Note: The unique index uses `COALESCE(distribution, '')` to ensure NULL distributions are treated as equal for uniqueness purposes.

### Code Organization
- Domain types: `io.sdkman.domain.VersionTag`, `io.sdkman.domain.UniqueTag` in `Domain.kt`
- Repository interface: `io.sdkman.domain.TagsRepository` in `Domain.kt`
- Repository implementation: `io.sdkman.repos.TagsRepositoryImpl`
- Test support: extend `src/test/kotlin/io/sdkman/support/Postgres.kt`
- Test spec: `src/test/kotlin/io/sdkman/TagsRepositorySpec.kt`

## Specification by Example

### Creating tags for a version
```
Given a version exists: java 27.0.2 (TEMURIN, LINUX_X64) with id=1
When replaceTags(versionId=1, candidate="java", distribution=Some(TEMURIN), platform=LINUX_X64, tags=["latest", "27"])
Then version_tags table contains:
  | candidate | tag    | distribution | platform  | version_id |
  | java      | latest | TEMURIN      | LINUX_X64 | 1          |
  | java      | 27     | TEMURIN      | LINUX_X64 | 1          |
```

### Mutual exclusivity on tag reassignment
```
Given version id=1: java 27.0.1 (TEMURIN, LINUX_X64) tagged as ["latest"]
And version id=2: java 27.0.2 (TEMURIN, LINUX_X64) exists with no tags
When replaceTags(versionId=2, candidate="java", distribution=Some(TEMURIN), platform=LINUX_X64, tags=["latest"])
Then version_tags table contains:
  | candidate | tag    | distribution | platform  | version_id |
  | java      | latest | TEMURIN      | LINUX_X64 | 2          |
And version id=1 has no tags
```

### Declarative replacement
```
Given version id=1: java 27.0.2 (TEMURIN, LINUX_X64) tagged as ["latest", "27", "lts"]
When replaceTags(versionId=1, candidate="java", distribution=Some(TEMURIN), platform=LINUX_X64, tags=["latest", "27"])
Then version id=1 tags are exactly ["latest", "27"]
And "lts" tag no longer exists
```

### NA distribution handling
```
Given version id=1: gradle 8.12 (UNIVERSAL, no distribution) tagged as ["latest"]
  → stored as distribution="NA" in version_tags
And version id=2: gradle 8.13 (UNIVERSAL, no distribution) exists with no tags
When replaceTags(versionId=2, candidate="gradle", distribution=None, platform=UNIVERSAL, tags=["latest"])
  → repository translates None to "NA" at the boundary
Then version id=2 has tag "latest" (distribution="NA" in DB)
And version id=1 has no tags
```

## Verification

- [ ] Flyway migration `V12__create_version_tags_table.sql` runs successfully on clean database
- [ ] `version_tags` table has correct schema (id, candidate, tag, distribution, platform, version_id, timestamps)
- [ ] `UNIQUE (candidate, tag, distribution, platform)` constraint prevents duplicate tags in scope
- [ ] `ON DELETE RESTRICT` prevents deleting a version that has tags
- [ ] `VersionTag` and `UniqueTag` domain types exist in `Domain.kt`
- [ ] `TagsRepository` interface defined with all required methods
- [ ] `TagsRepositoryImpl` implements all methods using Exposed ORM
- [ ] All repository methods return `Either<DatabaseFailure, T>`
- [ ] `replaceTags` enforces mutual exclusivity within scope
- [ ] `replaceTags` operates within a single transaction
- [ ] `NA` sentinel is used for candidates without distributions — no NULLs in the distribution column
- [ ] Repository correctly translates `Option<Distribution>` to/from `NA` sentinel at the boundary
- [ ] Test support utilities added for tag operations
- [ ] All repository integration tests pass
- [ ] No nullable types used — follows Arrow Option pattern
- [ ] Code formatted and ktlint clean
