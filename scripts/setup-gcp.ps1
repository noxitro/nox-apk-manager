<#
  .SYNOPSIS
  Google 側の初期設定(docs/SETUP.md のパート 1〜3)を gcloud で一度に行う。

  .DESCRIPTION
  Cloud Console を手で辿る代わりに、次を順に行う。途中まで済んでいれば、済んだ所は飛ばす。
    1. gcloud にログイン(ブラウザが開く。Drive の共有もするので Drive の権限も求める)
    2. プロジェクトを用意(名前が nox-apk-manager のものがあればそれ、無ければ作る)
    3. Google Drive API を有効にする
    4. サービスアカウント nox-drive-reader を作る
    5. 鍵(JSON)を ~/.secrets/nox-drive-sa.json に作る(既にあれば作らない)
    6. マイドライブ直下の builds/ をサービスアカウントに「閲覧者」で共有する
    7. check-drive-service-account.ps1 で読めることを確かめる

  6 が失敗したとき(Drive の権限がもらえない等)は、共有画面をブラウザで開き、
  アドレスをクリップボードに入れて止まる。貼り付けて「閲覧者」で共有すれば続きは要らない。

  要るもの: PowerShell 7 と Google Cloud CLI(gcloud)。
  gcloud が無ければ `winget install Google.CloudSDK` で入れる。

  .EXAMPLE
  pwsh scripts\setup-gcp.ps1
  .EXAMPLE
  pwsh scripts\setup-gcp.ps1 -ProjectId my-existing-project
#>
param(
    [string]$ProjectId = "",
    [string]$KeyFile = (Join-Path $HOME ".secrets\nox-drive-sa.json"),
    [string]$BuildsFolder = "builds",
    # 既存の鍵を捨てて作り直す(漏らした・無くしたとき)
    [switch]$NewKey
)

$ErrorActionPreference = "Stop"
$ProjectName = "nox-apk-manager"
$SaName = "nox-drive-reader"

function Step([string]$text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }
function Ok([string]$text) { Write-Host "   $text" -ForegroundColor Green }
function Note([string]$text) { Write-Host "   $text" }

# gcloud を呼んで、失敗なら止める。出力は文字列で返す。
# (関数名を gcloud に似せると PowerShell は大文字小文字を区別しないので自分を呼んでしまう)
function Invoke-Gcloud {
    $out = & $gcloud @args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud $($args -join ' ') が失敗しました:`n$($out -join "`n")"
    }
    ($out | Where-Object { $_ -is [string] }) -join "`n"
}

$gcloud = (Get-Command gcloud -ErrorAction SilentlyContinue).Source
if (-not $gcloud) {
    throw "gcloud が見つかりません。'winget install Google.CloudSDK' で入れてから、新しいターミナルでやり直してください。"
}

# --- 1. ログイン ---------------------------------------------------------------
Step "1/7 gcloud にログイン"
$account = (& $gcloud auth list --filter=status:ACTIVE --format="value(account)" 2>$null | Select-Object -First 1)
if (-not $account) {
    Note "ブラウザが開きます。Drive に使っている Google アカウントで許可してください。"
    & $gcloud auth login --enable-gdrive-access
    if ($LASTEXITCODE -ne 0) { throw "ログインできませんでした" }
    $account = (& $gcloud auth list --filter=status:ACTIVE --format="value(account)" | Select-Object -First 1)
}
Ok "ログイン中: $account"

# --- 2. プロジェクト -------------------------------------------------------------
Step "2/7 プロジェクト"
if (-not $ProjectId) {
    $ProjectId = (Invoke-Gcloud projects list --filter="name=$ProjectName" --format="value(projectId)" --limit=1).Trim()
}
if ($ProjectId) {
    Ok "既存のプロジェクトを使います: $ProjectId"
} else {
    # プロジェクト ID は世界で一意なので、名前の後ろに乱数を付ける
    $ProjectId = "$ProjectName-$(Get-Random -Minimum 100000 -Maximum 999999)"
    Note "作ります: $ProjectId"
    try {
        Invoke-Gcloud projects create $ProjectId --name=$ProjectName | Out-Null
    } catch {
        Write-Host $_ -ForegroundColor Red
        Note "初めて Google Cloud を使うアカウントは、利用規約の同意が要ります。"
        Note "https://console.cloud.google.com/ を一度開いて同意してから、もう一度実行してください。"
        exit 1
    }
    Ok "作りました: $ProjectId"
}

# --- 3. Drive API ---------------------------------------------------------------
Step "3/7 Google Drive API を有効にする"
Invoke-Gcloud services enable drive.googleapis.com --project=$ProjectId | Out-Null
Ok "有効です"

# --- 4. サービスアカウント -------------------------------------------------------
Step "4/7 サービスアカウント"
$saEmail = "$SaName@$ProjectId.iam.gserviceaccount.com"
$exists = & $gcloud iam service-accounts describe $saEmail --project=$ProjectId --format="value(email)" 2>$null
if ($LASTEXITCODE -eq 0 -and $exists) {
    Ok "既にあります: $saEmail"
} else {
    Invoke-Gcloud iam service-accounts create $SaName --project=$ProjectId --display-name="Nox APK Manager (Drive 読み取り)" | Out-Null
    Ok "作りました: $saEmail"
}

