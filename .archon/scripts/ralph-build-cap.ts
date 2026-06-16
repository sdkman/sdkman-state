#!/usr/bin/env bun
/**
 * Ralph BUILD-CAP — `until_bash` counter for the `build` loop.
 *
 * Replaces the inline `until_bash` block on the `build` node in
 * `.archon/workflows/ralph-wiggum.yaml`. Stops the build loop when the
 * plan is exhausted OR the budget is spent — mirroring ralph's
 * fixed-iteration build loop (`build_iterations = ceil(items × 1.2)`).
 *
 * Behaviour (mirrors the bash original verbatim aside from the structural
 * argv-vs-env swap):
 *   1. Read the artifacts directory from `process.argv[2]`. `until_bash`
 *      runs in Archon's loop executor, which does **not** inject
 *      `ARTIFACTS_DIR` into the subprocess env (see spec §Execution
 *      Contracts) — reading `process.env.ARTIFACTS_DIR` would be
 *      `undefined` and silently break the cap. The YAML therefore passes
 *      `"$ARTIFACTS_DIR"` (double-quoted) as the first argv slot.
 *   2. Read `<artifactsDir>/build-budget.txt`, defaulting to `1` when
 *      the file is missing, empty, or unreadable — mirroring the bash
 *      `cat … 2>/dev/null || echo 1`. Normally this file is seeded by
 *      `ralph-plan-count.ts` before the build loop starts.
 *   3. Read `<artifactsDir>/build-iter.txt`, defaulting to `0`,
 *      increment, write back. `ralph-plan-count.ts` initialises this to
 *      `0`, so on the first build iteration the read returns `0` and we
 *      write `1` — same as `n=$(( $(cat … || echo 0) + 1 ))`.
 *   4. Count incomplete `- [ ]` items in `IMPLEMENTATION_PLAN.md`
 *      (default `0` if the file is missing).
 *   5. Emit a one-line diagnostic to stderr
 *      (`build iteration=… budget=… remaining=…`) mirroring the bash
 *      original. stderr is forwarded as a warning by the loop executor
 *      and does not fail the node.
 *   6. Exit `0` when the plan is exhausted (`remaining <= 0`) OR the
 *      budget is spent (`n >= budget`); else exit `1` (continue loop).
 *
 * Invoked from the workflow YAML as
 *   until_bash: bun run .archon/scripts/ralph-build-cap.ts "$ARTIFACTS_DIR"
 */

import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const BUDGET_DEFAULT = 1;

function readNumber(file: string, fallback: number): number {
  if (!existsSync(file)) return fallback;
  try {
    const parsed = parseInt(readFileSync(file, "utf8").trim(), 10);
    return Number.isFinite(parsed) ? parsed : fallback;
  } catch {
    return fallback;
  }
}

function countIncompletePlanItems(): number {
  if (!existsSync("IMPLEMENTATION_PLAN.md")) return 0;
  return readFileSync("IMPLEMENTATION_PLAN.md", "utf8").match(/^- \[ \]/gm)?.length ?? 0;
}

const artifactsDir = process.argv[2];
if (!artifactsDir || artifactsDir.length === 0) {
  console.error("ralph-build-cap: ARTIFACTS_DIR argument is required (argv[2])");
  process.exit(1);
}

const budget = readNumber(join(artifactsDir, "build-budget.txt"), BUDGET_DEFAULT);

const iterFile = join(artifactsDir, "build-iter.txt");
const n = readNumber(iterFile, 0) + 1;
writeFileSync(iterFile, `${n}\n`);

const remaining = countIncompletePlanItems();

console.error(`build iteration=${n} budget=${budget} remaining=${remaining}`);

process.exit(remaining <= 0 || n >= budget ? 0 : 1);
