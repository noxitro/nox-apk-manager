<#
  .SYNOPSIS
  アダプティブアイコン(XML)から、旧来の PNG ランチャーアイコンを書き出す。

  .DESCRIPTION
  Meta Quest(Horizon OS)のライブラリはアダプティブアイコンを解釈しない。
  mipmap-anydpi-v26/ic_launcher.xml しか無いと、渡せる画像が無く頭文字の
  プレースホルダになる。各密度の PNG を置いておくとそちらが使われる。
  Android 8 以降の通常のランチャーは今までどおり XML の方を使うので、
  スマホ側の見た目は変わらない。

  形は ic_launcher_foreground.xml と同じ(直線だけの多角形 4 つ)。
  ベクターを解釈するのではなく、同じ座標をここに持っている。
  **ベクターを描き変えたら、この中の PATHS も合わせて直すこと。**

  アダプティブアイコンの 108x108 のうち、確実に見える中央 72x72 を切り出して
  各密度の大きさに拡大する。

  .EXAMPLE
  pwsh scripts\make-legacy-icons.ps1
#>
param(
    [string]$ResDir = (Join-Path $PSScriptRoot "..\app\src\main\res")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$Background = "#1E2A4A"

# ic_launcher_foreground.xml の path をそのまま写したもの(viewport 108x108)
$PATHS = @(
    @{ Fill = "#7AA2F7"; Points = @(@(34, 62), @(54, 52), @(74, 62), @(54, 72)) },
    @{ Fill = "#5A7FD6"; Points = @(@(34, 62), @(54, 72), @(54, 86), @(34, 76)) },
    @{ Fill = "#4463B5"; Points = @(@(74, 62), @(54, 72), @(54, 86), @(74, 76)) },
    @{ Fill = "#F7768E"; Points = @(@(54, 24), @(66, 38), @(59, 38), @(59, 50), @(49, 50), @(49, 38), @(42, 38)) }
)

# 密度ごとの一辺(px)。48dp を基準にした標準の倍率。
$SIZES = [ordered]@{
    "mipmap-mdpi"    = 48
    "mipmap-hdpi"    = 72
    "mipmap-xhdpi"   = 96
    "mipmap-xxhdpi"  = 144
    "mipmap-xxxhdpi" = 192
}

# 108 の中央 72 を使う
$CROP_ORIGIN = 18
$CROP_SIZE = 72

<#
  アダプティブアイコンの前景そのもの(108x108 全体、背景は透明)。
  前景がベクターだと Horizon OS のシェルがアイコンを取れず、
  タスクバーがアプリ名の文字だけになる。ビットマップにすると読める。
#>
function Write-Foreground([string]$Path, [int]$Size) {
    $bmp = New-Object System.Drawing.Bitmap($Size, $Size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.Clear([System.Drawing.Color]::Transparent)
        $scale = $Size / 108
        foreach ($p in $PATHS) {
            $brush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml($p.Fill))
            $pts = $p.Points | ForEach-Object {
                New-Object System.Drawing.PointF([float]($_[0] * $scale), [float]($_[1] * $scale))
            }
            $g.FillPolygon($brush, [System.Drawing.PointF[]]$pts)
            $brush.Dispose()
        }
        $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Output ("{0}: {1}x{1}" -f $Path, $Size)
    } finally { $g.Dispose(); $bmp.Dispose() }
}

$fgDir = Join-Path $ResDir "drawable-nodpi"
New-Item -ItemType Directory -Force -Path $fgDir | Out-Null
Write-Foreground (Join-Path $fgDir "ic_launcher_foreground.png") 432

foreach ($entry in $SIZES.GetEnumerator()) {
    $dir = Join-Path $ResDir $entry.Key
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $size = $entry.Value
    $scale = $size / $CROP_SIZE

    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.Clear([System.Drawing.ColorTranslator]::FromHtml($Background))
        foreach ($p in $PATHS) {
            $brush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml($p.Fill))
            $pts = $p.Points | ForEach-Object {
                New-Object System.Drawing.PointF(
                    [float](($_[0] - $CROP_ORIGIN) * $scale),
                    [float](($_[1] - $CROP_ORIGIN) * $scale))
            }
            $g.FillPolygon($brush, [System.Drawing.PointF[]]$pts)
            $brush.Dispose()
        }
        $out = Join-Path $dir "ic_launcher.png"
        $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Output ("{0}: {1}x{1}" -f $out, $size)
    } finally {
        $g.Dispose()
        $bmp.Dispose()
    }
}
