param(
    [Parameter(Mandatory)] [string] $Video,
    [Parameter(Mandatory)] [string] $FfmpegDir
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$raw = [IO.Path]::GetTempFileName()
try {
    & (Join-Path $FfmpegDir 'ffmpeg.exe') -v error -y -i $Video -map 0:v:0 -vf 'crop=iw:2:0:ih/2,scale=32:1:flags=neighbor,format=gray' -fps_mode passthrough -f rawvideo $raw
    $bytes = [IO.File]::ReadAllBytes($raw)
    $probe = & (Join-Path $FfmpegDir 'ffprobe.exe') -v error -select_streams v:0 -show_frames -show_entries 'frame=best_effort_timestamp_time' -of json $Video | ConvertFrom-Json
    $frames = $probe.frames
    if ($bytes.Length -ne ($frames.Count * 32)) { throw 'Frame count differs between decoder and timestamp probe.' }
    $samples = @(
        for ($i = 0; $i -lt $frames.Count; $i++) {
            $marker = 0
            for ($cell = 6; $cell -lt 26; $cell++) {
                $marker = ($marker -shl 1) -bor [int]($bytes[$i * 32 + $cell] -gt 128)
            }
            if (($marker -shr 16) -eq 10) {
                [pscustomobject]@{ Id = $marker -band 0xffff; Seconds = [double]$frames[$i].best_effort_timestamp_time }
            }
        }
    )
    if ($samples.Count -lt 2) { throw 'The recording did not contain a readable frame marker.' }
    $duration = $samples[-1].Seconds - $samples[0].Seconds
    if ($duration -le 0) { throw 'Invalid timestamp range.' }
    $unique = @($samples.Id | Sort-Object -Unique).Count
    $skipped = 0
    $discontinuities = 0
    $intervals = @(
        for ($i = 1; $i -lt $samples.Count; $i++) {
            $advance = ($samples[$i].Id - $samples[$i - 1].Id) -band 0xffff
            if ($advance -gt 0 -and $advance -lt 32768) { $skipped += $advance - 1 }
            if ($advance -ge 32768) { $discontinuities++ }
            1000 * ($samples[$i].Seconds - $samples[$i - 1].Seconds)
        }
    ) | Sort-Object
    [pscustomobject]@{
        EncodedFrames = $frames.Count
        ReadableFrames = $samples.Count
        UniqueIds = $unique
        RepeatedIds = $samples.Count - $unique
        SkippedIds = $skipped
        BackwardJumps = $discontinuities
        SpanSeconds = [math]::Round($duration, 3)
        EncodedFpsAcrossMarkers = [math]::Round(($samples.Count - 1) / $duration, 2)
        UniqueFps = [math]::Round(($unique - 1) / $duration, 2)
        P50IntervalMs = [math]::Round($intervals[[math]::Floor(($intervals.Count - 1) * 0.5)], 3)
        P95IntervalMs = [math]::Round($intervals[[math]::Floor(($intervals.Count - 1) * 0.95)], 3)
    }
} finally {
    Remove-Item -LiteralPath $raw
}
