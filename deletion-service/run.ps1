# Build (if needed) and run the deletion service as a REST API on port 8095.
#
# -Duser.timezone=UTC is required: this machine's default zone is the legacy
# alias "Asia/Calcutta", which the PostgreSQL containers reject on connect.
param([switch]$Build)

$here = $PSScriptRoot
if ($Build -or -not (Test-Path "$here\target\identity-data-deletion-service-1.0.0.jar")) {
    & mvn -f "$here\pom.xml" -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
}
& java "-Duser.timezone=UTC" -jar "$here\target\identity-data-deletion-service-1.0.0.jar"
