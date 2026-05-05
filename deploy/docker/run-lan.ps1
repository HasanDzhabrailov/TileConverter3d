$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")
$runtimeDir = Join-Path $PSScriptRoot "runtime"
$lanHostFile = Join-Path $runtimeDir "lan-host.txt"

function Get-LanIp {
    Get-NetIPConfiguration |
        Where-Object { $_.IPv4DefaultGateway -and $_.IPv4Address } |
        ForEach-Object { $_.IPv4Address.IPAddress } |
        Where-Object { $_ -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)' -and $_ -notmatch '^127\.' } |
        Select-Object -First 1
}

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$lanIp = Get-LanIp

if ($lanIp) {
    $lanIp | Set-Content -LiteralPath $lanHostFile -NoNewline
    $mobileDomain = $lanIp.Replace('.', '-') + ".sslip.io"

    "Detected LAN IP: $lanIp"
    "Mobile HTTP URL: http://$mobileDomain`:8080"
} else {
    "" | Set-Content -LiteralPath $lanHostFile -NoNewline
    "LAN IP not detected yet. Docker will start, and mobile links will update when Wi-Fi/LAN becomes available."
}

$syncJob = Start-Job -ScriptBlock {
    param($path)
    function Get-LanIp {
        Get-NetIPConfiguration |
            Where-Object { $_.IPv4DefaultGateway -and $_.IPv4Address } |
            ForEach-Object { $_.IPv4Address.IPAddress } |
            Where-Object { $_ -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)' -and $_ -notmatch '^127\.' } |
            Select-Object -First 1
    }

    while ($true) {
        $current = Get-LanIp
        if ($current) {
            $previous = if (Test-Path -LiteralPath $path) { Get-Content -LiteralPath $path -Raw } else { "" }
            if ($previous.Trim() -ne $current) {
                $current | Set-Content -LiteralPath $path -NoNewline
            }
        }
        Start-Sleep -Seconds 2
    }
} -ArgumentList $lanHostFile

try {
    docker compose -f (Join-Path $repoRoot "deploy\docker\docker-compose.yml") up --build
} finally {
    Stop-Job $syncJob -ErrorAction SilentlyContinue | Out-Null
    Remove-Job $syncJob -ErrorAction SilentlyContinue | Out-Null
}
