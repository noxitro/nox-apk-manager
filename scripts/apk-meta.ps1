<#
  APK から package 名 / versionName / versionCode / ラベル を読む共通関数。
  publish-apk.ps1 と regen-meta.ps1 から dot-source して使う。

  aapt2 は Android SDK の build-tools から一番新しいものを選ぶ。
#>

$script:Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }

function Get-Aapt2 {
    $candidates = Get-ChildItem -Path (Join-Path $script:Sdk "build-tools") -Directory -ErrorAction Stop |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending
    foreach ($dir in $candidates) {
        $exe = Join-Path $dir.FullName "aapt2.exe"
        if (Test-Path $exe) { return $exe }
    }
    throw "aapt2.exe が build-tools に見つかりません: $script:Sdk"
}

<#
  .SYNOPSIS
  APK のマニフェスト情報をハッシュテーブルで返す。
  読めない APK は例外にする(黙って空を返すと meta.json が壊れた状態で配布される)。
#>
function Read-ApkBadging([string]$ApkPath) {
    $aapt = Get-Aapt2
    $lines = & $aapt dump badging $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw "aapt2 dump badging に失敗: $ApkPath`n$lines" }
    $pkgLine = $lines | Where-Object { $_ -like "package:*" } | Select-Object -First 1
    if (-not $pkgLine) { throw "package 行が無い: $ApkPath" }
    $get = {
        param($key)
        if ($pkgLine -match "$key='([^']*)'") { $Matches[1] } else { $null }
    }
    $label = ($lines | Where-Object { $_ -like "application-label:*" } | Select-Object -First 1)
    $labelValue = if ($label -match "application-label:'([^']*)'") { $Matches[1] } else { $null }
    return @{
        packageName = & $get 'name'
        versionCode = [int](& $get 'versionCode')
        versionName = & $get 'versionName'
        label       = $labelValue
    }
}

function Get-Sha256([string]$Path) {
    (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant()
}

<#
  .SYNOPSIS
  ファイル名 <name>-<version>-<debug|release>.apk から variant を取り出す。
  規約外の名前は $null(呼び出し側で弾く)。
#>
function Get-VariantFromName([string]$FileName) {
    if ($FileName -match '-(debug|release)\.apk$') { return $Matches[1] }
    return $null
}

<#
  .SYNOPSIS
  1 つの配布フォルダ(builds/<project>/)を走査して meta.json を書く。
  既存の meta.json の description と label(手で直した分)は引き継ぐ。
#>
function Write-ProjectMeta([string]$ProjectDir, [string]$Description = $null) {
    $project = Split-Path -Leaf $ProjectDir
    $metaPath = Join-Path $ProjectDir "meta.json"
    $existing = $null
    if (Test-Path $metaPath) {
        try { $existing = Get-Content -Raw -Encoding UTF8 $metaPath | ConvertFrom-Json } catch { $existing = $null }
    }

    $builds = @()
    $packageName = $null
    $label = $null
    foreach ($apk in Get-ChildItem -Path $ProjectDir -Filter *.apk -File | Sort-Object Name) {
        $variant = Get-VariantFromName $apk.Name
        if (-not $variant) {
            Write-Warning "規約外の名前なので meta.json に載せない: $($apk.Name)"
            continue
        }
        $info = Read-ApkBadging $apk.FullName
        if ($packageName -and $info.packageName -ne $packageName) {
            throw "同じフォルダに別 package の APK が混ざっている: $packageName / $($info.packageName) ($($apk.Name))"
        }
        $packageName = $info.packageName
        if ($info.label) { $label = $info.label }
        $builds += [ordered]@{
            file        = $apk.Name
            variant     = $variant
            versionName = $info.versionName
            versionCode = $info.versionCode
            size        = $apk.Length
            sha256      = Get-Sha256 $apk.FullName
            builtAt     = $apk.LastWriteTime.ToString("o")
        }
    }
    if (-not $builds) {
        Write-Warning "APK が無いので meta.json を書かない: $ProjectDir"
        return
    }

    if (-not $Description) {
        $Description = if ($existing -and $existing.description) { $existing.description } else { "" }
    }
    if ($existing -and $existing.label -and -not $label) { $label = $existing.label }

    $meta = [ordered]@{
        schema      = 1
        project     = $project
        packageName = $packageName
        label       = $label
        description = $Description
        builds      = $builds
        updatedAt   = (Get-Date).ToString("o")
    }
    # BOM 無し UTF-8。アプリ側は kotlinx.serialization で読むので BOM があると落ちる。
    $json = $meta | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($metaPath, $json, (New-Object System.Text.UTF8Encoding $false))
    Write-Output "meta.json: $metaPath ($($builds.Count) builds, $packageName)"
}
