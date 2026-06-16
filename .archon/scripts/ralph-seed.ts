#!/usr/bin/env bun
/**
 * Ralph SEED node — scaffold loop artifacts from templates.
 *
 * Replaces the inline `seed` bash node in `.archon/workflows/ralph-wiggum.yaml`.
 *
 * Behaviour:
 *   1. Ensure `specs/` exists at the repo root.
 *   2. For each of {IMPLEMENTATION_PLAN.md, PROGRESS.md}: if absent, copy from
 *      `.archon/ralph/templates/`. If the template is also missing, exit
 *      non-zero — the previous "minimal fallback" branch is intentionally
 *      dropped (a missing checked-in template indicates misconfiguration
 *      and must surface loudly rather than be papered over).
 *   3. Print BRANCH / SPECS / PLAN_ITEMS_OUTSTANDING diagnostics to stdout
 *      for human use; no downstream node consumes `$seed.output`.
 *
 * Invoked by Archon as a named script (`runtime: bun`); no args, no stdin,
 * and `ARTIFACTS_DIR` is unused by this node.
 */

import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { execFileSync } from "node:child_process";

const TEMPLATE_DIR = ".archon/ralph/templates";
const ARTIFACTS = ["IMPLEMENTATION_PLAN.md", "PROGRESS.md"] as const;

function seedArtifact(name: string): void {
  if (existsSync(name)) return;
  const template = join(TEMPLATE_DIR, name);
  if (!existsSync(template)) {
    console.error(
      `ralph-seed: template missing at ${template}; refusing to create a minimal fallback`,
    );
    process.exit(1);
  }
  copyFileSync(template, name);
  console.log(`Created ${name} from template`);
}

function currentBranch(): string {
  try {
    const out = execFileSync("git", ["branch", "--show-current"], {
      stdio: ["ignore", "pipe", "ignore"],
    })
      .toString()
      .trim();
    return out.length > 0 ? out : "(no branch)";
  } catch {
    return "(no branch)";
  }
}

function listSpecsOrPlaceholder(): string[] {
  try {
    const entries = readdirSync("specs");
    return entries.length === 0 ? ["(none)"] : entries;
  } catch {
    return ["(none)"];
  }
}

function countIncompletePlanItems(): number {
  try {
    return readFileSync("IMPLEMENTATION_PLAN.md", "utf8").match(/^- \[ \]/gm)?.length ?? 0;
  } catch {
    return 0;
  }
}

mkdirSync("specs", { recursive: true });
for (const artifact of ARTIFACTS) seedArtifact(artifact);

console.log(`BRANCH=${currentBranch()}`);
console.log("SPECS:");
for (const entry of listSpecsOrPlaceholder()) console.log(entry);
console.log(`PLAN_ITEMS_OUTSTANDING=${countIncompletePlanItems()}`);
