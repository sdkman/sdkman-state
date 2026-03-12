# Hexagonal Architecture (Ports & Adapters)

Rules governing the implementation of Hexagonal Architecture as defined by Alistair Cockburn. These rules ensure proper separation of concerns, dependency inversion, and testability through the Ports & Adapters pattern.

## Context

Provide structural guidance for implementing hexagonal architecture to achieve isolation of business logic from external concerns.

*Applies to:* All application layers, particularly domain logic, infrastructure, and integration points  
*Level:* Strategic - foundational architectural decisions  
*Audience:* Developers and Architects implementing business applications  

## Core Principles

1. *Isolation:* Business logic must be completely isolated from external concerns (UI, database, frameworks)
2. *Dependency Inversion:* External systems depend on the application core, never the reverse
3. *Testability:* All business logic must be testable in isolation without external dependencies
4. *Symmetric Design:* All external interactions are treated equally through ports and adapters

## Rules

### Must Have (Critical)

- *RULE-001:* Domain/business logic MUST NOT depend on any infrastructure concerns (databases, frameworks, UI)
- *RULE-002:* All external interactions MUST go through ports (interfaces) defined in the application core
- *RULE-003:* Adapters MUST implement ports and handle translation between external systems and domain models
- *RULE-004:* Domain entities and value objects MUST NOT reference infrastructure types or annotations
- *RULE-005:* Application services MUST coordinate domain operations without knowledge of persistence or presentation

### Should Have (Important)

- *RULE-101:* Ports SHOULD be defined as interfaces in the domain or application layer
- *RULE-102:* Adapters SHOULD be organized by the external system they integrate with
- *RULE-103:* Domain events SHOULD be used for cross-aggregate communication
- *RULE-104:* Application services SHOULD orchestrate domain operations and coordinate with ports
- *RULE-105:* Configuration SHOULD happen at the application boundary, not within the domain

### Could Have (Preferred)

- *RULE-201:* Consider using dedicated DTOs for adapter boundaries to prevent external model leakage
- *RULE-202:* Consider implementing anti-corruption layers for complex external integrations
- *RULE-203:* Consider using dependency injection containers to wire adapters to ports
- *RULE-204:* Consider organizing packages by feature rather than technical layer

## Patterns & Anti-Patterns

### ✅ Do This

```kotlin
// Domain service with port dependency
class CandidateService(private val repository: CandidateRepository) {
    fun findByPlatform(platform: Platform): List<Candidate> {
        return repository.findByPlatform(platform)
    }
}

// Port definition in domain
interface CandidateRepository {
    fun findByPlatform(platform: Platform): List<Candidate>
}

// Adapter implementation
class MongoCandidateRepository : CandidateRepository {
    override fun findByPlatform(platform: Platform): List<Candidate> {
        // MongoDB-specific implementation
    }
}
```

### ❌ Don't Do This

```kotlin
// Domain service directly depending on infrastructure
class CandidateService {
    private val mongoClient = MongoClient.create()
    
    fun findByPlatform(platform: Platform): List<Candidate> {
        return mongoClient.getCollection("candidates")
            .find(eq("platform", platform.value))
            .map { doc -> Candidate.fromDocument(doc) }
    }
}
```

## Decision Framework

*When rules conflict:*
1. Prioritize domain isolation over convenience
2. Choose explicit ports over implicit dependencies
3. Favor composition over inheritance for adapters

*When facing edge cases:*
- If unsure about layer placement, put it in the outermost layer that needs it
- When external APIs change frequently, add an anti-corruption layer
- For simple CRUD operations, repository patterns are sufficient

## Exceptions & Waivers

*Valid reasons for exceptions:*
- Framework requirements that mandate specific annotations (document with // TODO: remove when framework updated)
- Performance-critical paths where abstraction overhead is prohibitive (measure first)
- Legacy integration constraints during migration periods (time-boxed)

*Process for exceptions:*
1. Document the exception and rationale in code comments
2. Create a technical debt item with removal timeline
3. Review exceptions quarterly for removal opportunities

## Quality Gates

- *Automated checks:* Dependency analysis tools to verify layer dependencies don't violate rules
- *Code review focus:* Verify no infrastructure imports in domain packages, check port/adapter relationships
- *Testing requirements:* Domain logic must have unit tests without test containers or external dependencies

## Related Rules

- rules/ddd-rules.md - Domain modeling complements hexagonal structure
- rules/kotlin-rules.md - Language-specific implementation patterns
- rules/kotest-rules.md - Testing approaches for isolated domain logic

## References

- [Hexagonal Architecture by Alistair Cockburn](https://alistair.cockburn.us/hexagonal-architecture/) - Original pattern definition
- [Ports & Adapters Pattern](https://jmgarridopaz.github.io/content/hexagonalarchitecture.html) - Detailed implementation guide
- [Clean Architecture by Uncle Bob](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html) - Complementary architectural principles

---

## TL;DR

*Key Principles:*
- Business logic is isolated from all external concerns through ports and adapters
- Dependencies point inward toward the domain core, never outward
- All external integrations are symmetric and testable in isolation
- Adapters translate between external protocols and domain models

*Critical Rules:*
- Must define ports as interfaces in the application core
- Must implement adapters that depend on ports, not the reverse
- Must never import infrastructure types into domain code
- Must be able to test business logic without external dependencies

*Quick Decision Guide:*
When in doubt: If it's not core business logic, put it behind a port with an adapter implementation.