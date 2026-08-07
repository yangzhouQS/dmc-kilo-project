/**
 * Marker DSL — strip, diff, and re-annotate custom_change markers.
 *
 * Adapted from kilocode's script/upstream/utils/markers.ts.
 * Key differences:
 *   - Marker name: `custom_change` (not `kilocode_change`)
 *   - No branding transforms (DMC doesn't rename packages)
 *   - Node.js APIs (no Bun)
 */

import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
import { execFileSync } from 'node:child_process';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface Text {
  lines: string[];
  eol: string;
  final: boolean;
}

export interface Clean {
  text: Text;
  marks: Marks;
}

export interface Diff {
  /** 0-based line indices that changed in the "head" version. */
  lines: Set<number>;
  /** Lines that exist in base but not in head (deleted upstream-only). */
  deleted: number;
}

export interface Range {
  start: number;
  end: number;
}

export interface Block extends Range {
  before: string;
  after: string;
}

export interface Marks {
  inline: Map<number, string>;
  starts: Map<number, string>;
  ends: Map<number, string>;
  blocks: Block[];
  file?: string;
}

type Style = 'slash' | 'hash' | 'xml' | 'block';

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const MARKER = 'custom_change';

const standalone = [
  new RegExp(`^\\s*//\\s*${MARKER}\\b.*$`),
  new RegExp(`^\\s*#\\s*${MARKER}\\b.*$`),
  new RegExp(`^\\s*\\{?\\s*/\\*\\s*${MARKER}\\b.*\\*/\\}?\\s*$`),
];

const startRe = new RegExp(`\\b${MARKER}\\s+start\\b`);
const endRe = new RegExp(`\\b${MARKER}\\s+end\\b`);
const freshmarkRe = new RegExp(`\\b${MARKER}\\s*-\\s*new\\s+file\\b`);

const unsupportedExt = new Set(['.json', '.jsonc', '.lock', '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico']);

const styles = new Map<string, Style>([
  ['.kt', 'slash'],
  ['.kts', 'slash'],
  ['.ts', 'slash'],
  ['.tsx', 'slash'],
  ['.js', 'slash'],
  ['.xml', 'xml'],
  ['.properties', 'hash'],
  ['.toml', 'hash'],
  ['.yml', 'hash'],
  ['.yaml', 'hash'],
  ['.sh', 'hash'],
  ['.css', 'block'],
]);

// ---------------------------------------------------------------------------
// Basic helpers
// ---------------------------------------------------------------------------

export function ext(file: string): string {
  return path.extname(file).toLowerCase();
}

export function supported(file: string, text: string): boolean {
  const kind = ext(file);
  if (unsupportedExt.has(kind)) return false;
  if (styles.has(kind)) return true;
  return !kind && text.startsWith('#!');
}

function style(file: string): Style {
  return styles.get(ext(file)) ?? 'hash';
}

export function split(text: string): Text {
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  const final = text.endsWith('\n');
  const body = final ? text.slice(0, text.endsWith('\r\n') ? -2 : -1) : text;
  return { lines: body ? body.split(/\r?\n/) : [], eol, final };
}

export function join(text: Text): string {
  return text.lines.join(text.eol) + (text.final ? text.eol : '');
}

// ---------------------------------------------------------------------------
// Strip markers (clean)
// ---------------------------------------------------------------------------

function stripInline(file: string, line: string): { line: string; mark?: string } {
  const s = style(file);
  if (s === 'hash') {
    return comment(line, [new RegExp(`^#\\s*${MARKER}\\b`)]);
  }
  return comment(line, [
    new RegExp(`^\\{/\\*\\s*${MARKER}\\b`),
    new RegExp(`^<!--\\s*${MARKER}\\b`),
    new RegExp(`^/\\*\\s*${MARKER}\\b`),
    new RegExp(`^//\\s*${MARKER}\\b`),
  ]);
}

