/**
 * Three-way file merge using `git merge-file`.
 *
 * `git merge-file` performs a proper 3-way diff/merge on a single file:
 *   ours ← base → theirs
 * It modifies the "ours" temp file in place, writing conflict markers
 * (<<<<<<< / ======= / >>>>>>>) when regions overlap.
 *
 * Exit codes:
 *   0  = clean merge, no conflicts
 *   >0 = number of conflicting regions
 *   <0 = error
 */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

export interface MergeResult {
  /** Merged content (may contain conflict markers if conflicts > 0). */
  merged: string;
  /** Number of conflicting regions (0 = clean). */
  conflicts: number;
  /** Whether an error occurred. */
  error?: string;
}

/**
 * Perform a 3-way merge of three text contents.
 *
 * @param ours   Current local content.
 * @param base   Common ancestor (upstream at sync point).
 * @param theirs Upstream at target tag.
 */
export function threeWayMerge(ours: string, base: string, theirs: string): MergeResult {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dmc-merge-'));
  const oursFile = path.join(tmp, 'ours');
  const baseFile = path.join(tmp, 'base');
  const theirsFile = path.join(tmp, 'theirs');

  try {
    fs.writeFileSync(oursFile, ours);
    fs.writeFileSync(baseFile, base);
    fs.writeFileSync(theirsFile, theirs);

    const result = spawnSync('git', ['merge-file', '--quiet', oursFile, baseFile, theirsFile], {
      encoding: 'utf-8',
    });

    // exit < 0 means error (e.g. file too large, bad arguments)
    if (result.status !== null && result.status < 0) {
      return { merged: ours, conflicts: 0, error: result.stderr || 'git merge-file failed' };
    }

    const conflicts = result.status ?? 0;
    const merged = fs.readFileSync(oursFile, 'utf-8');

    return { merged, conflicts };
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

/**
 * Strategy for merging a protected file during sync.
 */
export type MergeStrategy =
  | 'clean'          // 3-way merge succeeded with no conflicts
  | 'conflict'       // 3-way merge produced conflict markers
  | 'theirs-only'    // file is new upstream (base=null), take theirs
  | 'deleted'        // file deleted upstream (theirs=null)
  | 'unchanged'      // no changes detected
  | 'upstream-missing' // file doesn't exist upstream at all
  | 'error';

export interface ProtectedMergeResult {
  strategy: MergeStrategy;
  /** Merged content to write (null = don't write / delete). */
  content: string | null;
  /** Number of conflicts if strategy=conflict. */
  conflicts: number;
  /** Human-readable summary. */
  summary: string;
}

/**
 * Merge a single protected file using upstream content from two refs.
 *
 * @param monorepo    Path to the monorepo.
 * @param syncPoint   Git ref for the sync point (base).
 * @param targetTag   Git ref for the target tag (theirs).
 * @param gitPath     Monorepo-relative path (e.g. "packages/kilo-jetbrains/build.gradle.kts").
 * @param localContent Current local file content.
 */
export function mergeProtectedFile(
  readBase: () => string | null,
  readTheirs: () => string | null,
  localContent: string,
): ProtectedMergeResult {
  const base = readBase();
  const theirs = readTheirs();

  // Both null: file doesn't exist upstream at all
  if (base === null && theirs === null) {
    return {
      strategy: 'upstream-missing',
      content: null,
      conflicts: 0,
      summary: 'file does not exist upstream',
    };
  }

  // Base null but theirs exists: file is new upstream
  if (base === null && theirs !== null) {
    if (localContent === theirs) {
      return { strategy: 'unchanged', content: null, conflicts: 0, summary: 'already matches upstream (new)' };
    }
    return { strategy: 'theirs-only', content: theirs, conflicts: 0, summary: 'new upstream file, taking theirs' };
  }

  // Base exists but theirs null: deleted upstream
  if (base !== null && theirs === null) {
    return { strategy: 'deleted', content: null, conflicts: 0, summary: 'deleted in upstream' };
  }

  // Both exist: 3-way merge
  const result = threeWayMerge(localContent, base!, theirs!);

  if (result.error) {
    return { strategy: 'error', content: localContent, conflicts: 0, summary: result.error };
  }

  if (result.conflicts === 0) {
    // Check if anything actually changed
    if (result.merged === localContent) {
      return { strategy: 'unchanged', content: null, conflicts: 0, summary: 'no changes after merge' };
    }
    return { strategy: 'clean', content: result.merged, conflicts: 0, summary: 'clean 3-way merge' };
  }

  return {
    strategy: 'conflict',
    content: result.merged,
    conflicts: result.conflicts,
    summary: `${result.conflicts} conflict(s) need manual resolution`,
  };
}
