# nox-apk-manager

自作 Android アプリ専用のプライベートなストア。
Google Drive の `builds/<project>/` を端末から読み、入っている版と比べて、更新のあるものを上段にまとめ、
1 タップ(または「全て更新」)で入れ替える。Kotlin + Jetpack Compose (Material 3)。

Play ストアにも F-Droid にも出さないアプリを、ストア相当の体験(更新バッジ・一括更新・説明文・アイコン)で扱う。
配布元は既に使っている Google Drive のフォルダで、サーバーは立てない。

## 配布の規約(PC 側)

```
G:\マイドライブ\builds\<project>\
  <project>-<versionName>-<debug|release>.apk   … 版ごとに残す(古い版も入れ直せる)
  meta.json                                      … package 名・版・variant・sha256・説明。publish-apk.ps1(CI では publish-apk ワークフロー)が書く
  icon.png                                       … 任意。未インストールの行に出す
```

ビルドしたら各プロジェクトから:

```powershell
E:\dev\github.com\noxitro\nox-apk-manager\scripts\publish-apk.ps1 -Project <project> -Apk app\build\outputs\apk\release\app-release.apk
```

- 版・variant・package 名は APK 自身から `aapt2` で読む(引数で嘘を書けない)。
- Gradle の出力先を `G:` に直接向けない(Drive Desktop の同期と競合する)。完成品だけコピーする。
- `meta.json` が無い古いフォルダは、ファイル名 `<name>-<version>-<variant>.apk` から版を推定して一覧に出す。
  package 名は分からないので端末と突き合わせられず「状態不明」。一度導入すると次回から比較できる。
- 全フォルダの `meta.json` を作り直す: `scripts\regen-meta.ps1`。

## 配布の規約(GitHub Actions)

各リポジトリの `main` への push で、テスト → release ビルド → `builds/<project>/` への配置まで行う共通ワークフロー
(`.github/workflows/publish-apk.yml`)を用意している。置くものは `publish-apk.ps1` と同じ。
呼び出す側は yml を 1 つ置き、`scripts\set-ci-secrets.ps1 -Repo <リポジトリ名>` で Secrets を登録するだけ。
手順・入力・トークンの扱いは [docs/CD.md](docs/CD.md)。
このアプリ自身も同じ仕組みで配布する(`.github/workflows/cd.yml`)。`versionCode` を上げて `main` に push すると置かれ、端末ではこのアプリの行の「更新」から入れ替わる。

## 端末側

- 起動時に Drive を読む(バックグラウンド巡回・通知はしない)。
- 既定で入れるのは **release**。設定で debug に切り替えられる。行の詳細からは任意の版・variant を入れられる。
- debug と release は署名が違うと相互に上書きできない。失敗時は理由と次の一手を文で出す
  (署名不一致 → アンインストールが必要 / 端末の方が新しい → ダウングレード不可)。
- インストールは PackageInstaller セッション。OS の確認ダイアログは毎回出る。「全て更新」は 1 件ずつ順に進む。
- Drive は読み取り専用(`drive.readonly`)。整理・書き込みは PC 側(と CI)の責務。

初回のサービスアカウント作成・`builds/` の共有・端末への鍵の投入は [docs/SETUP.md](docs/SETUP.md)。
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

エミュレータは同一ホストで他プロジェクトと共有しない(`apkmanager_test` 専用)。

起動スモーク(エミュレータ)は CI では自動で走らない(`workflow_dispatch` の手動実行のみ)。
1 回約 13 分かかり、Actions の無料枠 2,000 分/月 はアカウント全体で共有されるため。
**APK を渡す前に必ず手元で `install-run.ps1` まで通すこと。**
2026-09-06 時点でこのホストは API 34 の google_apis イメージでも QEMU 起動段階でハングするため、
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
scripts/                    … publish-apk.ps1 / regen-meta.ps1 / apk-meta.ps1(PC 側の配布)、set-ci-secrets.ps1(CI の Secrets)
.github/actions/publish-apk … CI から builds/ に置く Action。.github/workflows/publish-apk.yml から使う
```

## 法的な位置づけ

ローカル環境での個人利用のみ。ストアや公開リポジトリには出さない。

## ライセンス

[MIT License](LICENSE)
