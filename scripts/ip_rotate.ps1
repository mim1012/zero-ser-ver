# IP Rotate Script — ADB data toggle for IP change

$DEVICE = "R3CN107JB5L"
$TRIGGER = "SCENARIO_DONE"
$DATA_OFF_SEC = 20
$RECOVERY_SEC = 10

Write-Host "=== IP Rotate Script ===" -ForegroundColor Green
Write-Host "Device: $DEVICE | OFF: ${DATA_OFF_SEC}s | Recovery: ${RECOVERY_SEC}s"

$lastTimestamp = ""
$count = 0

while ($true) {
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Waiting..." -NoNewline -ForegroundColor Cyan

    $found = $false
    while (-not $found) {
        $line = adb -s $DEVICE shell "logcat -d -s ZeroTraffic:I" 2>$null | Select-String $TRIGGER | Select-Object -Last 1

        if ($line) {
            $lineStr = $line.ToString()
            $ts = $lineStr.Substring(0, [Math]::Min(18, $lineStr.Length))

            if ($ts -ne $lastTimestamp) {
                $found = $true
                $lastTimestamp = $ts
            }
        }

        if (-not $found) {
            Start-Sleep -Seconds 3
        }
    }

    $count++
    Write-Host ""
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] [$count] Rotating..." -ForegroundColor Yellow

    Start-Sleep -Seconds 2
    adb -s $DEVICE shell "svc data disable" 2>$null
    Write-Host "  OFF ${DATA_OFF_SEC}s" -ForegroundColor Red
    Start-Sleep -Seconds $DATA_OFF_SEC

    adb -s $DEVICE shell "svc data enable" 2>$null
    Write-Host "  ON +${RECOVERY_SEC}s" -ForegroundColor Green
    Start-Sleep -Seconds $RECOVERY_SEC

    Write-Host "  [$count] done" -ForegroundColor Green
}
