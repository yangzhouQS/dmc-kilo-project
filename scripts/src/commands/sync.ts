/**
 * Sync upstream kilo-jetbrains changes into this project.
 *
 * Replaces sync-upstream.ps1 with:
 *   - Cross-platform path handling (no backslash hacks)
 *   - Fixed protected-files list (was missing settings.gradle.kts,
 *     had wrong plugin.xml path)
 *   - Marker-aware diff display for protected files
 *
 * Flow:
 *   1. Resolve monorepo path
 *   2. Read sync point (.upstream-sync)
 *   3. Diff changed files (sync-point → upstream HEAD)
 *   4. Classify: safe-copy / protected / deleted
 *   5. Copy safe files automatically
 *   6. Show diffs + marker regions for protected files (manual merge)
 *   7. Remove files deleted in upstream
 *   8. Update sync point
 */

import {
  diffNameStatus,
  diffFile,
  getHead,
  getHeadShort,
  getShortHash,
  gitTry,
  type NameStatusEntry,
} from '../lib/git.js';
import { readText, writeText, copy, remove, exists, ensureDir } from '../lib/files.js';
import path from 'node:path';
import {
  PROJECT_ROOT,
  SYNC_FILE,
  JET_PREFIX,
  ICON_PREFIX,
  isProtected,
  toRelPath,
  monorepoJetPath,
  monorepoIconPath,
  iconPath,
  jetPath,
  isCustomDir,
} from '../lib/paths.js';
import { scanMarkers, formatRegions, hasMarkers } from '../lib/markers.js';
import { c, header, ok, warn, err } from '../lib/colors.js';

export interface SyncOptions {
  monorepo?: string;
  dryRun?: boolean;
}

interface FileChange {
  rel: string;
  type: 'jet' | 'icon';
  action: 'copy' | 'protected' | 'delete';
  /** For renames: the old relative path being removed. */
  oldRel?: string;
}

