# nox-apk-manager

自作 Android アプリ専用のプライベートなストア。
Google Drive の `builds/<project>/` を端末から読み、入っている版と比べて、更新のあるものを上段にまとめ、
1 タップ(または「全て更新」)で入れ替える。Kotlin + Jetpack Compose (Material 3)。

Play ストアにも F-Droid にも出さないアプリを、ストア相当の体験(更新バッジ・一括更新・説明文・アイコン)で扱う。
配布元は既に使っている Google Drive のフォルダで、サーバーは立てない。

**最初の 1 本は [Releases](https://github.com/noxitro/nox-apk-manager/releases/latest) から入れる。**
APK には鍵も配布先の情報も入っていないので、鍵を入れるまでは何も読めない。以後の更新はアプリ自身が Drive から行う。

## 配布の規約(PC 側)

```
%NOX_BUILDS_ROOT%\<project>\                     … Drive for Desktop の「マイドライブ\builds」。ドライブ文字はアカウントごとに違うので環境変数で持つ
  <project>-<versionName>-<debug|release>.apk   … 版ごとに残す(古い版も入れ直せる)
  meta.json                                      … package 名・版・variant・sha256・説明。publish-apk.ps1(CI では publish-apk ワークフロー)が書く
  icon.png                                       … 任意。未インストールの行に出す
```

ビルドしたら各プロジェクトから:

```powershell
E:\dev\github.com\noxitro\nox-apk-manager\scripts\publish-apk.ps1 -Project <project> -Apk app\build\outputs\apk\release\app-release.apk
```

- 版・variant・package 名は APK 自身から `aapt2` で読む(引数で嘘を書けない)。
- Gradle の出力先を Drive の同期フォルダに直接向けない(Drive Desktop の同期と競合する)。完成品だけコピーする。
- `meta.json` が無い古いフォルダは、ファイル名 `<name>-<version>-<variant>.apk` から版を推定して一覧に出す。
  package 名は分からないので端末と突き合わせられず「状態不明」。一度導入すると次回から比較できる。
- 全フォルダの `meta.json` を作り直す: `scripts\regen-meta.ps1`。

## 配布の規約(GitHub Actions)

各リポジトリの `main` への push で、テスト → release ビルド → `builds/<project>/` への配置まで行う共通ワークフロー
(`.github/workflows/publish-apk.yml`)を用意している。置くものは `publish-apk.ps1` と同じ。
呼び出す側は yml を 1 つ置き、`scripts\set-ci-secrets.ps1 -Repo <リポジトリ名>` で Secrets を登録するだけ。
手順・入力・トークンの扱いは [docs/CD.md](docs/CD.md)。

## このアプリ自身の配布(PC)

署名鍵(ほかの自作アプリと共通の debug 鍵)と Drive 全体を読み書きできるトークンを GitHub に置かないため、
このアプリ自身は **PC でビルドして配る**。`versionCode` を上げて `main` に入れたあと:

```powershell
scripts\build.ps1
.\gradlew.bat :app:assembleRelease
scripts\publish-apk.ps1 -Project nox-apk-manager -Apk app\build\outputs\apk\release\app-release.apk
Copy-Item app\build\outputs\apk\release\app-release.apk "$env:TEMP\nox-apk-manager-<版>-release.apk"
gh release create v<版> --target main --title "<版>" "$env:TEMP\nox-apk-manager-<版>-release.apk"
```

Drive に置いた版は、端末でこのアプリの行の「更新」から入れ替わる。Releases の版は最初の 1 本に使う。
`.github/workflows/cd.yml` は手で起動したときだけ動く(Secrets を登録した場合)。

## 端末側

- 起動時に Drive を読む(バックグラウンド巡回・通知はしない)。
- 既定で入れるのは **release**。設定で debug に切り替えられる。行の詳細からは任意の版・variant を入れられる。
- debug と release は署名が違うと相互に上書きできない。失敗時は理由と次の一手を文で出す
  (署名不一致 → アンインストールが必要 / 端末の方が新しい → ダウングレード不可)。
- インストールは PackageInstaller セッション。OS の確認ダイアログは毎回出る。「全て更新」は 1 件ずつ順に進む。
- Drive は読み取り専用(`drive.readonly`)。整理・書き込みは PC 側(と CI)の責務。

初回の設定はスマホ単体で完結する(アプリの画面の手順に沿ってブラウザで鍵を作り、ファイルで選ぶ)。
手順はアプリの「接続方法」画面(ホーム右上の「?」/ 設定)にもまとめてある。
PC からなら `pwsh scripts\setup-gcp.ps1`(Google 側)と `pwsh scripts\setup-device.ps1`(端末側)の 2 つ。
中身と手作業の手順は [docs/SETUP.md](docs/SETUP.md)。
Meta Quest 3 への移植の検証手順は [docs/QUEST.md](docs/QUEST.md)。

## 開発

```powershell
scripts\build.ps1                 # testDebugUnitTest + assembleDebug(JBR を JAVA_HOME にする)
scripts\emulator.ps1              # 専用 AVD apkmanager_test を起動して serial を返す
scripts\install-run.ps1           # 入れて起動してスクショと自プロセスの logcat を取る
```

認証無しで一覧とインストールを通すには、debug ビルドで実物の配布フォルダを端末に置く
([docs/SETUP.md](docs/SETUP.md) の 4)。

```bash
adb -s <serial> push "G:\マイドライブ\builds" /sdcard/Android/data/com.noxitro.apkmanager/files/
```

エミュレータは同一ホストで他プロジェクトと共有しない(`apkmanager_*` 専用)。

### インストールの E2E テスト(計装テスト)

本物の PackageInstaller にダミーのアプリ(`fixture/`、パッケージ `com.noxitro.apkmanager.fixture`)を
入れさせ、OS の確認画面を UiAutomator で押して結果を確かめる
(`app/src/androidTest/java/com/noxitro/apkmanager/install/InstallE2ETest.kt`)。
見ているのは、新規 / 自分が入れたアプリの無確認の更新 / 確認画面での取り消し / 署名違い / 版の巻き戻し。

端末はユーザーの実機に寄せた 2 台の AVD で走らせる。2 台とも立ち上げてから、`ANDROID_SERIAL` を付けずに
`gradlew connectedDebugAndroidTest` を実行すると両方で走る。

| AVD | 寄せた実機 | 画面 | Android |
|---|---|---|---|
| `apkmanager_galaxy` | Galaxy(機種は未定のため S24 の画面) | 1080×2340・420dpi | 15(API 35) |
| `apkmanager_pad8` | Xiaomi Pad 8 | 3200×2136・400dpi | 16(API 36) |

- エミュレータの OS は素の Android。One UI / HyperOS が独自に挟む画面や保護機能(オートブロッカーなど)は再現しない。
- fixture を署名する 2 つの鍵は、ビルドのたびに `fixture/build/fixture-keys/` に作る使い捨て(リポジトリには置かない)。配布用の鍵とは無関係。
- CI では PR と main への push のたびに 2 台で走る。このリポジトリは公開なので、Actions の実行時間は無料で、無料枠も減らない。
- テストは、OS の「30 秒以内に同じアプリを続けて無確認で更新させない」制限(SilentUpdatePolicy)を、
  テストの間だけ `pm set-silent-updates-policy` で外す。外さないと v1 の直後の v2 で必ず確認画面が出る。

**APK を渡す前に、手元で計装テストと `install-run.ps1` まで通すこと。**
画面の最終確認は Galaxy 実機で行う。

## 構成

```
app/src/main/java/com/noxitro/apkmanager/
  model/Catalog.kt          … meta.json の型、ファイル名パーサ、版比較
  drive/DriveSource.kt      … 「builds/ を読む」口。DriveApi(REST v3 / OkHttp) と LocalDriveSource(検証用)
  auth/DriveAuth.kt         … Play 開発者サービスの AuthorizationClient でアクセストークンを取る
  data/CatalogRepository.kt … Drive の一覧 × PackageManager → AppEntry(状態付き)
  data/Prefs.kt             … 既定 variant、project→package 名の記憶
  install/ApkInstaller.kt   … PackageInstaller セッションと結果の受信
  ui/HomeViewModel.kt       … 同期・インストール・一括更新の進行
  ui/HomeScreen.kt          … ヒーロー帯 / チップ / 「更新あり N 件」の板 / 導入済み・未導入
  ui/AppRow.kt, AppDetailSheet.kt, SettingsScreen.kt
scripts/                    … setup-gcp.ps1 / setup-device.ps1(初期設定)、publish-apk.ps1 / regen-meta.ps1 / apk-meta.ps1(PC 側の配布)、set-ci-secrets.ps1(CI の Secrets)
.github/actions/publish-apk … CI から builds/ に置く Action。.github/workflows/publish-apk.yml から使う
```

## 法的な位置づけ

ローカル環境での個人利用のみ。ストアや公開リポジトリには出さない。

## ライセンス

[MIT License](LICENSE)
