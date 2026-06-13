$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$appDir = Join-Path $repoRoot "spring-boot-agent"
$maven = "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.1\plugins\maven\lib\maven3\bin\mvn.cmd"
$outLog = Join-Path $appDir "agent-local.log"
$errLog = Join-Path $appDir "agent-local.err.log"

if (!(Test-Path $maven)) {
    throw "Maven was not found at $maven. Install Maven or update this script with the local mvn.cmd path."
}

& (Join-Path $repoRoot "tools\mongodb\start-local-mongodb.ps1")

$existing = Get-NetTCPConnection -LocalPort 8080, 8081, 8082 -ErrorAction SilentlyContinue |
    Where-Object { $_.State -eq "Listen" }

if ($existing) {
    $ports = ($existing | Select-Object -ExpandProperty LocalPort -Unique) -join ", "
    Write-Host "A local web server is already listening on port(s): $ports"
    Write-Host "Not starting another OMSR agent process."
    exit 0
}

Remove-Item -LiteralPath $outLog, $errLog -Force -ErrorAction SilentlyContinue

$arguments = @(
    "-DskipTests",
    "spring-boot:run",
    "-Dspring-boot.run.profiles=local"
)

$process = Start-Process `
    -FilePath $maven `
    -ArgumentList $arguments `
    -WorkingDirectory $appDir `
    -RedirectStandardOutput $outLog `
    -RedirectStandardError $errLog `
    -WindowStyle Hidden `
    -PassThru

Write-Host "Started local OMSR agent. PID: $($process.Id)"
Write-Host "Output log: $outLog"
Write-Host "Error log: $errLog"
