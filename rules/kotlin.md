# Functional Kotlin with Arrow

Functional programming guidelines for Kotlin development using Arrow FP library. Emphasizes immutability, type safety, and explicit error handling while completely avoiding nullable types in favor of Arrow's `Option`.

## Context

*Applies to:* All Kotlin source code, domain models, business logic, and data access layers
*Level:* Tactical - Direct impact on code quality and maintainability
*Audience:* All Kotlin developers working on this codebase

## Core Principles

1. *Null Safety:* NEVER use nullable types (`?`). Always use Arrow's `Option<A>` for optional values
2. *Immutability First:* Default to `val` and immutable data structures to prevent accidental mutations
3. *Explicit Error Handling:* Use Arrow's `Either<E,A>` instead of exceptions for recoverable errors
4. *Type Safety:* Make impossible states unrepresentable through algebraic data types
5. *Pure Functions:* Prefer functions without side effects that return the same output for the same input
6. *Composition Over Complexity:* Build complex logic by composing smaller, focused functions

## Rules

### Must Have (Critical)

- *RULE-001:* NEVER use nullable types (`String?`, `Int?`, etc.). Convert external nullable APIs to `Option<A>` immediately at boundaries
- *RULE-002:* Use `Either.catch {}` for exception handling instead of try-catch blocks
- *RULE-003:* All public functions must have explicit return types
- *RULE-004:* Use `val` for all variable declarations unless mutation is absolutely required
- *RULE-005:* Convert nullable external API results to `Option<A>` using `.toOption()` at the earliest opportunity
- *RULE-006:* Use Arrow's `Either<E,A>` for operations that can fail instead of throwing exceptions

### Should Have (Important)

- *RULE-101:* Prefer immutable collection interfaces (`List`, `Set`, `Map`) over mutable variants
- *RULE-102:* Use sealed classes for representing finite sets of types and domain states
- *RULE-103:* Implement smart constructors for domain types that can fail validation
- *RULE-104:* Use `@JvmInline value class` for type-safe primitives (IDs, quantities, etc.)
- *RULE-105:* Chain collection operations (`map`, `filter`, `fold`) instead of imperative loops
- *RULE-106:* Use expression bodies for simple functions: `fun transform(x: String): Int = x.length`
- *RULE-107:* Prefer `map`, `getOrElse` over `fold` when working with `Option<A>` or `Either<E,A>`

### Could Have (Preferred)

- *RULE-201:* Group related functions and data classes in the same file when cohesive
- *RULE-202:* Use `Validated` for accumulating multiple validation errors
- *RULE-203:* Prefer function composition over deeply nested if-else chains
- *RULE-204:* Use descriptive function names that eliminate need for comments
- *RULE-205:* Organize imports: standard library, third-party, Arrow, local packages

## Patterns & Anti-Patterns

### ✅ Do This

```kotlin
// Option instead of nullable
fun findUser(id: UserId): Option<User> =
    userRepository.findById(id).toOption()

// Either for error handling
fun validateEmail(email: String): Either<ValidationError, Email> =
    if (email.matches(EMAIL_REGEX)) Email(email).right()
    else ValidationError("Invalid email").left()

// Smart constructor
data class Age private constructor(val value: Int) {
    companion object {
        fun of(value: Int): Either<ValidationError, Age> =
            if (value in 0..150) Age(value).right()
            else ValidationError("Age must be 0-150").left()
    }
}

// Immutable collections
val updatedItems = items + newItem
val filtered = users.filter { it.isActive }

// Chain operations instead of loops
val result = orders
    .filter { it.isValid() }
    .map { it.calculateTotal() }
    .fold(Money.ZERO) { acc, total -> acc + total }
```

### ❌ Don't Do This

```kotlin
// NEVER use nullable types
fun findUser(id: String): User? = null  // ❌

// NEVER use try-catch for business logic
try {
    val result = parseInput(data)
    return result
} catch (e: Exception) {  // ❌
    log.error("Parse failed", e)
    return null
}

// NEVER mutate collections
val items = mutableListOf<String>()  // ❌
items.add("new item")

// NEVER use imperative loops when functional alternatives exist
val results = mutableListOf<String>()  // ❌
for (item in items) {
    if (item.isValid()) {
        results.add(item.process())
    }
}
```

## Decision Framework

*When rules conflict:*
1. Prioritize null safety - always choose `Option<A>` over nullable types
2. Prefer explicit error handling with `Either<E,A>` over exceptions
3. Choose immutability and pure functions when performance impact is acceptable

*When facing edge cases:*
- Convert external nullable APIs to `Option<A>` at the boundary immediately
- Use `Either.catch {}` to wrap legacy exception-throwing code
- Document any temporary violations with TODO comments and timeline for resolution

## Exceptions & Waivers

*Valid reasons for exceptions:*
- Interoperating with Java/Android APIs that require nullable types (convert immediately)
- Performance-critical code where immutability creates unacceptable overhead
- Third-party library constraints that cannot be wrapped effectively

*Process for exceptions:*
1. Document the exception with clear rationale in code comments
2. Create TODO item to revisit and eliminate the exception when possible
3. Isolate exceptional code in boundary layers away from core business logic

## Quality Gates

- *Automated checks:* Detekt rules for nullable usage, mutable collection detection
- *Code review focus:* Verify Option/Either usage, check for null safety violations
- *Testing requirements:* Test error cases using Either.Left, verify Option handling for missing values

## Related Rules

- rules/ddd-rules.md - Domain modeling aligns with functional types and smart constructors
- rules/hexagonal-architecture-rules.md - Boundary conversions from nullable to Option types
- rules/kotest-rules.md - Testing patterns for functional code with Arrow types

## References

- [Arrow Documentation](https://arrow-kt.io/) - Comprehensive Arrow FP library guide
- [Kotlin Functional Programming](https://kotlinlang.org/docs/lambdas.html) - Official Kotlin functional features
- [Domain Modeling Made Functional](https://pragprog.com/titles/swdddf/domain-modeling-made-functional/) - F# but applicable principles

---

## TL;DR

*Key Principles:*
- Never use nullable types, always use Arrow's `Option<A>` for optional values
- Handle errors explicitly with `Either<E,A>` instead of throwing exceptions
- Default to immutable data structures and pure functions

*Critical Rules:*
- Must convert nullable external APIs to `Option<A>` immediately at boundaries
- Must use `Either.catch {}` instead of try-catch for business logic
- Must use `val` and immutable collections unless mutation absolutely required

*Quick Decision Guide:*
When in doubt: Choose the more type-safe, explicit option that makes invalid states impossible to represent
