# Build image agent và xuất ra file .tar để mang sang site không có internet.
#   powershell -ExecutionPolicy Bypass -File agent-docker\build-image.ps1
# Tại site:  docker load -i vm-monitor-agent.tar   rồi   docker compose up -d   (thư mục chứa docker-compose.yml + .env)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
docker build -t vm-monitor-agent:latest .
if ($LASTEXITCODE) { throw "docker build lỗi" }
$out = Join-Path $PSScriptRoot 'vm-monitor-agent.tar'
docker save -o $out vm-monitor-agent:latest
if ($LASTEXITCODE) { throw "docker save lỗi" }
Write-Host ""
Write-Host "Đã tạo $out ($([math]::Round((Get-Item $out).Length / 1MB)) MB)"
Write-Host "Mang sang site 3 file: vm-monitor-agent.tar, docker-compose.yml, .env.example"
