# ---------------------------------------------------------------------------
# One-command startup for the whole MOSIP Collab deletion environment.
#
# Run this after a machine reboot (or any time things are down). It brings up,
# in the right order and in a way that survives reboots:
#
#   1. Docker Desktop (started if its daemon is down)
#   2. Deletion data stack   : docker/docker-compose.yml  (7 DBs + MinIO)
#   3. eSignet stack         : esignet/docker-compose      (eSignet, oidc-ui,
#                              mock-identity, DB, redis)
#   4. Host services         : deletion-service 8096, auth-gateway 8095,
#                              admin 8090   (each in its own visible window)
#   5. Static pages          : landing 5500, delete-uin 5501
#   6. eSignet log windows   : readable live view of esignet 8088 and
#                              mock-identity 8082, which have no console of
#                              their own because they run as containers
#
# Why eSignet needs special handling: the eSignet and oidc-ui containers have
# init scripts that fail on a *restart* (stale hsm-client dir, missing
# plugins/temp), and eSignet's software keystore desyncs from its key_alias /
# key_store DB rows when the container filesystem is reset. So this script
# force-recreates those two containers from a clean image AND clears eSignet's
# key tables first, so eSignet regenerates a matching master key on boot. That
# combination is the only reliably-working state. The DBs, redis and
# mock-identity-system are left as normal `up -d` (they restart fine and their
# data persists in Docker volumes).
# ---------------------------------------------------------------------------

$ErrorActionPreference = 'Stop'
$repo = $PSScriptRoot
$esignetCompose = Join-Path $repo 'esignet\docker-compose\docker-compose.yml'
$deletionCompose = Join-Path $repo 'docker\docker-compose.yml'

# Windows PowerShell 5.1 turns a native command's stderr into an ErrorRecord, and
# with $ErrorActionPreference = 'Stop' above, that becomes a TERMINATING error.
# So probing something that is legitimately "not ready yet" -- `docker info` with
# the daemon down, `pg_isready` before Postgres accepts connections -- used to
# kill this script on line 1 instead of letting it react. Routing the probe
# through cmd.exe keeps stderr out of PowerShell completely, so only the exit
# code matters and a failed probe is just a value, never an exception.
function Invoke-Probe([string]$CommandLine) {
    cmd /c "$CommandLine >nul 2>&1"
    return $LASTEXITCODE
}

function Test-DockerUp {
    return (Invoke-Probe 'docker info') -eq 0
}

