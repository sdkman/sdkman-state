# TODO

Consider the following rules during execution of the tasks:
- rules/kotlin.md
- rules/kotest.md
- rules/hexagonal-architecture.md

### Task 1: Improve Error Handling and Pattern Matching in Routing

- [X] Replace conditional logic with pattern matching and implement proper error handling

**Prompt**: Update the routing logic in `src/main/kotlin/io/sdkman/plugins/Routing.kt` to address three TODO comments: (1) Replace the conditional logic at line 71 with proper pattern matching using `when`, (2) Add better error handling with `Either` type at line 83 and include appropriate tests, and (3) Handle validation errors for UniqueVersion at line 85. The implementation should follow functional programming principles using Arrow's Either type and provide appropriate HTTP error responses.

**Files affected**:
- `src/main/kotlin/io/sdkman/plugins/Routing.kt`

### Task 2: Refactor Validation Logic to Use Functional Programming

- [X] Improve ValidationLogic.kt to follow functional programming best practices

**Prompt**: Refactor the validation logic in `src/main/kotlin/io/sdkman/validation/ValidationLogic.kt` to address four TODO comments: (1) Consider renaming the object at line 12 to something more descriptive, (2) Replace `fold` with `map` and `getOrElse` at line 16 as per Kotlin rules, (3) Never raise errors but return `Either.left()` directly at line 21, and (4) Return `Either.right()` for success cases at line 24. Ensure the refactored code follows the functional programming guidelines in rules/kotlin.md.

**Files affected**:
- `src/main/kotlin/io/sdkman/validation/ValidationLogic.kt`

### Task 3: Implement Strict Vendor Suffix Validation

- [ ] Add validation to reject all vendor suffixes regardless if they match the actual vendor

**Prompt**: Implement strict vendor suffix validation logic to address TODO comments in `src/test/kotlin/io/sdkman/validation/ValidationLogicSpec.kt` at lines 56, 67, 71, and 82. The validation should: (1) Reject ANY versions with vendor suffixes, (2) Reject all uppercase vendors, (3) Return appropriate `Either.left()` errors for validation failures. Update the corresponding test in `src/test/kotlin/io/sdkman/PostVersionApiSpec.kt` at line 107 to reflect the new strict validation behavior.

**Files affected**:
- `src/main/kotlin/io/sdkman/validation/ValidationLogic.kt`
- `src/test/kotlin/io/sdkman/validation/ValidationLogicSpec.kt`
- `src/test/kotlin/io/sdkman/PostVersionApiSpec.kt`

### Task 4: Add Missing Test Coverage for Version API

- [ ] Implement missing test cases for platform-specific versions and no-vendor scenarios

**Prompt**: Add comprehensive test coverage by implementing the missing test cases identified in TODO comments: (1) Add test for platform-specific versions in `src/test/kotlin/io/sdkman/GetVersionApiSpec.kt` at line 18, (2) Add test for versions with NO vendor at line 19 in the same file, (3) Add test for deleting versions with platform and NO vendor in `src/test/kotlin/io/sdkman/DeleteVersionApiSpec.kt` at line 17, and (4) Add test for UniqueVersion validation failure at line 18. Follow Kotest ShouldSpec style and existing test patterns.

**Files affected**:
- `src/test/kotlin/io/sdkman/GetVersionApiSpec.kt`
- `src/test/kotlin/io/sdkman/DeleteVersionApiSpec.kt`

### Task 5: Optimize Database Schema Migration

- [X] Replace incremental migration with complete table recreation for vendor nullable change

**Prompt**: Address the TODO comment in `src/main/resources/db/migration/V5__make_vendor_nullable.sql` at line 1. Since the table doesn't hold valuable data yet, replace the current migration approach with a complete table drop and recreation using the new schema. This will be cleaner than the incremental migration approach and ensure optimal schema structure.

**Files affected**:
- `src/main/resources/db/migration/V5__make_vendor_nullable.sql`

## Execution plan workflow

The following workflow applies when executing this TODO list:
- Execute one task at a time
- Implement the task in **THE SIMPLEST WAY POSSIBLE**
- Run the tests, format and perform static analysis on the code:
    - ./gradlew ktlintFormat
    - ./gradlew test
    - ./gradlew detekt
- **Ask me to review the task once you have completed and then WAIT FOR ME**
- Mark the TODO item as complete with [X]
- Commit the change to Git when I've approved and/or amended the code
- HALT execution
