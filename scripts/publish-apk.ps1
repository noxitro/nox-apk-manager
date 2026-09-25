<#
  .SYNOPSIS
  ビルド済み APK を Google Drive の配布フォルダへ置き、meta.json を更新する。

  .DESCRIPTION
  各プロジェクトの README に書いてあった手打ちの copy コマンドの置き換え。
  - 配布先は G:\マイドライブ\builds\<Project>\<Project>-<versionName>-<variant>.apk
    (ドライブレターは環境変数 NOX_BUILDS_ROOT で差し替えられる)
  - versionName / variant は APK 自身から読む(引数で嘘を書けない)
  - ビルド出力先を Drive に直接向けない(Drive Desktop の同期と Gradle の書き込みが競合する)
  - 同名の APK が既にあれば上書きする(同じ versionName の再ビルド)

  .EXAMPLE
  .\publish-apk.ps1 -Project photo-viewer -Apk ..\..\photo-viewer\app\build\outputs\apk\release\app-release.apk
  .\publish-apk.ps1 -Project photo-viewer -Apk ...\app-debug.apk -Description "写真ビューア"
#>
param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][string]$Apk,
    [string]$Description = $null,
    [ValidateSet("", "debug", "release")][string]$Variant = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "apk-meta.ps1")

$root = if ($env:NOX_BUILDS_ROOT) { $env:NOX_BUILDS_ROOT } else { "G:\マイドライブ\builds" }
if (-not (Test-Path $root)) { throw "配布ルートが無い(Google Drive Desktop は起動している?): $root" }

if (-not (Test-Path $Apk)) { throw "APK が無い: $Apk" }
$apkItem = Get-Item $Apk

if (-not $Variant) {
    # Gradle の既定出力名 app-debug.apk / app-release.apk から推定する
    if ($apkItem.Name -match '(debug|release)') { $Variant = $Matches[1] }
    else { throw "-Variant を指定してください(ファイル名から debug/release を判別できない): $($apkItem.Name)" }
}

$info = Read-ApkBadging $apkItem.FullName
$dest = Join-Path $root $Project
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$destName = "$Project-$($info.versionName)-$Variant.apk"
$destPath = Join-Path $dest $destName

Copy-Item -Path $apkItem.FullName -Destination $destPath -Force
Write-Output "copied: $destPath ($($info.packageName) v$($info.versionName) code $($info.versionCode))"

Write-ProjectMeta -ProjectDir $dest -Description $Description
