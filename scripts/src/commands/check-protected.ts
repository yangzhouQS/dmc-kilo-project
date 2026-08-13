/**
 * 提交前检查：验证所有含 custom_change 的上游文件都已注册到 PROTECTED_FILES
 *
 * 用法：npx tsx scripts/src/cli.ts check-protected
 * 或：npm run check-protected
 */

import { readFileSync, existsSync, readdirSync, statSync } from "node:fs"
import { join, relative } from "node:path"
import { PROTECTED_FILES } from "../lib/paths.js"

const JET_DIR = join(process.cwd(), "packages/kilo-jetbrains")

function scanCustomChangeFiles(dir: string, base: string = ""): string[] {
    const results: string[] = []
    if (!existsSync(dir)) return results

    for (const entry of readdirSync(dir)) {
        // Skip custom/ directory and build artifacts
        if (entry === "custom" || entry === "build" || entry === ".gradle" ||
            entry === ".intellijPlatform" || entry === ".kilo-dev" || entry === "node_modules") continue

        const fullPath = join(dir, entry)
        const relPath = base ? `${base}/${entry}` : entry

        if (statSync(fullPath).isDirectory()) {
            results.push(...scanCustomChangeFiles(fullPath, relPath))
        } else if (entry.endsWith(".kt") || entry.endsWith(".kts") || entry.endsWith(".xml") ||
                   entry.endsWith(".properties") || entry.endsWith(".json")) {
            try {
                const content = readFileSync(fullPath, "utf-8")
                if (/custom_change/i.test(content)) {
                    results.push(relPath)
                }
            } catch { /* skip */ }
        }
    }
    return results
}

// Root-level config files
const rootFiles = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "package.json",
    "src/main/resources/META-INF/plugin.xml",
]

const found: string[] = []

for (const f of rootFiles) {
    const path = join(JET_DIR, f)
    if (existsSync(path)) {
        try {
            if (/custom_change/i.test(readFileSync(path, "utf-8"))) {
                found.push(f)
            }
        } catch { /* skip */ }
    }
}

found.push(...scanCustomChangeFiles(JET_DIR))

// Check each found file against PROTECTED_FILES
const missing: string[] = []
for (const file of found) {
    if (!PROTECTED_FILES.includes(file)) {
        missing.push(file)
    }
}

console.log("=== PROTECTED_FILES Consistency Check ===")
console.log(`Files with custom_change markers: ${found.length}`)
console.log(`Registered in PROTECTED_FILES:   ${PROTECTED_FILES.length}`)
console.log()

if (missing.length > 0) {
    console.error(`\u2716 ${missing.length} file(s) with custom_change NOT in PROTECTED_FILES:`)
    for (const f of missing) {
        console.error(`  ! ${f}`)
    }
    console.error()
    console.error("These files will be OVERWRITTEN during upstream sync!")
    console.error("Add them to scripts/src/lib/paths.ts PROTECTED_FILES array.")
    process.exit(1)
} else {
    console.log("\u2714 All custom_change files are properly protected.")
}

// Also check for custom_change files inside custom/ (should not have markers)
const customDir = join(JET_DIR, "custom/src")
if (existsSync(customDir)) {
    const customFiles = scanCustomChangeFiles(customDir)
    if (customFiles.length > 0) {
        console.log()
        console.log(`\u26a0  ${customFiles.length} file(s) in custom/ have custom_change markers (unnecessary):`)
        for (const f of customFiles) {
            console.log(`  - custom/src/${f}`)
        }
    }
}
