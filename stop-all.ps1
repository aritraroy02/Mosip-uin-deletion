# ---------------------------------------------------------------------------
# Graceful shutdown for everything start-all.ps1 brings up, in reverse order:
#
#   1. Host windows : auth-gateway 8095, deletion-service 8096, admin 8090,
#                     landing 5500, delete-uin 5501, eSignet log windows.
#                     Each gets a Ctrl+C (so Spring Boot runs its shutdown hooks
#                     and python exits cleanly), and its window closes once the
#                     program inside it has exited. Force-kill only as a fallback.
#   2. eSignet stack         : docker compose stop (containers kept)
#   3. Deletion data stack   : docker compose stop (containers kept)
#
# `stop`, not `down`: containers and volumes are kept, so the seeded data
# survives and start-all.ps1 brings everything back as before. Docker Desktop
# itself is left running.
# ---------------------------------------------------------------------------

$repo = $PSScriptRoot
$esignetCompose = Join-Path $repo 'esignet\docker-compose\docker-compose.yml'
$deletionCompose = Join-Path $repo 'docker\docker-compose.yml'

# Sending Ctrl+C to another console means detaching from our own console and
# attaching to theirs, so it runs in a short-lived hidden helper process.
$helper = Join-Path $env:TEMP 'mosip-send-ctrlc.ps1'
Set-Content -Path $helper -Encoding utf8 -Value @'
param([int]$Target)
Add-Type -Namespace W -Name K -MemberDefinition @"
[DllImport("kernel32.dll")] public static extern bool FreeConsole();
[DllImport("kernel32.dll")] public static extern bool AttachConsole(uint p);
[DllImport("kernel32.dll")] public static extern bool SetConsoleCtrlHandler(System.IntPtr h, bool add);
[DllImport("kernel32.dll")] public static extern bool GenerateConsoleCtrlEvent(uint e, uint g);
"@
[W.K]::FreeConsole() | Out-Null
if ([W.K]::AttachConsole([uint32]$Target)) {
    [W.K]::SetConsoleCtrlHandler([System.IntPtr]::Zero, $true) | Out-Null
    [W.K]::GenerateConsoleCtrlEvent(0, 0) | Out-Null
    Start-Sleep -Milliseconds 500
    [W.K]::FreeConsole() | Out-Null
}
'@

function Get-Descendants([int]$ParentId, $all) {
    foreach ($c in $all | Where-Object { $_.ParentProcessId -eq $ParentId }) {
        $c
        Get-Descendants $c.ProcessId $all
    }
}

function Stop-Window([string]$Title) {
    $all = Get-CimInstance Win32_Process
    $windows = $all | Where-Object { $_.Name -eq 'cmd.exe' -and $_.CommandLine -like "*title $Title*" }
    if (-not $windows) { Write-Host "   $Title : not running"; return }
    foreach ($w in $windows) {
        # conhost.exe owns the window itself and only exits when it closes
        $children = @(Get-Descendants $w.ProcessId $all | Where-Object { $_.Name -ne 'conhost.exe' })
        Start-Process powershell -WindowStyle Hidden -Wait -ArgumentList `
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$helper`"", '-Target', $w.ProcessId
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline -and
               ($children | Where-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })) {
            Start-Sleep -Milliseconds 500
        }
        $left = @($children | Where-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })
        if ($left) {
            Write-Host "   $Title : did not exit within 30s, forcing"
            $left | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        } else {
            Write-Host "   $Title : stopped"
        }
        Stop-Process -Id $w.ProcessId -Force -ErrorAction SilentlyContinue   # the now-idle window
    }
}

# 1. Host windows: front-facing first, the deletion service after the gateway
Write-Host '==> [1/3] host services and windows'
foreach ($t in 'AUTH-GATEWAY', 'DELETION-SERVICE', 'ADMIN', 'LANDING', 'DELETE-UIN',
                'ESIGNET LOGS', 'MOCK-IDENTITY LOGS') {
    Stop-Window $t
}
# the start-all.ps1 launcher window, left open by -NoExit
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object { $_.CommandLine -like '*start-all.ps1*' -and $_.ProcessId -ne $PID } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Remove-Item $helper -ErrorAction SilentlyContinue

# 2 + 3. Containers (SIGTERM, then compose's own timeout)
cmd /c 'docker info >nul 2>&1'
if ($LASTEXITCODE -ne 0) {
    Write-Host '==> Docker is not running, nothing to stop there'
} else {
    Write-Host '==> [2/3] eSignet stack'
    cmd /c "docker compose -f `"$esignetCompose`" stop 2>&1"
    Write-Host '==> [3/3] deletion databases + MinIO'
    cmd /c "docker compose -f `"$deletionCompose`" stop 2>&1"
}

Write-Host ''
Write-Host 'All stopped. Data is kept; run start-all.ps1 to bring everything back.'
