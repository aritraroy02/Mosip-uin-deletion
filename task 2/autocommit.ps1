param(
    [string]$Message = "Auto commit $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
    [switch]$Push
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..')).Path
Set-Location $repoRoot

Write-Host "Repository root: $repoRoot"

try {
    git rev-parse --is-inside-work-tree | Out-Null
}
catch {
    Write-Error "This script must be run from inside a Git repository."
    exit 1
}

$branch = git branch --show-current
if ([string]::IsNullOrWhiteSpace($branch)) {
    $branch = "(detached HEAD)"
}

Write-Host "Current branch: $branch"

$changes = git status --porcelain
if ([string]::IsNullOrWhiteSpace($changes)) {
    Write-Host "No changes detected. Nothing to commit."
    exit 0
}

git add -A
git commit -m $Message

if ($Push) {
    git push origin HEAD
}
else {
    Write-Host "Commit created locally. Use -Push to send it to origin."
}
