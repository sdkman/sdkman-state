# CLAUDE.md

Operational guardrails for the sdkman-state service. Keep this concise and operational — architecture and domain details belong in the code.

## Project Overview

SDKMAN State API is a Kotlin-based Ktor application that manages SDKMAN candidate and version state through a JSON API.

## MCP Integration

This project uses the Gradle MCP server for enhanced Gradle operations. Always use the gradle-mcp when available for building, testing, dependency management, and task execution.

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
- **Build Docker image:** `./gradlew buildImage`

## Database Setup

The application requires PostgreSQL. For development:
```bash
docker run --restart=always \
    --name postgres \
    -p 5432:5432 \
    -e POSTGRES_USER=postgres \
    -e POSTGRES_PASSWORD=postgres \
    -e POSTGRES_DB=sdkman \
    -d postgres
```

## Commit

Use the `/commit` skill for all commits. This ensures conventional commit format.

## Rules

Project rules are defined in `.claude/rules/` — study these before making changes.

## Key Conventions

- Kotlin with Ktor, Exposed ORM, Arrow for functional types
- Kotest for assertions, JUnit Platform as test runner
- Flyway for database migrations
- kotlinx-serialization for JSON
