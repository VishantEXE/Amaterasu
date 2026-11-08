param(
    [Parameter(Mandatory)] [string] $Sdk,
    [Parameter(Mandatory)] [string] $Jdk,
    [Parameter(Mandatory)] [string] $Output,
    [string] $BuildToolsVersion = '35.0.0',
    [string] $Platform = 'android-36',
    [string] $Source = $PSScriptRoot,
    [string] $ApkName = 'QuestHeldCaptureProbe.apk',
    [string] $Assets,
    [string] $NativeLibraryRoot,
    [string] $Manifest,
    [string] $ResourcesRoot
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$build = Join-Path $Sdk "build-tools/$BuildToolsVersion"
$android = Join-Path $Sdk "platforms/$Platform/android.jar"
$out = (New-Item -ItemType Directory -Force $Output).FullName
$classes = (New-Item -ItemType Directory -Force (Join-Path $out classes)).FullName
$java = Join-Path $Jdk 'bin/java.exe'
$apk = Join-Path $out $ApkName
$unsigned = Join-Path $out 'unsigned.apk'
if (!$Manifest) { $Manifest = Join-Path $Source 'AndroidManifest.xml' }
$linkArgs = @('link', '-I', $android, '--manifest', $Manifest, '-o', $unsigned)
if ($Assets) { $linkArgs += @('-A', $Assets) }
if ($ResourcesRoot) {
    $compiled = Join-Path $out 'resources.zip'
    & (Join-Path $build 'aapt2.exe') compile --dir $ResourcesRoot -o $compiled
    $linkArgs += @('--java', (Join-Path $out 'generated'), '--custom-package', 'dev.questheld.host', $compiled)
}
& (Join-Path $build 'aapt2.exe') @linkArgs
$sources = (Get-ChildItem $Source -Filter '*.java' -File).FullName
if ($ResourcesRoot) { $sources += (Get-ChildItem (Join-Path $out 'generated') -Filter '*.java' -Recurse).FullName }
& (Join-Path $Jdk 'bin/javac.exe') -encoding UTF-8 --release 8 -cp $android -d $classes $sources
& $java -cp (Join-Path $build 'lib/d8.jar') com.android.tools.r8.D8 --min-api 33 --lib $android --output $out (Get-ChildItem $classes -Filter '*.class' -Recurse).FullName
$jar = Join-Path $Jdk 'bin/jar.exe'
& $jar uf $unsigned -C $out classes.dex
if ($NativeLibraryRoot) { & $jar uf $unsigned -C $NativeLibraryRoot lib }
$aligned = Join-Path $out 'aligned.apk'
& (Join-Path $build 'zipalign.exe') -f 4 $unsigned $aligned
& $java -jar (Join-Path $build 'lib/apksigner.jar') sign --ks (Join-Path $env:USERPROFILE '.android/debug.keystore') --ks-pass pass:android --out $apk $aligned
& $java -jar (Join-Path $build 'lib/apksigner.jar') verify $apk
Write-Output $apk
