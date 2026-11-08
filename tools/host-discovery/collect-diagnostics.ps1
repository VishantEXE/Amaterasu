param(
    [Parameter(Mandatory)] [string] $Adb,
    [Parameter(Mandatory)] [string] $Output,
    [Parameter(Mandatory)] [string] $HostSerial,
    [Alias('ClientSerial')] [string] $QuestSerial,
    [string] $ClientPackage = 'com.gilleece.moonlightxr',
    [string] $ClientLabel = 'quest',
    [int] $WaitMinutes = 5,
    [string] $HostPackage = 'dev.questheld.host.pacing',
    [int] $Minutes = 10,
    [switch] $StartImmediately,
    [switch] $HostOnly
)
$ErrorActionPreference = 'Stop'
if (!$HostOnly -and !$QuestSerial) { throw 'Pass -ClientSerial or use -HostOnly.' }
$folder = (New-Item -ItemType Directory -Force $Output).FullName
$logs = @()
$sourceUidCache = @{}
function Save-DeviceState([string] $Serial, [string] $Label, [string] $Package, [int] $Sample) {
    $prefix = Join-Path $folder ("{0}-{1:D3}" -f $Label, $Sample)
    & $Adb -s $Serial shell dumpsys meminfo -s $Package > "$prefix-memory.txt"
    & $Adb -s $Serial shell cmd wifi status > "$prefix-wifi.txt"
    if ($Label -eq 'host') {
        & $Adb -s $Serial shell cat /proc/meminfo > "$prefix-system-memory.txt"
        & $Adb -s $Serial shell cat /proc/pressure/memory > "$prefix-memory-pressure.txt"
        $foreground = (& $Adb -s $Serial shell dumpsys activity activities | Select-String 'topResumedActivity=' | Select-Object -First 1).Line
        Set-Content "$prefix-foreground.txt" $foreground
        if ($foreground -match '\s([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+') {
            $sourcePackage = $Matches[1]
            if ($sourcePackage -ne $Package) {
                if (!$sourceUidCache.ContainsKey($sourcePackage)) {
                    $packageInfo = (& $Adb -s $Serial shell dumpsys package $sourcePackage) -join "`n"
                    if ($packageInfo -match 'userId=(\d+)') { $sourceUidCache[$sourcePackage] = $Matches[1] }
                }
                $sourceUid = $sourceUidCache[$sourcePackage]
                $sourceProcesses = @()
                foreach ($line in (& $Adb -s $Serial shell ps -A -o PID,UID,RSS,NAME)) {
                    if ($line -match '^\s*(\d+)\s+(\d+)\s+(\d+)\s+(.+)$') {
                        if ($Matches[2] -eq $sourceUid) {
                            $sourceProcesses += [pscustomobject]@{Pid=$Matches[1];Uid=$Matches[2];RssKiB=[long]$Matches[3];Name=$Matches[4]}
                        }
                    }
                }
                $sourceProcesses | ConvertTo-Json -Depth 3 | Set-Content "$prefix-source-processes.json"
                # Native game processes may not carry the Android package prefix.
                # Sample the four largest same-UID processes without repeatedly dumping every helper.
                foreach ($sourceProcess in ($sourceProcesses | Sort-Object RssKiB -Descending | Select-Object -First 4)) {
                    $sourcePid = $sourceProcess.Pid
                    & $Adb -s $Serial shell dumpsys meminfo -s $sourcePid > "$prefix-source-$sourcePid-memory.txt"
                }
            }
        }
    }
    if ($Sample % 2 -eq 0) {
        & $Adb -s $Serial shell dumpsys thermalservice > "$prefix-thermal.txt"
        & $Adb -s $Serial shell dumpsys cpuinfo > "$prefix-cpu.txt"
    }
}
try {
    $hostOptions = @{
        FilePath = $Adb
        ArgumentList = @('-s', $HostSerial, 'logcat', '-T', '1', '-v', 'threadtime',
            '-s', 'QuestHeldHost', 'MediaCodec', 'CCodec', 'AudioRecord', 'AndroidRuntime')
        WindowStyle = 'Hidden'
        PassThru = $true
        RedirectStandardOutput = "$folder/host-live.log"
        RedirectStandardError = "$folder/host-logcat-errors.txt"
    }
    $logs += Start-Process @hostOptions
    $systemOptions = @{
        FilePath = $Adb
        ArgumentList = @('-s', $HostSerial, 'logcat', '-T', '1', '-v', 'threadtime',
            '-s', 'ActivityManager', 'lowmemorykiller', 'lmkd', 'AndroidRuntime')
        WindowStyle = 'Hidden'
        PassThru = $true
        RedirectStandardOutput = "$folder/host-system.log"
        RedirectStandardError = "$folder/host-system-errors.txt"
    }
    $logs += Start-Process @systemOptions
    $questOptions = @{
        FilePath = $Adb
        ArgumentList = @('-s', $QuestSerial, 'logcat', '-T', '1', '-v', 'threadtime',
            '-s', 'moonlight-common-c', 'com.limelight.LimeLog', 'LimeLog', 'MediaCodec', 'CCodec', 'AudioTrack', 'AndroidRuntime')
        WindowStyle = 'Hidden'
        PassThru = $true
        RedirectStandardOutput = "$folder/${ClientLabel}-live.log"
        RedirectStandardError = "$folder/${ClientLabel}-logcat-errors.txt"
    }
    if (!$HostOnly) { $logs += Start-Process @questOptions }
    & $Adb -s $HostSerial shell dumpsys activity exit-info $HostPackage > "$folder/host-exits-before.txt"
    if (!$HostOnly) { & $Adb -s $QuestSerial shell dumpsys activity exit-info $ClientPackage > "$folder/${ClientLabel}-exits-before.txt" }
    $waiting = [Diagnostics.Stopwatch]::StartNew()
    $session = $null
    if ($StartImmediately) {
        $session = [Diagnostics.Stopwatch]::StartNew()
        Set-Content "$folder/capture-start-utc.txt" ([DateTime]::UtcNow.ToString('o'))
    }
    $nextSample = 0.0
    $sample = 0
    $nextStatus = 60.0
    Write-Output $(if ($StartImmediately) { "Recording the $Minutes-minute extension now." } else { 'Recording logs. Waiting for the live capture session to start.' })
    while ($null -eq $session -or $session.Elapsed.TotalSeconds -lt $Minutes * 60) {
        if ($null -eq $session) {
            if (Select-String -LiteralPath "$folder/host-live.log" -Pattern 'Live MediaProjection started:' -Quiet) {
                $session = [Diagnostics.Stopwatch]::StartNew()
                Set-Content "$folder/capture-start-utc.txt" ([DateTime]::UtcNow.ToString('o'))
                Write-Output "Live capture detected. Starting the $Minutes-minute measurement window."
            } elseif ($waiting.Elapsed.TotalMinutes -ge $WaitMinutes) {
                throw "No live capture started within $WaitMinutes minutes. Collected logs were preserved."
            }
        }
        if ($null -ne $session) {
            if ($session.Elapsed.TotalSeconds -ge $nextSample) {
                Set-Content "$folder/sample-$sample-utc.txt" ([DateTime]::UtcNow.ToString('o'))
                Save-DeviceState $HostSerial 'host' $HostPackage $sample
                if (!$HostOnly) { Save-DeviceState $QuestSerial $ClientLabel $ClientPackage $sample }
                $sample++
                $nextSample = $session.Elapsed.TotalSeconds + 15
            }
            if ($session.Elapsed.TotalSeconds -ge $nextStatus) {
                Write-Output ("Measured {0:N1} of {1} minutes" -f $session.Elapsed.TotalMinutes, $Minutes)
                $nextStatus += 60
            }
        }
        Start-Sleep -Seconds 2
    }
    Set-Content "$folder/capture-end-utc.txt" ([DateTime]::UtcNow.ToString('o'))
    & $Adb -s $HostSerial shell dumpsys activity exit-info $HostPackage > "$folder/host-exits-after.txt"
    if (!$HostOnly) { & $Adb -s $QuestSerial shell dumpsys activity exit-info $ClientPackage > "$folder/${ClientLabel}-exits-after.txt" }
    & $Adb -s $HostSerial pull "/sdcard/Android/data/$HostPackage/files/diagnostics" "$folder/host-timing"
    if (!$HostOnly -and $ClientPackage -eq 'com.gilleece.moonlightxr') { & $Adb -s $QuestSerial pull /sdcard/Download/MoonlightXR/moonlight.log "$folder/${ClientLabel}-moonlight.log" }
    Write-Output "Measurement complete. Diagnostics saved in $folder"
} finally {
    foreach ($process in $logs) {
        if (!$process.HasExited) { Stop-Process -Id $process.Id }
    }
}
