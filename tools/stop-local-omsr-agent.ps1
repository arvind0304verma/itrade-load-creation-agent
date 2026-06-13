$ErrorActionPreference = "Stop"

$listeners = Get-NetTCPConnection -LocalPort 8080, 8081, 8082 -ErrorAction SilentlyContinue |
    Where-Object { $_.State -eq "Listen" }

if (!$listeners) {
    Write-Host "No local OMSR agent web process is listening on ports 8080, 8081, or 8082."
    exit 0
}

$processIds = $listeners | Select-Object -ExpandProperty OwningProcess -Unique

foreach ($processId in $processIds) {
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $processId
        Write-Host "Stopped local OMSR agent process. PID: $processId"
    }
}
