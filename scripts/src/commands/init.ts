/**
 * Initialize dmc-kilo-project from a local monorepo.
 *
 * Replaces init-project.ps1.
 * Copies packages/kilo-jetbrains + provider icons, records sync point,
 * sets up git.
 */

import fs from 'node:fs';
import path from 'node:path';
import { exists, ensureDir, walkDir } from '../lib/files.js';
import { PROJECT_ROOT, JET_DIR, SYNC_FILE } from '../lib/paths.js';
import { getHeadShort, gitExec } from '../lib/git.js';
import { c, header, ok, warn, err } from '../lib/colors.js';

export interface InitOptions {
  monorepo?: string;
}

const EXCLUDE_DIRS = new Set(['build', '.gradle', '.idea', 'node_modules', 'dist']);

export function runInit(opts: InitOptions = {}): void {
  const monorepo = opts.monorepo ?? path.resolve(PROJECT_ROOT, '..');

  if (!exists(path.join(monorepo, '.git'))) {
    console.error(err(`Monorepo not found at: ${monorepo}`));
    process.exit(1);
  }

  console.log(header('=== DMC Kilo Project - Initial Setup ==='));
  console.log(`Project root : ${PROJECT_ROOT}`);
  console.log(`Monorepo     : ${monorepo}`);
  console.log();

  // --- Step 1: Copy packages/kilo-jetbrains ---
  const srcJet = path.join(monorepo, 'packages', 'kilo-jetbrains');
  if (!exists(srcJet)) {
    console.error(err('packages/kilo-jetbrains not found in monorepo'));
    process.exit(1);
  }

  console.log(warn('[1/5] Copying packages/kilo-jetbrains ...'));
  copyJetbrains(srcJet, JET_DIR);
  console.log(ok('      Done.'));

  // --- Step 2: Copy provider icons ---
  const srcIcons = path.join(monorepo, 'packages', 'ui', 'src', 'assets', 'icons', 'provider');
  const dstIcons = path.join(PROJECT_ROOT, 'packages', 'ui', 'src', 'assets', 'icons', 'provider');

  if (exists(srcIcons)) {
    console.log(warn('[2/5] Copying provider icons ...'));
    ensureDir(dstIcons);
    let iconCount = 0;
    for (const file of walkDir(srcIcons)) {
      if (file.endsWith('.svg')) {
        const rel = path.relative(srcIcons, file);
        const dst = path.join(dstIcons, rel);
        ensureDir(path.dirname(dst));
        fs.copyFileSync(file, dst);
        iconCount++;
      }
    }
    console.log(ok(`      Copied ${iconCount} icons.`));
  } else {
    console.log(err('[2/5] Provider icons not found - skipped.'));
  }

  // --- Step 3: Record upstream sync point ---
  const upstreamHead = gitExec(monorepo, ['rev-parse', 'HEAD']);
  const upstreamShort = getHeadShort(monorepo);

  fs.writeFileSync(SYNC_FILE, upstreamHead, 'utf-8');
  console.log(ok(`[3/5] Sync point recorded: ${upstreamShort}`));

  // --- Step 4: Git init ---
  if (!exists(path.join(PROJECT_ROOT, '.git'))) {
    console.log(warn('[4/5] Initializing git ...'));
    gitExec(PROJECT_ROOT, ['init', '--quiet']);
    gitExec(PROJECT_ROOT, ['add', '-A']);
    gitExec(PROJECT_ROOT, ['commit', '--quiet', '-m', `init: import from upstream ${upstreamShort}`]);
    console.log(ok('      Initial commit created.'));
  } else {
    console.log(c.gray('[4/5] Git already initialized.'));
  }

  // --- Step 5: Add upstream remotes ---
  console.log(warn('[5/5] Configuring git remotes ...'));
  const remotes = gitExec(PROJECT_ROOT, ['remote']);
  if (remotes.includes('upstream')) {
    gitExec(PROJECT_ROOT, ['remote', 'remove', 'upstream']);
  }
  if (remotes.includes('upstream-local')) {
    gitExec(PROJECT_ROOT, ['remote', 'remove', 'upstream-local']);
  }
  gitExec(PROJECT_ROOT, ['remote', 'add', 'upstream-local', monorepo]);
  gitExec(PROJECT_ROOT, ['remote', 'add', 'upstream', 'https://github.com/Kilo-Org/kilocode.git']);
  console.log(ok(`      upstream-local -> ${monorepo}`));
  console.log(ok('      upstream       -> github.com/Kilo-Org/kilocode'));

  console.log();
  console.log(header('=== Setup Complete ==='));
  console.log();
  console.log('Next steps:');
  console.log('  1. npx tsx src/cli.ts apply          — set your plugin ID/name');
  console.log('  2. npx tsx src/cli.ts scan-markers   — verify markers');
  console.log('  3. npx tsx src/cli.ts sync --dry-run — preview upstream sync');
}

/**
 * Recursively copy kilo-jetbrains, excluding build artifacts.
 */
function copyJetbrains(src: string, dst: string): void {
  fs.cpSync(src, dst, {
    recursive: true,
    filter: (source: string) => {
      const basename = path.basename(source);
      if (source === src) return true;
      if (EXCLUDE_DIRS.has(basename)) return false;
      if (basename.endsWith('.iml')) return false;
      return true;
    },
  });
}
