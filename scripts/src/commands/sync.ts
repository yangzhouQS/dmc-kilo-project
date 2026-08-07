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
  getHead,
  getHeadShort,
  getShortHash,
  gitTry,
  showFile,
  resolveTag,
  fetchTags,
  listTags,
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
import { mergeProtectedFile, type MergeStrategy } from '../lib/merge.js';
import { c, header, ok, warn, err } from '../lib/colors.js';

export interface SyncOptions {
  monorepo?: string;
  dryRun?: boolean;
  /** Upstream tag to sync to (e.g. "jetbrains/v7.0.12"). */
  tag?: string;
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

  // --- Read sync point (commit hash, optional tag) ---
  if (!exists(SYNC_FILE)) {
    console.error(err(`No sync point found at ${SYNC_FILE}.`));
    console.error(err('Run "npx tsx src/cli.ts init" first.'));
    process.exit(1);
  }

  const syncParts = readText(SYNC_FILE).trim().split(/\s+/);
  const syncPoint = syncParts[0];
  const syncTag = syncParts[1];

  // --- Resolve sync target (tag or HEAD) ---
  let targetCommit: string;
  let targetTag: string | undefined;

  if (opts.tag) {
    const resolved = resolveTag(monorepo, opts.tag);
    if (!resolved) {
      console.log(warn(`Tag "${opts.tag}" not found in monorepo, fetching from upstream...`));
      const upstreamUrl = gitTry(PROJECT_ROOT, ['remote', 'get-url', 'upstream']).stdout;
      if (upstreamUrl) {
        try {
          fetchTags(monorepo, upstreamUrl);
        } catch {
          // monorepo's origin might differ; try fetching from upstream remote URL
        }
      }
      const retry = resolveTag(monorepo, opts.tag);
      if (!retry) {
        console.error(err(`Tag "${opts.tag}" not found. Available jetbrains tags:`));
        const tags = listTags(monorepo, 'jetbrains/*').slice(0, 10);
        tags.forEach((t: string) => console.error(`  ${t}`));
        if (tags.length === 0) console.error('  (none found — run: git -C <monorepo> fetch origin --tags)');
        process.exit(1);
      }
      targetCommit = retry.commit;
      targetTag = retry.tag;
    } else {
      targetCommit = resolved.commit;
      targetTag = resolved.tag;
    }
  } else {
    targetCommit = getHead(monorepo);
    targetTag = undefined;
  }

  const targetShort = getShortHash(monorepo, targetCommit);
  const syncShort = getShortHash(monorepo, syncPoint);

  if (syncPoint === targetCommit) {
    const tagInfo = syncTag ? ` (tag: ${syncTag})` : '';
    console.log(ok(`Already up to date (${syncShort}${tagInfo})`));
    process.exit(0);
  }

  console.log(header('=== Upstream Sync ==='));
  console.log(`From : ${syncShort}${syncTag ? ` (tag: ${syncTag})` : ''}`);
  console.log(`To   : ${targetShort}${targetTag ? ` (tag: ${c.cyan(targetTag)})` : ' (HEAD)'}`);
  console.log();

  // --- Get changed files ---
  const changed = diffNameStatus(monorepo, syncPoint, targetCommit, [
    JET_PREFIX,
    ICON_PREFIX,
  ]);

  if (changed.length === 0) {
    console.log(ok('No changes in tracked paths.'));
    if (!dryRun) writeSyncPoint(targetCommit, targetTag);
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

  // --- 3-way merge protected files ---
  let totalConflicts = 0;
  if (protectedChanges.length > 0) {
    console.log();
    console.log(warn('Merging protected files (3-way) ...'));
    for (const f of protectedChanges) {
      const conflictCount = mergeProtected(monorepo, syncPoint, targetCommit, f.rel, f.type, dryRun);
      totalConflicts += conflictCount;
    }
    if (totalConflicts > 0) {
      console.log();
      console.log(err(`${totalConflicts} conflict(s) need manual resolution.`));
      console.log(err('Files with conflict markers are written to disk — open them in your editor.'));
      console.log(err('Resolve conflicts, then run: npx tsx src/cli.ts fix-markers --all'));
    }
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
  writeSyncPoint(targetCommit, targetTag);
  console.log();
  console.log(ok(`Sync point updated to ${targetShort}${targetTag ? ` (tag: ${targetTag})` : ''}`));

  // --- Suggest commit ---
  console.log();
  console.log(header('Review changes and commit:'));
  console.log('  git add -A');
  const commitMsg = targetTag
    ? `sync upstream ${targetTag}`
    : `sync upstream ${syncShort}..${targetShort}`;
  console.log(`  git commit -m "${commitMsg}"`);
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

/**
 * Perform a 3-way merge of a protected file.
 * Returns the number of conflicts (0 = clean).
 */
function mergeProtected(
  monorepo: string,
  syncPoint: string,
  targetCommit: string,
  rel: string,
  type: 'jet' | 'icon',
  dryRun: boolean,
): number {
  const gitPath = type === 'jet' ? `${JET_PREFIX}${rel}` : `${ICON_PREFIX}${rel}`;
  const localPath = resolveLocalPath(rel, type);

  const localContent = exists(localPath) ? readText(localPath) : '';

  const result = mergeProtectedFile(
    () => showFile(monorepo, syncPoint, gitPath),
    () => showFile(monorepo, targetCommit, gitPath),
    localContent,
  );

  const tag = strategyTag(result.strategy);

  switch (result.strategy) {
    case 'clean':
      if (!dryRun && result.content !== null) {
        writeText(localPath, result.content);
      }
      console.log(`  ${ok(tag)} ${rel} — ${result.summary}`);
      return 0;

    case 'conflict':
      if (!dryRun && result.content !== null) {
        writeText(localPath, result.content);
      }
      console.log(`  ${err(tag)} ${rel} — ${result.summary}`);
      // Show local markers for reference
      if (hasMarkers(localContent)) {
        const { regions } = scanMarkers(localContent);
        if (regions.length > 0) {
          console.log(`    ${c.cyan('Existing custom_change markers:')}`);
          formatRegions(regions).forEach((line) => console.log(`    ${c.cyan(line)}`));
        }
      }
      return result.conflicts;

    case 'theirs-only':
      if (!dryRun && result.content !== null) {
        writeText(localPath, result.content);
      }
      console.log(`  ${ok(tag)} ${rel} — ${result.summary}`);
      return 0;

    case 'deleted':
      if (!dryRun && exists(localPath)) {
        remove(localPath);
      }
      console.log(`  ${c.gray(tag)} ${rel} — ${result.summary}`);
      return 0;

    case 'unchanged':
      console.log(`  ${c.gray(tag)} ${rel} — ${result.summary}`);
      return 0;

    case 'upstream-missing':
      console.log(`  ${c.gray(tag)} ${rel} — ${result.summary}`);
      return 0;

    case 'error':
      console.log(`  ${err(tag)} ${rel} — ${result.summary}`);
      return 1;
  }
}

function strategyTag(strategy: MergeStrategy): string {
  switch (strategy) {
    case 'clean': return '[merged]';
    case 'conflict': return '[CONFLICT]';
    case 'theirs-only': return '[new]';
    case 'deleted': return '[deleted]';
    case 'unchanged': return '[skip]';
    case 'upstream-missing': return '[n/a]';
    case 'error': return '[ERROR]';
  }
}

/**
 * Write sync point file: commit hash + optional tag name.
 */
function writeSyncPoint(commit: string, tag?: string): void {
  writeText(SYNC_FILE, tag ? `${commit} ${tag}` : commit);
}
