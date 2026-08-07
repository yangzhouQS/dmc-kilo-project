/**
 * File system helpers.
 */

import fs from 'node:fs';
import path from 'node:path';

export function readText(filePath: string): string {
  return fs.readFileSync(filePath, 'utf-8');
}

export function writeText(filePath: string, content: string): void {
  fs.writeFileSync(filePath, content, 'utf-8');
}

export function exists(filePath: string): boolean {
  return fs.existsSync(filePath);
}

export function ensureDir(dirPath: string): void {
  fs.mkdirSync(dirPath, { recursive: true });
}

export function copy(src: string, dst: string): void {
  ensureDir(path.dirname(dst));
  fs.copyFileSync(src, dst);
}

export function remove(filePath: string): void {
  fs.rmSync(filePath, { force: true });
}

export function isDirectory(filePath: string): boolean {
  return fs.existsSync(filePath) && fs.statSync(filePath).isDirectory();
}

/**
 * Walk a directory recursively, returning all file paths.
 */
export function walkDir(dir: string): string[] {
  const results: string[] = [];
  if (!exists(dir)) return results;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...walkDir(full));
    } else {
      results.push(full);
    }
  }
  return results;
}
