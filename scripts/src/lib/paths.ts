/**
 * Project path constants and helpers.
 *
 * All relative paths use forward slashes internally.
 * Conversion to OS-specific paths happens only at fs boundary.
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

// paths.ts lives at scripts/src/lib/paths.ts
// up 2 = scripts/, up 3 = project root
const thisDir = path.dirname(fileURLToPath(import.meta.url));

export const SCRIPTS_DIR = path.resolve(thisDir, '..', '..');
export const PROJECT_ROOT = path.resolve(SCRIPTS_DIR, '..');
export const JET_DIR = path.join(PROJECT_ROOT, 'packages', 'kilo-jetbrains');
export const SYNC_FILE = path.join(PROJECT_ROOT, '.upstream-sync');

// Git path prefixes inside the monorepo
export const JET_PREFIX = 'packages/kilo-jetbrains/';
export const ICON_PREFIX = 'packages/ui/src/assets/icons/provider/';

/**
 * Files that may contain custom_change markers.
 * These are the ONLY upstream files we modify.
 * Paths are relative to packages/kilo-jetbrains/.
 */
export const PROTECTED_FILES: readonly string[] = [
  'settings.gradle.kts',
  'src/main/resources/META-INF/plugin.xml',
  'build.gradle.kts',
  'gradle.properties',
  'package.json',
  'frontend/src/main/kotlin/ai/kilocode/client/session/SessionManager.kt',
  'frontend/src/main/kotlin/ai/kilocode/client/session/SessionSidePanelManager.kt',
  'frontend/src/main/kotlin/ai/kilocode/client/session/SessionUi.kt',
  'frontend/src/main/kotlin/ai/kilocode/client/session/ui/prompt/PromptPanel.kt',
] as const;

/**
 * Check if a relative path (forward-slash) is a protected file.
 */
export function isProtected(relPath: string): boolean {
  return PROTECTED_FILES.includes(relPath.replace(/\\/g, '/'));
}

/**
 * Convert a monorepo git path to a jetbrains-relative path.
 * Returns null if the path is not under our tracked prefixes.
 */
export function toRelPath(gitPath: string): { rel: string; type: 'jet' | 'icon' } | null {
  if (gitPath.startsWith(JET_PREFIX)) {
    return { rel: gitPath.slice(JET_PREFIX.length), type: 'jet' };
  }
  if (gitPath.startsWith(ICON_PREFIX)) {
    return { rel: gitPath.slice(ICON_PREFIX.length), type: 'icon' };
  }
  return null;
}

/**
 * Resolve a jetbrains-relative path to an absolute OS path in this project.
 */
export function jetPath(rel: string): string {
  return path.join(JET_DIR, ...rel.split('/'));
}

/**
 * Resolve a jetbrains-relative path to an absolute OS path in the monorepo.
 */
export function monorepoJetPath(monorepo: string, rel: string): string {
  return path.join(monorepo, 'packages', 'kilo-jetbrains', ...rel.split('/'));
}

/**
 * Resolve an icon-relative path to an absolute OS path in this project.
 */
export function iconPath(rel: string): string {
  return path.join(PROJECT_ROOT, 'packages', 'ui', 'src', 'assets', 'icons', 'provider', ...rel.split('/'));
}

/**
 * Resolve an icon-relative path to an absolute OS path in the monorepo.
 */
export function monorepoIconPath(monorepo: string, rel: string): string {
  return path.join(monorepo, 'packages', 'ui', 'src', 'assets', 'icons', 'provider', ...rel.split('/'));
}

/**
 * Check if a jetbrains-relative path is inside the custom/ directory.
 */
export function isCustomDir(rel: string): boolean {
  const normalized = rel.replace(/\\/g, '/');
  return normalized.startsWith('custom/') || normalized === 'custom';
}
