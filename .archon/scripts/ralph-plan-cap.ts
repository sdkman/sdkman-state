#!/usr/bin/env bun
/**
 * Ralph PLAN-CAP — `until_bash` counter for the `plan` loop.
 *
 * Replaces the inline `until_bash` block on the `plan` node in
 * `.archon/workflows/ralph-wiggum.yaml`. Caps the plan loop at the same
 * hard ceiling as the upstream port (`PLAN_DEFAULT_CAP=3`) and treats the
 * cap as a **graceful** stop — exit `0` once the count reaches 3, not a
 * `max_iterations` failure.
 *
 * Behaviour:
 *   1. Read the artifacts directory from `process.argv[2]`. `until_bash`
 *      runs in Archon's loop executor, which does **not** inject
 *      `ARTIFACTS_DIR` into the subprocess env (see spec §Execution
 *      Contracts) — reading `process.env.ARTIFACTS_DIR` would be
 *      `undefined` and silently break the cap. The YAML therefore passes
 *      `"$ARTIFACTS_DIR"` (double-quoted) as the first argv slot.
 *   2. Read `<artifactsDir>/plan-iter.txt`, defaulting to `0` when the
 *      file is missing, empty, or unreadable. Nothing seeds this file
 *      (unlike `build-iter.txt`, which `ralph-plan-count.ts` initialises
 *      to `0` up front), so the **first** plan pass always reads a
 *      non-existent file — mirroring the original bash
 *      `cat … 2>/dev/null || echo 0`.
 *   3. Increment, write the new count back.
 *   4. Exit `0` when `n >= 3` (loop complete — graceful stop), else
 *      exit `1` (continue to the next iteration).
 *
 * Invoked from the workflow YAML as
 *   until_bash: bun run .archon/scripts/ralph-plan-cap.ts "$ARTIFACTS_DIR"
 */

import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const PLAN_DEFAULT_CAP = 3;

function readCounter(file: string): number {
  if (!existsSync(file)) return 0;
  try {
    const parsed = parseInt(readFileSync(file, "utf8").trim(), 10);
    return Number.isFinite(parsed) ? parsed : 0;
  } catch {
    return 0;
  }
}

const artifactsDir = process.argv[2];
if (!artifactsDir || artifactsDir.length === 0) {
  console.error("ralph-plan-cap: ARTIFACTS_DIR argument is required (argv[2])");
  process.exit(1);
}

const iterFile = join(artifactsDir, "plan-iter.txt");
const n = readCounter(iterFile) + 1;
writeFileSync(iterFile, `${n}\n`);

process.exit(n >= PLAN_DEFAULT_CAP ? 0 : 1);
