# ---------------------------------------------------------------------------
# Readable, live view of what eSignet or the mock identity system is doing.
#
# Both run as Docker containers, so unlike the deletion service they have no
# console window of their own. They also log one dense JSON object per line,
# which is unreadable while a demo is running. This follows a container's log
# and prints only what matters: the request, the status, and any warning.
#
#   .\esignet-logs.ps1                 the eSignet OIDC API   (default)
#   .\esignet-logs.ps1 -Which mock     the mock identity system
#   .\esignet-logs.ps1 -All            every line, nothing filtered out
#
# start-all.ps1 opens one window for each, so during a deletion you can watch
# the OIDC handshake and the OTP check side by side with the deletion audit.
# Close the window to stop following.
# ---------------------------------------------------------------------------
param(
    [ValidateSet('esignet', 'mock')]
    [string]$Which = 'esignet',
    [switch]$All,
    [int]$Tail = 15
)

$ErrorActionPreference = 'Continue'

if ($Which -eq 'mock') {
    $container = 'docker-compose-mock-identity-system-1'
    $label = 'MOCK-IDENTITY'
    $colour = 'Green'
} else {
    $container = 'docker-compose-esignet-1'
    $label = 'ESIGNET'
    $colour = 'Cyan'
}

# The calls worth seeing during a deletion: the OIDC handshake and the OTP path.
$interesting = 'authorization|oauth|token|userinfo|authenticate|send-otp|auth-code|kyc|identity'

Write-Host ""
Write-Host "  $label  ($container)" -ForegroundColor White
Write-Host "  following live. Health polling is hidden; re-run with -All to see everything." -ForegroundColor DarkGray
Write-Host ("  " + ("-" * 74)) -ForegroundColor DarkGray

docker logs -f --tail $Tail $container 2>&1 | ForEach-Object {
    $line = [string]$_

    $uri = $null
    if ($line -match '"req\.requestURI":"([^"]+)"') { $uri = $matches[1] }
    $status = $null
    if ($line -match '"statusCode":(\d+)') { $status = $matches[1] }
    $method = ''
    if ($line -match '"req\.method":"([^"]+)"') { $method = $matches[1] }
    $msg = $null
    if ($line -match '"message":"((?:[^"\\]|\\.)*)"') { $msg = $matches[1] }
    $level = ''
    if ($line -match '"level":"([A-Z]+)"') { $level = $matches[1] }

    # Lines that are not JSON at all (startup banners) pass through when -All.
    if (-not $uri -and -not $msg) {
        if ($All) { Write-Host "  $line" -ForegroundColor DarkGray }
        return
    }

    $subject = $uri
    if (-not $subject) { $subject = $msg }

    if (-not $All) {
        if ($subject -match 'actuator/health') { return }
        if ($subject -notmatch $interesting -and $level -notmatch 'ERROR|WARN') { return }
    }

    $stamp = (Get-Date).ToString('HH:mm:ss')
    if ($uri) {
        $text = "$stamp  $method $uri"
        if ($status) { $text = "$text   -> $status" }
    } else {
        $clean = $msg -replace '\\n', ' ' -replace '\\t', ' ' -replace '\s+', ' '
        $text = "$stamp  $clean"
    }
    if ($text.Length -gt 150) { $text = $text.Substring(0, 147) + '...' }

    $fg = $colour
    if ($level -eq 'ERROR') { $fg = 'Red' }
    elseif ($level -eq 'WARN') { $fg = 'Yellow' }
    elseif ($status -and [int]$status -ge 400) { $fg = 'Red' }

    Write-Host $text -ForegroundColor $fg
}
