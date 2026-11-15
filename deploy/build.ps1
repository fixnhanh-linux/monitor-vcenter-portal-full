# Build 2 file jar (web-admin, web-khach) vào deploy\dist\. Có Maven + JDK 21 thì build trực tiếp, không thì build trong Docker.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory -Force "$root\deploy\dist" | Out-Null
foreach ($m in 'web-admin', 'web-khach') {
    if (Get-Command mvn -ErrorAction SilentlyContinue) { Push-Location "$root\$m"; mvn -q -B -DskipTests package; Pop-Location }
    else { docker run --rm -v "${root}\${m}:/src" -v vmmon-m2:/root/.m2 -w /src maven:3.9.9-eclipse-temurin-21 mvn -q -B -DskipTests package }
    if ($LASTEXITCODE) { throw "build $m lỗi" }
}
Copy-Item -Force "$root\web-admin\target\vm-monitor-admin.jar", "$root\web-khach\target\vm-monitor-khach.jar" "$root\deploy\dist\"
Get-ChildItem "$root\deploy\dist"
