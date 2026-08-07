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
  .option('--dry-run', 'Show what would change without modifying files')
  .action((opts) => {
    runSync({ monorepo: opts.monorepo, dryRun: opts.dryRun });
  });

// --- scan-markers ---
program
  .command('scan-markers')
  .description('Scan protected files for custom_change markers and validate')
  .option('--include-custom', 'Also scan custom/ directory for stray markers')
  .action((opts) => {
    runScanMarkers({ includeCustom: opts.includeCustom });
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

program.parse(process.argv);
