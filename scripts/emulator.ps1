<#
  指定した AVD を起動して boot 完了まで待ち、その serial を標準出力に返す。

  このホストには他プロジェクトのエミュレータが同時に立っている。
  兄弟プロジェクトで 2026-09-04 に共有 AVD の衝突を踏んだ経緯は wiki「共有エミュレータでの計装テスト衝突」を参照。
  相手のアプリが前面を奪った瞬間に Compose が
  「No compose hierarchies found」で落ち、端末全体のクラッシュを
  UTP が実行の異常終了と解釈して 43 件で中断した。
  そのため「どれか 1 台でも動いていれば起動を省く」旧実装は使わない。
  **AVD 名で照合し、無ければ立て、その serial だけを相手にする。**

  ## AVD はプロジェクト専用のものを使う
  既定の `apkmanager_test` はこのプロジェクトのために作った AVD
  (`avdmanager create avd -n apkmanager_test -k "system-images;android-34;google_apis;x86_64" -d pixel_6`)。
  `sc_test_*` は複数のプロジェクトが掴みに来る **共有プール** で、
  衝突の原因はそこにあった。このホストには `cockpit_test_26` /
  `launchdrawer_test` / `opencode_dev` のようにプロジェクト名を冠した AVD が
  既にあり、専用機を持つのがこの環境の慣習になっている。
  名前に持ち主が書いてあれば、`adb devices` と `emu avd name` だけで
  誰の端末かが分かる。

  API 36 イメージが起動ハングした前歴があるため
  (global-llm-wiki「Androidエミュレータ検証環境の落とし穴」)、既定は API 34。
#>
param(
    [string]$Avd = "apkmanager_test",
    [int]$TimeoutSec = 300
)

$ErrorActionPreference = "Stop"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$emu = "$sdk\emulator\emulator.exe"
$adb = "$sdk\platform-tools\adb.exe"

function Get-EmulatorSerials {
    # `adb devices` の "emulator-5554<TAB>device" 行だけを拾う。
    # offline / unauthorized は使えないので除く。
    (& $adb devices) |
        Where-Object { $_ -match '^(emulator-\d+)\s+device\s*$' } |
        ForEach-Object { $Matches[1] }
}

function Get-AvdNameOf([string]$serial) {
    # `adb emu avd name` は「名前」に続けて "OK" を返す。1 行目が名前。
    try {
        $out = & $adb -s $serial emu avd name 2>$null
        if ($out) { return ("$($out[0])".Trim()) }
    } catch { }
    return ""
}

function Find-SerialForAvd([string]$name) {
    foreach ($s in Get-EmulatorSerials) {
        if ((Get-AvdNameOf $s) -eq $name) { return $s }
    }
    return ""
}

# 既に同じ AVD が立っていれば、それを使い回す。
$serial = Find-SerialForAvd $Avd
if (-not $serial) {
    # 他プロジェクトの端末には一切触れず、自分の AVD だけを追加で立てる。
    Start-Process $emu -ArgumentList "-avd", $Avd, "-gpu", "host", "-no-boot-anim" -WindowStyle Minimized
}

$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
    if (-not $serial) { $serial = Find-SerialForAvd $Avd }
    if ($serial) {
        $booted = ""
        try { $booted = & $adb -s $serial shell getprop sys.boot_completed 2>$null } catch { }
        if ("$booted".Trim() -eq "1") {
            # 呼び出し側が serial を掴めるよう、最後に serial だけを出す。
            Write-Output $serial
            exit 0
        }
    }
    Start-Sleep -Seconds 3
}

throw "AVD $Avd が $TimeoutSec 秒で boot しませんでした(別の AVD を試す)"
