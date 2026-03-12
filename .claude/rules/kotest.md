# Kotest Testing Rules

Testing conventions for **sdkman-broker** using Kotest, Testcontainers, and MockK for structured test layers that align with hexagonal architecture and outside-in development practices.

## Context

*Applies to:* All Kotlin services following hexagonal architecture patterns  
*Level:* Tactical - guides daily testing decisions and implementation  
*Audience:* Developers writing and maintaining tests

## Core Principles

1. *Outside-In Testing:* Start with acceptance tests for business value, then integration tests for adapters, finally unit tests for domain logic
2. *Test Layer Isolation:* Each test layer has a distinct purpose and scope - don't blur the boundaries
3. *Meaningful Assertions:* Tests should clearly express intent and provide actionable failure messages
4. *Just-Enough Coverage:* Focus on critical paths and edge cases rather than achieving arbitrary coverage metrics

## Rules

### Must Have (Critical)

- *RULE-001:* Use Kotest ShouldSpec exclusively - no mixing of test frameworks
- *RULE-002:* Follow the three-layer testing strategy: Acceptance (end-to-end), Integration (adapters), Unit (services/domain)
- *RULE-003:* Tag slow tests with `@Tag("acceptance")` or `@Tag("integration")` for selective execution
- *RULE-004:* Use Testcontainers for all database interactions in acceptance and integration tests
- *RULE-005:* Use MockK for unit test isolation - never mock across hexagon boundaries in integration tests

### Should Have (Important)

- *RULE-101:* Name test classes as `<TypeUnderTest><Layer>Spec` (e.g., `DownloadRouteAcceptanceSpec`)
- *RULE-102:* Structure tests with given/when/then comments for clarity
- *RULE-103:* Use descriptive test names that explain the scenario and expected outcome
- *RULE-104:* Provide clues with assertions using `withClue` for easier debugging
- *RULE-105:* Keep acceptance tests focused on happy paths and critical unhappy paths only
- *RULE-106:* Assert only the one thing stated in the test's intention - avoid multiple unrelated assertions

### Could Have (Preferred)

- *RULE-201:* Use singleton Testcontainers with `withReuse(true)` for faster test execution
- *RULE-202:* Apply property-based testing to pure functions with complex logical boundaries
- *RULE-203:* Group related test utilities in companion objects or separate utility classes
- *RULE-204:* Use Wiremock for stubbing external HTTP dependencies in integration tests

## Patterns & Anti-Patterns

### ✅ Do This

```kotlin
class DownloadRouteAcceptanceSpec : ShouldSpec({
    val mongo = MongoContainerListener
    val app = testApplication { application { module() } }

    should("redirect to universal artifact when platform is unavailable") {
        // given: version exists with UNIVERSAL platform only
        seedVersions(mongo, "spark", "2.3.1", Platform.UNIVERSAL)

        // when: client requests MAC_OSX platform
        val response = app.client.get("/download/spark/2.3.1/MAC_OSX")

        // then: redirect to universal artifact
        response.status shouldBe HttpStatusCode.Found withClue {
            "Should redirect when requested platform unavailable"
        }
        response.headers["Location"] shouldContain "spark-2.3.1.tgz"
    }
})
```

### ❌ Don't Do This

```kotlin
class DownloadServiceTest { // Wrong framework and naming
    @Test
    fun test() { // Non-descriptive name
        val service = DownloadService(mockRepo, mockClient) // Mocking across boundaries
        val result = service.download("spark", "2.3.1")
        assertTrue(result.isSuccess) // Weak assertion
        assertEquals("spark-2.3.1.tgz", result.filename) // Multiple unrelated assertions
        assertNotNull(result.checksum) // Testing more than the stated intention
    }
}
```

## Decision Framework

*When choosing test layer:*
1. Can this be tested through the public API? → Acceptance test
2. Does this test an adapter (repository, HTTP client)? → Integration test  
3. Is this pure business logic or service coordination? → Unit test

*When facing test complexity:*
- Simplify the test scenario before adding more sophisticated tooling
- Consider if the complexity indicates a design problem in the production code
- Prefer explicit setup over clever test utilities that obscure intent

## Exceptions & Waivers

*Valid reasons for exceptions:*
- Legacy code migration where immediate full compliance is not feasible
- Performance-critical scenarios where test execution time is prohibitive
- Third-party integration constraints that require alternative approaches

## Quality Gates

- *Automated checks:* Detekt rules enforce test naming conventions and prevent empty test blocks
- *Linting checks:* Ktlint enforces code formatting
- *Code review focus:* Verify correct test layer selection and meaningful assertions
- *Testing requirements:* All tests must have descriptive names and appropriate tags for CI pipeline execution

## Related Rules

- rules/hexagonal-architecture.md - Defines the architectural boundaries that testing layers should respect
- rules/kotlin.md - General Kotlin coding standards that apply to test code
- rules/domain-driven-design.md - Domain modeling principles that inform unit test structure

## References

- [Kotest Documentation](https://kotest.io/) - Framework capabilities and best practices
- [Testcontainers Documentation](https://testcontainers.com/) - Container management for integration testing
- [MockK Documentation](https://mockk.io/) - Mocking framework for Kotlin

---

## TL;DR

*Key Principles:*
- Test outside-in: acceptance → integration → unit, following hexagonal architecture
- Each test layer has distinct scope and tooling - don't blur boundaries
- Focus on meaningful assertions with clear failure messages
- Use just-enough coverage on critical paths rather than chasing metrics

*Critical Rules:*
- Must use Kotest ShouldSpec exclusively
- Must follow three-layer strategy: acceptance (E2E), integration (adapters), unit (services/domain)
- Must use Testcontainers for database tests and MockK for unit test isolation
- Must tag slow tests for selective CI execution

*Quick Decision Guide:*
When in doubt: Start with an acceptance test that proves business value, then work inward to cover the components that make it work.
