param(
    [Parameter(Mandatory)] [string] $Sdk,
    [Parameter(Mandatory)] [string] $Jdk
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = (New-Item -ItemType Directory -Force (Join-Path $env:TEMP 'QuestHeld-memory-checks')).FullName
$android = Join-Path $Sdk 'platforms/android-36/android.jar'
$sources = @('StreamSettings.java','FramePacer.java','VideoBufferPool.java','VideoBlock.java',
    'CongestionGovernor.java','AudioPacer.java','SessionState.java','OverrideOwnership.java','checks/HostChecks.java') | ForEach-Object { Join-Path $root $_ }
& "$Jdk/bin/javac.exe" --release 8 -cp $android -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'Host checks failed to compile' }
& "$Jdk/bin/java.exe" -cp "$out;$android" dev.questheld.host.HostChecks
if ($LASTEXITCODE -ne 0) { throw 'Host checks failed' }
