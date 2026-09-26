<#
  .SYNOPSIS
  builds/ 配下の全プロジェクトを走査して meta.json を作り直す。

  .DESCRIPTION
  既存の APK から package 名と版を読み直す。description は既存 meta.json から引き継ぐ。
  -Descriptions にハッシュテーブルを渡すと、その分だけ上書きする。

  .EXAMPLE
  .\regen-meta.ps1
  .\regen-meta.ps1 -Descriptions @{ "photo-viewer" = "写真ビューア" }
#>
param(
    [hashtable]$Descriptions = @{},
    [string]$Root = $null
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "apk-meta.ps1")

if (-not $Root) { $Root = if ($env:NOX_BUILDS_ROOT) { $env:NOX_BUILDS_ROOT } else { "G:\マイドライブ\builds" } }
if (-not (Test-Path $Root)) { throw "配布ルートが無い: $Root" }

foreach ($dir in Get-ChildItem -Path $Root -Directory | Sort-Object Name) {
    $desc = if ($Descriptions.ContainsKey($dir.Name)) { $Descriptions[$dir.Name] } else { $null }
    Write-ProjectMeta -ProjectDir $dir.FullName -Description $desc
}
