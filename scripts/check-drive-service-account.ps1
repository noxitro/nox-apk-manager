<#
  .SYNOPSIS
  サービスアカウントで Drive の builds/ フォルダを読めるかを確かめる(実機不要)。

  .DESCRIPTION
  Quest 3 移植の認証方式を決めるための裏取り(docs/QUEST.md の付録)。
  ユーザー認証(OAuth 同意画面)を一切使わず、サービスアカウントの秘密鍵で
  JWT を署名してアクセストークンを取り、共有された builds/ が一覧できるかを見る。

  確かめるのは 3 つ。
    1. JWT bearer でアクセストークンが取れるか
    2. 共有されたフォルダの直下(= project フォルダ)が一覧できるか
    3. その 1 つ下(= APK と meta.json)まで降りられるか
  3 まで通れば、アプリ側は現行の DriveSource をそのまま使える。

  依存は無い(PowerShell 7 の .NET だけ)。秘密鍵はリポジトリに置かないこと。

  .EXAMPLE
  .\check-drive-service-account.ps1 -KeyFile ~\.secrets\nox-drive-sa.json -FolderId 1AbC...
#>
param(
    [Parameter(Mandatory)][string]$KeyFile,
    [Parameter(Mandatory)][string]$FolderId
)

$ErrorActionPreference = "Stop"

function ConvertTo-Base64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function ConvertTo-Base64UrlText([string]$text) {
    ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($text))
}

if (-not (Test-Path $KeyFile)) { throw "鍵ファイルが無い: $KeyFile" }
$key = Get-Content -Raw $KeyFile | ConvertFrom-Json
if (-not $key.client_email -or -not $key.private_key) {
    throw "サービスアカウントの JSON ではない(client_email / private_key が無い): $KeyFile"
}

Write-Host "サービスアカウント: $($key.client_email)" -ForegroundColor Cyan
Write-Host "対象フォルダ ID   : $FolderId"
Write-Host ""

# --- 1. JWT を署名してアクセストークンを取る --------------------------------
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$header = @{ alg = "RS256"; typ = "JWT" } | ConvertTo-Json -Compress
$claims = [ordered]@{
    iss   = $key.client_email
    scope = "https://www.googleapis.com/auth/drive.readonly"
    aud   = "https://oauth2.googleapis.com/token"
    exp   = $now + 3600
    iat   = $now
} | ConvertTo-Json -Compress

$signingInput = "$(ConvertTo-Base64UrlText $header).$(ConvertTo-Base64UrlText $claims)"

$pem = $key.private_key -replace '-----(BEGIN|END) PRIVATE KEY-----', '' -replace '\s', ''
$rsa = [Security.Cryptography.RSA]::Create()
$rsa.ImportPkcs8PrivateKey([Convert]::FromBase64String($pem), [ref]0)
$sig = $rsa.SignData(
    [Text.Encoding]::UTF8.GetBytes($signingInput),
    [Security.Cryptography.HashAlgorithmName]::SHA256,
    [Security.Cryptography.RSASignaturePadding]::Pkcs1)
$jwt = "$signingInput.$(ConvertTo-Base64Url $sig)"

try {
    $token = Invoke-RestMethod -Method Post -Uri "https://oauth2.googleapis.com/token" -Body @{
        grant_type = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        assertion  = $jwt
    }
} catch {
    Write-Host "[1] トークン取得: 失敗" -ForegroundColor Red
    Write-Host "    $($_.ErrorDetails.Message ?? $_.Exception.Message)"
    Write-Host "    account not found = サービスアカウントが消えているか、JSON が別プロジェクトのもの。"
    Write-Host "    invalid_grant のその他 / unauthorized_client = Drive API が無効、鍵の失効、PC の時刻ずれ。"
    exit 1
}
Write-Host "[1] トークン取得: OK(有効期限 $($token.expires_in) 秒)" -ForegroundColor Green

$headers = @{ Authorization = "Bearer $($token.access_token)" }

function Get-Children([string]$id) {
    $q = [Uri]::EscapeDataString("'$id' in parents and trashed = false")
    $f = [Uri]::EscapeDataString("files(id,name,mimeType,size)")
    (Invoke-RestMethod -Headers $headers `
        -Uri "https://www.googleapis.com/drive/v3/files?q=$q&fields=$f&pageSize=100").files
}

# --- 2. builds/ 直下(project フォルダ)-------------------------------------
try {
    $projects = Get-Children $FolderId
} catch {
    Write-Host "[2] builds/ の一覧: 失敗" -ForegroundColor Red
    Write-Host "    $($_.ErrorDetails.Message ?? $_.Exception.Message)"
    Write-Host "    403 なら共有できていない。Drive で builds/ を $($key.client_email) に"
    Write-Host "    「閲覧者」として共有し直すこと。"
    exit 1
}
if (-not $projects) {
    Write-Host "[2] builds/ の一覧: 0 件" -ForegroundColor Yellow
    Write-Host "    フォルダ ID が違うか、共有したのが別のフォルダの可能性がある。"
    exit 1
}
Write-Host "[2] builds/ の一覧: OK($($projects.Count) 件)" -ForegroundColor Green
$projects | ForEach-Object { Write-Host "      $($_.name)" }

# --- 3. project/ の中身(APK と meta.json)----------------------------------
$folders = @($projects | Where-Object { $_.mimeType -eq 'application/vnd.google-apps.folder' })
if (-not $folders) {
    Write-Host "[3] 下位フォルダが無いので確かめられない" -ForegroundColor Yellow
    exit 1
}
$sample = $folders[0]
$files = Get-Children $sample.id
Write-Host "[3] $($sample.name)/ の一覧: OK($($files.Count) 件)" -ForegroundColor Green
$files | ForEach-Object {
    $size = if ($_.size) { "{0:N1} MB" -f ($_.size / 1MB) } else { "" }
    Write-Host "      $($_.name) $size"
}

$hasMeta = $files | Where-Object { $_.name -eq 'meta.json' }
$hasApk  = $files | Where-Object { $_.name -like '*.apk' }
Write-Host ""
if ($hasMeta -and $hasApk) {
    Write-Host "結論: サービスアカウント方式で行ける。docs/QUEST.md の付録に ◎ を書き込むこと。" -ForegroundColor Green
} else {
    Write-Host "結論: 一覧は通ったが、meta.json か APK が見当たらない。共有したフォルダを確認すること。" -ForegroundColor Yellow
}
