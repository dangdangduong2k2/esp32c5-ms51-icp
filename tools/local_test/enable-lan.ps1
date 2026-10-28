# Run as administrator. Only the local subnet can reach these two test ports.
$ErrorActionPreference='Stop'
$name='MS51-LAN-Test-HTTPS-MQTT'
if (!(Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -Name $name -DisplayName 'MS51 LAN test HTTPS MQTT' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8443,8884 -RemoteAddress LocalSubnet -Profile Any | Out-Null
}
Write-Output 'LAN access enabled. Remove-NetFirewallRule -Name MS51-LAN-Test-HTTPS-MQTT reverses this change.'
