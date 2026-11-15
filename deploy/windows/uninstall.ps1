# Gỡ web-admin + web-khach chạy nền (GIỮ dữ liệu trong C:\VMMonitor). Chạy bằng quyền Administrator.
param([string]$Dir = 'C:\VMMonitor')
foreach ($name in 'VM Monitor - Web khach', 'VM Monitor - Web admin') {
    if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $name; Unregister-ScheduledTask -TaskName $name -Confirm:$false; Write-Host "Đã gỡ $name"
    }
}
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'vm-monitor-(admin|khach)\.jar' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Get-NetFirewallRule -DisplayName 'VM Monitor *' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Write-Host "Dữ liệu vẫn ở $Dir (xoá tay nếu không cần)."
