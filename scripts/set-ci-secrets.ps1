<#
  .SYNOPSIS
  GitHub Actions から Google Drive の builds/ に APK を置くための Secrets を、リポジトリに登録する。

  .DESCRIPTION
  .github/workflows/publish-apk.yml を使うリポジトリには、次の 2 つの Secrets が要る(docs/CD.md)。
  - DEBUG_KEYSTORE_BASE64: この PC の debug 鍵(~/.android/debug.keystore)。ほかの自作アプリと同じ鍵で署名するため
  - RCLONE_DRIVE_TOKEN: あなたの Google アカウントとして Drive に書き込むトークン。rclone authorize "drive" で作る(ブラウザで許可する)

  値は画面にもファイルにも出さず、gh secret set に標準入力で渡す。
  トークンは 1 回の実行で 1 つ作り、指定したリポジトリすべてに同じものを入れる。
  トークンが失効したとき(6 か月使わなかったときなど)は、配布しているリポジトリをまとめて指定して実行し直す。

  必要なもの:
  - GitHub CLI: winget install GitHub.cli のあと gh auth login
  - rclone: winget install Rclone.Rclone

  .EXAMPLE
  pwsh scripts\set-ci-secrets.ps1 -Repo modukit
  pwsh scripts\set-ci-secrets.ps1 -Repo modukit, photo-viewer    # トークンを作り直して、まとめて入れ直す
  pwsh scripts\set-ci-secrets.ps1 -Repo photo-viewer -SkipToken  # 鍵だけ入れる
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    # リポジトリ名(複数可)。owner/ を省くと -Owner のリポジトリ
    [Parameter(Mandatory, Position = 0)][string[]]$Repo,
    [string]$Owner = "noxitro",
    [string]$Keystore = (Join-Path $HOME ".android" "debug.keystore"),
    [switch]$SkipKeystore,
    [switch]$SkipToken,
    # pwsh scripts\... で起動すると "-Repo a, b" の b が別の引数として届くので、ここで受ける
    [Parameter(ValueFromRemainingArguments, DontShow)][string[]]$MoreRepo = @()
)

$ErrorActionPreference = "Stop"
if ($SkipKeystore -and $SkipToken) { throw "-SkipKeystore と -SkipToken を両方つけると、登録するものが無い" }

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "GitHub CLI が無い。winget install GitHub.cli のあと gh auth login を実行する"
}
gh auth status *> $null
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI にログインしていない。gh auth login を実行する" }

# "a," と "b" に分かれて届くことも、"a,b" のまま届くこともあるので、カンマでも区切る
$names = (@($Repo) + @($MoreRepo)) -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ }
if (-not $names) { throw "-Repo にリポジトリ名を指定する" }
$unknown = $names | Where-Object { $_ -like "-*" }
if ($unknown) { throw "知らないオプション: $($unknown -join ' ')" }
# ブラウザで許可したあとに名前の間違いで止まらないよう、先に全部のリポジトリを確かめる
$targets = foreach ($r in $names) { if ($r -like "*/*") { $r } else { "$Owner/$r" } }
foreach ($target in $targets) {
    gh repo view $target --json name *> $null
    if ($LASTEXITCODE -ne 0) { throw "リポジトリが見つからない(名前と、gh でログインしたアカウントを確かめる): $target" }
}

$secrets = [ordered]@{}
if (-not $SkipKeystore) {
    if (-not (Test-Path $Keystore)) { throw "debug 鍵が無い(Android Studio で一度ビルドすると作られる): $Keystore" }
    $secrets["DEBUG_KEYSTORE_BASE64"] = [Convert]::ToBase64String([IO.File]::ReadAllBytes($Keystore))
}
if (-not $SkipToken) {
    if (-not (Get-Command rclone -ErrorAction SilentlyContinue)) {
        throw "rclone が無い。winget install Rclone.Rclone のあと、ターミナルを開き直して実行する"
    }
    Write-Host "ブラウザが開くので、builds/ のある Google アカウントで rclone を許可してください"
    # トークンは標準出力に出るので、画面に出さずに受け取る(進み具合は標準エラーに出る)
    $output = & rclone authorize drive
    if ($LASTEXITCODE -ne 0) { throw "rclone authorize が失敗した(exit $LASTEXITCODE)" }
    if (($output -join "`n") -notmatch '(?s)--->\s*(.+?)\s*<---End paste') {
        throw "rclone authorize の出力からトークンを読めない"
    }
    $token = $Matches[1]
    $refreshToken = try { ($token | ConvertFrom-Json).refresh_token } catch { $null }
    if (-not $refreshToken) { throw "rclone authorize の出力がトークンの形をしていない" }
    $secrets["RCLONE_DRIVE_TOKEN"] = $token
}

foreach ($target in $targets) {
    foreach ($name in $secrets.Keys) {
        $secrets[$name] | gh secret set $name --repo $target
        if ($LASTEXITCODE -ne 0) { throw "$target に $name を登録できなかった" }
    }
    Write-Output "registered: $target ($($secrets.Keys -join ', '))"
}