export function runSync(opts: SyncOptions = {}): void {
  const monorepo = opts.monorepo ?? resolveMonorepo();
  const dryRun = opts.dryRun ?? false;

  // --- Validate monorepo ---
  if (!exists(`${monorepo}/.git`)) {
    console.error(err(`Monorepo not found at: ${monorepo}`));
    process.exit(1);
  }

  // --- Read sync point ---
  if (!exists(SYNC_FILE)) {
    console.error(err(`No sync point found at ${SYNC_FILE}.`));
    console.error(err('Run "npx tsx src/cli.ts init" first.'));
    process.exit(1);
  }

  const syncPoint = readText(SYNC_FILE).trim();

  // --- Get upstream HEAD ---
  const upstreamHead = getHead(monorepo);
  const upstreamShort = getHeadShort(monorepo);
  const syncShort = getShortHash(monorepo, syncPoint);

  if (syncPoint === upstreamHead) {
    console.log(ok(`Already up to date (sync point = HEAD = ${upstreamShort})`));
    process.exit(0);
  }

  console.log(header('=== Upstream Sync ==='));
  console.log(`From : ${syncShort}`);
  console.log(`To   : ${upstreamShort}`);
  console.log();

  // --- Get changed files ---
  const changed = diffNameStatus(monorepo, syncPoint, upstreamHead, [
    JET_PREFIX,
    ICON_PREFIX,
  ]);

  if (changed.length === 0) {
    console.log(ok('No changes in tracked paths.'));
    if (!dryRun) writeText(SYNC_FILE, upstreamHead);
    process.exit(0);
  }

  // --- Classify changes ---
  const changes = classifyChanges(changed);

  // --- Summary ---
  const toCopy = changes.filter((c) => c.action === 'copy');
  const protectedChanges = changes.filter((c) => c.action === 'protected');
  const toDelete = changes.filter((c) => c.action === 'delete');

  console.log(`${warn(`Changed files: ${changes.length}`)}`);
  console.log(`  Safe to copy : ${toCopy.length}`);
  console.log(`  Protected    : ${protectedChanges.length}`);
  console.log(`  Deleted      : ${toDelete.length}`);
  console.log();

  if (dryRun) {
    console.log(c.magenta('[DRY RUN] No files modified.'));
    console.log();
    if (toCopy.length > 0) {
      console.log('Files to copy:');
      toCopy.forEach((f) => console.log(`  ${c.gray('+')} ${f.rel}`));
    }
    if (protectedChanges.length > 0) {
      console.log();
      console.log(c.magenta('Protected files (manual merge needed):'));
      protectedChanges.forEach((f) => console.log(`  ${c.magenta('!')} ${f.rel}`));
    }
    process.exit(0);
  }

  // --- Copy safe files ---
  if (toCopy.length > 0) {
    console.log(warn('Copying safe files ...'));
    for (const f of toCopy) {
      copyChange(monorepo, f);
      console.log(`  ${c.gray('+')} ${f.rel}`);
    }
    console.log(ok(`Copied ${toCopy.length} files.`));
  }

  // --- Show protected file diffs + markers ---
  if (protectedChanges.length > 0) {
    console.log();
    console.log(c.magenta(c.bold('=== Protected files need manual merge ===')));
    for (const f of protectedChanges) {
      showProtectedDiff(monorepo, syncPoint, upstreamHead, f.rel, f.type);
    }
    console.log();
    console.log(
      c.magenta('These files were NOT overwritten. Review the diffs above and'),
    );
    console.log(
      c.magenta('manually apply upstream changes while keeping your customizations.'),
    );
  }

  // --- Handle deleted files ---
  if (toDelete.length > 0) {
    console.log();
    console.log(c.yellow('Deleted in upstream:'));
    for (const f of toDelete) {
      if (isCustomDir(f.rel)) continue;
      const localFile = resolveLocalPath(f.rel, f.type);
      if (exists(localFile)) {
        remove(localFile);
        console.log(`  ${c.gray('-')} ${f.rel} (removed)`);
      }
    }
  }

  // --- Update sync point ---
  writeText(SYNC_FILE, upstreamHead);
  console.log();
  console.log(ok(`Sync point updated to ${upstreamShort}`));

  // --- Suggest commit ---
  console.log();
  console.log(header('Review changes and commit:'));
  console.log('  git add -A');
  console.log(`  git commit -m "sync upstream ${syncShort}..${upstreamShort}"`);
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function resolveMonorepo(): string {
  // 1. Try upstream-local remote URL
  const remoteUrl = gitTry(PROJECT_ROOT, ['remote', 'get-url', 'upstream-local']).stdout;
  if (remoteUrl && exists(remoteUrl)) {
    return remoteUrl;
  }
  // 2. Default: parent of project root
  return path.resolve(PROJECT_ROOT, '..');
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function classifyChanges(entries: NameStatusEntry[]): FileChange[] {
  const changes: FileChange[] = [];

  for (const entry of entries) {
    const relInfo = toRelPath(entry.file);
    if (!relInfo) continue;

    const { rel, type } = relInfo;

    // Skip custom module entirely
    if (isCustomDir(rel)) continue;

    // Deleted
    if (entry.status === 'D') {
      changes.push({ rel, type, action: 'delete' });
      continue;
    }

    // Renamed / Copied
    if (entry.status[0] === 'R' || entry.status[0] === 'C') {
      const oldRelInfo = entry.newFile ? toRelPath(entry.newFile) : null;
      // Old path gets deleted, new path gets copied
      if (oldRelInfo) {
        changes.push({ rel: oldRelInfo.rel, type: oldRelInfo.type, action: 'delete' });
      }
      changes.push({ rel, type, action: isProtected(rel) ? 'protected' : 'copy' });
      continue;
    }

    // Modified or Added
    changes.push({
      rel,
      type,
      action: isProtected(rel) ? 'protected' : 'copy',
    });
  }

  return changes;
}

function copyChange(monorepo: string, change: FileChange): void {
  const src = resolveMonorepoPath(monorepo, change.rel, change.type);
  const dst = resolveLocalPath(change.rel, change.type);
  ensureDir(path.dirname(dst));
  copy(src, dst);
}

function resolveMonorepoPath(monorepo: string, rel: string, type: 'jet' | 'icon'): string {
  if (type === 'icon') return monorepoIconPath(monorepo, rel);
  return monorepoJetPath(monorepo, rel);
}

function resolveLocalPath(rel: string, type: 'jet' | 'icon'): string {
  if (type === 'icon') return iconPath(rel);
  return jetPath(rel);
}

function showProtectedDiff(
  monorepo: string,
  syncPoint: string,
  upstreamHead: string,
  rel: string,
  type: 'jet' | 'icon',
): void {
  const gitPath = type === 'jet' ? `${JET_PREFIX}${rel}` : `${ICON_PREFIX}${rel}`;
  console.log();
  console.log(`${c.bold(`--- ${rel} ---`)}`);

  // Show upstream diff
  const diff = diffFile(monorepo, syncPoint, upstreamHead, gitPath);
  if (diff) {
    console.log(colorizeDiff(diff));
  } else {
    console.log('  (no text diff)');
  }

  // Show local marker regions
  const localPath = resolveLocalPath(rel, type);
  if (exists(localPath)) {
    const content = readText(localPath);
    if (hasMarkers(content)) {
      const { regions, errors } = scanMarkers(content);
      console.log();
      console.log(`  ${c.cyan('Local custom_change markers:')}`);
      formatRegions(regions).forEach((line) => console.log(c.cyan(line)));
      if (errors.length > 0) {
        errors.forEach((e) => console.log(`  ${err(e)}`));
      }
    } else {
      console.log(`  ${c.gray('(no custom_change markers in local file)')}`);
    }
  }
}

function colorizeDiff(diff: string): string {
  return diff
    .split('\n')
    .map((line) => {
      if (line.startsWith('+++') || line.startsWith('---')) return c.bold(line);
      if (line.startsWith('@@')) return c.cyan(line);
      if (line.startsWith('+')) return c.green(line);
      if (line.startsWith('-')) return c.red(line);
      return c.gray(line);
    })
    .join('\n');
}
