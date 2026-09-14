# Repair eSignet's key state, then bring the stack up.
#
# eSignet and mock-identity-system keep their master keys in a PKCS12 keystore
# INSIDE the container, but record the key aliases in Postgres. The Postgres
# volume survives container recreation; the keystore does not. Once a container
# is recreated, the database points at an alias whose key no longer exists and
# startup dies with:
#
#   NoSuchSecurityProviderException: KER-KMA-004 --> No such alias: <uuid>
#
# Clearing the alias rows makes both services generate fresh keys on boot.
# key_policy_def and client_detail live in other tables and are untouched, so
# registered OIDC clients survive.
#
#   .\fix-esignet-keys.ps1

$ErrorActionPreference = 'Stop'
$compose = Join-Path (Split-Path (Split-Path $PSScriptRoot)) 'esignet\docker-compose\docker-compose.yml'

function Invoke-Psql([string]$db, [string]$sql) {
    docker compose -f $compose exec -T database psql -U postgres -d $db -c $sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed against $db" }
}

Write-Host '==> clearing orphaned key aliases'
Invoke-Psql 'mosip_esignet'            'DELETE FROM esignet.key_alias; DELETE FROM esignet.key_store;'
Invoke-Psql 'mosip_mockidentitysystem' 'DELETE FROM mockidentitysystem.key_alias; DELETE FROM mockidentitysystem.key_store;'

Write-Host ''
Write-Host '==> recreating esignet and mock-identity-system so they regenerate keys'
docker compose -f $compose up -d --force-recreate mock-identity-system esignet esignet-ui

Write-Host ''
Write-Host '==> waiting for esignet to report healthy (up to 3 minutes)'
$deadline = (Get-Date).AddMinutes(3)
while ((Get-Date) -lt $deadline) {
    $state = (docker inspect docker-compose-esignet-1 --format '{{.State.Health.Status}}' 2>$null)
    if ($state -eq 'healthy') { Write-Host '    esignet is healthy'; break }
    if ($state -eq 'unhealthy') { Write-Host '    esignet went unhealthy - see logs below'; break }
    Start-Sleep -Seconds 5
}

Write-Host ''
docker compose -f $compose ps

$ui = (docker inspect docker-compose-esignet-ui-1 --format '{{.State.Status}}' 2>$null)
Write-Host ''
if ($state -eq 'healthy' -and $ui -eq 'running') {
    Write-Host 'Ready. Open http://localhost:5500/ and click "Delete my UIN".'
} else {
    Write-Host 'Not ready. Last 20 error lines from esignet:'
    docker logs docker-compose-esignet-1 2>&1 |
        Select-String -Pattern 'ERROR|Exception|No such alias' |
        Select-Object -Last 20
}
