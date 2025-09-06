# TODO

Consider the following rules during execution of the tasks:
- rules/kotlin.md
- rules/kotest.md

### Task 1: Implement Dependency Injection for HealthRepository

- [X] Refactor dependency injection to properly inject HealthRepository interface

**Prompt**: Refactor the dependency injection pattern for HealthRepository to follow the same pattern as VersionsRepository. Address the TODO comments in `src/main/kotlin/io/sdkman/Application.kt` at line 20, `src/main/kotlin/io/sdkman/plugins/Routing.kt` at line 32, and line 34. The changes should: (1) Instantiate the HealthRepository in the Application.kt file and pass it to configureRouting function, (2) Update the configureRouting function signature to accept HealthRepository by interface rather than instantiating HealthRepositoryImpl directly, and (3) Remove the direct instantiation of HealthRepositoryImpl from the routing configuration. Follow the existing dependency injection pattern used for VersionsRepository.

**Files affected**:
- `src/main/kotlin/io/sdkman/Application.kt`
- `src/main/kotlin/io/sdkman/plugins/Routing.kt`

### Task 2: Optimize HealthRepository Database Query

- [X] Replace versions table usage with proper SELECT 1 query for health checks

**Prompt**: Optimize the HealthRepository implementation to address the TODO comment in `src/main/kotlin/io/sdkman/repos/HealthRepository.kt` at line 13. Replace the current approach that uses the versions table with a proper `SELECT 1` query using Exposed SQL strings as documented at https://www.jetbrains.com/help/exposed/working-with-sql-strings.html. The health check should not depend on any application tables and should use a simple database connectivity test instead.

**Files affected**:
- `src/main/kotlin/io/sdkman/repos/HealthRepository.kt`

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
- Move on to the next task
