# GitHub Actions から配布する(CD)

各リポジトリの `main` に push すると、テスト → release ビルド → Drive の `builds/<project>/` への配置まで行う共通ワークフロー。
置くものは PC の `publish-apk.ps1` と同じ 3 つ(APK・`meta.json`・`icon.png`)で、端末では「全て更新」を押すだけになる。
このリポジトリは公開なので、非公開のリポジトリからも呼べる。

| ファイル | 役割 |
| --- | --- |
| [`.github/workflows/publish-apk.yml`](../.github/workflows/publish-apk.yml) | 呼び出される本体。テストと release ビルド(PC と同じ debug 鍵で署名)のジョブと、Drive に置くジョブ |
| [`.github/actions/publish-apk/`](../.github/actions/publish-apk/) | ビルド済みの APK を Drive に置くところだけの Action と、`meta.json` を更新する `publish_drive.py` |
| [`scripts/set-ci-secrets.ps1`](../scripts/set-ci-secrets.ps1) | 呼び出す側のリポジトリに Secrets を登録する(PC で実行) |
| [`.github/workflows/publish-apk-selftest.yml`](../.github/workflows/publish-apk-selftest.yml) | Action の自己テスト。Drive の代わりにランナー上のフォルダに置いて確かめる |

使っている例: modukit の [`wake-update-cd.yml`](https://github.com/noxitro/modukit/blob/main/.github/workflows/wake-update-cd.yml)。

## 新しいリポジトリで使う

### 1. アプリの準備

PC から配布するときと同じ。

- release も debug 鍵で署名する(端末に入っている版と同じ鍵でないと上書きできない)

  ```kotlin
  android {
      buildTypes {
          release {
              signingConfig = signingConfigs.getByName("debug")
          }
      }
  }
  ```

- 配布のたびに `versionCode` を上げる(Drive にある版より大きいときだけ置く)
- 一覧に出る名前は APK の既定のアプリ名(`values/strings.xml` の `app_name`)

### 2. Secrets を登録する(PC で 1 回)

このリポジトリのフォルダで実行する。ブラウザが開くので、`builds/` のある Google アカウントで rclone を許可する。

```powershell
winget install GitHub.cli     # 初回だけ。そのあと gh auth login
winget install Rclone.Rclone  # 初回だけ
pwsh scripts\set-ci-secrets.ps1 -Repo <リポジトリ名>
```

次の 2 つが入る。値は画面にもファイルにも出ない。

| Secret | 中身 |
| --- | --- |
| `DEBUG_KEYSTORE_BASE64` | この PC の `~/.android/debug.keystore` を Base64 にしたもの |
| `RCLONE_DRIVE_TOKEN` | 自分の Google アカウントとして Drive に書き込むトークン |

`-Repo modukit, <リポジトリ名>` のように並べると、1 つのトークンをまとめて入れる。トークンを入れ直すときも同じ。

<details>
<summary>スクリプトを使わずに登録する</summary>

リポジトリの Settings → Secrets and variables → Actions → New repository secret で登録する。

- `DEBUG_KEYSTORE_BASE64`: PowerShell で次を実行するとクリップボードに入る

  ```powershell
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard
  ```

- `RCLONE_DRIVE_TOKEN`: `rclone authorize "drive"` を実行してブラウザで許可する。ターミナルの `Paste the following into your remote machine --->` と `<---End paste` の間に出る `{"access_token":...}` の 1 行を貼る

</details>

### 3. ワークフローを置く

`.github/workflows/cd.yml` を作る。`project` は `builds/` 直下のフォルダ名。

```yaml
name: CD

on:
  push:
    branches: [main]
  workflow_dispatch:
    inputs:
      force:
        description: 同じ versionCode の版が Drive にあっても置き直す
        type: boolean
        default: false

permissions:
  contents: read

jobs:
  publish:
    uses: noxitro/nox-apk-manager/.github/workflows/publish-apk.yml@main
    with:
      project: <project>
      gradle-tasks: ":app:testDebugUnitTest :app:assembleRelease"
      apk: app/build/outputs/apk/release/app-release.apk
      force: ${{ inputs.force == true }}
    secrets:
      DEBUG_KEYSTORE_BASE64: ${{ secrets.DEBUG_KEYSTORE_BASE64 }}
      RCLONE_DRIVE_TOKEN: ${{ secrets.RCLONE_DRIVE_TOKEN }}
```

`versionCode` を上げて `main` に push すると置かれる。同じ版を置き直すときは、Actions の「Run workflow」で「置き直す」にチェックする。

### 入力

| 入力 | 既定 | 内容 |
| --- | --- | --- |
| `project` | (必須) | `builds/` 直下のフォルダ名。APK のファイル名 `<project>-<versionName>-<variant>.apk` にも使う |
| `gradle-tasks` | (必須) | テストとビルドの Gradle タスク(スペース区切り) |
| `apk` | (必須) | ビルドした APK のパス(リポジトリのルートから) |
| `variant` | `release` | `release` か `debug` |
| `description-file` | なし | 一覧に出す説明文(UTF-8 のテキスト)。省くと Drive にある説明を残す |
| `icon` | なし | 一覧に出すアイコン(512px くらいの PNG。角丸に切り抜いて出す) |
| `sdk-packages` | なし | ビルドの前に入れる Android SDK のパッケージ(例 `platforms;android-37.2`) |
| `java-version` | `21` | ビルドに使う JDK |
| `signing-cert-sha256` | なし | 端末の版の署名の SHA-256。Secrets の鍵がこれと違えば、置く前に止める |
| `drive-folder` | `builds` | マイドライブから見た `builds/` の場所 |
| `force` | `false` | 同じ versionCode の版が Drive にあっても置き直す |

`signing-cert-sha256` は、リポジトリの Variables に `SIGNING_CERT_SHA256` として登録して `${{ vars.SIGNING_CERT_SHA256 }}` を渡すと楽。
値は `keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android` の SHA256。

### 独自の鍵で署名するアプリ

book-log のように release 専用の鍵で署名するアプリは、ビルドと署名を自分のジョブで行い、APK を別のジョブに渡して Action だけを呼ぶ。
トークンをビルドと同じジョブに入れないため(下の「トークン」)。ランナーは `ubuntu-latest`(Android SDK・Java・Python 3 入り)。

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      # ...ビルドと署名...
      - uses: actions/upload-artifact@v7
        with:
          name: apk
          path: app/build/outputs/apk/release/app-release.apk
          retention-days: 1

  publish:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/download-artifact@v8
        with:
          name: apk
      - uses: noxitro/nox-apk-manager/.github/actions/publish-apk@main
        with:
          project: book-log
          apk: app-release.apk
          drive-token: ${{ secrets.RCLONE_DRIVE_TOKEN }}
```

## 仕組みと注意

- 置く前に、APK の署名が Secrets の鍵と一致するか、Drive の `meta.json` と同じ package か、versionCode が上がっているかを確かめる
- PC の `publish-apk.ps1` はフォルダの APK を全部読み直して `meta.json` を作るが、CI では古い APK をダウンロードしないよう、Drive の `meta.json` に新しい版を足して書き直す
- 過去の版の APK は消さない(manager から古い版も入れ直せる)
- Actions の実行時間は、公開リポジトリなら無料。非公開リポジトリでは無料枠(月 2,000 分、アカウント全体で共有)から引かれる

### トークン

manager が Drive を読むサービスアカウントは、マイドライブにファイルを作れない(容量を持たないので、アップロードが 403 になる)。
そのため、書き込みは自分の Google アカウントとして rclone で行う。

- rclone の既定のアプリを使うので、テスト中の自作 OAuth アプリのようにトークンが 7 日で切れることはない
- 6 か月使わないと失効する。同じトークンを使い回していれば、どれかのリポジトリで 6 か月以内に 1 回配布するだけで保たれる。失効したら `set-ci-secrets.ps1` に配布しているリポジトリを並べて実行し直す
- 既にある `builds/` に書き込むため、トークンは Drive 全体を読み書きできる。Secrets 以外に置かない
- トークンを使うのは、ビルドとは別のジョブ(リポジトリのコードを動かさず、ビルド済みの APK を Drive に置くだけのジョブ)。
  同じジョブだと、ビルド中に動いたコード(Gradle のプラグインなど)が `$GITHUB_ENV` などを書き換えて、あとのステップからトークンを盗めるため
- 呼び出す側は、上の例のように `main` への push と手動実行だけで動かす(プルリクエストでは動かさない)
- 漏れたら、Google アカウントの「セキュリティ」→「サードパーティ製のアプリとサービス」から rclone のアクセスを削除すると無効になる。そのあと `set-ci-secrets.ps1` で入れ直す
