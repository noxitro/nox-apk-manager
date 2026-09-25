<#
  APK を入れて起動し、スクリーンショットと直近のクラッシュログを取る。

  端末は必ず serial で固定する。このホストには他プロジェクトのエミュレータが
  同時に立っており、指定しないと相手の端末に入れてしまう。
  logcat も自分のプロセスだけに絞る。端末全体を読むと他アプリのクラッシュが
  混ざり、原因を取り違える(兄弟プロジェクトで 2026-09-04 に実際に踏んだ)。
#>
param(
    [string]$Serial = "",
    [string]$Avd = "apkmanager_test",
    [string]$Shot = ""
)

$ErrorActionPreference = "Stop"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = "$sdk\platform-tools\adb.exe"
$root = Join-Path $PSScriptRoot ".."
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$shots = Join-Path $root "build\shots"
New-Item -ItemType Directory -Force -Path $shots | Out-Null

if (-not $Serial) {
    $out = & (Join-Path $PSScriptRoot "emulator.ps1") -Avd $Avd
    $Serial = ("$($out | Select-Object -Last 1)").Trim()
}
if (-not $Serial) { throw "端末の serial を決められませんでした" }
Write-Output "対象: $Serial"

$pkg = "com.noxitro.apkmanager"

& $adb -s $Serial install -r $apk
& $adb -s $Serial shell am force-stop $pkg
& $adb -s $Serial logcat -c
& $adb -s $Serial shell am start -n "$pkg/.MainActivity"
Start-Sleep -Seconds 5

if (-not $Shot) { $Shot = (Get-Date -Format "yyyyMMdd-HHmmss") }
& $adb -s $Serial shell screencap -p /sdcard/shot.png
& $adb -s $Serial pull /sdcard/shot.png (Join-Path $shots "$Shot.png")

# 自分のプロセスの分だけを見る。--pid は Android 7 以降で使える。
$appPid = ("$(& $adb -s $Serial shell pidof $pkg)").Trim()
Write-Output "--- logcat ($pkg pid=$appPid) ---"
if ($appPid) {
    & $adb -s $Serial logcat -d --pid $appPid
} else {
    Write-Output "(プロセスが見つからない = 起動直後に落ちた可能性)"
    & $adb -s $Serial logcat -d -s AndroidRuntime:E
}
