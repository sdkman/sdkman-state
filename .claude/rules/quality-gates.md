# Quality Gates

The validation chain (`./gradlew check`) is non-negotiable. Under **no circumstances** should any of the following be done to make code compile or tests pass:

- Disable, skip, or ignore tests (e.g., `@Disabled`, `@Ignore`, `xshould`)
- Suppress detekt rules (e.g., `@Suppress("detekt:...")`, modifying `detekt.yml` to weaken rules)
- Suppress ktlint rules or relax formatting standards
- Add `@Suppress` annotations to silence compiler or static analysis warnings
- Disable detekt or ktlint entirely (e.g., removing plugins, commenting out task configuration, setting `isEnabled = false`)

If a test fails, fix the code. If a detekt or ktlint rule flags an issue, fix the code to comply. The rules exist to maintain code quality — work within them, not around them.
