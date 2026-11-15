# Cài web-admin + web-khach chạy nền trên Windows Server / Windows 10-11 (tự chạy khi máy khởi động, không cần đăng nhập).
# Chạy PowerShell "Run as Administrator":
#   powershell -ExecutionPolicy Bypass -File deploy\windows\install.ps1 [-JarDir <thư mục chứa 2 file jar>] [-Dir C:\VMMonitor]
#
# Sau khi cài:  web admin http://<máy>:3000  ·  web khách http://<máy>:8080
# Dữ liệu: C:\VMMonitor (config.json, data\, backups\)  ·  cấu hình cổng khách: C:\VMMonitor\khach.env
param(
    [string]$JarDir = '',
    [string]$Dir = 'C:\VMMonitor'
)
$ErrorActionPreference = 'Stop'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Cần chạy PowerShell bằng quyền Administrator'
}
$here = $PSScriptRoot
function Find-Jar($name, $module) {
    foreach ($d in @($JarDir, $here, (Join-Path $here "..\..\$module\target"))) {
        if ($d -and (Test-Path (Join-Path $d $name))) { return (Resolve-Path (Join-Path $d $name)).Path }
    }
    throw "Không thấy $name — tải ở trang Releases của repo hoặc build bằng deploy\build.ps1"
}
$adminJar = Find-Jar 'vm-monitor-admin.jar' 'web-admin'
$khachJar = Find-Jar 'vm-monitor-khach.jar' 'web-khach'

# Java 21+
$java = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { "$env:JAVA_HOME\bin\java.exe" } else { (Get-Command java -ErrorAction SilentlyContinue).Source }
if (-not $java) { throw 'Chưa có Java 21. Cài Eclipse Temurin JRE 21 (https://adoptium.net) rồi chạy lại.' }
$ver = (& $java -version 2>&1 | Select-Object -First 1) -replace '.*version "(\d+).*', '$1'
if ([int]$ver -lt 21) { throw "Cần Java 21 trở lên (đang có Java $ver)" }

New-Item -ItemType Directory -Force (Join-Path $Dir 'app'), (Join-Path $Dir 'data') | Out-Null
Copy-Item -Force $adminJar, $khachJar (Join-Path $Dir 'app')
Copy-Item -Force (Join-Path $here 'run-service.ps1') (Join-Path $Dir 'app')
$envFile = Join-Path $Dir 'khach.env'
if (-not (Test-Path $envFile)) {
    @('# Cổng khách hàng', 'ADMIN_URL=http://127.0.0.1:3000', 'PORT=8080', 'BRAND_NAME=Cổng khách hàng', '# 1 khi đặt sau reverse proxy có HTTPS', 'TRUST_PROXY=0') |
        Out-File -Encoding utf8 $envFile
}

foreach ($part in 'admin', 'khach') {
    $name = if ($part -eq 'admin') { 'VM Monitor - Web admin' } else { 'VM Monitor - Web khach' }
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$Dir\app\run-service.ps1`" -Part $part -Dir `"$Dir`""
    $trigger = New-ScheduledTaskTrigger -AtStartup
    if ($part -eq 'khach') { $trigger.Delay = 'PT20S' }
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
    Start-ScheduledTask -TaskName $name
    Write-Host "Đã cài và chạy: $name"
}
# mở tường lửa cho 2 cổng
foreach ($p in 3000, 8080) {
    if (-not (Get-NetFirewallRule -DisplayName "VM Monitor $p" -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName "VM Monitor $p" -Direction Inbound -Protocol TCP -LocalPort $p -Action Allow | Out-Null
    }
}
Write-Host ''
Write-Host "Xong. Web admin: http://localhost:3000  ·  Web khách: http://localhost:8080"
Write-Host "Dữ liệu: $Dir  ·  Log: $Dir\data\portal.log, $Dir\data\admin-service.log, $Dir\data\khach-service.log"
