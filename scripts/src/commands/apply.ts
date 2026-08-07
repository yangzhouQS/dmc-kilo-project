/**
 * Apply DMC customizations to upstream plugin files.
 *
 * Replaces apply-custom-changes.ps1.
 * All modifications are idempotent and marked with `// custom_change` or `<!-- custom_change -->`.
 *
 * Modifications (3 files):
 *   1. settings.gradle.kts  — include(":custom")
 *   2. plugin.xml            — add custom module + change <name>
 *   3. build.gradle.kts      — change plugin ID
 */

import { readText, writeText } from '../lib/files.js';
import { JET_DIR, PROTECTED_FILES } from '../lib/paths.js';
import { hasMarkers, scanMarkers } from '../lib/markers.js';
import { c, header, ok, warn } from '../lib/colors.js';
import path from 'node:path';

export interface ApplyOptions {
  pluginId?: string;
  pluginName?: string;
  dryRun?: boolean;
}

const DEFAULT_ID = 'com.dmc.kilo';
const DEFAULT_NAME = 'DMC Kilo';

export function runApply(opts: ApplyOptions = {}): void {
  const pluginId = opts.pluginId ?? DEFAULT_ID;
  const pluginName = opts.pluginName ?? DEFAULT_NAME;
  const dryRun = opts.dryRun ?? false;

  console.log(header('=== Applying DMC Customizations ==='));
  console.log(`Plugin ID   : ${pluginId}`);
  console.log(`Plugin Name : ${pluginName}`);
  console.log(`Mode        : ${dryRun ? c.magenta('DRY RUN') : 'live'}`);
  console.log();

  const settingsPath = path.join(JET_DIR, 'settings.gradle.kts');
  const xmlPath = path.join(JET_DIR, 'src', 'main', 'resources', 'META-INF', 'plugin.xml');
  const buildPath = path.join(JET_DIR, 'build.gradle.kts');

  let changes = 0;

  // --- 1. settings.gradle.kts ---
  changes += applySettings(settingsPath, dryRun);

  // --- 2. plugin.xml ---
  changes += applyPluginXml(xmlPath, pluginName, dryRun);

  // --- 3. build.gradle.kts ---
  changes += applyBuildGradle(buildPath, pluginId, dryRun);

  // --- Summary ---
  console.log();
  console.log(header('=== Summary ==='));
  console.log(`Files modified: ${changes} (dry-run: ${dryRun})`);
  console.log();
  console.log('Protected files (all marked with custom_change):');
  for (const f of PROTECTED_FILES) {
    console.log(`  ${c.gray('-')} ${f}`);
  }
  console.log();
  console.log('Custom module (never conflicts with upstream):');
  console.log(`  ${c.gray('-')} custom/build.gradle.kts`);
  console.log(`  ${c.gray('-')} custom/src/main/resources/dmc.custom.xml`);
  console.log(`  ${c.gray('-')} custom/src/main/kotlin/com/dmc/**`);

  if (!dryRun) {
    console.log();
    console.log(ok('Next: run "npx tsx src/cli.ts scan-markers" to verify markers.'));
  }
}

/**
 * settings.gradle.kts: add include("custom") after include("shared").
 */
function applySettings(filePath: string, dryRun: boolean): number {
  const content = readText(filePath);

  if (/include\("custom"\)/.test(content)) {
    console.log(`${c.gray('[1/3]')} settings.gradle.kts: already has custom include`);
    return 0;
  }

  const updated = content.replace(
    /include\("shared"\)/,
    'include("shared")\ninclude("custom") // custom_change',
  );

  if (updated === content) {
    console.log(warn(`[1/3] settings.gradle.kts: could not find include("shared") — skipping`));
    return 0;
  }

  if (!dryRun) writeText(filePath, updated);
  console.log(`${ok('[1/3]')} settings.gradle.kts: added include("custom") // custom_change`);
  return 1;
}

/**
 * plugin.xml: add custom module to <content> + change <name>.
 */
function applyPluginXml(filePath: string, pluginName: string, dryRun: boolean): number {
  let content = readText(filePath);
  let changed = false;

  // Add custom module to <content> block
  if (!/com\.dmc\.kilo\.custom/.test(content)) {
    content = content.replace(
      /(<module\s+name="ai\.kilocode\.jetbrains\.backend"\s*\/>)/,
      '$1\n        <module name="com.dmc.kilo.custom"/> <!-- custom_change -->',
    );
    changed = true;
    console.log(`${ok('      [2a]')} plugin.xml: added custom module to <content>`);
  } else {
    console.log(`${c.gray('      [2a]')} plugin.xml: custom module already present`);
  }

  // Change <name> (skip if already correct)
  const currentName = content.match(/<name>([^<]*)<\/name>/)?.[1]?.trim();
  if (currentName !== pluginName) {
    const nameRegex = /<name>[^<]*<\/name>/;
    if (nameRegex.test(content)) {
      content = content.replace(nameRegex, `<name>${pluginName}</name> <!-- custom_change -->`);
      changed = true;
      console.log(`${ok('      [2b]')} plugin.xml: name -> ${pluginName}`);
    } else {
      console.log(warn(`      [2b] plugin.xml: <name> tag not found`));
    }
  } else {
    console.log(`${c.gray('      [2b]')} plugin.xml: name already ${pluginName}`);
  }

  if (changed && !dryRun) {
    writeText(filePath, content);
  }
  return changed ? 1 : 0;
}

/**
 * build.gradle.kts: change plugin ID in pluginConfiguration.
 */
function applyBuildGradle(filePath: string, pluginId: string, dryRun: boolean): number {
  const content = readText(filePath);

  const idRegex = /id\s*=\s*"ai\.kilocode\.jetbrains"/;
  if (!idRegex.test(content)) {
    console.log(
      warn('[3/3] build.gradle.kts: plugin ID pattern not found (may already be customized)'),
    );
    return 0;
  }

  const updated = content.replace(idRegex, `id = "${pluginId}" // custom_change`);
  if (!dryRun) writeText(filePath, updated);
  console.log(`${ok('[3/3]')} build.gradle.kts: plugin ID -> ${pluginId}`);
  return 1;
}
