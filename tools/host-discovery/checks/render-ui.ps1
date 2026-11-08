param(
    [Parameter(Mandatory)] [string] $Studio,
    [Parameter(Mandatory)] [string] $BuildClasses,
    [Parameter(Mandatory)] [string] $Resources,
    [Parameter(Mandatory)] [string] $Output
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$true
$data=Join-Path $Studio 'plugins/design-tools/resources/layoutlib/data'
$temporary=(New-Item -ItemType Directory -Force (Join-Path $env:TEMP 'QuestHeld-ui-preview')).FullName
$framework=Join-Path $temporary 'framework'
if(!(Test-Path $framework)) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $data 'framework_res.jar'),$framework)
}
$cp="$Studio/plugins/design-tools/lib/layoutlib.jar;$Studio/plugins/android/lib/*;$Studio/plugins/design-tools/lib/*;$Studio/lib/*;$BuildClasses"
& "$Studio/jbr/bin/javac.exe" -encoding UTF-8 -cp $cp -d $temporary (Join-Path $PSScriptRoot 'UiRender.java')
& "$Studio/jbr/bin/java.exe" '-Djava.awt.headless=true' '--enable-native-access=ALL-UNNAMED' '--add-opens=java.base/java.lang=ALL-UNNAMED' -cp "$temporary;$cp" dev.questheld.host.UiRender $data $Resources $Output $framework