function comment(line: string, tokens: RegExp[]): { line: string; mark?: string } {
  let quote = '';
  let escape = false;
  for (let i = 0; i < line.length; i++) {
    const char = line[i];
    if (!char) continue;
    if (quote) {
      if (escape) { escape = false; continue; }
      if (char === '\\') { escape = true; continue; }
      if (char === quote) { quote = ''; continue; }
      continue;
    }
    if (char === '"' || char === "'" || char === '`') { quote = char; continue; }
    const rest = line.slice(i);
    if (tokens.some((t) => t.test(rest))) {
      const next = line.slice(0, i).trimEnd();
      return { line: next, mark: line.slice(next.length) };
    }
  }
  return { line };
}

export function clean(file: string, text: string): Clean {
  const parsed = split(text);
  const marks: Marks = { inline: new Map(), starts: new Map(), ends: new Map(), blocks: [] };
  const lines: string[] = [];
  const opens: { before: string; start?: number }[] = [];

  for (const line of parsed.lines) {
    if (standalone.some((re) => re.test(line))) {
      if (freshmarkRe.test(line)) marks.file = line;
      if (startRe.test(line)) {
        opens.push({ before: line });
        continue;
      }
      if (endRe.test(line)) {
        const open = opens.pop();
        const last = lines.length - 1;
        if (open?.start !== undefined && last >= open.start) {
          marks.ends.set(last, line);
          marks.blocks.push({ start: open.start, end: last, before: open.before, after: line });
        }
        continue;
      }
      continue;
    }

    const next = stripInline(file, line);
    const index = lines.length;
    lines.push(next.line);

    for (const open of opens) {
      if (open.start !== undefined) continue;
      open.start = index;
      marks.starts.set(index, open.before);
    }

    if (next.mark) marks.inline.set(index, next.mark);
  }

  return { text: { ...parsed, lines }, marks };
}

// ---------------------------------------------------------------------------
// Diff
// ---------------------------------------------------------------------------

