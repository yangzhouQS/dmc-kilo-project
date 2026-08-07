<#
.SYNOPSIS
    Build the DMC Kilo JetBrains plugin.
.DESCRIPTION
    Runs Gradle buildPlugin from packages/kilo-jetbrains.
    First cold build downloads the pinned CLI release (network required).
.PARAMETER Task
    Gradle task to run. Default: buildPlugin. Other options: typecheck, test.
#>
param(
    [string]$Task = "buildPlugin"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$JetDir = Join-Path $ProjectRoot "packages\kilo-jetbrains"

if (-not (Test-Path (Join-Path $JetDir "build.gradle.kts"))) {
    Write-Error "Plugin source not found at: $JetDir"
    Write-Error "Run scripts\init-project.ps1 first."
    exit 1
}

Write-Host "=== Building DMC Kilo Plugin ===" -ForegroundColor Cyan
Write-Host "Task : $Task"
Write-Host "Dir  : $JetDir"
Write-Host ""

Set-Location $JetDir

if ($Task -eq "buildPlugin") {
    & .\gradlew.bat buildPlugin 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -eq 0) {
        $distributions = Join-Path $JetDir "build\distributions"
        $zips = Get-ChildItem $distributions -Filter "*.zip" -ErrorAction SilentlyContinue
        if ($zips) {
            Write-Host ""
            Write-Host "Build succeeded!" -ForegroundColor Green
            Write-Host "Output:" -ForegroundColor Green
            $zips | ForEach-Object { Write-Host "  $($_.FullName)" }
        }
    } else {
        Write-Host ""
        Write-Host "Build FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
    }
} else {
    & .\gradlew.bat $Task 2>&1 | ForEach-Object { Write-Host $_ }
}
