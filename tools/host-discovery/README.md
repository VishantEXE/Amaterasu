# Amaterasu Android host

Amaterasu registers the GameStream discovery service with Android NSD and answers compatible Moonlight clients' HTTP server information requests. It listens on Wi-Fi IPv4 ports 47989 (HTTP) and 47984 (mutual TLS). It persists its host UUID, Android Keystore identity and one paired client certificate. Live screen/audio sharing runs in a foreground service when switching to a game.

Build from PowerShell 7 with the same Android SDK, JDK and debug keystore as the [capture probe](../capture-probe/README.md), plus Android NDK, SDK CMake 3.22.1, FFmpeg with OpenH264 and libopus, the [pinned ENet checkout](https://github.com/cgutman/enet/tree/aca87840b57f045a1f7f9299e4b1b9b8e2a5e2f1), and [Opus v1.5.2](https://github.com/xiph/opus/tree/ddbe48383984d56acd9e1ab6a090c54ca6b735a6). Use a dedicated output directory.

```powershell
./tools/host-discovery/build-fixture.ps1 -Sdk $sdk -Jdk $jdk -Ndk $ndk -Enet $enet -NanoRs $nanors -Opus $opus -Fsr $fsr -Ffmpeg $ffmpeg -Output $output -SideBySide
# After the user approves this build:
adb install -r (Join-Path $output 'Amaterasu-0.6.1.apk')
adb shell am start -n dev.questheld.host.pacing/dev.questheld.host.HostActivity
```

The idle diagnostic fixture uses **1080p, 60 FPS, H.264 and stereo**. Select **Amaterasu**, then **Screen**. If pairing is required, enter the PIN shown by the Moonlight client in Amaterasu. The fixture sends an 18-second moving pattern and stereo tone; approve live screen sharing to stream the actual device.

For live mode, choose the host codec, bitrate limit and FSR setting before selecting **Start sharing** and authorizing Android's prompts. Then open **Screen** in Moonlight at **60 FPS** (or 120 when enabled), stereo, and the matching output resolution. HEVC mode supports hardware HEVC with H.264 fallback. Use Auto or HEVC in Moonlight to negotiate HEVC. The host and encoder bitrate limits apply alongside Moonlight's requested bitrate. The UI range is 10–200 Mbps and defaults to 20 Mbps.

FSR off uses a GPU pass-through at 1080p. FSR on offers **720p → 1080p**, **1080p → 3840×2160**, and in version 0.4 **1080p → 2560×1440**, through AMD EASU and RCAS before encoding. Select the matching output resolution in Moonlight. Shader initialization failure ends startup with a logged error. Capability checks do not qualify sustained 4K performance.

The foreground service remains active when switching to a game or video. One live stream is supported per capture consent. Stop through the foreground notification or host activity. Android permits playback capture only for eligible apps, and the audio channel carries silence when capture is unavailable.

The sender uses 20% video parity, two audio parity packets per four data packets, and an independent paced audio thread. Exceptionally large access units use the stock protocol's four-block path without parity. Late output is discarded with keyframe recovery. Version 0.4 reduces bitrate after queue/recovery drops and oversized units as well as lateness, with a five-second cooldown. Check `adb logcat -s QuestHeldHost` for the negotiated profile, PCM peak, queue drops and sent totals.

The user requires approval before installing every new version. Build and verify the APK, then obtain approval for that build before installation.

Version 0.3 adds a 120 FPS switch, defaulting to off. Match the Quest's selected FPS to that switch. Capture/encoding run independently of video transport, with a bounded queue and keyframe recovery on overflow. The capture scheduler selects fresh source frames at the selected rate. Source timestamps are logged separately from encoder input presentation timestamps.

Use the historical -SideBySide build option for the upgrade package dev.questheld.host.pacing, formerly QuestHeld Pacing. Its public name is now **Amaterasu**. Existing pairing/preferences survive an in-place update with the same signing key. Both installed host variants use the standard ports, so run one host at a time.

Timing CSVs are automatically saved under the app's external files/diagnostics directory. For the pacing app, use adb pull on /sdcard/Android/data/dev.questheld.host.pacing/files/diagnostics.

Logs retain source latches, scheduled encoder inputs, encoder outputs, queue resets, and video send start/end records. The writer is asynchronous and bounded. Up to five sessions are kept, with a 16 MiB event limit per session and a dropped-row count.

From a PC on the LAN, request `http://<host-ip>:47989/serverinfo` to confirm that the host is reachable. Public discovery reports an unpaired state. Authenticated HTTPS reports the pairing state of the client certificate. Pending pairing expires after 90 seconds and a successful new pairing replaces the single saved peer.

Version 0.6 adds supported Android lifecycle/notification/recovery controls and public local-only hotspot setup. See [validation and known limitations](../../docs/VALIDATION.md). Keep LAN selected when both devices join an external/mobile hotspot; select Direct Wi-Fi Hotspot only when this Android host creates the AP. The new APK is a development candidate, not a measured 4K stability result.

## Dependency setup (Windows)

Requires PowerShell 7, Git, JDK 17, Android SDK platform 36, build-tools 35.0.0, NDK 29.0.14206865 and SDK CMake 3.22.1. Install an FFmpeg executable supporting libopenh264 and libopus. Create the standard Android debug keystore with Android Studio, or keytool, before building. The build signs with USERPROFILE/.android/debug.keystore (standard development password android); never commit this key. Use a consistent signing key for updates.

Clone each dependency into a separate directory and check out the exact revision below. Build scripts reject other revisions. NanoRS includes its tracked deps/obl sources.

| Argument | Source | Revision |
| --- | --- | --- |
| -Enet | https://github.com/cgutman/enet | aca87840b57f045a1f7f9299e4b1b9b8e2a5e2f1 |
| -Opus | https://github.com/xiph/opus | ddbe48383984d56acd9e1ab6a090c54ca6b735a6 |
| -NanoRs | https://github.com/sleepybishop/nanors | b1e3c22ca0cdc0bb83e3cd6ed1a2fc77869ed99a |
| -Fsr | https://github.com/GPUOpen-Effects/FidelityFX-FSR | a21ffb8f6c13233ba336352bdff293894c706575 |

For example, run git clone for each URL, then git -C <checkout> checkout <revision>. Set the example build variables to the absolute SDK, JDK, NDK, dependency, FFmpeg executable and output paths. The output directory must be outside the source tree or under ignored outputs/. Only arm64-v8a is built; Android 13 or newer is required. Existing historical package names in source are intentional.

To collect diagnostics, pass explicit -HostSerial and -ClientSerial to collect-diagnostics.ps1 (or -HostOnly). It writes device/app/network details into the chosen output directory; inspect before sharing. No device identifiers are baked into the collector.
