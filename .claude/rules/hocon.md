# HOCON Configuration Defaults

Rules governing where configuration defaults live and how `DefaultAppConfig` reads them. Every default value for the application must be declared exactly once — in `application.conf` (HOCON) — never duplicated as a Kotlin fallback literal.

## Context

*Applies to:* `src/main/resources/application.conf`, `AppConfig.kt`, `ConfigExtensions.kt`, and any code that reads Ktor `ApplicationConfig`
*Level:* Tactical — direct impact on config correctness and maintainability
*Audience:* Developers wiring configuration at the application boundary

## Core Principles

1. *Single Source of Truth:* A default belongs in exactly one place — `application.conf`. Two copies (HOCON + a Kotlin literal) inevitably drift and nothing keeps them in sync.
2. *Fail Fast:* A missing config key is a deploy misconfiguration. Required reads should throw at startup so the problem surfaces immediately, rather than being masked by a silent Kotlin fallback.
3. *No Dead Fallbacks:* `application.conf` always supplies its keys in production, so a Kotlin-side default never fires there. Such fallbacks are dead production code that only exist to be re-asserted by circular tests.
4. *Helpers Earn Their Keep:* A config helper should do genuine work (type/`Option` conversion, parsing) — not merely hold a duplicated default.

## Rules

### Must Have (Critical)

- *RULE-001:* Declare every config default exactly once, in `application.conf`. No Kotlin literal may duplicate a HOCON default value.
- *RULE-002:* Read mandatory values as required properties — `config.property(path).getString()` (with `.toInt()` / `.toLong()` as needed) — matching the existing `jwtSecret` style. A required read throwing on a missing key is the desired behaviour.
- *RULE-003:* Do not introduce `getXxxOrDefault`-style helpers (or inline `propertyOrNull(...) ?: default` equivalents) that carry a default. Defaulting is HOCON's job.
- *RULE-004:* When adding a new config key, add it to `application.conf` with its default (and a `${?ENV_VAR}` override where its siblings have one) **before** reading it in `DefaultAppConfig`.

### Should Have (Important)

- *RULE-101:* Read genuinely optional values as `Option<A>` via `getOptionString` (using `propertyOrNull(...).toOption()`), never as a defaulted read or a nullable.
- *RULE-102:* Keep non-defaulting helpers that do real work (e.g. `getOptionString`, `getCommaSeparatedSet`); name them for the work they do, not for a default they no longer hold.
- *RULE-103:* Do not write unit tests whose only assertion re-states `application.conf` constants. Once defaults live solely in HOCON such a test is circular; rely on integration/acceptance specs that boot from real config.

### Could Have (Preferred)

- *RULE-201:* If repeated `.getString().toInt()` / `.toLong()` reads feel noisy, thin **non-defaulting** typed helpers (`getInt(path)`, `getLong(path)`) are acceptable — provided they carry no default. Pick one approach and apply it uniformly.
- *RULE-202:* Mirror the `${?ENV_VAR}` override convention across a config block so siblings stay consistent (e.g. every `database.*` key has its override).

## Patterns & Anti-Patterns

### ✅ Do This

```hocon
# application.conf — the one place the default lives
database {
    name = "sdkman"
    name = ${?DATABASE_NAME}
}
```

```kotlin
// AppConfig.kt — read directly; HOCON supplies the default
override val databaseName: String = config.property("database.name").getString()
override val databasePort: Int = config.property("database.port").getString().toInt()
override val databaseUsername: Option<String> = config.getOptionString("database.username")
```

### ❌ Don't Do This

```kotlin
// Default duplicated in Kotlin — drifts from application.conf, never fires in prod
override val databaseName: String = config.getStringOrDefault("database.name", "sdkman")  // ❌
override val databasePort: Int = config.getIntOrDefault("database.port", 5432)             // ❌

// Helper that exists only to hold a duplicated default
fun ApplicationConfig.getIntOrDefault(path: String, default: Int): Int =                   // ❌
    propertyOrNull(path)?.getString()?.toInt() ?: default
```

## Decision Framework

*When deciding how to read a value:*
1. Mandatory in production → required read (RULE-002).
2. Genuinely optional (may be absent by design) → `Option<A>` (RULE-101).
3. Needs parsing/transformation → a non-defaulting helper (RULE-102).

*When tempted to add a Kotlin default:* add it to `application.conf` instead. If you think a key might be absent at runtime, decide whether that is a misconfiguration (let it throw) or a real optional (use `Option`) — never paper over it with a literal.

*Tests don't load `application.conf`:* test code builds `MapApplicationConfig` directly, so HOCON defaults never apply. Test config builders must supply every required key explicitly — fix the test map, do not reintroduce a Kotlin fallback to cover the gap.

## Exceptions & Waivers

*Valid reasons for exceptions:*
- A value that is legitimately absent in some environments — use `Option<A>`, not a default.
- Third-party config APIs that force a nullable boundary — convert to `Option` immediately, still without a default.

*Process for exceptions:* document the rationale in a code comment and prefer `Option` over a literal default in every case.

## Quality Gates

- *Automated checks:* `grep -RIn "OrDefault" src/main` returns no hits; `./gradlew check` passes (detekt forbids nullables/suppressions).
- *Code review focus:* every default appears once (HOCON only); no Kotlin literal mirrors a HOCON value; reads are required / `Option` / parsed as appropriate.
- *Testing requirements:* no unit test re-asserts HOCON constants; required keys are supplied by the test config builder, not by a Kotlin fallback.

## Related Rules

- rules/kotlin.md — `Option` over nullables, expression bodies, immutability at the config boundary.
- rules/hexagonal-architecture.md — configuration is wired at the application boundary, not inside the domain.
- rules/kotest.md — boot-from-real-config acceptance tests prove every required key is present; avoid circular unit tests.

## References

- [HOCON specification](https://github.com/lightbend/config/blob/main/HOCON.md) — substitution and `${?ENV}` override syntax.
- [Ktor configuration](https://ktor.io/docs/configuration-file.html) — `ApplicationConfig`, `property`, and `propertyOrNull`.

---

## TL;DR

*Key Principles:*
- Every config default lives exactly once, in `application.conf`.
- Required reads fail fast on a missing key; that is desirable, not a bug to mask.
- A Kotlin fallback literal is dead production code that drifts and breeds circular tests.

*Critical Rules:*
- Must declare defaults only in HOCON; no Kotlin literal may duplicate one.
- Must read mandatory values as required properties (`config.property(path).getString()`).
- Must not add `getXxxOrDefault`-style helpers that carry a default; use `Option<A>` for genuinely optional values.

*Quick Decision Guide:*
When in doubt: put the default in `application.conf` and read it directly — if a key might be missing, that is either a misconfiguration (let it throw) or an `Option`, never a Kotlin literal.
