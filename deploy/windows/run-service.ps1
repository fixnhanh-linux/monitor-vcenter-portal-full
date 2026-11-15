# Chạy một thành phần (admin | khach) và tự khởi động lại khi bị dừng. Được Task Scheduler gọi lúc máy khởi động (install.ps1).
param(
    [Parameter(Mandatory)][ValidateSet('admin', 'khach')][string]$Part,
    [string]$Dir = 'C:\VMMonitor'
)
$ErrorActionPreference = 'Continue'
$app = Join-Path $Dir 'app'
$logDir = Join-Path $Dir 'data'
New-Item -ItemType Directory -Force $logDir | Out-Null
$java = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { "$env:JAVA_HOME\bin\java.exe" } else { 'java' }
$crash = Join-Path $logDir "$Part-service.log"

if ($Part -eq 'khach') {
    # biến môi trường cho cổng khách: C:\VMMonitor\khach.env (KEY=VALUE mỗi dòng)
    $envFile = Join-Path $Dir 'khach.env'
    if (Test-Path $envFile) {
        Get-Content $envFile -Encoding UTF8 | Where-Object { $_ -match '^\s*[A-Z_]+\s*=' } | ForEach-Object {
            $k, $v = $_ -split '=', 2; [Environment]::SetEnvironmentVariable($k.Trim(), $v.Trim(), 'Process')
        }
    }
    $jargs = @('-XX:MaxRAMPercentage=40', '-jar', (Join-Path $app 'vm-monitor-khach.jar'))
} else {
    $jargs = @('-XX:MaxRAMPercentage=70', '-jar', (Join-Path $app 'vm-monitor-admin.jar'), '--root', $Dir, '--log-file', (Join-Path $logDir 'portal.log'))
}
$env:TZ = 'Asia/Ho_Chi_Minh'
while ($true) {
    if ((Test-Path $crash) -and (Get-Item $crash).Length -gt 5MB) { Move-Item -Force $crash "$crash.1" }
    "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] start $Part" | Out-File -Append -Encoding utf8 $crash
    & $java @args *>> $crash
    "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $Part dừng (mã $LASTEXITCODE) - chạy lại sau 5 giây" | Out-File -Append -Encoding utf8 $crash
    Start-Sleep -Seconds 5
}
