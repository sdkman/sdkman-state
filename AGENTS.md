# AGENTS.md

Operational guardrails for the sdkman-state service. This file is loaded every loop iteration — keep it concise and operational only. Status updates and progress notes belong in PROGRESS.md.

## Build & Run

- **Run service:** `./gradlew run` (starts Ktor on port 8080)
- **Run with Docker:** See README.md for PostgreSQL setup

## Validation

Run after every implementation to get immediate feedback:

- **Full chain:** `./gradlew check` (compile → detekt → ktlintCheck → test)
- **Tests only:** `./gradlew test`
- **Lint only:** `./gradlew ktlintCheck`
- **Lint auto-fix:** `./gradlew ktlintFormat`
- **Static analysis:** `./gradlew detekt` (config in `detekt.yml`)

## Commit

Use the `/commit` skill for all commits. This ensures conventional commit format.

## Rules

Project rules are defined in `.claude/rules/` — study these before making changes.

## Key Conventions

- Kotlin with Ktor, Exposed ORM, Arrow for functional types
- Kotest for assertions, JUnit Platform as test runner
- Flyway for database migrations
- kotlinx-serialization for JSON
