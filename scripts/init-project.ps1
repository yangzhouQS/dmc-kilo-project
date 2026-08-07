<#
.SYNOPSIS
    Initialize dmc-kilo-project from the local monorepo.
.DESCRIPTION
    Copies packages/kilo-jetbrains and provider icons from the local monorepo,
    sets up git, and records the upstream sync point.
.PARAMETER MonorepoPath
    Path to the local kilocode monorepo. Defaults to the parent of this project.
#>
param(
    [string]$MonorepoPath = ""
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

if (-not $MonorepoPath) {
    $MonorepoPath = Split-Path -Parent $ProjectRoot
}

if (-not (Test-Path (Join-Path $MonorepoPath ".git"))) {
    Write-Error "Monorepo not found at: $MonorepoPath"
    exit 1
}

Write-Host "=== DMC Kilo Project - Initial Setup ===" -ForegroundColor Cyan
Write-Host "Project root : $ProjectRoot"
Write-Host "Monorepo     : $MonorepoPath"
Write-Host ""

# --- Step 1: Copy packages/kilo-jetbrains ---
$srcJet = Join-Path $MonorepoPath "packages\kilo-jetbrains"
$dstJet = Join-Path $ProjectRoot "packages\kilo-jetbrains"

if (-not (Test-Path $srcJet)) {
    Write-Error "packages/kilo-jetbrains not found in monorepo"
    exit 1
}

Write-Host "[1/5] Copying packages/kilo-jetbrains ..." -ForegroundColor Yellow
robocopy $srcJet $dstJet /E /XD build .gradle .idea /XF *.iml /NJH /NJS /NDL /NP /NFL /NFL | Out-Null
Write-Host "      Done." -ForegroundColor Green

# --- Step 2: Copy provider icons ---
$srcIcons = Join-Path $MonorepoPath "packages\ui\src\assets\icons\provider"
$dstIcons = Join-Path $ProjectRoot "packages\ui\src\assets\icons\provider"

if (Test-Path $srcIcons) {
    Write-Host "[2/5] Copying provider icons ..." -ForegroundColor Yellow
    robocopy $srcIcons $dstIcons *.svg /NJH /NJS /NDL /NP /NFL /NFL | Out-Null
    $iconCount = (Get-ChildItem $dstIcons -Filter *.svg).Count
    Write-Host "      Copied $iconCount icons." -ForegroundColor Green
} else {
    Write-Host "[2/5] Provider icons not found - skipped." -ForegroundColor Red
}

# --- Step 3: Record upstream sync point ---
$syncFile = Join-Path $ProjectRoot ".upstream-sync"
Push-Location $MonorepoPath
$upstreamHead = git rev-parse HEAD
$upstreamShort = git rev-parse --short HEAD
Pop-Location

Set-Content -Path $syncFile -Value $upstreamHead -NoNewline -Encoding utf8
Write-Host "[3/5] Sync point recorded: $upstreamShort" -ForegroundColor Green

# --- Step 4: Git init ---
Set-Location $ProjectRoot
if (-not (Test-Path ".git")) {
    Write-Host "[4/5] Initializing git ..." -ForegroundColor Yellow
    git init --quiet
    git add -A
    git commit --quiet -m "init: import from upstream $($upstreamShort)"
    Write-Host "      Initial commit created." -ForegroundColor Green
} else {
    Write-Host "[4/5] Git already initialized." -ForegroundColor Gray
}

# --- Step 5: Add upstream remotes ---
Write-Host "[5/5] Configuring git remotes ..." -ForegroundColor Yellow
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
git remote remove upstream 2>&1 | Out-Null
git remote remove upstream-local 2>&1 | Out-Null
$ErrorActionPreference = $prevEAP
git remote add upstream-local $MonorepoPath
git remote add upstream "https://github.com/Kilo-Org/kilocode.git"
Write-Host "      upstream-local -> $MonorepoPath" -ForegroundColor Green
Write-Host "      upstream       -> github.com/Kilo-Org/kilocode" -ForegroundColor Green

Write-Host ""
Write-Host "=== Setup Complete ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. Run scripts\apply-custom-changes.ps1 to set your plugin ID/name"
Write-Host "  2. Run scripts\build-plugin.ps1 to verify the build"
Write-Host "  3. Run scripts\sync-upstream.ps1 periodically to sync upstream changes"
Write-Host ""
