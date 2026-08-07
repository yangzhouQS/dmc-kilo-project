/**
 * Find files whose drift from the last synced upstream is insignificant
 * and (optionally) reset them back to upstream.
 *
 * Adapted from kilocode's find-reset-candidates.ts.
 *
 * Usage:
 *   npx tsx src/cli.ts reset-candidates --dry-run
 *   npx tsx src/cli.ts reset-candidates --review-limit 3
 *   npx tsx src/cli.ts reset-candidates
 */

import path from 'node:path';
import { diffNameStatus, showFile, gitTry, type NameStatusEntry } from '../lib/git.js';
import { readText, exists } from '../lib/files.js';
import { PROJECT_ROOT, SYNC_FILE, JET_PREFIX, ICON_PREFIX, toRelPath, jetPath, iconPath } from '../lib/paths.js';
import { classifyDrift, resetToUpstream, shouldSkip, RESET_BUCKETS, type Bucket, type ClassifyResult } from '../lib/drift.js';
import { c, header, ok, warn, err } from '../lib/colors.js';

export interface ResetCandidatesOptions {
  scope?: string;
  reviewLimit?: number;
  dryRun?: boolean;
}

const BUCKET_ORDER: Bucket[] = [
  'markers-only',
  'cosmetic-only',
  'small-diff',
  'large-diff',
  'identical',
  'binary-diff',
  'binary-identical',
  'too-large',
  'upstream-missing',
  'local-missing',
];

interface Entry extends ClassifyResult {
  file: string;
  reset?: boolean;
}

export function runResetCandidates(opts: ResetCandidatesOptions = {}): void {
  const reviewLimit = opts.reviewLimit ?? 5;
  const dryRun = opts.dryRun ?? false;
  const scope = opts.scope;

  const monorepo = resolveMonorepo();
  const syncPoint = readText(SYNC_FILE).trim().split(/\s+/)[0];
  const syncShort = gitTry(monorepo, ['rev-parse', '--short', syncPoint]).stdout || syncPoint.slice(0, 8);

  console.log(header('=== Find reset-to-upstream candidates ==='));
  console.log(`Last synced upstream: ${syncShort}`);
  console.log(`Review limit: ${reviewLimit} non-marker diff line(s)`);
  console.log(`Mode: ${dryRun ? c.magenta('DRY RUN') : 'auto-apply'}`);
  console.log();

  // Get changed files between sync point and HEAD
  const changes = getChangedFiles(monorepo, syncPoint);

  if (changes.length === 0) {
    console.log(ok('No files differ from upstream. Nothing to do.'));
    return;
  }

  // Filter candidates
  const candidates = changes.filter((entry) => {
    const relInfo = toRelPath(entry.file);
    if (!relInfo) return false;
    return !shouldSkip(relInfo.rel);
  });

  console.log(`Candidate files: ${candidates.length}`);
  console.log();

  // Classify each candidate
  const entries: Entry[] = [];
  for (const entry of candidates) {
    const relInfo = toRelPath(entry.file);
    if (!relInfo) continue;

    const { rel, type } = relInfo;
    const localPath = type === 'jet' ? jetPath(rel) : iconPath(rel);
    const result = classifyDrift({
      monorepo,
      syncPoint,
      gitPath: entry.file,
      localPath,
      reviewLimit,
    });
    entries.push({ file: rel, ...result });
  }

  // Group by bucket
  const grouped = groupByBucket(entries);

  // Print summary table
  printSummary(grouped, dryRun);

  // Print details
  printDetails(grouped, dryRun);

  // Auto-reset
  if (!dryRun) {
    const resets = entries.filter((e) => RESET_BUCKETS.has(e.bucket));
    if (resets.length > 0) {
      console.log();
      console.log(warn(`Resetting ${resets.length} file(s) to upstream...`));
      for (const entry of resets) {
        const gitPath = `${JET_PREFIX}${entry.file}`;
        const localPath = jetPath(entry.file);
        const result = resetToUpstream(monorepo, syncPoint, gitPath, localPath, false);
        entry.reset = result.action !== 'identical';
        if (result.action === 'deleted') {
          console.log(`  ${c.gray('-')} ${entry.file} (deleted)`);
        } else if (result.action === 'written') {
          console.log(`  ${ok('+')} ${entry.file} (reset)`);
        }
      }
      console.log();
      console.log(ok(`Done. Review with: git diff`));
    }
  }
}

