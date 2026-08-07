/**
 * Rebuild custom_change markers for a file by comparing it with the
 * last synced upstream version.
 *
 * Adapted from kilocode's fix-kilocode-markers.ts.
 *
 * Algorithm:
 *   1. Strip existing custom_change markers from the local file (clean)
 *   2. Read the upstream version at the sync point (.upstream-sync)
 *   3. Diff clean-local vs upstream → changed line numbers
 *   4. Re-annotate: wrap changed ranges with fresh markers
 *   5. If file doesn't exist upstream → add "new file" marker
 *
 * Usage:
 *   npx tsx src/cli.ts fix-markers packages/kilo-jetbrains/build.gradle.kts
 *   npx tsx src/cli.ts fix-markers packages/kilo-jetbrains/build.gradle.kts --dry-run
 *   npx tsx src/cli.ts fix-markers --all
 */

import path from 'node:path';
import { readText, writeText, exists } from '../lib/files.js';
import { PROJECT_ROOT, SYNC_FILE, JET_DIR, PROTECTED_FILES } from '../lib/paths.js';
import { showFile, gitTry } from '../lib/git.js';
import {
  supported,
  clean,
  changed,
  ranges,
  annotate,
  fresh,
  join,
} from '../lib/marker-dsl.js';
import { c, header, ok, warn, err } from '../lib/colors.js';

export interface FixMarkersOptions {
  /** Repo-relative file path. */
  file?: string;
  /** Process all protected files. */
  all?: boolean;
  dryRun?: boolean;
}

export function runFixMarkers(opts: FixMarkersOptions = {}): void {
  const files = resolveFiles(opts);
  if (files.length === 0) {
    console.error(err('No files specified. Use: fix-markers <file> or fix-markers --all'));
    process.exit(1);
  }

  // Resolve monorepo + sync point
  const monorepo = resolveMonorepo();
  const syncParts = readText(SYNC_FILE).trim().split(/\s+/);
  const syncPoint = syncParts[0];
  const syncShort = gitTry(monorepo, ['rev-parse', '--short', syncPoint]).stdout || syncPoint.slice(0, 8);

  console.log(header('=== Rebuilding custom_change markers ==='));
  console.log(`Upstream sync point: ${syncShort}`);
  console.log(`Mode: ${opts.dryRun ? c.magenta('DRY RUN') : 'live'}`);
  console.log();

  let updated = 0;
  let unchanged = 0;

  for (const file of files) {
    const result = fixOneFile(monorepo, syncPoint, file, opts.dryRun ?? false);
    if (result === 'updated') updated++;
    else unchanged++;
  }

  console.log();
  console.log(`Updated: ${updated}, Unchanged: ${unchanged}`);
  if (updated > 0 && !opts.dryRun) {
    console.log(ok('Review changes with: git diff'));
  }
}

function resolveFiles(opts: FixMarkersOptions): string[] {
  if (opts.all) {
    // Process all protected files that exist locally
    return PROTECTED_FILES.map((rel) => `packages/kilo-jetbrains/${rel}`).filter((rel) => {
      const abs = path.join(PROJECT_ROOT, ...rel.split('/'));
      return exists(abs);
    });
  }
  return opts.file ? [normalizePath(opts.file)] : [];
}

function normalizePath(p: string): string {
  return p.replace(/\\/g, '/').replace(/^\.\//, '');
}

function fixOneFile(monorepo: string, syncPoint: string, repoRel: string, dryRun: boolean): 'updated' | 'unchanged' {
  const abs = path.join(PROJECT_ROOT, ...repoRel.split('/'));

  if (!exists(abs)) {
    console.log(`  ${c.gray('[skip]')} ${repoRel} — file not found`);
    return 'unchanged';
  }

  const current = readText(abs);

  if (!supported(repoRel, current)) {
    console.log(`  ${c.gray('[skip]')} ${repoRel} — unsupported file type`);
    return 'unchanged';
  }

  if (current.includes('\0')) {
    console.log(`  ${c.gray('[skip]')} ${repoRel} — binary file`);
    return 'unchanged';
  }

  // Strip existing markers
  const cleaned = clean(repoRel, current);

  // Read upstream version at sync point
  const upstreamText = showFile(monorepo, syncPoint, repoRel);

  let next: string;
  if (upstreamText === null) {
    // File doesn't exist upstream → mark as new file
    next = fresh(repoRel, cleaned);
    console.log(`  ${warn('[new]')} ${repoRel} — does not exist upstream`);
  } else {
    // Diff clean-local vs upstream
    const upstreamCleaned = clean(repoRel, upstreamText);
    const diff = changed(upstreamCleaned.text, cleaned.text);

    if (diff.lines.size === 0 && diff.deleted === 0) {
      // No differences after stripping markers
      next = cleaned.text.lines.length === 0 ? current : join(cleaned.text);
      if (next === current) {
        console.log(`  ${c.gray('[ok]')} ${repoRel} — no changes, markers normalized`);
        return 'unchanged';
      }
    } else {
      // Re-annotate with fresh markers
      const foundRanges = ranges(diff.lines);
      next = annotate(repoRel, cleaned, foundRanges);

      if (diff.deleted > 0) {
        console.log(`  ${warn('[warn]')} ${repoRel} — ${diff.deleted} upstream-only line(s) cannot be annotated`);
      }
    }
  }

  if (next === current) {
    console.log(`  ${c.gray('[ok]')} ${repoRel} — already has normalized markers`);
    return 'unchanged';
  }

  if (!dryRun) {
    writeText(abs, next);
  }
  console.log(`  ${ok('[updated]')} ${repoRel}`);
  return 'updated';
}

function resolveMonorepo(): string {
  const remoteUrl = gitTry(PROJECT_ROOT, ['remote', 'get-url', 'upstream-local']).stdout;
  if (remoteUrl && exists(remoteUrl)) return remoteUrl;
  return path.resolve(PROJECT_ROOT, '..');
}