function Wait-Url($url, $seconds, $label) {
    for ($i = 0; $i -lt $seconds; $i++) {
        try {
            $c = (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop).StatusCode
            if ($c -ge 200 -and $c -lt 500) { Write-Host "   $label up"; return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    Write-Host "   $label NOT up after ${seconds}x2s"; return $false
}

# 1. Docker ----------------------------------------------------------------
Write-Host '==> [1/6] Docker'
if (-not (Test-DockerUp)) {
    $dockerDesktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path $dockerDesktop)) {
        throw "The Docker daemon is down and Docker Desktop was not found at $dockerDesktop. " +
              'Start Docker manually, wait for it to finish starting, then re-run this script.'
    }
    if (Get-Process 'Docker Desktop' -ErrorAction SilentlyContinue) {
        Write-Host '   Docker Desktop is already starting, waiting for the daemon...'
    } else {
        Write-Host '   starting Docker Desktop...'
        Start-Process $dockerDesktop
    }
    $ready = $false
    for ($i = 0; $i -lt 120; $i++) {
        if (Test-DockerUp) { $ready = $true; break }
        if ($i -gt 0 -and $i % 20 -eq 0) { Write-Host "   still waiting for the daemon ($($i * 3)s)..." }
        Start-Sleep -Seconds 3
    }
    if (-not $ready) {
        throw 'Docker Desktop did not become ready within 6 minutes. Start it manually, ' +
              'wait for the whale icon to stop animating, then re-run this script.'
    }
}
Write-Host '   docker ready'

# 2. Deletion data stack ---------------------------------------------------
Write-Host '==> [2/6] deletion databases + MinIO'
docker compose -f $deletionCompose up -d | Out-Null

# 3. eSignet stack (reboot-safe) -------------------------------------------
Write-Host '==> [3/6] eSignet stack'
# Bring up ONLY the stateful deps first (postgres, redis restart cleanly).
# eSignet, oidc-ui and mock-identity-system have init scripts that fail on a
# plain restart (stale hsm-client dir) and software keystores that desync from
# their key_alias/key_store DB rows -- so all three are force-recreated from a
# clean image AND their key tables cleared, so each regenerates a matching key.
docker compose -f $esignetCompose up -d database redis | Out-Null
Write-Host '   waiting for eSignet DB...'
$dbReady = $false
for ($i = 0; $i -lt 30; $i++) {
    if ((Invoke-Probe "docker compose -f `"$esignetCompose`" exec -T database pg_isready -U postgres") -eq 0) {
        $dbReady = $true; break
    }
    Start-Sleep -Seconds 2
}
if (-not $dbReady) {
    throw 'The eSignet database did not accept connections within 60s. Check: ' +
          "docker compose -f `"$esignetCompose`" logs database"
}
Write-Host '   clearing eSignet + mock-identity key tables (keeps keystore and DB in sync)'
docker compose -f $esignetCompose exec -T database `
    psql -U postgres -d mosip_esignet -c 'TRUNCATE esignet.key_alias; TRUNCATE esignet.key_store;' | Out-Null
docker compose -f $esignetCompose exec -T database `
    psql -U postgres -d mosip_mockidentitysystem -c 'TRUNCATE mockidentitysystem.key_alias; TRUNCATE mockidentitysystem.key_store;' | Out-Null
Write-Host '   force-recreating esignet + oidc-ui + mock-identity from clean image'
docker compose -f $esignetCompose up -d --force-recreate esignet esignet-ui mock-identity-system | Out-Null
Wait-Url 'http://localhost:8088/v1/esignet/actuator/health' 60 'eSignet 8088' | Out-Null
# oidc-ui waits for eSignet to be healthy. On a cold start (Docker just booted)
# eSignet can take longer than compose waits, leaving oidc-ui created but never
# started. Now that eSignet is up, start it if needed; if it already runs this
# is a no-op. (Plain start, not recreate: its init script only fails on restarts.)
Invoke-Probe "docker compose -f `"$esignetCompose`" up -d esignet-ui" | Out-Null
Wait-Url 'http://localhost:3000/' 40 'oidc-ui 3000' | Out-Null
Wait-Url 'http://localhost:8082/v1/mock-identity-system/actuator/health' 40 'mock-identity 8082' | Out-Null

# make sure the delete-uin client is registered and the seeded residents exist
Write-Host '   registering RP client + loading mock identities'
Get-Content (Join-Path $repo 'collab-ui\local-dev\register-client.sql') |
    docker compose -f $esignetCompose exec -T database psql -U postgres -d mosip_esignet | Out-Null
# eSignet caches client details in redis for a day, and redis survives restarts,
# so drop the cached copy or a changed client name/logo would not show up.
Invoke-Probe 'docker exec redis-server redis-cli DEL esignet:clientdetails::mosip-collab-delete-uin-client' | Out-Null
python (Join-Path $repo 'seed\load_mock_identities.py') | Out-Null

# 4. Host services (each in its own visible window) ------------------------
Write-Host '==> [4/6] host services (deletion 8096, gateway 8095, admin 8090)'
$delDir = Join-Path $repo 'deletion-service'
$gwDir  = Join-Path $repo 'auth-gateway'
$admDir = Join-Path $repo 'admin'
$delCmd = "title DELETION-SERVICE 8096 && cd /d `"$delDir`" && java -Duser.timezone=UTC -jar target\identity-data-deletion-service-1.0.0.jar"
$gwCmd  = "title AUTH-GATEWAY 8095 (userinfo logs) && cd /d `"$gwDir`" && java -jar target\auth-gateway-1.0.0.jar"
$admCmd = "title ADMIN 8090 && cd /d `"$admDir`" && python server.py"
Start-Process cmd -ArgumentList '/k', $delCmd
Start-Sleep -Seconds 10          # let the deletion service bind 8096 before the gateway starts
Start-Process cmd -ArgumentList '/k', $gwCmd
Start-Process cmd -ArgumentList '/k', $admCmd

# 5. Static pages ----------------------------------------------------------
Write-Host '==> [5/6] static pages (landing 5500, delete-uin 5501)'
# Free 5500/5501 by PORT (reliable), and close any old page-server cmd windows
# whose working directory was inside dist/ (those windows lock the folder and
# make render.py's rmtree fail). Then render, then serve with --directory so the
# server's cwd is the repo root, never inside dist -- so nothing locks dist.
foreach ($p in 5500, 5501) {
    Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
}
Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" |
    Where-Object { $_.CommandLine -like '*http.server 550*' -or $_.CommandLine -like '*dist\landing-page*' -or $_.CommandLine -like '*dist\delete-uin*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2
python (Join-Path $repo 'collab-ui\local-dev\render.py') | Out-Null
$landingDir = Join-Path $repo 'collab-ui\local-dev\dist\landing-page'
$deleteDir  = Join-Path $repo 'collab-ui\local-dev\dist\delete-uin'
Start-Process cmd -ArgumentList '/k', "title LANDING 5500 && cd /d `"$repo`" && python -m http.server 5500 --bind 127.0.0.1 --directory `"$landingDir`""
Start-Process cmd -ArgumentList '/k', "title DELETE-UIN 5501 && cd /d `"$repo`" && python -m http.server 5501 --bind 127.0.0.1 --directory `"$deleteDir`""

# 6. eSignet log windows --------------------------------------------------
# eSignet and mock-identity run as containers, so they have no console of their
# own and their raw logs are one dense JSON object per line. These two windows
# follow them in readable form, so a deletion can be watched across three
# terminals: the OIDC handshake, the OTP check, and the deletion audit itself.
Write-Host '==> [6/6] eSignet log windows (esignet 8088, mock-identity 8082)'
Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*esignet-logs.ps1*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Process cmd -ArgumentList '/k', "title ESIGNET LOGS 8088 && cd /d `"$repo`" && powershell -NoProfile -ExecutionPolicy Bypass -File esignet-logs.ps1 -Which esignet"
Start-Process cmd -ArgumentList '/k', "title MOCK-IDENTITY LOGS 8082 && cd /d `"$repo`" && powershell -NoProfile -ExecutionPolicy Bypass -File esignet-logs.ps1 -Which mock"

Write-Host ''
Write-Host 'All up. Open http://localhost:5501/  (login OTP is 111111).'
Write-Host 'Watch three windows during a deletion:'
Write-Host '   ESIGNET LOGS 8088       the OIDC handshake (token, userinfo)'
Write-Host '   MOCK-IDENTITY LOGS 8082 the UIN and OTP check'
Write-Host '   DELETION-SERVICE 8096   the claims received and every table cleared'
