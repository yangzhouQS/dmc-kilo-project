/**
 * Minimal ANSI color helper — zero dependencies.
 * Falls back to plain text when stdout is not a TTY.
 */

const isTTY = process.stdout.isTTY ?? false;

function wrap(code: string): (s: string) => string {
  return (s: string) => (isTTY ? `\x1b[${code}m${s}\x1b[0m` : s);
}

export const c = {
  red: wrap('31'),
  green: wrap('32'),
  yellow: wrap('33'),
  blue: wrap('34'),
  magenta: wrap('35'),
  cyan: wrap('36'),
  gray: wrap('90'),
  bold: wrap('1'),
  dim: wrap('2'),
};

export function header(text: string): string {
  return c.bold(c.cyan(text));
}

export function ok(text: string): string {
  return c.green(text);
}

export function warn(text: string): string {
  return c.yellow(text);
}

export function err(text: string): string {
  return c.red(text);
}
