/**
 * Scan all protected files for custom_change markers.
 *
 * Reports marker regions and validation errors.
 * Exits non-zero if any errors are found.
 *
 * New functionality — no PS1 equivalent.
 */

import { readText, exists, walkDir } from '../lib/files.js';
import { JET_DIR, PROTECTED_FILES, isCustomDir } from '../lib/paths.js';
import { scanMarkers, formatRegions, hasMarkers, countMarkers } from '../lib/markers.js';
import { c, header, ok, warn, err } from '../lib/colors.js';
import path from 'node:path';

export interface ScanOptions {
  /** Also scan the custom/ directory for stray markers. */
  includeCustom?: boolean;
}

export function runScanMarkers(opts: ScanOptions = {}): void {
  const includeCustom = opts.includeCustom ?? false;

  console.log(header('=== Scanning custom_change markers ==='));
  console.log();

  let totalMarkers = 0;
  let totalErrors = 0;
  let filesWithMarkers = 0;

  // --- Protected files ---
  console.log(c.bold('Protected files:'));
  for (const rel of PROTECTED_FILES) {
    const full = path.join(JET_DIR, ...rel.split('/'));
    if (!exists(full)) {
      console.log(`  ${c.gray('?')} ${rel} ${c.gray('(not found)')}`);
      continue;
    }

    const content = readText(full);
    const { regions, errors } = scanMarkers(content);
    const counts = countMarkers(content);

    if (counts.total === 0 && errors.length === 0) {
      console.log(`  ${c.gray('·')} ${rel} ${c.gray('(no markers)')}`);
      continue;
    }

    filesWithMarkers++;
    totalMarkers += counts.total;
    totalErrors += errors.length;

    const status = errors.length > 0 ? err('ERROR') : ok('OK');
    console.log(`  ${c.green('!')} ${rel}  [${counts.inline} inline, ${counts.block} block]  ${status}`);

    if (regions.length > 0) {
      for (const line of formatRegions(regions)) {
        console.log(`    ${c.cyan(line)}`);
      }
    }

    for (const e of errors) {
      console.log(`    ${err(e)}`);
    }
  }

  // --- Custom directory (optional) ---
  if (includeCustom) {
    console.log();
    console.log(c.bold('Custom module (stray marker check):'));
    const customDir = path.join(JET_DIR, 'custom');
    if (exists(customDir)) {
      const files = walkDir(customDir).filter(
        (f) => f.endsWith('.kt') || f.endsWith('.kts') || f.endsWith('.xml'),
      );
      let strayFound = false;
      for (const f of files) {
        const content = readText(f);
        if (hasMarkers(content)) {
          strayFound = true;
          const rel = path.relative(JET_DIR, f).replace(/\\/g, '/');
          const counts = countMarkers(content);
          console.log(`  ${warn('!')} ${rel} ${c.gray(`(${counts.total} markers — custom files should not need custom_change)`)}`);
        }
      }
      if (!strayFound) {
        console.log(`  ${c.gray('·')} No stray markers found.`);
      }
    } else {
      console.log(`  ${c.gray('?')} custom/ directory not found`);
    }
  }

  // --- Summary ---
  console.log();
  console.log(header('=== Summary ==='));
  console.log(`Files with markers : ${filesWithMarkers}`);
  console.log(`Total markers      : ${totalMarkers}`);
  console.log(`Validation errors  : ${totalErrors > 0 ? err(String(totalErrors)) : ok('0')}`);

  if (totalErrors > 0) {
    console.log();
    console.error(err('Marker validation failed! Fix the errors above before syncing.'));
    process.exit(1);
  }
}
