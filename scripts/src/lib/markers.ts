/**
 * custom_change marker parsing and validation.
 *
 * Supported marker formats (from AGENTS.md):
 *
 *   Kotlin/line inline:
 *     val x = 1 // custom_change
 *
 *   Kotlin/line block:
 *     // custom_change start
 *     val x = 1
 *     // custom_change end
 *
 *   XML block:
 *     <!-- custom_change -->
 *     <action .../>
 *     <!-- /custom_change -->
 */

/** A detected marker region (0-based line indices, inclusive). */
export interface MarkerRegion {
  type: 'inline' | 'block';
  /** 0-based line index where the region starts (inclusive). */
  startLine: number;
  /** 0-based line index where the region ends (inclusive). */
  endLine: number;
  /** The marker tag that opened this region. */
  opener: string;
}

/** Result of scanning a file for markers. */
export interface MarkerScanResult {
  regions: MarkerRegion[];
  errors: string[];
}

type LineRole = 'none' | 'block-start' | 'block-end' | 'inline';

/**
 * Classify a single line's role in marker semantics.
 */
function classifyLine(line: string): LineRole {
  const t = line.trim();

  // --- Block start ---
  // Kotlin:   // custom_change start
  // XML open: <!-- custom_change -->  (ONLY when it occupies the entire line)
  if (/custom_change\s+start/i.test(t)) return 'block-start';
  if (/^<!--\s*custom_change\s*-->$/.test(t)) return 'block-start';

  // --- Block end ---
  // Kotlin:   // custom_change end
  // XML close:<!-- /custom_change -->  (ONLY when it occupies the entire line)
  if (/custom_change\s+end/i.test(t)) return 'block-end';
  if (/^<!--\s*\/\s*custom_change\s*-->$/.test(t)) return 'block-end';

  // --- Inline (custom_change embedded in a line with other content) ---
  // e.g.  val x = 1 // custom_change
  //       <name>DMC Kilo</name> <!-- custom_change -->
  if (/custom_change/i.test(t)) return 'inline';

  return 'none';
}

/**
 * Scan file content for all custom_change markers.
 * Returns regions (with line ranges) and validation errors.
 */
export function scanMarkers(content: string): MarkerScanResult {
  const lines = content.split('\n');
  const regions: MarkerRegion[] = [];
  const errors: string[] = [];

  let blockStart: number | null = null;
  let blockOpener = '';

  for (let i = 0; i < lines.length; i++) {
    const role = classifyLine(lines[i]);

    if (role === 'block-start') {
      if (blockStart !== null) {
        errors.push(
          `Line ${i + 1}: nested custom_change start (unclosed block opened at line ${blockStart + 1})`,
        );
      }
      blockStart = i;
      blockOpener = lines[i].trim();
      continue;
    }

    if (role === 'block-end') {
      if (blockStart === null) {
        errors.push(`Line ${i + 1}: custom_change end without matching start`);
      } else {
        regions.push({
          type: 'block',
          startLine: blockStart,
          endLine: i,
          opener: blockOpener,
        });
        blockStart = null;
        blockOpener = '';
      }
      continue;
    }

    if (role === 'inline') {
      regions.push({
        type: 'inline',
        startLine: i,
        endLine: i,
        opener: lines[i].trim(),
      });
    }
  }

  if (blockStart !== null) {
    errors.push(`Unclosed custom_change block starting at line ${blockStart + 1}`);
  }

  return { regions, errors };
}

/**
 * Quick check: does the content contain any custom_change markers?
 */
export function hasMarkers(content: string): boolean {
  return /custom_change/i.test(content);
}

/**
 * Count markers of each type.
 */
export function countMarkers(content: string): {
  inline: number;
  block: number;
  total: number;
  errors: number;
} {
  const { regions, errors } = scanMarkers(content);
  const inline = regions.filter((r) => r.type === 'inline').length;
  const block = regions.filter((r) => r.type === 'block').length;
  return { inline, block, total: regions.length, errors: errors.length };
}

/**
 * Format marker regions as a human-readable summary.
 */
export function formatRegions(regions: MarkerRegion[]): string[] {
  const out: string[] = [];
  for (const r of regions) {
    const range =
      r.startLine === r.endLine
        ? `line ${r.startLine + 1}`
        : `lines ${r.startLine + 1}-${r.endLine + 1}`;
    out.push(`  [${r.type}] ${range}  ${r.opener}`);
  }
  return out;
}
