# Ralph Workflow Declarative Extraction

The `ralph-wiggum` Archon workflow (`.archon/workflows/ralph-wiggum.yaml`) currently embeds large AI prompts and multi-line bash scripts directly inside its node definitions. The two loop prompts (plan, build), the report prompt, three bash nodes (`seed`, `plan-count`, `archive`), and two `until_bash` loop counters all live as inline code in the YAML. This makes the workflow hard to read, impossible to test in isolation, and impossible to reuse.

This spec defines a behaviour-preserving refactor that extracts every embedded prompt and script into its own file, leaving the YAML as **pure declarations** — node IDs, dependency edges, references to named commands and scripts, and the unavoidable one-line loop hooks. No logic remains in the YAML.

This is a **refactor, not a feature change**: the workflow's observable behaviour (artifacts seeded, plan loop capped at 3 passes, build budget sized at `ceil(items × 1.2)`, build loop bounded by budget or plan exhaustion, report content, archive layout) must be identical before and after, **aside from two documented, intentional exceptions**: the dropped "minimal fallback" in `seed` (a missing template now hard-fails — see Behavioural Fidelity), and the zero-items budget computation, where the rewrite fixes a pre-existing bash bug while preserving the intended `budget = 1`.

## Background: Archon's two extraction mechanisms

Archon supports extracting embedded content in two ways, with **asymmetric support** that determines the entire shape of this work:

1. **AI prompts extract by name.** A `command:` node, and a `loop.command:` field, load a named Markdown file from `.archon/commands/` (repo → home → bundled precedence) and use its body as the prompt. This is first-class and fully declarative. `loop.command:` is mutually exclusive with `loop.prompt:` and is verified present in the Archon source (`packages/workflows/src/schemas/loop.ts`).
2. **Bash does *not* extract by name.** Archon's named-script mechanism (`.archon/scripts/`) resolves **bun (TypeScript/JS) or uv (Python) only** — there is no `.sh` resolution. Bash nodes accept an inline string only, and `until_bash` is an inline shell hook with no named-file form.

Consequently, "zero code in the YAML" means: move prompts into `.archon/commands/` command files, and **rewrite** the bash logic as bun named scripts in `.archon/scripts/`. The only shell that survives is two one-line `until_bash: bun run …` invocations, which carry no logic.

## Behaviour

After extraction, the workflow YAML contains only declarative node definitions. Each node delegates its work as follows:

- The plan and build loop prompts are loaded from command files via `loop.command:`.
- The report prompt is loaded from a command file via `command:`.
- The `seed`, `plan-count`, and `archive` bash nodes become `script:` nodes (`runtime: bun`) referencing named TypeScript scripts.
- The two `until_bash` counters become one-line `bun run` invocations of dedicated TypeScript scripts.

Variable substitution (`$ARGUMENTS`, `$ARTIFACTS_DIR`, `$plan-count.output`, `$build.output`) continues to resolve exactly as before, because substitution is applied to prompt bodies and inline `until_bash` strings before execution. The chosen script runtime is **bun / TypeScript** throughout: Archon itself runs on bun, so the runtime is guaranteed present, and `node:fs`/`node:path` cover every operation (directory creation, template copy, checkbox counting, timestamping, file moves).

## Target Structure

```
.archon/
  workflows/ralph-wiggum.yaml     # pure declarations + 2 one-line until_bash calls
  commands/
    ralph-plan.md                 # ← plan node loop.prompt
    ralph-build.md                # ← build node loop.prompt
    ralph-report.md               # ← report node prompt
  scripts/
    ralph-seed.ts                 # ← seed node bash
    ralph-plan-count.ts           # ← plan-count node bash
    ralph-archive.ts              # ← archive node bash
    ralph-plan-cap.ts             # ← plan node until_bash
    ralph-build-cap.ts            # ← build node until_bash
  ralph/templates/                # unchanged
    IMPLEMENTATION_PLAN.md
    PROGRESS.md
```

