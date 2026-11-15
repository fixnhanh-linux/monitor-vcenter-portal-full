# Tao chung chi HTTPS tu ky cho VM Monitor Portal -> certs\portal.pfx
# Chay tren may portal:  powershell -ExecutionPolicy Bypass -File tools\make_cert.ps1 [-DnsName monitor.congty.vn,10.0.0.5]
# Sau do vao Cai dat -> Nguoi dung & bao mat -> Bao mat: bat HTTPS, file .pfx = certs\portal.pfx, nhap mat khau da dat, Luu, khoi dong lai portal.
# Chung chi tu ky: trinh duyet canh bao "khong an toan" lan dau (bam Nang cao -> Tiep tuc). Dung chung chi that cua cong ty neu co.
param(
    [string[]]$DnsName = @(),
    [int]$Years = 3
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$certDir = Join-Path $root 'certs'
New-Item -ItemType Directory -Force $certDir | Out-Null
$out = Join-Path $certDir 'portal.pfx'

if (-not $DnsName.Count) {
    # mac dinh: ten may + localhost + cac IPv4 cua may
    $ips = Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '169.254*' } | Select-Object -ExpandProperty IPAddress
    $DnsName = @($env:COMPUTERNAME, 'localhost') + $ips
}
Write-Host "Tao chung chi cho: $($DnsName -join ', ')"
$cert = New-SelfSignedCertificate -DnsName $DnsName -CertStoreLocation 'Cert:\CurrentUser\My' `
    -FriendlyName 'VM Monitor Portal' -NotAfter (Get-Date).AddYears($Years) -KeyAlgorithm RSA -KeyLength 2048 -KeyExportPolicy Exportable

$pw = Read-Host -AsSecureString 'Dat mat khau cho file .pfx (nho de nhap vao trang Cai dat)'
Export-PfxCertificate -Cert $cert -FilePath $out -Password $pw | Out-Null
# chi giu file .pfx, xoa khoi kho chung chi cua Windows
Remove-Item "Cert:\CurrentUser\My\$($cert.Thumbprint)"
Write-Host ""
Write-Host "Da tao: $out (het han $($cert.NotAfter.ToString('dd/MM/yyyy')))"
Write-Host "Tiep theo: Cai dat -> Nguoi dung & bao mat -> Bao mat -> bat HTTPS, file .pfx = certs\portal.pfx"
