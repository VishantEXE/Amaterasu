param(
    [Parameter(Mandatory)] [string] $Sdk,
    [Parameter(Mandatory)] [string] $Jdk,
    [string] $Platform = 'android-36'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = (New-Item -ItemType Directory -Force (Join-Path $env:TEMP 'QuestHeld-memory-checks')).FullName
$android = Join-Path $Sdk "platforms/$Platform/android.jar"
$sources = @('StreamSettings.java','FramePacer.java','VideoBufferPool.java','VideoBlock.java',
    'CongestionGovernor.java','AudioPacer.java','SessionState.java','LiveStats.java','OverrideOwnership.java','StreamMode.java','checks/HostChecks.java') | ForEach-Object { Join-Path $root $_ }
& "$Jdk/bin/javac.exe" --release 8 -cp $android -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'Host checks failed to compile' }
& "$Jdk/bin/java.exe" -cp "$out;$android" dev.questheld.host.HostChecks
if ($LASTEXITCODE -ne 0) { throw 'Host checks failed' }
