# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SDKMAN State API is a Kotlin-based Ktor application that manages SDKMAN candidate and version state through a JSON API. It serves as both a backend for native Rust components and an admin API for the datastore, supporting GET, POST, PATCH, and DELETE operations on candidates and versions.

## MCP Integration

This project uses the Gradle MCP server for enhanced Gradle operations. Always use the gradle-mcp when available for any Gradle-related tasks including:
- Building and testing
- Dependency management
- Task execution
- Project analysis

## Build and Development Commands

### Build and Test
- **Build**: `./gradlew build` - Compiles the project and runs all checks
- **Test**: `./gradlew test` - Runs all Kotest specifications  
- **Run locally**: `./gradlew run` - Starts the server on port 8080
- **Clean**: `./gradlew clean` - Removes build artifacts

### Docker Operations
- **Build Docker image**: `./gradlew buildImage` - Creates Docker image with git SHA tag
- **Run with Docker**: The image is tagged as `registry.digitalocean.com/sdkman/sdkman-state:${GIT_SHA}`

### Database Setup
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

## Architecture and Key Components

### Core Domain Model (`src/main/kotlin/io/sdkman/domain/Domain.kt`)
- **Version**: Main entity representing a software version with candidate, version, vendor, platform, URL, visibility, and checksums
- **UniqueVersion**: Identifier for deletion operations
- **Platform**: Enum supporting Linux (x32/x64/ARM variants), macOS (x64/ARM64), Windows x64, and Universal platforms

### Application Structure
- **Application.kt**: Main entry point configuring Ktor modules (database, HTTP, authentication, routing)
- **plugins/**: Ktor configuration modules
  - `Routing.kt`: API endpoints with authentication for POST/DELETE operations
  - `Databases.kt`: PostgreSQL connection and Flyway migrations
  - `Authentication.kt`: Basic authentication for write operations
- **repos/VersionsRepository.kt**: Data access layer using Jetbrains Exposed ORM

### API Design
- **GET /versions/{candidate}**: List versions with optional platform/vendor/visibility filtering
- **GET /versions/{candidate}/{version}**: Get specific version by candidate/version/platform/vendor
- **POST /versions**: Create new version (requires authentication)
- **DELETE /versions**: Remove version by unique identifier (requires authentication)

### Database Schema
Uses Flyway migrations in `src/main/resources/db/migration/`:
- Versions table with candidate, version, vendor, platform, URL, visibility, and hash columns
- Audit table for tracking changes

### Testing Strategy
- **Kotest** framework with `ShouldSpec` style
- Test support utilities in `src/test/kotlin/io/sdkman/support/`
- Integration tests using test application and clean database setup
- Focus on API behavior testing rather than unit tests

### Key Technologies
- **Ktor**: Web framework with Netty engine
- **Kotlin Serialization**: JSON handling with Arrow Option types
- **Jetbrains Exposed**: SQL ORM
- **Arrow**: Functional programming utilities (Option, Either)
- **PostgreSQL**: Primary database
- **Flyway**: Database migrations
- **Kotest**: Testing framework

## Configuration
- **application.conf**: Ktor server and database configuration with environment variable overrides
- **openapi/documentation.yaml**: API specification
- Environment variables supported: `PORT`, `DATABASE_HOST/PORT/USERNAME/PASSWORD`, `BASIC_AUTH_USERNAME/PASSWORD`