# --- 5. 鍵 -----------------------------------------------------------------------
Step "5/7 鍵(JSON)"
$haveKey = $false
if ((Test-Path $KeyFile) -and -not $NewKey) {
    $existing = Get-Content -Raw $KeyFile | ConvertFrom-Json
    if ($existing.client_email -eq $saEmail -and $existing.private_key) {
        $haveKey = $true
        Ok "既にあるものを使います: $KeyFile"
    } else {
        throw "$KeyFile は別のサービスアカウント($($existing.client_email))の鍵です。-KeyFile で別の場所を指定するか、-NewKey で作り直してください。"
    }
}
if (-not $haveKey) {
    New-Item -ItemType Directory -Force (Split-Path $KeyFile) | Out-Null
    # 作った直後のサービスアカウントは数秒見えないことがあるので、何度か試す
    for ($i = 1; ; $i++) {
        try {
            Invoke-Gcloud iam service-accounts keys create $KeyFile --iam-account=$saEmail --project=$ProjectId | Out-Null
            break
        } catch {
            if ($i -ge 5) { throw }
            Start-Sleep -Seconds (3 * $i)
        }
    }
    Ok "作りました: $KeyFile"
    Note "この JSON はパスワードと同じです。リポジトリ・Drive・チャットに置かないでください。"
}

# --- 6. builds/ を共有 -------------------------------------------------------------
Step "6/7 Drive の $BuildsFolder/ をサービスアカウントに共有"

function Invoke-Drive([string]$method, [string]$uri, $body = $null) {
    $token = (& $gcloud auth print-access-token 2>$null)
    $req = @{ Method = $method; Uri = $uri; Headers = @{ Authorization = "Bearer $token" } }
    if ($body) { $req.Body = ($body | ConvertTo-Json -Compress); $req.ContentType = "application/json" }
    Invoke-RestMethod @req
}

function Find-BuildsFolder {
    $q = [Uri]::EscapeDataString("'root' in parents and name = '$BuildsFolder' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
    (Invoke-Drive GET "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id,name)").files | Select-Object -First 1
}

function Share-Folder([string]$folderId) {
    Invoke-Drive POST "https://www.googleapis.com/drive/v3/files/$folderId/permissions?sendNotificationEmail=false" @{
        type = "user"; role = "reader"; emailAddress = $saEmail
    } | Out-Null
}

$folder = $null
$shared = $false
$notFound = $false
for ($attempt = 1; $attempt -le 2 -and -not $shared -and -not $notFound; $attempt++) {
    try {
        $folder = Find-BuildsFolder
        if (-not $folder) { $notFound = $true; continue }
        Share-Folder $folder.id
        $shared = $true
    } catch {
        # ログイン時に Drive の権限をもらっていない。1 回だけ取り直す。
        if ($attempt -eq 1) {
            Note "Drive の権限が足りません。ブラウザでもう一度許可してください。"
            & $gcloud auth login --enable-gdrive-access
        } else {
            $detail = try { ($_.ErrorDetails.Message | ConvertFrom-Json).error.message } catch { $null }
            Note "自動で共有できませんでした: $($detail ?? $_.Exception.Message)"
        }
    }
}

if ($shared) {
    Ok "共有しました(閲覧者): https://drive.google.com/drive/folders/$($folder.id)"
} elseif ($notFound) {
    Write-Host "   マイドライブ直下に '$BuildsFolder' フォルダがありません。作ってから、もう一度実行してください。" -ForegroundColor Yellow
    exit 1
} else {
    # 手で共有してもらう。アドレスはクリップボードに入れておく。
    try { Set-Clipboard -Value $saEmail } catch { }
    Write-Host ""
    Write-Host "   手で共有してください(アドレスはクリップボードに入っています):" -ForegroundColor Yellow
    Write-Host "     $saEmail"
    Write-Host "   ブラウザで '$BuildsFolder' を右クリック →「共有」→ 貼り付け → 閲覧者 → 送信(通知はオフでよい)"
    Start-Process "https://drive.google.com/drive/my-drive"
    Read-Host "   共有したら Enter"
    try { $folder = Find-BuildsFolder } catch { $folder = $null }
}

# --- 7. 確かめる -------------------------------------------------------------------
Step "7/7 サービスアカウントで読めるか確かめる"
if ($folder) {
    & (Join-Path $PSScriptRoot "check-drive-service-account.ps1") -KeyFile $KeyFile -FolderId $folder.id
} else {
    Note "フォルダ ID が分からないので飛ばします。確かめるなら:"
    Note "  pwsh scripts\check-drive-service-account.ps1 -KeyFile `"$KeyFile`" -FolderId <builds のフォルダ ID>"
}

Write-Host ""
Write-Host "Google 側は完了。次は端末に入れます(USB でつないで):" -ForegroundColor Green
Write-Host "  pwsh scripts\setup-device.ps1"