export function changed(base: Text, head: Text): Diff {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dmc-diff-'));
  try {
    const left = path.join(tmp, 'base');
    const right = path.join(tmp, 'head');
    // Force final=true on both sides so git diff doesn't flag the last line
    // as changed due to "No newline at end of file" differences.
    fs.writeFileSync(left, join({ ...base, eol: '\n', final: true }));
    fs.writeFileSync(right, join({ ...head, eol: '\n', final: true }));

    let raw: string;
    try {
      raw = execFileSync('git', ['diff', '--no-index', '--no-ext-diff', '--unified=0', '--', left, right], {
        encoding: 'utf-8',
        stdio: ['pipe', 'pipe', 'pipe'],
      });
    } catch (e: any) {
      // git diff --no-index returns exit 1 when files differ (expected)
      raw = (e.stdout ?? '').toString();
    }

    if (!raw) return { lines: new Set(), deleted: 0 };
    return parseDiff(raw);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

function parseDiff(out: string): Diff {
  const lines = new Set<number>();
  let next = 0;
  let deleted = 0;
  let added = 0;
  let removed = 0;

  const flush = () => {
    if (removed > 0 && added === 0) deleted += removed;
    added = 0;
    removed = 0;
  };

  for (const line of out.split('\n')) {
    const hunk = line.match(/^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/);
    if (hunk) {
      flush();
      next = Number(hunk[1]) - 1;
      continue;
    }
    if (line.startsWith('+++') || line.startsWith('---')) continue;
    if (line.startsWith('+')) {
      if (line.slice(1).trim()) lines.add(next);
      added++;
      next++;
      continue;
    }
    if (line.startsWith('-')) {
      removed++;
      continue;
    }
    if (line.startsWith(' ')) next++;
  }

  flush();
  return { lines, deleted };
}

/**
 * Pure in-process line multiset diff. Moving lines around doesn't count as drift.
 */
export function approxDiff(base: string, head: string, opts?: { ignoreWhitespace?: boolean }): number {
  if (base === head) return 0;
  const norm = opts?.ignoreWhitespace
    ? (line: string) => line.replace(/\s+/g, ' ').trim()
    : (line: string) => line;
  const counts = new Map<string, number>();
  for (const line of base.split(/\r?\n/)) {
    const key = norm(line);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  for (const line of head.split(/\r?\n/)) {
    const key = norm(line);
    counts.set(key, (counts.get(key) ?? 0) - 1);
  }
  let total = 0;
  for (const v of counts.values()) total += Math.abs(v);
  return total;
}

// ---------------------------------------------------------------------------
// Ranges
// ---------------------------------------------------------------------------

export function ranges(nums: Set<number>): Range[] {
  const sorted = [...nums].sort((a, b) => a - b);
  return sorted.reduce<Range[]>((acc, num) => {
    const prev = acc.at(-1);
    if (prev && num === prev.end + 1) {
      prev.end = num;
      return acc;
    }
    acc.push({ start: num, end: num });
    return acc;
  }, []);
}

function mergeRanges(items: Range[]): Range[] {
  return [...items]
    .sort((a, b) => a.start - b.start)
    .reduce<Range[]>((acc, item) => {
      const prev = acc.at(-1);
      if (prev && item.start <= prev.end + 1) {
        prev.end = Math.max(prev.end, item.end);
        return acc;
      }
      acc.push({ ...item });
      return acc;
    }, []);
}

// ---------------------------------------------------------------------------
// Annotate (re-add markers)
// ---------------------------------------------------------------------------

function note(s: Style): string {
  if (s === 'hash') return ` # ${MARKER}`;
  if (s === 'xml') return ` <!-- ${MARKER} -->`;
  if (s === 'block') return ` /* ${MARKER} */`;
  return ` // ${MARKER}`;
}

function blockPair(s: Style, pad: string): { start: string; end: string } {
  if (s === 'hash') return { start: `${pad}# ${MARKER} start`, end: `${pad}# ${MARKER} end` };
  if (s === 'xml') return { start: `${pad}<!-- ${MARKER} -->`, end: `${pad}<!-- /${MARKER} -->` };
  if (s === 'block') return { start: `${pad}/* ${MARKER} start */`, end: `${pad}/* ${MARKER} end */` };
  return { start: `${pad}// ${MARKER} start`, end: `${pad}// ${MARKER} end` };
}

function indent(line: string): string {
  return line.match(/^\s*/)?.[0] ?? '';
}

function canInline(s: Style): boolean {
  return s === 'slash' || s === 'hash' || s === 'xml';
}

function expand(found: Range[], marks: Marks): Range[] {
  return mergeRanges(
    found.map((range) => {
      const next = { ...range };
      for (const block of marks.blocks) {
        if (next.end < block.start || next.start > block.end) continue;
        next.start = Math.min(next.start, block.start);
        next.end = Math.max(next.end, block.end);
      }
      return next;
    }),
  );
}

export function annotate(file: string, cleaned: Clean, found: Range[]): string {
  const text = cleaned.text;
  const marks = cleaned.marks;
  const lines = [...text.lines];
  const s = style(file);

  for (const range of expand(found, marks).reverse()) {
    const isSingle = range.start === range.end;
    const hadExisting = marks.blocks.find((b) => b.start === range.start && b.end === range.end);

    if (!hadExisting && isSingle && canInline(s)) {
      const existingMark = marks.inline.get(range.start);
      lines[range.start] = `${lines[range.start]}${existingMark ?? note(s)}`;
      continue;
    }

    const pad = indent(text.lines[range.start] ?? '');
    const pair = blockPair(s, pad);
    const startLine = hadExisting?.before ?? marks.starts.get(range.start) ?? pair.start;
    const endLine = hadExisting?.after ?? marks.ends.get(range.end) ?? pair.end;

    lines.splice(range.end + 1, 0, endLine);
    lines.splice(range.start, 0, startLine);
  }

  return join({ ...text, lines });
}

export function fresh(file: string, cleaned: Clean): string {
  const lines = [...cleaned.text.lines];
  const s = style(file);
  const line = cleaned.marks.file
    ?? (s === 'hash' ? `# ${MARKER} - new file`
      : s === 'xml' ? `<!-- ${MARKER} - new file -->`
      : `// ${MARKER} - new file`);
  const at = lines[0]?.startsWith('#!') ? 1 : 0;
  lines.splice(at, 0, line);
  return join({ ...cleaned.text, lines });
}