Naming is **flat with a `ralph-` prefix** in each required directory. Scripts must live in `.archon/scripts/` and commands in `.archon/commands/` for name resolution to work; only the templates remain under `.archon/ralph/`. The `ralph-` prefix keeps the port's files self-identifying and avoids shadowing bundled commands (e.g. a bare `plan`). The ralph artifacts are therefore intentionally split across three directories — this is a constraint of Archon's resolution, not a choice.

**Worktree availability.** The new `commands/` and `scripts/` files resolve in worktree runs via the same mechanism the templates already rely on: `archon workflow run` recursively copies the whole `.archon/` directory from the canonical working tree into the new worktree (default `copyFiles: ['.archon']`, `worktree-copy.ts`). The copy reads the *working tree*, so the new files are available **even uncommitted** — no special handling is needed. (This holds as long as the project's `config.yaml` does not override `copyFiles` to exclude `.archon`; the current config does not.)

### Node-by-node transformation

| Node | Before | After | `ARTIFACTS_DIR` source |
|---|---|---|---|
| `seed` | `bash:` | `script: ralph-seed`, `runtime: bun` | `process.env.ARTIFACTS_DIR` |
| `plan` | `loop.prompt` + `until_bash` | `loop.command: ralph-plan` + `until_bash: bun run .archon/scripts/ralph-plan-cap.ts "$ARTIFACTS_DIR"` | env (none needed) / **argv** for cap |
| `plan-count` | `bash:` | `script: ralph-plan-count`, `runtime: bun` | `process.env.ARTIFACTS_DIR` |
| `build` | `loop.prompt` + `until_bash` | `loop.command: ralph-build` + `until_bash: bun run .archon/scripts/ralph-build-cap.ts "$ARTIFACTS_DIR"` | env (none needed) / **argv** for cap |
| `report` | `prompt:` | `command: ralph-report` | n/a |
| `archive` | `bash:` | `script: ralph-archive`, `runtime: bun` | n/a |

The node IDs, `depends_on` edges, `idle_timeout`, `model`, loop `until`/`max_iterations`/`fresh_context` settings, and the workflow-level comments are all preserved unchanged.

## Execution Contracts

These are the load-bearing facts about how Archon executes the extracted units. They are derived from the Archon source (`packages/workflows/src/dag-executor.ts`, `executor-shared.ts`, `schemas/loop.ts`) and each one constrains the rewrite.

### Command files

- A `command:` / `loop.command:` reference loads the file body **verbatim** — Archon does **not** strip YAML frontmatter (`loadCommandPrompt`, `executor-shared.ts`). Any frontmatter becomes part of the prompt the model sees. This is the documented convention; frontmatter is inert metadata the model ignores, and `description:` / `argument-hint:` earn their place in listings and the web UI.
- Variable substitution applies to the loaded body, so `$ARGUMENTS`, `$plan-count.output`, and `$build.output` resolve as they did inline.
- A command file must be non-empty or the node fails.
- Because frontmatter is **not** parsed (only carried verbatim into the prompt), any node configuration — notably `report`'s `model: sonnet` — must remain a **node-level field in the YAML**. A `model:` key in a command file's frontmatter is inert and would be silently ignored.

### Named `script:` nodes (`seed`, `plan-count`, `archive`)

- Receive `ARTIFACTS_DIR`, `LOG_DIR`, and `BASE_BRANCH` as **environment variables** (`dag-executor.ts`). Read via `process.env.ARTIFACTS_DIR`.
- `$ARGUMENTS` / `$nodeId.output` substitution does **not** reach a named-script file body (substitution applies only to the `script:` field, which for a named script is just the name). None of these three nodes need those variables, so this is not a constraint here.
- stdout is captured as `$nodeId.output`; a non-zero exit **fails** the node; stderr is forwarded as a warning and does not fail the node.
- Invoked as `bun --no-env-file run <path>`, which prevents the target repo's `.env` leaking into the subprocess.

### `until_bash` counter scripts (`ralph-plan-cap`, `ralph-build-cap`)

- `until_bash` runs in the loop executor, which injects `USER_MESSAGE`, `ARGUMENTS`, and the `LOOP_*` / `CONTEXT*` variables into the subprocess environment but **does not inject `ARTIFACTS_DIR`** (`dag-executor.ts`). The cap scripts must therefore receive the artifacts directory via **argv** — `until_bash: bun run .archon/scripts/ralph-build-cap.ts "$ARTIFACTS_DIR"` — reading `process.argv[2]`, **not** `process.env.ARTIFACTS_DIR` (which would be `undefined` and silently break the budget).
- `$ARTIFACTS_DIR` *is* substituted into the inline `until_bash` string before execution (it is in Archon's always-substituted variable group, even under `shellSafe`), which is what makes passing it as an argument work. Because that substitution happens **before** the `shellSafe` escaping pass, the value lands in the command string unquoted — so the invocation **must** wrap it in double quotes (`"$ARTIFACTS_DIR"`) to stay correct if the artifacts path ever contains spaces.
- Exit code drives loop completion: `process.exit(0)` = loop complete, `process.exit(1)` = continue to the next iteration.

### Consumed stdout

- `report` interpolates `$plan-count.output`, so `ralph-plan-count.ts` **must** print its `PLAN_ITEMS=…` and `BUILD_BUDGET=…` lines to stdout verbatim — this stdout is a consumed contract, not diagnostic logging.
- `$seed.output` and `$archive.output` are consumed by no downstream node; their stdout is informational only.

## Behavioural Fidelity

The rewrite must preserve the existing logic exactly:

- **`ralph-seed.ts`** — creates `specs/` if absent; copies `IMPLEMENTATION_PLAN.md` and `PROGRESS.md` from `.archon/ralph/templates/` if they do not already exist. The previous "minimal fallback when the template is missing" branch is **dropped**: a missing template is now a hard precondition that fails the node (a missing checked-in template indicates a misconfiguration and should surface loudly rather than be papered over). The branch/specs/plan-items diagnostics may be retained as informational stdout.
- **`ralph-plan-count.ts`** — counts incomplete plan items (`^- \[ \]`), computes `budget = items < 1 ? 1 : ceil(items × 1.2)`, writes `build-budget.txt` and an initial `build-iter.txt` (`0`) to `ARTIFACTS_DIR`, and prints `PLAN_ITEMS=` / `BUILD_BUDGET=`. **Use integer arithmetic** — `Math.floor((items * 6 + 4) / 5)` — to compute the ceiling, mirroring the original bash `(count * 6 + 4) / 5` exactly. Do **not** write `Math.ceil(items * 1.2)`: the literal `1.2` is a floating-point value and the decimal form invites rounding surprises; the integer form is bit-identical to the original by construction. **One deliberate divergence at zero items:** the original bash `grep -c … || echo 0` is latently buggy when there are no incomplete items — `grep -c` prints `0` *and* exits non-zero, so `|| echo 0` produces a two-line `"0\n0"` that breaks the subsequent integer comparison. The TS rewrite (`items < 1 ? 1 : …`) is clean and yields the *intended* `budget = 1`. The refactor therefore preserves the **intended** behaviour and silently fixes this pre-existing edge-case bug rather than reproducing it.
- **`ralph-archive.ts`** — moves `IMPLEMENTATION_PLAN.md` and `PROGRESS.md` into `.ralph/<timestamp>/`, creating the destination only when there is something to move, and reporting "Nothing to archive." otherwise.
- **`ralph-plan-cap.ts`** — increments the plan iteration counter in `ARTIFACTS_DIR/plan-iter.txt`; exits `0` (graceful stop, not a failure) once the count reaches the fixed cap of 3, else exits `1`. Nothing seeds `plan-iter.txt` (unlike `build-iter.txt`, which `ralph-plan-count.ts` initializes), so the **first** pass reads a non-existent file: the script must default a missing/empty counter to `0` before incrementing (mirroring the original `cat … || echo 0`), or the first iteration throws.
- **`ralph-build-cap.ts`** — reads `build-budget.txt`, increments `build-iter.txt`, and exits `0` when either the budget is spent (`n >= budget`) **or** the plan is exhausted (no `^- \[ \]` items remain), else exits `1`.
- **Command files** — the plan, build, and report prompt bodies move verbatim, preserving their `<promise>` completion signals (`PLAN_STABLE`, `PLAN_COMPLETE`) and all variable references.

## Business Rules

1. **No logic in the YAML.** After extraction the workflow file contains only node declarations, dependency edges, configuration values, comments, and the two one-line `until_bash` invocations. No multi-line bash, no inline prompts.
2. **Prompts are extracted by name; bash is rewritten.** Prompts move to `.archon/commands/` unchanged. Bash logic is reimplemented as bun TypeScript in `.archon/scripts/` — it is not copied verbatim, because no named-bash mechanism exists.
3. **bun / TypeScript is the only script runtime.** All five scripts are `.ts` run under `runtime: bun`. uv/Python is not used.
4. **`until_bash` receives `ARTIFACTS_DIR` via argv, never env.** The two cap scripts read `process.argv[2]`. Named `script:` nodes read `process.env.ARTIFACTS_DIR`.
5. **`ralph-plan-count.ts` stdout is a contract.** It must emit `PLAN_ITEMS=` and `BUILD_BUDGET=` so the `report` node's `$plan-count.output` interpolation continues to work.
6. **Exit codes carry semantics.** Non-zero fails a `script:` node; for the cap scripts, exit `0` completes the loop and exit `1` continues it. The plan cap's reaching the limit must remain a graceful `0`, not a failure.
7. **Behaviour is preserved.** Seeding, the 3-pass plan cap, the `ceil(items × 1.2)` budget, the build loop's dual exit conditions, the report output, and the timestamped archive layout are all identical to the current workflow.
8. **Flat `ralph-` naming.** Extracted files are flat within their required directories and prefixed `ralph-`; templates stay at `.archon/ralph/templates/`.
9. **Command files carry minimal frontmatter.** Each of the three command files includes `description` (and `argument-hint` where useful). Frontmatter is accepted as part of the prompt body and is not stripped.

## Out of Scope

- Any change to the workflow's observable behaviour, loop bounds, budget formula, or artifact layout.
- Changing the templates under `.archon/ralph/templates/` or moving them elsewhere.
- Consolidating the two cap scripts into a single parameterised script (they are deliberately kept as two dedicated, branch-free scripts).
- Porting the scripts to uv / Python.
- Subfolder grouping of commands or scripts (e.g. `commands/ralph/plan.md`); flat `ralph-` naming is used instead.
- Restoring the dropped "minimal fallback" behaviour in `seed`.
- Any change to the application service itself (`src/`), its build, or its API. This work touches only `.archon/`.

## Acceptance Criteria

- [ ] `.archon/workflows/ralph-wiggum.yaml` contains no inline prompts and no multi-line bash; every node delegates to a command file, a named script, or a one-line `until_bash` invocation
- [ ] `ralph-plan.md`, `ralph-build.md`, `ralph-report.md` exist in `.archon/commands/` with the prompt bodies moved verbatim, including `<promise>` signals and minimal frontmatter
- [ ] `ralph-seed.ts`, `ralph-plan-count.ts`, `ralph-archive.ts`, `ralph-plan-cap.ts`, `ralph-build-cap.ts` exist in `.archon/scripts/` as bun TypeScript
- [ ] `seed`, `plan-count`, `archive` are `script:` nodes with `runtime: bun` reading `process.env.ARTIFACTS_DIR`
- [ ] The `plan` and `build` nodes use `loop.command:` and an `until_bash: bun run …` invocation that passes `$ARTIFACTS_DIR` as an argument
- [ ] `ralph-plan-count.ts` prints `PLAN_ITEMS=` and `BUILD_BUDGET=` and the `report` node still renders the budget-vs-items section
- [ ] `ralph-seed.ts` fails the node when a template is missing (no minimal fallback)
- [ ] `ralph-plan-cap.ts` caps at 3 passes with a graceful exit `0`; `ralph-build-cap.ts` exits `0` on budget-spent or plan-exhausted
- [ ] `archon validate workflows ralph-wiggum` passes (all `command:` / `loop.command:` files resolve, named scripts exist with matching runtime, `$nodeId.output` refs intact)
- [ ] An end-to-end run of the workflow produces the same seeding, planning, building, reporting, and archiving behaviour as before the refactor
