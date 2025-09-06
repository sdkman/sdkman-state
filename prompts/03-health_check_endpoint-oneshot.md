# Health Check Endpoint

Implement a deep health check endpoint that validates the service's connectivity to its PostgreSQL database. This endpoint will be used by monitoring systems and load balancers to determine service health and availability, ensuring the service can respond to requests and access its data layer.

## Requirements

- Health check endpoint accessible at `GET /meta/health`
- Execute a `SELECT 1` query against the PostgreSQL database to verify connectivity
- Return HTTP 200 status code when database connection is successful
- Return HTTP 503 status code when database connection fails
- Response body must be JSON format with a `status` field containing enum values `SUCCESS` or `FAILURE`
- Include optional `message` field in response body for failure cases containing error details
- Endpoint should not require authentication (publicly accessible for monitoring)

## Rules

- rules/domain-driven-design.md
- rules/hexagonal-architecture.md
- rules/kotlin.md
- rules/kotest.md

## Domain

```kotlin
// Database failure domain model
data class DatabaseFailure(
    override val message: String, 
    override val cause: Throwable
): Throwable()

enum class HealthStatus {
    SUCCESS,
    FAILURE
}

// Repository interface for health checks
interface HealthRepository {
    suspend fun checkDatabaseConnection(): Either<DatabaseFailure, Unit>
}
```

Health check response DTO (lives in routing layer, not domain):

```kotlin
@Serializable
data class HealthCheckResponse(
    val status: HealthStatus,
    val message: String?
)
```

## Testing Considerations

- Acceptance test covering successful health check scenario (database available)
- Acceptance test covering failure scenario using mock repository injection
- Simple integration test at repository boundary to verify actual database query execution
- No unit tests required for this simple feature
- Tests should use existing test support helpers and patterns

## Implementation Notes

- Integrate with existing Ktor routing configuration in `plugins/Routing.kt`
- Follow existing repository pattern used by `VersionsRepository`
- Use Kotlin Serialization for JSON response serialization
- Leverage existing database connection setup from `plugins/Databases.kt`
- Keep implementation simple and focused on core functionality
- Follow existing architectural patterns and code organization
- HealthCheckResponse is a DTO that should live in the routing layer, not the domain
- Use Either<DatabaseFailure, Unit> pattern for repository return types
- Implement SELECT 1 query using proper Exposed SQL syntax (not using VersionsRepository)
- Never use nullable types - always use Arrow Either or Option

## Specification by Example

### Successful Health Check
```http
GET /meta/health HTTP/1.1
Host: localhost:8080

HTTP/1.1 200 OK
Content-Type: application/json

{
  "status": "SUCCESS"
}
```

### Failed Health Check
```http
GET /meta/health HTTP/1.1
Host: localhost:8080

HTTP/1.1 503 Service Unavailable
Content-Type: application/json

{
  "status": "FAILURE",
  "message": "Database connection failed: Connection refused"
}
```

### Gherkin Scenarios
```gherkin
Feature: Health Check Endpoint

  Scenario: Service is healthy with database connectivity
    Given the PostgreSQL database is available
    When I request GET /meta/health
    Then the response status should be 200
    And the response body should contain status "SUCCESS"

  Scenario: Service is unhealthy due to database failure
    Given the PostgreSQL database is unavailable
    When I request GET /meta/health
    Then the response status should be 503
    And the response body should contain status "FAILURE"
    And the response body should contain an error message
```

## Repository Implementation Example

```kotlin
class HealthRepositoryImpl : HealthRepository {
    
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    override fun checkDatabaseConnection(): Either<DatabaseFailure, Unit> = Either.try {
        dbQuery {
            // Execute SELECT 1 using Exposed SQL syntax
            exec("SELECT 1") { rs ->
                rs.next() && rs.getInt(1) == 1
            }
            Unit
        }
    }.mapLeft {
        // transform exception
    }
}
```

## Extra Considerations

- Keep the database query lightweight (`SELECT 1`) to minimize resource usage
- Handle database timeouts gracefully with appropriate error messages
- Ensure the endpoint responds quickly (within reasonable timeout limits)
- Follow existing error handling patterns in the codebase
- Use existing database connection configuration and connection pool
- Repository should return Either<DatabaseFailure, Unit> and handle all exceptions
- Routing handler should map Either results to appropriate HTTP status codes

## Verification

- [ ] Health check endpoint responds at `GET /meta/health`
- [ ] Returns 200 status with `SUCCESS` when database is available
- [ ] Returns 503 status with `FAILURE` when database is unavailable
- [ ] Response includes error message in failure cases
- [ ] JSON response format matches specification
- [ ] Acceptance tests pass for both success and failure scenarios
- [ ] Integration test verifies repository database query execution
- [ ] Endpoint does not require authentication
- [ ] Implementation follows existing architectural patterns