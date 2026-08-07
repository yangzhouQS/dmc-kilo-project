/**
 * Git operation wrapper — uses child_process, zero dependencies.
 */

import { execFileSync } from 'node:child_process';

/**
 * Run a git command in `root`, return trimmed stdout.
 * Throws on non-zero exit.
 */
export function git(root: string, args: string[]): string {
  return execFileSync('git', ['-C', root, ...args], {
    encoding: 'utf-8',
    stdio: ['pipe', 'pipe', 'pipe'],
    maxBuffer: 50 * 1024 * 1024,
  }).trim();
}

/**
 * Run a git command, return raw (untrimmed) stdout.
 */
export function gitRaw(root: string, args: string[]): string {
  return execFileSync('git', ['-C', root, ...args], {
    encoding: 'utf-8',
    stdio: ['pipe', 'pipe', 'pipe'],
    maxBuffer: 50 * 1024 * 1024,
  });
}

/**
 * Run a git command, return { ok, stdout } without throwing.
 */
export function gitTry(root: string, args: string[]): { ok: boolean; stdout: string } {
  try {
    return { ok: true, stdout: git(root, args) };
  } catch {
    return { ok: false, stdout: '' };
  }
}

export function getHead(root: string): string {
  return git(root, ['rev-parse', 'HEAD']);
}

export function getHeadShort(root: string): string {
  return git(root, ['rev-parse', '--short', 'HEAD']);
}

export function getShortHash(root: string, hash: string): string {
  return git(root, ['rev-parse', '--short', hash]);
}

export interface NameStatusEntry {
  /** Status code: M, A, D, R##, C##, etc. */
  status: string;
  /** Source file path (forward slashes). */
  file: string;
  /** Destination file path for renames/copies. */
  newFile?: string;
}

/**
 * Get changed files between two refs as name-status entries.
 */
export function diffNameStatus(
  root: string,
  from: string,
  to: string,
  paths: string[] = [],
): NameStatusEntry[] {
  const args = ['diff', '--name-status', from, to];
  if (paths.length > 0) {
    args.push('--', ...paths);
  }
  const raw = gitRaw(root, args);
  return parseNameStatus(raw);
}

export function parseNameStatus(raw: string): NameStatusEntry[] {
  const entries: NameStatusEntry[] = [];
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    const parts = line.split('\t');
    const status = parts[0];
    if (status[0] === 'R' || status[0] === 'C') {
      entries.push({ status, file: parts[1] ?? '', newFile: parts[2] });
    } else {
      entries.push({ status, file: parts[1] ?? '' });
    }
  }
  return entries;
}

/**
 * Get a unified diff for a single file between two refs.
 */
export function diffFile(root: string, from: string, to: string, filePath: string): string {
  return gitRaw(root, ['diff', from, to, '--', filePath]).trimEnd();
}

/**
 * Get file content at a specific ref.
 */
export function showFile(root: string, ref: string, filePath: string): string | null {
  const result = gitTry(root, ['show', `${ref}:${filePath}`]);
  return result.ok ? result.stdout : null;
}

/**
 * Run a raw git command in a directory (for init, add, commit, etc.)
 * Returns trimmed stdout.
 */
export function gitExec(cwd: string, args: string[]): string {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf-8',
    stdio: ['pipe', 'pipe', 'pipe'],
    maxBuffer: 50 * 1024 * 1024,
  }).trim();
}
