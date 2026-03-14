0a. Study `specs/*` with up to 5 parallel Sonnet subagents to learn the application specifications.
0b. Study @IMPLEMENTATION_PLAN.md.
0c. Study `build.gradle.kts` and `src/main/kotlin/*` to understand the application structure, dependencies, and routing.
0d. Study `src/test/kotlin/*` to understand existing test coverage and patterns.

1. Your task is to implement functionality per the specifications using parallel subagents. Follow @IMPLEMENTATION_PLAN.md and choose the most important item to address. Before making changes, search the codebase (don't assume not implemented) using Sonnet subagents. You may use up to 30 parallel Sonnet subagents for searches/reads and only 1 Sonnet subagent for build/tests. Use Opus subagents when complex reasoning is needed (debugging, architectural decisions).
2. After implementing functionality or resolving problems, run `./gradlew test` for validation. If tests fail, Ultrathink to debug the root cause before attempting fixes. If functionality is missing, add it per the specifications.
3. When you discover issues, immediately update @IMPLEMENTATION_PLAN.md with your findings using a subagent. When resolved, update and remove the item.
4. When the tests pass, update @IMPLEMENTATION_PLAN.md, then use `/commit` to commit the changes. After the commit, `git push`.

99999. Important: When authoring documentation, capture the why — tests and implementation importance.
999999. Important: Single sources of truth. If tests unrelated to your work fail, resolve them as part of the increment.
9999999. As soon as there are no build or test errors create a git tag. If there are no git tags start at 0.0.0 and increment patch by 1 for example 0.0.1 if 0.0.0 does not exist.
99999999. You may add extra logging if required to debug issues.
999999999. Keep @IMPLEMENTATION_PLAN.md current with learnings using a subagent — future work depends on this to avoid duplicating efforts. Update especially after finishing your turn.
9999999999. After completing work or encountering notable findings, append an entry to @PROGRESS.md following the template defined in its header. This is an append-only log — never edit or remove previous entries.
99999999999. For any bugs you notice, resolve them or document them in @IMPLEMENTATION_PLAN.md using a subagent even if unrelated to the current piece of work.
999999999999. Implement functionality completely. Placeholders and stubs waste efforts and time redoing the same work.
9999999999999. When @IMPLEMENTATION_PLAN.md becomes large, periodically clean out completed items using a subagent.
99999999999999. If you find inconsistencies in `specs/*` then use an Opus subagent with 'ultrathink' to update the specs.
999999999999999. IMPORTANT: @AGENTS.md is for operational guardrails only (build commands, test commands, rules file references). Do not put status updates or progress notes there. Only update it if a genuinely new operational learning warrants it (e.g. a build command was wrong). Iterative learnings go in @PROGRESS.md.