function resolveMonorepo(): string {
  const remoteUrl = gitTry(PROJECT_ROOT, ['remote', 'get-url', 'upstream-local']).stdout;
  if (remoteUrl && exists(remoteUrl)) return remoteUrl;
  return path.resolve(PROJECT_ROOT, '..');
}

function getChangedFiles(monorepo: string, syncPoint: string): NameStatusEntry[] {
  const head = gitTry(monorepo, ['rev-parse', 'HEAD']).stdout;
  if (!head) {
    console.error(err('Cannot determine monorepo HEAD'));
    process.exit(1);
  }
  return diffNameStatus(monorepo, syncPoint, head, [JET_PREFIX, ICON_PREFIX])
    .filter((e) => e.status === 'M' || e.status === 'A');
}

function groupByBucket(entries: Entry[]): Map<Bucket, Entry[]> {
  const out = new Map<Bucket, Entry[]>();
  for (const entry of entries) {
    const list = out.get(entry.bucket) ?? [];
    list.push(entry);
    out.set(entry.bucket, list);
  }
  for (const list of out.values()) {
    list.sort((a, b) => a.file.localeCompare(b.file));
  }
  return out;
}

function describe(bucket: Bucket, count: number, dryRun: boolean): { label: string; action: string } {
  const wouldReset = dryRun ? 'would reset' : 'reset';
  switch (bucket) {
    case 'markers-only': return { label: `markers-only (${count})`, action: wouldReset };
    case 'cosmetic-only': return { label: `cosmetic-only (${count})`, action: wouldReset };
    case 'small-diff': return { label: `small-diff (${count})`, action: wouldReset };
    case 'large-diff': return { label: `large-diff (${count})`, action: 'skipped' };
    case 'identical': return { label: `identical (${count})`, action: 'nothing' };
    case 'binary-diff': return { label: `binary-diff (${count})`, action: 'skipped' };
    case 'binary-identical': return { label: `binary-identical (${count})`, action: 'nothing' };
    case 'too-large': return { label: `too-large (${count})`, action: 'skipped' };
    case 'upstream-missing': return { label: `upstream-missing (${count})`, action: 'skipped' };
    case 'local-missing': return { label: `local-missing (${count})`, action: 'skipped' };
  }
}

function printSummary(grouped: Map<Bucket, Entry[]>, dryRun: boolean): void {
  console.log(c.bold('Summary:'));
  console.log();
  console.log('| Bucket | Count | Action |');
  console.log('|---|---|---|');
  for (const bucket of BUCKET_ORDER) {
    const items = grouped.get(bucket) ?? [];
    if (items.length === 0) continue;
    const info = describe(bucket, items.length, dryRun);
    console.log(`| ${bucket} | ${items.length} | ${info.action} |`);
  }
  console.log();
}

function printDetails(grouped: Map<Bucket, Entry[]>, dryRun: boolean): void {
  for (const bucket of BUCKET_ORDER) {
    const items = grouped.get(bucket) ?? [];
    if (items.length === 0) continue;
    const info = describe(bucket, items.length, dryRun);
    console.log(c.bold(`${info.label} — ${info.action}:`));
    for (const entry of items) {
      const suffix = entry.lines !== undefined ? ` (${entry.lines} line${entry.lines === 1 ? '' : 's'})` : '';
      const note = entry.reset === false ? ' [reset failed]' : '';
      console.log(`  ${entry.file}${c.gray(suffix)}${note}`);
    }
    console.log();
  }
}
