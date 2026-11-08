param(
    [string] $Source = (Join-Path $PSScriptRoot '../../branding/amaterasu-icon-original.png')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$inputIcon = [Drawing.Image]::FromFile((Resolve-Path -LiteralPath $Source).Path)
try {
    $destination = New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot 'res/drawable-nodpi')
    $bitmap = [Drawing.Bitmap]::new(512,512)
    try {
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.Clear([Drawing.Color]::White)
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $scale = [Math]::Min(512.0/$inputIcon.Width,512.0/$inputIcon.Height)
            $width = [single]($inputIcon.Width*$scale)
            $height = [single]($inputIcon.Height*$scale)
            $graphics.DrawImage($inputIcon,([single]((512-$width)/2)),([single]((512-$height)/2)),$width,$height)
        } finally { $graphics.Dispose() }
        $bitmap.Save((Join-Path $destination.FullName 'amaterasu_art.png'),[Drawing.Imaging.ImageFormat]::Png)
    } finally { $bitmap.Dispose() }
} finally { $inputIcon.Dispose() }
