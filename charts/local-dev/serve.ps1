# Render the two chart pages with local values and serve them, so the
# "Delete my UIN" button really redirects to the mock eSignet.
#
#   .\serve.ps1            # render, then serve 5500 (landing) + 5501 (delete-uin)
#   .\serve.ps1 -RenderOnly # just rebuild dist/ and stop
#
# The pages must be served over http://localhost -- not opened as file:// --
# because eSignet redirects back to a registered origin, and a file:// page has
# no origin to redirect to.
#
# Each server runs in its own window so both stay up after this script returns
# and survive closing whatever started it.
param([switch]$RenderOnly)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot

Write-Host '==> rendering chart templates with values-local.json'
& python (Join-Path $here 'render.py')
if ($LASTEXITCODE -ne 0) { throw 'render failed' }

if ($RenderOnly) { return }

$landing = Join-Path $here 'dist\landing-page'
$delete  = Join-Path $here 'dist\delete-uin'

Write-Host ''
Write-Host '==> serving landing page   http://localhost:5500/'
Start-Process powershell -ArgumentList @(
    '-NoExit', '-Command',
    "Set-Location '$landing'; python -m http.server 5500 --bind 127.0.0.1"
)

Write-Host '==> serving delete-uin     http://localhost:5501/'
Start-Process powershell -ArgumentList @(
    '-NoExit', '-Command',
    "Set-Location '$delete'; python -m http.server 5501 --bind 127.0.0.1"
)

Write-Host ''
Write-Host 'Open http://localhost:5500/ and click "Delete my UIN".'
Write-Host 'Close the two spawned windows to stop the servers.'
