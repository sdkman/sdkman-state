#!/usr/bin/env bun
/**
 * Ralph PLAN-COUNT node — size the build budget from the plan.
 *
 * Replaces the inline `plan-count` bash node in
 * `.archon/workflows/ralph-wiggum.yaml`.
 *
 * Behaviour:
 *   1. Count incomplete plan items in `IMPLEMENTATION_PLAN.md`
 *      (lines matching `^- \[ \]`). Missing file → 0 items.
 *   2. Compute the build budget with integer arithmetic:
 *        items < 1 → 1   (nothing to do; build loop no-ops once)
 *        items ≥ 1 → Math.floor((items * 6 + 4) / 5)   ≡ ceil(items * 1.2)
 *      The integer form is bit-identical to the bash original
 *      `(count * 6 + 4) / 5`; `Math.ceil(items * 1.2)` is avoided because
 *      `1.2` is not representable in IEEE-754 and would invite rounding
 *      surprises. The `items < 1` branch silently fixes a pre-existing
 *      latent bug in the bash where `grep -c … || echo 0` produced a
 *      two-line "0\n0" when there were no incomplete items.
 *   3. Write `build-budget.txt` (the budget) and `build-iter.txt` (`0`,
 *      the initial counter) into `process.env.ARTIFACTS_DIR`. These
 *      files are read later by `ralph-build-cap.ts`.
 *   4. Print `PLAN_ITEMS=<n>` and `BUILD_BUDGET=<n>` on stdout. The
 *      `report` node interpolates `$plan-count.output`, so these two
 *      lines are a **consumed contract**, not diagnostic logging.
 *
 * Invoked by Archon as a named script (`runtime: bun`); no args, no stdin.
 */

import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

function countIncompletePlanItems(): number {
  if (!existsSync("IMPLEMENTATION_PLAN.md")) return 0;
  return readFileSync("IMPLEMENTATION_PLAN.md", "utf8").match(/^- \[ \]/gm)?.length ?? 0;
}

function computeBudget(items: number): number {
  // Integer ceil(items * 1.2). Bit-identical to bash `(count * 6 + 4) / 5`.
  if (items < 1) return 1;
  return Math.floor((items * 6 + 4) / 5);
}

const artifactsDir = process.env.ARTIFACTS_DIR;
if (!artifactsDir || artifactsDir.length === 0) {
  console.error("ralph-plan-count: ARTIFACTS_DIR is not set");
  process.exit(1);
}

const items = countIncompletePlanItems();
const budget = computeBudget(items);

writeFileSync(join(artifactsDir, "build-budget.txt"), `${budget}\n`);
writeFileSync(join(artifactsDir, "build-iter.txt"), "0\n");

console.log(`PLAN_ITEMS=${items}`);
console.log(`BUILD_BUDGET=${budget}`);
