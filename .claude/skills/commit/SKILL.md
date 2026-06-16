---
name: commit
description: Use this skill when you need to commit code changes with properly formatted commit messages that follow conventional commit standards. Use it when you have finished implementing a feature, fixed a bug, or have any other change ready to save. Produces fine-grained atomic commits with short imperative subjects and an optional short body.
allowed-tools: Bash
---

You are an expert Git committer who creates commit messages following the Conventional Commits specification (https://www.conventionalcommits.org/en/v1.0.0/).

Use plain Bash `git` commands. No MCP servers required — this skill is self-contained.

## Commit Message Format

```
<type>[optional scope]: <description>

[optional short body — up to 3 bulleted lines]

[optional footer(s)]
```

## Commit Types

| Type       | Purpose                                           | SemVer   |
|------------|---------------------------------------------------|----------|
| feat       | New feature                                       | MINOR    |
| fix        | Bug fix                                           | PATCH    |
| docs       | Documentation only                                | -        |
| style      | Formatting/linting, whitespace (no code change)   | -        |
| refactor   | Code restructuring (no feature/fix)               | -        |
| perf       | Performance improvement                           | -        |
| test       | Adding or correcting tests                        | -        |
| build      | Build system or dependencies                      | -        |
| ci         | CI configuration                                  | -        |
| chore      | Maintenance tasks                                 | -        |

## Breaking Changes

Indicate breaking changes (MAJOR version bump) using either:
- Append `!` after type/scope (e.g., `feat!: remove deprecated API`)
- Add footer with `BREAKING CHANGE: description of what broke`

## Rules

1. **Analyse changes**: review `git status` and `git diff` before composing anything.
2. **Atomic commits**: each commit captures one logical change. If the working tree contains separable concerns (e.g. a refactor *and* the feature it enables, or two unrelated fixes), produce multiple commits — one per concern — rather than a single grab-bag commit.
3. **Stage selectively**: pass explicit paths to `git add -- <paths>`. Never use `git add -A` or `git add .` when the diff spans multiple logical changes.
4. **Check recent commits**: `git log --oneline -10` to match the project's prevailing style before writing your own subject.
5. **Select type**: pick the most specific type that fits the change.
6. **Optional scope**: parenthetical, lowercase, names the affected area (e.g. `feat(sandbox):`).
7. **Subject**: imperative mood, lowercase, ≤50 characters, no trailing period.
8. **Body (optional)**: at most **3 short bulleted lines** summarising what was implemented. Use a body only when the subject alone doesn't make the change self-evident. Skip the body entirely for trivial or self-describing diffs.
9. **Commit immediately**: do not seek approval before committing — the user invoked this skill *because* they want a commit.

## Quality Standards

- **Always include** footer: `Co-Authored-By: Claude <noreply@anthropic.com>`
- **Never include** "Generated with Claude Code" signatures
- Keep the subject ≤50 characters where possible
- One logical change per commit — split larger diffs into multiple atomic commits in a single skill invocation
- The body, when present, is **at most 3 bulleted lines**; no prose paragraphs, no long explanations
- Ignore previous commit messages when composing new ones — match style, not content

## Examples

Subject only (preferred for self-evident diffs):

```
feat: add user authentication module
```

```
fix(api): resolve null pointer in login validation
```

Subject with short bulleted body (when the diff isn't self-explanatory):

```
feat(sandbox): mount ssh agent socket

- bind /tmp/ssh-agent.sock into container
- forward SSH_AUTH_SOCK env var
- guard empty array under bash 3.2
```

Breaking change:

```
refactor!: restructure database schema
```

## Tool reference

- `git status` — inspect the working tree
- `git diff` / `git diff --staged` — review unstaged and staged changes
- `git log --oneline -10` — recent commit style
- `git add -- <paths>` — stage specific paths only (never `-A` / `.` for multi-concern diffs)
- Use a HEREDOC for the commit message so the subject, body bullets, and footer keep their newlines:

  ```bash
  git commit -m "$(cat <<'EOF'
  <type>(<scope>): <subject>

  - bullet one
  - bullet two

  Co-Authored-By: Claude <noreply@anthropic.com>
  EOF
  )"
  ```

If the intent or scope of the changes is unclear, ask specific questions before committing.
