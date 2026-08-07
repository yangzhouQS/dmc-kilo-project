<#
.SYNOPSIS
    Apply DMC customizations to the upstream plugin files.
.DESCRIPTION
    After init-project.ps1 copies upstream files, this script applies
    the minimal changes needed to turn it into the DMC Kilo plugin:
      1. settings.gradle.kts  - include(":custom")
      2. plugin.xml           - add custom module + change name/vendor
      3. build.gradle.kts     - change plugin ID
    These are the ONLY upstream files that get modified. All changes
    are marked with custom_change comments for easy conflict resolution.
.PARAMETER PluginId
    Plugin ID (default: com.dmc.kilo)
.PARAMETER PluginName
    Plugin display name (default: DMC Kilo)
#>
param(
    [string]$PluginId = "com.dmc.kilo",
    [string]$PluginName = "DMC Kilo"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$JetDir = Join-Path $ProjectRoot "packages\kilo-jetbrains"

Write-Host "=== Applying DMC Customizations ===" -ForegroundColor Cyan
Write-Host "Plugin ID   : $PluginId"
Write-Host "Plugin Name : $PluginName"
Write-Host ""

# --- 1. settings.gradle.kts: include :custom ---
$settingsFile = Join-Path $JetDir "settings.gradle.kts"
$settings = Get-Content $settingsFile -Raw

if ($settings -notmatch 'include\(":custom"\)') {
    $settings = $settings -replace 'include\("shared"\)', 'include("shared")`ninclude("custom") // custom_change'
    Set-Content -Path $settingsFile -Value $settings -NoNewline
    Write-Host "[1/3] settings.gradle.kts: added include("":custom"")" -ForegroundColor Green
} else {
    Write-Host "[1/3] settings.gradle.kts: already has custom" -ForegroundColor Gray
}

# --- 2. plugin.xml: add custom module + change name/vendor ---
$pluginXmlFile = Join-Path $JetDir "src\main\resources\plugin.xml"
$pluginXml = Get-Content $pluginXmlFile -Raw

# Add custom module to <content>
if ($pluginXml -notmatch 'com\.dmc\.kilo\.custom') {
    $pluginXml = $pluginXml -replace
        '(<module name="ai\.kilocode\.jetbrains\.backend"/>)',
        '$1`n        <module name="com.dmc.kilo.custom"/> <!-- custom_change -->'
    Write-Host "[2/3] plugin.xml: added custom module to <content>" -ForegroundColor Green
} else {
    Write-Host "[2/3] plugin.xml: custom module already present" -ForegroundColor Gray
}

# Change name and vendor
$pluginXml = $pluginXml -replace '<name>[^<]*</name>', "<name>$PluginName</name> <!-- custom_change -->"
Set-Content -Path $pluginXmlFile -Value $pluginXml -NoNewline
Write-Host "      plugin.xml: name -> $PluginName" -ForegroundColor Green

# --- 3. build.gradle.kts: change plugin ID ---
$buildFile = Join-Path $JetDir "build.gradle.kts"
$build = Get-Content $buildFile -Raw

# The plugin ID is set via IntelliJ Platform Gradle Plugin's pluginConfiguration
# in build.gradle.kts. Look for id = "ai.kilocode.jetbrains" and replace.
if ($build -match 'id\s*=\s*"ai\.kilocode\.jetbrains"') {
    $build = $build -replace 'id\s*=\s*"ai\.kilocode\.jetbrains"', "id = `"$PluginId`" // custom_change"
    Set-Content -Path $buildFile -Value $build -NoNewline
    Write-Host "[3/3] build.gradle.kts: plugin ID -> $PluginId" -ForegroundColor Green
} else {
    Write-Host "[3/3] build.gradle.kts: plugin ID not found (may already be customized)" -ForegroundColor Gray
}

# --- Summary ---
Write-Host ""
Write-Host "=== Customizations Applied ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Modified files (3) - all marked with custom_change:"
Write-Host "  - settings.gradle.kts"
Write-Host "  - src/main/resources/plugin.xml"
Write-Host "  - build.gradle.kts"
Write-Host ""
Write-Host "New files (custom module, never conflicts with upstream):"
Write-Host "  - custom/build.gradle.kts"
Write-Host "  - custom/src/main/resources/dmc.custom.xml"
Write-Host "  - custom/src/main/kotlin/com/dmc/**"
Write-Host ""
Write-Host "Next: run scripts\build-plugin.ps1 to verify the build."
