param(
    [Parameter(Mandatory)] [string] $Sdk,
    [Parameter(Mandatory)] [string] $Jdk,
    [Parameter(Mandatory)] [string] $Ndk,
    [Parameter(Mandatory)] [string] $Enet,
    [Parameter(Mandatory)] [string] $Opus,
    [Parameter(Mandatory)] [string] $NanoRs,
    [Parameter(Mandatory)] [string] $Fsr,
    [Parameter(Mandatory)] [string] $Ffmpeg,
    [Parameter(Mandatory)] [string] $Output,
    [switch] $SideBySide
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
& (Join-Path $PSScriptRoot 'checks/run-checks.ps1') -Sdk $Sdk -Jdk $Jdk
if ((git -C $Enet rev-parse HEAD) -ne 'aca87840b57f045a1f7f9299e4b1b9b8e2a5e2f1') {
    throw 'Use the ENet revision pinned by the audited Moonlight common-c source.'
}
$out = (New-Item -ItemType Directory -Force $Output).FullName
$assets = (New-Item -ItemType Directory -Force "$out/assets").FullName
$native = (New-Item -ItemType Directory -Force "$out/lib/arm64-v8a").FullName
$clang = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android29-clang.cmd'
if ((git -C $Opus rev-parse HEAD) -ne 'ddbe48383984d56acd9e1ab6a090c54ca6b735a6') {
    throw 'Use the pinned Opus v1.5.2 checkout.'
}
if ((git -C $NanoRs rev-parse HEAD) -ne 'b1e3c22ca0cdc0bb83e3cd6ed1a2fc77869ed99a') {
    throw 'Use the Reed-Solomon revision pinned by Moonlight common-c.'
}
$cmakeBin = Join-Path $Sdk 'cmake/3.22.1/bin'
& "$cmakeBin/cmake.exe" -S $Opus -B "$out/opus-build" -G Ninja "-DCMAKE_MAKE_PROGRAM=$cmakeBin/ninja.exe" "-DCMAKE_TOOLCHAIN_FILE=$Ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DOPUS_BUILD_TESTING=OFF -DOPUS_BUILD_PROGRAMS=OFF
& "$cmakeBin/cmake.exe" --build "$out/opus-build" --parallel 4
$enetSources = @('callbacks.c', 'compress.c', 'host.c', 'list.c', 'packet.c', 'peer.c', 'protocol.c', 'unix.c') |
    ForEach-Object { Join-Path $Enet $_ }
$obl = Join-Path $NanoRs 'deps/obl'
& $clang -shared -fPIC -O2 '-Wl,-z,max-page-size=16384' -DHAS_FCNTL -DHAS_POLL -DHAS_GETADDRINFO -DHAS_GETNAMEINFO -DHAS_INET_PTON -DHAS_INET_NTOP -DHAS_MSGHDR_FLAGS -DHAS_SOCKLEN_T "-I$Enet/include" "-I$Opus/include" "-I$NanoRs" "-I$obl" (Join-Path $PSScriptRoot 'control.c') @enetSources "$NanoRs/rs.c" "$obl/oblas_common.c" "$obl/oblas_lite.c" "$out/opus-build/libopus.a" -lm -o "$native/libquestheld_control.so"
Copy-Item (Join-Path $Enet 'LICENSE') "$assets/ENet-LICENSE.txt"
Copy-Item (Join-Path $Opus 'COPYING') "$assets/Opus-LICENSE.txt"
Copy-Item (Join-Path $NanoRs 'LICENSE') "$assets/NanoRS-LICENSE.txt"
if ((git -C $Fsr rev-parse HEAD) -ne 'a21ffb8f6c13233ba336352bdff293894c706575') {
    throw 'Use the pinned AMD FidelityFX FSR 1 checkout.'
}
Copy-Item (Join-Path $Fsr 'ffx-fsr/ffx_a.h') $assets
Copy-Item (Join-Path $Fsr 'ffx-fsr/ffx_fsr1.h') $assets
Copy-Item (Join-Path $Fsr 'license.txt') "$assets/FSR1-LICENSE.txt"

Copy-Item (Join-Path $PSScriptRoot '../../LICENSE') "$assets/Amaterasu-LICENSE.txt"
Copy-Item (Join-Path $PSScriptRoot '../../THIRD_PARTY_NOTICES') "$assets/THIRD_PARTY_NOTICES.txt"
$notices = [IO.File]::ReadAllText("$assets/THIRD_PARTY_NOTICES.txt").Replace("`r`n", "`n")
foreach ($license in 'ENet-LICENSE.txt','Opus-LICENSE.txt','NanoRS-LICENSE.txt','FSR1-LICENSE.txt') {
    $required = [IO.File]::ReadAllText("$assets/$license").Replace("`r`n", "`n").Trim()
    if (!$notices.Contains($required)) { throw "Update THIRD_PARTY_NOTICES: full upstream text missing for $license" }
}

function Write-Record($Stream, [byte[]] $Bytes) {
    $size = [BitConverter]::GetBytes([Net.IPAddress]::HostToNetworkOrder([int]$Bytes.Length))
    $Stream.Write($size)
    $Stream.Write($Bytes)
}

$video = "$out/pattern.h264"
& $Ffmpeg -v error -y -f lavfi -i 'testsrc2=size=1920x1080:rate=60' -frames:v 60 -c:v libopenh264 -g 60 -pix_fmt yuv420p -b:v 6M -maxrate 6M -bsf:v 'h264_metadata=aud=insert' -f h264 $video
$encoded = [IO.File]::ReadAllBytes($video)
$markers = [regex]::Matches([Text.Encoding]::Latin1.GetString($encoded), '\x00?\x00\x00\x01\x09')
if ($markers.Count -ne 60 -or $markers[0].Index -ne 0) { throw 'Unexpected H.264 fixture access units' }
$videoRecords = [IO.File]::Create("$assets/video.bin")
try {
    for ($i = 0; $i -lt $markers.Count; $i++) {
        $start = $markers[$i].Index
        $end = $encoded.Length
        if ($i + 1 -lt $markers.Count) { $end = $markers[$i + 1].Index }
        Write-Record $videoRecords ($encoded[$start..($end - 1)])
    }
} finally { $videoRecords.Dispose() }

foreach ($duration in 5, 10) {
    $ogg = "$out/tone-$duration.ogg"
    & $Ffmpeg -v error -y -f lavfi -i 'sine=frequency=660:sample_rate=48000:duration=1' -af "volume='if(lt(t,0.55),4,0)':eval=frame" -ac 2 -c:a libopus -application lowdelay -frame_duration $duration -b:a 96k -vbr off $ogg
    $encoded = [IO.File]::ReadAllBytes($ogg)
    $audioRecords = [IO.File]::Create("$assets/audio-$duration.bin")
    $packet = [IO.MemoryStream]::new()
    $offset = 0
    $index = 0
    try {
        while ($offset -lt $encoded.Length) {
            if ([Text.Encoding]::ASCII.GetString($encoded, $offset, 4) -ne 'OggS') { throw 'Invalid Ogg fixture' }
            $segments = [int]$encoded[$offset + 26]
            $payload = $offset + 27 + $segments
            for ($i = 0; $i -lt $segments; $i++) {
                $length = [int]$encoded[$offset + 27 + $i]
                $packet.Write($encoded, $payload, $length)
                $payload += $length
                if ($length -eq 255) { continue }
                if ($index -ge 2 -and $index -lt (2 + 1000 / $duration)) {
                    Write-Record $audioRecords ($packet.ToArray())
                }
                $index++
                $packet.SetLength(0)
            }
            $offset = $payload
        }
        if ($index -lt (2 + 1000 / $duration)) { throw 'Incomplete Opus fixture' }
    } finally {
        $packet.Dispose()
        $audioRecords.Dispose()
    }
}

$manifest = Join-Path $PSScriptRoot 'AndroidManifest.xml'
$apkName = 'Amaterasu-0.6.7.apk'
if ($SideBySide) {
    $manifest = Join-Path $PSScriptRoot 'AndroidManifest.pacing.xml'
}
& (Join-Path $PSScriptRoot '../capture-probe/build.ps1') -Sdk $Sdk -Jdk $Jdk -Source $PSScriptRoot -Output $out -Assets $assets -NativeLibraryRoot $out -ApkName $apkName -Manifest $manifest -ResourcesRoot (Join-Path $PSScriptRoot 'res')
