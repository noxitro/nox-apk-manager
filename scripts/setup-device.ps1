<#
  .SYNOPSIS
  端末側の初期設定(docs/SETUP.md のパート 4)を一度に行う。USB でつないで実行するだけ。

  .DESCRIPTION
    1. 端末を決める(1 台だけつながっていればそれ。複数なら -Serial)
    2. 鍵(JSON)を PC 側で確かめる(OAuth クライアントの JSON などの取り違えをここで止める)
    3. アプリが入っていなければ入れる(-Apk、無ければ Drive の builds/nox-apk-manager/ の最新 release)
    4. アプリを一度開く(files/ はアプリの初回起動で作られる。先に push すると失敗する)
    5. 鍵を push し、アプリを開き直して取り込ませる
    6. 取り込まれたか確かめる(アプリは取り込んだ鍵のファイルを消すので、消えれば成功)
    7. 「この提供元のアプリを許可」の画面を開く(オンにするのだけは端末で)

  Quest を他の作業と共有しているときは、ヘッドセットが空いているのを確かめてから実行すること
  (docs/QUEST.md。アプリの起動で前面が切り替わる)。

  .EXAMPLE
  pwsh scripts\setup-device.ps1
  .EXAMPLE
  pwsh scripts\setup-device.ps1 -Serial 2G0YC1ZF... -KeyFile D:\keys\nox-drive-sa.json
#>
param(
    [string]$Serial = "",
    [string]$KeyFile = (Join-Path $HOME ".secrets\nox-drive-sa.json"),
    [string]$Apk = "",
    [string]$BuildsDir = "G:\マイドライブ\builds"
)

$ErrorActionPreference = "Stop"
$pkg = "com.noxitro.apkmanager"
$remoteDir = "/sdcard/Android/data/$pkg/files"
$remoteKey = "$remoteDir/nox-drive-sa.json"

function Step([string]$text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }
function Ok([string]$text) { Write-Host "   $text" -ForegroundColor Green }
function Note([string]$text) { Write-Host "   $text" }

# adb は SDK のものを優先し、無ければ PATH から
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = "$sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if (-not $adb) { throw "adb が見つかりません。'winget install Google.PlatformTools' で入れてください。" }
}

function Adb { & $adb -s $Serial @args }

# --- 1. 端末 ---------------------------------------------------------------------
Step "1/7 端末"
$devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' } | ForEach-Object {
    $cols = $_ -split '\s+'
    [pscustomobject]@{ Serial = $cols[0]; State = $cols[1] }
})
if (-not $Serial) {
    $ready = @($devices | Where-Object State -eq 'device')
    if ($ready.Count -eq 1) {
        $Serial = $ready[0].Serial
    } elseif ($ready.Count -eq 0) {
        if ($devices | Where-Object State -eq 'unauthorized') {
            throw "端末が USB デバッグを許可していません。端末の画面(Quest ならヘッドセット内)で「許可」を押してから、もう一度実行してください。"
        }
        throw "端末が見つかりません。USB でつなぎ、開発者オプションの USB デバッグを有効にしてください。"
    } else {
        $ready | ForEach-Object { Note "$($_.Serial)" }
        throw "端末が複数つながっています。-Serial で選んでください。"
    }
}
$model = ("$(Adb shell getprop ro.product.model)").Trim()
Ok "$model ($Serial)"

# --- 2. 鍵を確かめる --------------------------------------------------------------------
Step "2/7 鍵を確かめる"
if (-not (Test-Path $KeyFile)) {
    throw "鍵がありません: $KeyFile`n先に 'pwsh scripts\setup-gcp.ps1' を実行するか、-KeyFile で場所を指定してください。"
}
try { $key = Get-Content -Raw $KeyFile | ConvertFrom-Json } catch { throw "JSON として読めません: $KeyFile" }
if ($key.installed -or $key.web) {
    throw "これは OAuth クライアントの JSON です(旧方式)。サービスアカウントの鍵を指定してください: $KeyFile"
}
if (-not $key.client_email -or -not $key.private_key) {
    throw "サービスアカウントの鍵ではありません(client_email / private_key がありません): $KeyFile"
}
Ok $key.client_email

