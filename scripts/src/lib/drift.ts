/**
 * Drift classification — compare local files against upstream to determine
 * how far they've drifted and whether they can be auto-reset.
 *
 * Adapted from kilocode's script/upstream/utils/reset.ts.
 */

import path from 'node:path';
import fs from 'node:fs';
import { showFile } from './git.js';
import { clean, join, approxDiff } from './marker-dsl.js';
import { JET_DIR, PROTECTED_FILES, isCustomDir } from './paths.js';

export type Bucket =
  | 'identical'
  | 'markers-only'
  | 'cosmetic-only'
  | 'small-diff'
  | 'large-diff'
  | 'upstream-missing'
  | 'binary-diff'
  | 'binary-identical'
  | 'local-missing'
  | 'too-large';

export interface ClassifyResult {
  bucket: Bucket;
  /** Non-marker, non-whitespace diff line count (for text files). */
  lines?: number;
}

export interface ClassifyOptions {
  monorepo: string;
  syncPoint: string;
  /** Monorepo-relative git path (e.g. "packages/kilo-jetbrains/src/..."). */
  gitPath: string;
  /** Local absolute file path. */
  localPath: string;
  /** Threshold for small-diff vs large-diff. */
  reviewLimit: number;
}

const MAX_SIZE = 256 * 1024;

/**
 * Check if data is binary (contains null bytes).
 */
function isBinary(data: Uint8Array): boolean {
  return data.includes(0);
}

/**
 * Classify how a local file compares to the upstream version at sync point.
 */
export function classifyDrift(opts: ClassifyOptions): ClassifyResult {
  // Read upstream data
  const upstreamData = readUpstreamData(opts.monorepo, opts.syncPoint, opts.gitPath);

  if (upstreamData === null) {
    return { bucket: 'upstream-missing' };
  }

  if (upstreamData.length > MAX_SIZE) {
    return { bucket: 'too-large', lines: upstreamData.length };
  }

  // Check local file exists
  if (!fs.existsSync(opts.localPath)) {
    return { bucket: 'local-missing' };
  }

  // Binary check
  if (isBinary(upstreamData)) {
    const localData = fs.readFileSync(opts.localPath);
    if (isBinary(localData)) {
      return localData.length === upstreamData.length &&
        localData.every((b, i) => b === upstreamData[i])
        ? { bucket: 'binary-identical' }
        : { bucket: 'binary-diff' };
    }
  }

  // Text comparison
  const upstreamText = new TextDecoder().decode(upstreamData);
  const local = fs.readFileSync(opts.localPath, 'utf-8');

  if (local === upstreamText) {
    return { bucket: 'identical' };
  }

  // Strip markers and compare
  const repoRel = opts.gitPath.replace(/^packages\/kilo-jetbrains\//, '');
  const cleanedLocal = join(clean(repoRel, local).text);
  const cleanedUpstream = join(clean(repoRel, upstreamText).text);

  if (cleanedLocal === cleanedUpstream) {
    return { bucket: 'markers-only' };
  }

  const count = approxDiff(cleanedUpstream, cleanedLocal, { ignoreWhitespace: true });
  if (count === 0) {
    return { bucket: 'cosmetic-only' };
  }
  if (count <= opts.reviewLimit) {
    return { bucket: 'small-diff', lines: count };
  }
  return { bucket: 'large-diff', lines: count };
}

/**
 * Read upstream file content as raw bytes.
 */
function readUpstreamData(monorepo: string, ref: string, gitPath: string): Uint8Array | null {
  const text = showFile(monorepo, ref, gitPath);
  if (text === null) return null;
  return new TextEncoder().encode(text);
}

/**
 * Buckets that should be auto-reset to upstream.
 */
export const RESET_BUCKETS: Set<Bucket> = new Set(['markers-only', 'cosmetic-only', 'small-diff']);

/**
 * Reset a file to the upstream version at sync point.
 */
export function resetToUpstream(
  monorepo: string,
  syncPoint: string,
  gitPath: string,
  localPath: string,
  dryRun: boolean = false,
): { action: 'written' | 'deleted' | 'identical'; } {
  const upstreamText = showFile(monorepo, syncPoint, gitPath);

  if (upstreamText === null) {
    if (!dryRun && fs.existsSync(localPath)) {
      fs.rmSync(localPath, { force: true });
    }
    return { action: 'deleted' };
  }

  const local = fs.existsSync(localPath) ? fs.readFileSync(localPath, 'utf-8') : '';
  if (local === upstreamText) {
    return { action: 'identical' };
  }

  if (!dryRun) {
    fs.mkdirSync(path.dirname(localPath), { recursive: true });
    fs.writeFileSync(localPath, upstreamText, 'utf-8');
  }
  return { action: 'written' };
}

/**
 * Check if a file path should be excluded from drift classification.
 */
export function shouldSkip(repoRel: string): boolean {
  // Skip custom module
  if (isCustomDir(repoRel)) return true;

  // Skip protected files (they have intentional customizations)
  if (PROTECTED_FILES.some((p) => repoRel === p || repoRel.endsWith('/' + p))) return true;

  // Skip non-code assets
  const ext = path.extname(repoRel).toLowerCase();
  const skipExts = new Set([
    '.svg', '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.bmp',
    '.woff', '.woff2', '.ttf', '.otf', '.eot', '.zip', '.tar', '.gz',
    '.wasm', '.bin', '.db', '.sqlite', '.mp3', '.mp4', '.mov', '.pdf',
    '.lock', '.json',
  ]);
  if (skipExts.has(ext)) return true;

  const skipNames = new Set(['bun.lock', 'package-lock.json', 'yarn.lock', 'pnpm-lock.yaml', 'Cargo.lock']);
  if (skipNames.has(path.basename(repoRel))) return true;

  return false;
}
