#!/usr/bin/env tsx
/**
 * DMC Kilo Project CLI
 *
 * Unified entry point replacing 4 PowerShell scripts.
 *
 * Usage:
 *   npx tsx src/cli.ts <command> [options]
 *
 * Commands:
 *   init          Initialize project from monorepo
 *   apply         Apply custom changes to upstream files
 *   sync          Sync upstream changes (marker-aware)
 *   scan-markers  Scan and validate custom_change markers
 *   build         Build the plugin (delegates to gradlew)
 */

import { Command } from 'commander';
import { runInit } from './commands/init.js';
import { runApply } from './commands/apply.js';
import { runSync } from './commands/sync.js';
import { runScanMarkers } from './commands/scan-markers.js';
import { runFixMarkers } from './commands/fix-markers.js';
import { runResetCandidates } from './commands/reset-candidates.js';
import { execSync } from 'node:child_process';
import path from 'node:path';
import { JET_DIR } from './lib/paths.js';
import { exists } from './lib/files.js';

const program = new Command();

program
  .name('dmc-cli')
  .description('DMC Kilo Project build/sync toolchain')
  .version('1.0.0');

// --- init ---
program
  .command('init')
  .description('Initialize project from a local monorepo')
  .option('-m, --monorepo <path>', 'Path to the kilocode monorepo')
  .action((opts) => {
    runInit({ monorepo: opts.monorepo });
  });

// --- apply ---
program
  .command('apply')
  .description('Apply DMC customizations to upstream plugin files')
  .option('--plugin-id <id>', 'Plugin ID', 'com.dmc.kilo')
  .option('--plugin-name <name>', 'Plugin display name', 'DMC Kilo')
  .option('--dry-run', 'Show changes without modifying files')
  .action((opts) => {
    runApply({
      pluginId: opts.pluginId,
      pluginName: opts.pluginName,
      dryRun: opts.dryRun,
    });
  });

// --- sync ---
program
  .command('sync')
  .description('Sync upstream kilo-jetbrains changes into this project')
  .option('-m, --monorepo <path>', 'Path to the kilocode monorepo')
  .option('-t, --tag <tag>', 'Sync to a specific upstream tag (e.g. jetbrains/v7.0.12)')
  .option('--dry-run', 'Show what would change without modifying files')
  .action((opts) => {
    runSync({ monorepo: opts.monorepo, dryRun: opts.dryRun, tag: opts.tag });
  });

// --- scan-markers ---
program
  .command('scan-markers')
  .description('Scan protected files for custom_change markers and validate')
  .option('--include-custom', 'Also scan custom/ directory for stray markers')
  .action((opts) => {
    runScanMarkers({ includeCustom: opts.includeCustom });
  });

// --- fix-markers ---
program
  .command('fix-markers')
  .description('Rebuild custom_change markers by comparing with upstream sync point')
  .argument('[file]', 'Repo-relative file path')
  .option('--all', 'Process all protected files')
  .option('--dry-run', 'Show what would change without writing files')
  .action((file: string | undefined, opts) => {
    runFixMarkers({ file, all: opts.all, dryRun: opts.dryRun });
  });

// --- reset-candidates ---
program
  .command('reset-candidates')
  .description('Find files that drifted insignificantly from upstream and optionally reset them')
  .option('--review-limit <n>', 'Max non-marker diff lines for auto-reset', '5')
  .option('--dry-run', 'Classify only, do not write any files')
  .action((opts) => {
    runResetCandidates({
      reviewLimit: parseInt(opts.reviewLimit, 10),
      dryRun: opts.dryRun,
    });
  });

// --- build (thin wrapper around gradlew) ---
program
  .command('build')
  .description('Build the plugin via Gradle (delegates to gradlew)')
  .argument('[task]', 'Gradle task', 'buildPlugin')
  .action((task: string) => {
    if (!exists(path.join(JET_DIR, 'build.gradle.kts'))) {
      console.error('Plugin source not found. Run "init" first.');
      process.exit(1);
    }
    const isWin = process.platform === 'win32';
    const gradlew = isWin ? 'gradlew.bat' : './gradlew';
    execSync(`${gradlew} ${task}`, {
      cwd: JET_DIR,
      stdio: 'inherit',
    });
  });

// --- check-protected ---
program
  .command('check-protected')
  .description('Verify all files with custom_change markers are registered in PROTECTED_FILES')
  .action(() => {
    import('./commands/check-protected.js');
  });

program.parse(process.argv);