# --- 3. アプリを入れる --------------------------------------------------------------------
Step "3/7 アプリ"
$installed = [bool](Adb shell pm list packages $pkg | Where-Object { $_.Trim() -eq "package:$pkg" })
if (-not $Apk -and -not $installed) {
    # 配布フォルダの最新 release を探す(名前は <project>-<version>-<variant>.apk)
    $dir = Join-Path $BuildsDir "nox-apk-manager"
    $Apk = Get-ChildItem $dir -Filter "nox-apk-manager-*-release.apk" -ErrorAction SilentlyContinue |
        Sort-Object {
            $v = ($_.BaseName -replace '^nox-apk-manager-', '' -replace '-release$', '') -replace '[^0-9.]', ''
            $parsed = $null
            if ([version]::TryParse($v, [ref]$parsed)) { $parsed } else { [version]"0.0" }
        } |
        Select-Object -Last 1 -ExpandProperty FullName
    if (-not $Apk) { throw "入れる APK が見つかりません。-Apk で指定してください(探した場所: $dir)" }
}
if ($Apk) {
    Note "入れます: $Apk"
    $out = Adb install -r $Apk 2>&1
    if ($LASTEXITCODE -ne 0) {
        if ("$out" -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
            throw "署名の違う版が入っています。端末の Nox APK Manager をアンインストールしてから、もう一度実行してください。"
        }
        throw "インストールに失敗しました:`n$out"
    }
    Ok "入れました"
} else {
    Ok "入っています(入れ直すなら -Apk)"
}

# --- 4. 一度開く(files/ を作らせる)---------------------------------------------------------
Step "4/7 アプリを一度開く"
Adb shell am start -n "$pkg/.MainActivity" | Out-Null
$made = $false
for ($i = 0; $i -lt 20; $i++) {
    Adb shell ls -d $remoteDir 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $made = $true; break }
    Start-Sleep -Milliseconds 500
}
if (-not $made) { throw "$remoteDir ができません。端末でアプリが開いているか確かめてください。" }
Ok "開きました"

# --- 5. 鍵を送る -------------------------------------------------------------------------
Step "5/7 鍵を送って取り込ませる"
$out = Adb push $KeyFile $remoteKey 2>&1
if ($LASTEXITCODE -ne 0) { throw "push に失敗しました:`n$out" }
# 開き直すと起動時に取り込む(取り込んだらファイルは消える)
Adb shell am force-stop $pkg
Adb shell am start -n "$pkg/.MainActivity" | Out-Null

# --- 6. 取り込まれたか ------------------------------------------------------------------------
Step "6/7 取り込まれたか確かめる"
$imported = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 500
    Adb shell ls $remoteKey 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { $imported = $true; break }
}
if ($imported) {
    Ok "取り込みました。アプリの一覧に Drive の builds/ が出ます"
} else {
    Write-Host "   鍵が取り込まれていません。端末の画面に理由が出ています。" -ForegroundColor Yellow
    Note "古い版のアプリは理由を出しません。-Apk で新しい版を入れてから、もう一度実行してください。"
    # 秘密鍵を外部ストレージに残さない
    Adb shell rm -f $remoteKey | Out-Null
    exit 1
}

# --- 7. インストールの許可 -----------------------------------------------------------------------
Step "7/7 インストールの許可"
Adb shell am start -a android.settings.MANAGE_UNKNOWN_APP_SOURCES -d "package:$pkg" 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
    Note "端末に設定画面を開きました。「この提供元のアプリを許可」をオンにしてください。"
} else {
    Note "設定画面を開けませんでした。アプリの「設定」タブ →「インストールの許可」から開いてください。"
}
Note "(これが無いと、更新を押しても OS がインストールを拒みます)"

Write-Host ""
Write-Host "端末の設定は完了。別の端末にも入れるなら、つなぎ替えてもう一度実行してください。" -ForegroundColor Green
