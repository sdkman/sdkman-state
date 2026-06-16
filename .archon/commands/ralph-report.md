---
description: Ralph Wiggum run report — read-only factual summary of seed/plan/build/archive outputs.
---

# Ralph run — completion report

The Ralph build loop has finished. Produce a concise, factual report.

**Goal:** $ARGUMENTS

**Build budget vs items:**

$plan-count.output

**Last build iteration output:**

$build.output

## Steps

1. `cat IMPLEMENTATION_PLAN.md` — note which items are complete vs still `- [ ]`.
2. `git log --oneline -20` — commits produced during this run.
3. `tail -n 60 PROGRESS.md` — latest learnings and gotchas.

## Output format

```
═══════════════════════════════════════════════
RALPH WIGGUM — RUN REPORT
═══════════════════════════════════════════════
Goal:    {goal or "none given"}
Branch:  {current branch}

Plan:    {N complete} / {M total} items   (budget was {budget})
Remaining items:
- {each still-incomplete item, or "none"}

Commits this run:
{git log output}

Learnings (from PROGRESS.md):
- {key patterns / gotchas}
═══════════════════════════════════════════════
```

Keep it factual — just the data, no commentary. Do NOT modify any files.
