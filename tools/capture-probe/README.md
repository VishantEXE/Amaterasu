# QuestHeld capture probe

Android 13+ diagnostic for the AYN Odin2 Portal. It displays a new visual frame ID on each draw and records for eight seconds through MediaProjection and the selected Qualcomm surface encoder. The APK is debug-signed and has no network permission. Use only the probe pattern during recording.

Build from PowerShell 7 with a JDK, Android SDK platform 36, build-tools 35.0.0 and a standard Android debug keystore. Pass an output directory outside the repository.

```powershell
./build.ps1 -Sdk $sdk -Jdk $jdk -Output $output
adb install -r (Join-Path $output 'QuestHeldCaptureProbe.apk')
adb shell am force-stop dev.questheld.captureprobe
adb shell am start -n dev.questheld.captureprobe/.ProbeActivity --ei fps 120
```

Tap Start now and leave the animated pattern visible. Wait for the completion message in the logs. For the AVC baseline, stop the app and start it with `--ei fps 60 --ez avc true`. Add `--ez uncapped true` to omit the MediaCodec input frame-rate cap while keeping the requested encoder rate. Pull each result before starting another run. The log reports fixture draw count, monotonic draw span and the number of draw intervals over 12 ms.

```powershell
adb logcat -d -s QuestHeldProbe
adb exec-out run-as dev.questheld.captureprobe cat files/capture.mp4 > capture.mp4
./analyze.ps1 -Video capture.mp4 -FfmpegDir $ffmpegDir
adb shell am force-stop dev.questheld.captureprobe
```

Use the same FFmpeg installation for decode and timestamps. The analyzer checks the marker prefix and compares visible frame IDs with the MP4 timestamps. The gap count includes intentional sampling when the encoder is capped below the source cadence. The probe measures the combined capture-to-encoded-file path. Separate instrumentation is needed to locate missed IDs within it.
