# Run the interactive terminal flow (enter UIN -> consent -> delete).
# Web server is disabled so the process exits cleanly on 'quit'.
$here = $PSScriptRoot
# Run from this folder: the local key paths and .env are relative to it.
Set-Location $here
if (-not (Test-Path "$here\target\identity-data-deletion-service-1.0.0.jar")) {
    & mvn -f "$here\pom.xml" -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
}
& java "-Duser.timezone=UTC" -jar "$here\target\identity-data-deletion-service-1.0.0.jar" `
    --spring.profiles.active=cli --spring.main.web-application-type=none
