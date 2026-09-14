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
Write-Host '==> [1/5] Docker'
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host '   starting Docker Desktop...'
    Start-Process 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    for ($i = 0; $i -lt 90; $i++) { docker info *> $null; if ($LASTEXITCODE -eq 0) { break }; Start-Sleep -Seconds 3 }
}
Write-Host '   docker ready'

# 2. Deletion data stack ---------------------------------------------------
Write-Host '==> [2/5] deletion databases + MinIO'
docker compose -f $deletionCompose up -d | Out-Null

# 3. eSignet stack (reboot-safe) -------------------------------------------
Write-Host '==> [3/5] eSignet stack'
# Bring up ONLY the stateful deps first (postgres, redis restart cleanly).
# eSignet, oidc-ui and mock-identity-system have init scripts that fail on a
# plain restart (stale hsm-client dir) and software keystores that desync from
# their key_alias/key_store DB rows -- so all three are force-recreated from a
# clean image AND their key tables cleared, so each regenerates a matching key.
docker compose -f $esignetCompose up -d database redis | Out-Null
Write-Host '   waiting for eSignet DB...'
for ($i = 0; $i -lt 30; $i++) {
    docker compose -f $esignetCompose exec -T database pg_isready -U postgres *> $null
    if ($LASTEXITCODE -eq 0) { break }; Start-Sleep -Seconds 2
}
Write-Host '   clearing eSignet + mock-identity key tables (keeps keystore and DB in sync)'
docker compose -f $esignetCompose exec -T database `
    psql -U postgres -d mosip_esignet -c 'TRUNCATE esignet.key_alias; TRUNCATE esignet.key_store;' | Out-Null
docker compose -f $esignetCompose exec -T database `
    psql -U postgres -d mosip_mockidentitysystem -c 'TRUNCATE mockidentitysystem.key_alias; TRUNCATE mockidentitysystem.key_store;' | Out-Null
Write-Host '   force-recreating esignet + oidc-ui + mock-identity from clean image'
docker compose -f $esignetCompose up -d --force-recreate esignet esignet-ui mock-identity-system | Out-Null
Wait-Url 'http://localhost:8088/v1/esignet/actuator/health' 60 'eSignet 8088' | Out-Null
Wait-Url 'http://localhost:3000/' 40 'oidc-ui 3000' | Out-Null
Wait-Url 'http://localhost:8082/v1/mock-identity-system/actuator/health' 40 'mock-identity 8082' | Out-Null

# make sure the delete-uin client is registered and the seeded residents exist
Write-Host '   registering RP client + loading mock identities'
Get-Content (Join-Path $repo 'charts\local-dev\register-client.sql') |
    docker compose -f $esignetCompose exec -T database psql -U postgres -d mosip_esignet | Out-Null
python (Join-Path $repo 'seed\load_mock_identities.py') | Out-Null

# 4. Host services (each in its own visible window) ------------------------
Write-Host '==> [4/5] host services (deletion 8096, gateway 8095, admin 8090)'
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
Write-Host '==> [5/5] static pages (landing 5500, delete-uin 5501)'
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
python (Join-Path $repo 'charts\local-dev\render.py') | Out-Null
$landingDir = Join-Path $repo 'charts\local-dev\dist\landing-page'
$deleteDir  = Join-Path $repo 'charts\local-dev\dist\delete-uin'
Start-Process cmd -ArgumentList '/k', "title LANDING 5500 && cd /d `"$repo`" && python -m http.server 5500 --bind 127.0.0.1 --directory `"$landingDir`""
Start-Process cmd -ArgumentList '/k', "title DELETE-UIN 5501 && cd /d `"$repo`" && python -m http.server 5501 --bind 127.0.0.1 --directory `"$deleteDir`""

Write-Host ''
Write-Host 'All up. Open http://localhost:5501/  (login OTP is 111111).'
Write-Host 'Gateway window shows the /userinfo call on each login.'
