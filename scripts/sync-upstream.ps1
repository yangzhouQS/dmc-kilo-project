<#
.SYNOPSIS
    Sync upstream kilo-jetbrains changes into this project.
.DESCRIPTION
    Detects changed files in the monorepo since the last sync point,
    copies non-protected files, and shows diffs for protected files
    (build.gradle.kts, plugin.xml, gradle.properties, package.json).
.PARAMETER MonorepoPath
    Path to the local kilocode monorepo. Defaults to upstream-local remote.
.PARAMETER DryRun
    Show what would change without modifying files.
#>
param(
    [string]$MonorepoPath = "",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$syncFile = Join-Path $ProjectRoot ".upstream-sync"

# --- Resolve monorepo path ---
if (-not $MonorepoPath) {
    $remoteUrl = git -C $ProjectRoot remote get-url upstream-local 2>$null
    if ($remoteUrl -and (Test-Path $remoteUrl)) {
        $MonorepoPath = $remoteUrl
    } else {
        $MonorepoPath = Split-Path -Parent $ProjectRoot
    }
}

if (-not (Test-Path (Join-Path $MonorepoPath ".git"))) {
    Write-Error "Monorepo not found at: $MonorepoPath"
    exit 1
}

# --- Read sync point ---
if (-not (Test-Path $syncFile)) {
    Write-Error "No sync point found. Run init-project.ps1 first."
    exit 1
}

$syncPoint = Get-Content $syncFile -Raw
$syncPoint = $syncPoint.Trim()

# --- Get current upstream HEAD ---
$upstreamHead = git -C $MonorepoPath rev-parse HEAD
$upstreamShort = git -C $MonorepoPath rev-parse --short HEAD
$syncShort = git -C $MonorepoPath rev-parse --short $syncPoint

if ($syncPoint -eq $upstreamHead) {
    Write-Host "Already up to date (sync point = HEAD = $upstreamShort)" -ForegroundColor Green
    exit 0
}

Write-Host "=== Upstream Sync ===" -ForegroundColor Cyan
Write-Host "From : $syncShort"
Write-Host "To   : $upstreamShort"
Write-Host ""

# --- Get changed files ---
$jetPrefix = "packages/kilo-jetbrains/"
$iconPrefix = "packages/ui/src/assets/icons/provider/"

$changedRaw = git -C $MonorepoPath diff --name-status $syncPoint HEAD -- $jetPrefix $iconPrefix 2>$null

if (-not $changedRaw) {
    Write-Host "No changes in tracked paths." -ForegroundColor Green
    Set-Content -Path $syncFile -Value $upstreamHead -NoNewline -Encoding utf8
    exit 0
}

# --- Protected files (user-modified, require manual merge) ---
$protectedFiles = @(
    "build.gradle.kts"
    "src\main\resources\plugin.xml"
    "gradle.properties"
    "package.json"
)

$copiedFiles = @()
$protectedChanged = @()
$deletedFiles = @()

foreach ($line in $changedRaw) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split "`t"
    $status = $parts[0]
    $file = $parts[1]

    if ($file.StartsWith($jetPrefix)) {
        $relPath = $file.Substring($jetPrefix.Length) -replace "/", "\"
    } elseif ($file.StartsWith($iconPrefix)) {
        $relPath = "ui\src\assets\icons\provider\" + $file.Substring($iconPrefix.Length) -replace "/", "\"
    } else {
        continue
    }

    # Deleted files
    if ($status -eq "D") {
        $deletedFiles += $relPath
        continue
    }

    # Renamed files
    if ($status.StartsWith("R")) {
        $oldFile = $parts[1]
        $newFile = $parts[2]
        $oldRel = $oldFile.Substring($jetPrefix.Length) -replace "/", "\"
        $newRel = $newFile.Substring($jetPrefix.Length) -replace "/", "\"
        $deletedFiles += $oldRel
        $copiedFiles += $newRel
        continue
    }

    # Check if protected
    $isProtected = $false
    foreach ($p in $protectedFiles) {
        if ($relPath -eq $p) { $isProtected = $true; break }
    }

    # Skip custom module
    if ($relPath.StartsWith("custom\")) { continue }

    if ($isProtected) {
        $protectedChanged += $relPath
    } else {
        $copiedFiles += $relPath
    }
}

# --- Summary ---
Write-Host "Changed files: $($changedRaw.Count)" -ForegroundColor Yellow
Write-Host "  Safe to copy : $($copiedFiles.Count)"
Write-Host "  Protected    : $($protectedChanged.Count)"
Write-Host "  Deleted      : $($deletedFiles.Count)"
Write-Host ""

if ($DryRun) {
    Write-Host "[DRY RUN] No files modified." -ForegroundColor Magenta
    Write-Host ""
    if ($copiedFiles.Count -gt 0) {
        Write-Host "Files to copy:" -ForegroundColor White
        $copiedFiles | ForEach-Object { Write-Host "  $_" }
    }
    if ($protectedChanged.Count -gt 0) {
        Write-Host "`nProtected files (manual merge needed):" -ForegroundColor Magenta
        $protectedChanged | ForEach-Object { Write-Host "  $_" }
    }
    exit 0
}

# --- Copy safe files ---
if ($copiedFiles.Count -gt 0) {
    Write-Host "Copying safe files ..." -ForegroundColor Yellow
    foreach ($rel in $copiedFiles) {
        if ($rel.StartsWith("ui\")) {
            $srcFile = Join-Path $MonorepoPath "packages\$rel"
            $dstFile = Join-Path $ProjectRoot "packages\$rel"
        } else {
            $srcFile = Join-Path $MonorepoPath "$jetPrefix$($rel -replace '\\','/')"
            $srcFile = Join-Path $MonorepoPath $srcFile.Substring($MonorepoPath.Length + 1)
            $dstFile = Join-Path $ProjectRoot "packages\kilo-jetbrains\$rel"
            $srcFile = Join-Path $MonorepoPath "packages\kilo-jetbrains\$($rel -replace '\\','/')"
        }
        $dstDir = Split-Path -Parent $dstFile
        if (-not (Test-Path $dstDir)) { New-Item -ItemType Directory -Path $dstDir -Force | Out-Null }
        Copy-Item -LiteralPath $srcFile -Destination $dstFile -Force
        Write-Host "  + $rel" -ForegroundColor DarkGray
    }
    Write-Host "Copied $($copiedFiles.Count) files." -ForegroundColor Green
}

# --- Show protected file diffs ---
if ($protectedChanged.Count -gt 0) {
    Write-Host ""
    Write-Host "=== Protected files need manual merge ===" -ForegroundColor Magenta
    foreach ($rel in $protectedChanged) {
        $gitPath = $jetPrefix + ($rel -replace "\\", "/")
        Write-Host ""
        Write-Host "--- $rel ---" -ForegroundColor White
        $diff = git -C $MonorepoPath diff $syncPoint HEAD -- $gitPath
        if ($diff) {
            Write-Host $diff
        } else {
            Write-Host "  (no text diff)"
        }
    }
    Write-Host ""
    Write-Host "These files were NOT overwritten. Review the diffs above and"
    Write-Host "manually apply upstream changes while keeping your customizations." -ForegroundColor Magenta
}

# --- Handle deleted files ---
if ($deletedFiles.Count -gt 0) {
    Write-Host ""
    Write-Host "Deleted in upstream:" -ForegroundColor DarkYellow
    foreach ($rel in $deletedFiles) {
        if ($rel.StartsWith("custom\")) { continue }
        $localFile = Join-Path $ProjectRoot "packages\kilo-jetbrains\$rel"
        if (Test-Path $localFile) {
            Remove-Item -LiteralPath $localFile -Force
            Write-Host "  - $rel (removed)" -ForegroundColor DarkGray
        }
    }
}

# --- Update sync point ---
Set-Content -Path $syncFile -Value $upstreamHead -NoNewline -Encoding utf8
Write-Host ""
Write-Host "Sync point updated to $upstreamShort" -ForegroundColor Green

# --- Suggest git commit ---
Write-Host ""
Write-Host "Review changes and commit:" -ForegroundColor Cyan
Write-Host "  git add -A"
Write-Host "  git commit -m `"sync upstream $($syncShort)..$($upstreamShort)`""
