#!/usr/bin/env bun
/**
 * Ralph ARCHIVE node — relocate loop artifacts to `.ralph/<timestamp>/`.
 *
 * Replaces the inline `archive` bash node in
 * `.archon/workflows/ralph-wiggum.yaml`. Mirrors `ralph archive` from the
 * upstream port: stamp the run, move the two artifact files aside, leave
 * a clean working tree for the next ralph invocation.
 *
 * Behaviour:
 *   1. Mint a `YYYYMMDD-HHMMSS` timestamp from the current local time
 *      (matches `date +%Y%m%d-%H%M%S`).
 *   2. For each of {IMPLEMENTATION_PLAN.md, PROGRESS.md} that exists at
 *      the repo root, move it into `.ralph/<timestamp>/`. Create the
 *      destination directory **lazily** — only when something is about
 *      to land in it. With neither file present, `.ralph/<timestamp>/`
 *      is **not** created.
 *   3. Print `Archived: <f> -> <DEST>/<f>` per move, or
 *      `Nothing to archive.` if neither file was found. `$archive.output`
 *      is informational only — no downstream node consumes it.
 *
 * Invoked by Archon as a named script (`runtime: bun`); no args, no stdin,
 * and `ARTIFACTS_DIR` is unused by this node (the artifacts live at the
 * repo root, not in the workflow artifacts directory).
 */

import { existsSync, mkdirSync, renameSync } from "node:fs";
import { join } from "node:path";

const ARTIFACTS = ["IMPLEMENTATION_PLAN.md", "PROGRESS.md"] as const;

function timestamp(now: Date): string {
  // YYYYMMDD-HHMMSS in local time, matching `date +%Y%m%d-%H%M%S`.
  const pad = (n: number): string => String(n).padStart(2, "0");
  const date = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`;
  const time = `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  return `${date}-${time}`;
}

const dest = join(".ralph", timestamp(new Date()));
let moved = 0;

for (const artifact of ARTIFACTS) {
  if (!existsSync(artifact)) continue;
  // Lazy mkdir — destination created only when something is about to land in it.
  if (moved === 0) mkdirSync(dest, { recursive: true });
  renameSync(artifact, join(dest, artifact));
  console.log(`Archived: ${artifact} -> ${dest}/${artifact}`);
  moved += 1;
}

if (moved === 0) console.log("Nothing to archive.");
