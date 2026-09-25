# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Kotlin + Jetpack Compose (Material 3)。兄弟プロジェクト(book-log 等)と同じ構成で、AGP 8.13 / Kotlin 2.0 / compileSdk 36 / minSdk 26。
Drive は REST v3 を OkHttp で直接叩く。認証は**サービスアカウント**(秘密鍵で署名した JWT でトークンを取る)。
Google Play 開発者サービスに依存しないので GMS 非搭載端末でも動き、同意画面の refresh token 7 日失効にも当たらない。
DI フレームワーク無し(画面が少ない)。

## Users

作者本人だけ。個人開発の Android アプリ(現在 8 本)を自分の端末に入れて使っている開発者。
状況: PC でビルドして Google Drive の `builds/<project>/` に APK を置いた後、
端末側で「どれが新しくなったか」を確かめて入れ直す作業が、アプリの数だけ繰り返される。
端末は複数台あり、台ごとにインストール状態が違う。

## Product Purpose

Drive の `builds/` を端末から読み、インストール済みの版と比べ、更新のあるものを一覧の上段にまとめて、
1 タップ(または「全て更新」)で入れ替えられるようにする。
成功 = 「PC で配布したら、端末ではこのアプリを開いて全て更新を押すだけ」になること。

## Positioning

自作アプリ専用のプライベートなストア。Play ストアにも F-Droid にも出さないアプリを、
ストア相当の体験(更新バッジ・一括更新・説明文・アイコン)で扱う。
配布元は既に使っている Google Drive のフォルダで、サーバーを立てない。

## Operating Context

- 配布元: マイドライブ直下 `builds/<project>/<project>-<versionName>-<debug|release>.apk`。
  PC 側の `scripts/publish-apk.ps1` が APK を置き、同じフォルダに `meta.json`(package 名・版・variant・sha256・説明)と
  任意の `icon.png` を書く。古いフォルダには `meta.json` が無いことがある。
- 端末: Samsung Galaxy(One UI、ダークテーマ常用)を含む複数台。参考にしている体験は Samsung Good Lock の
  「更新ありを上段に束ねて全て更新」「行ごとにアイコン・名前・説明・更新ボタン」。
- 更新の検知はアプリを開いた時だけ(バックグラウンド巡回・通知はしない)。
- インストールは PackageInstaller セッション。OS の確認ダイアログは毎回出る(自作アプリなので回避しない)。

## Capabilities and Constraints

- 既定で入れる variant は **release**。行ごとに debug へ切り替えられる。
  debug と release は署名が違うと相互に上書きできない(book-log だけ release 専用鍵、他は debug 鍵)。
  失敗時は「署名が違うので一度アンインストールが必要」と言葉で出す。
- 端末の方が新しい(手元で直接入れた)場合はダウングレードとして区別し、黙って上書きしない。
- `meta.json` が無いフォルダは、ファイル名から版と variant を推定して一覧に出す。package 名が分からないので
  端末と突き合わせられず「状態不明」。一度ダウンロードした APK から package 名を読んで記憶し、次回から比較できる。
- Drive の読み取りは `drive.readonly` スコープ。書き込みはしない(整理は PC 側の責務)。
- Drive を読むのはサービスアカウント。`builds/` をそのアドレスに閲覧者として共有しておく。
  秘密鍵は `adb push` で端末に入れる(端末側で文字入力は不要)。アプリ自身は debug 鍵で署名する。
- 用語: **project**(builds/ 直下のフォルダ名)、**build**(1 つの APK)、**variant**(debug / release)。

## Brand Commitments

名前は「Nox APK Manager」(noxitro 名義の個人プロジェクト群の一つ)。ロゴ・既存のビジュアル資産は無い。
UI 文言は日本語。

**見た目の方針(2026-09-06 決定)**: 業界標準を正面から丁寧に作る。独自の世界観は載せない。
構造は Samsung Good Lock 型(ヒーロー帯 → 絞り込みチップ → 「更新あり N 件 / 全て更新」の板 → アイコン・名前・説明・ボタンの行 → 下部ナビ)。
質感と部品の語彙は Material 3 の標準。
隣に並べて見劣りしないことを基準にするアプリ: **Samsung Good Lock、Google Play ストア(更新タブ)、Galaxy Store**。

## Evidence on Hand

- 実在する配布フォルダ 8 つ(book-log, clipboard, Cockpit, LaunchDrawer, manual-rotate, opencode ほか)。
  版は 0.1〜0.6 台、APK は 1.4MB〜27MB。
- 各プロジェクトの説明文は README/PRODUCT.md から `meta.json` に転記する。アプリ内で捏造しない。
- アイコンは端末にインストール済みなら PackageManager から、未インストールなら Drive の `icon.png` か、
  ダウンロード後の APK から読む。無ければ頭文字のプレースホルダ。

## Product Principles

1. 開いた瞬間に「何を更新すればよいか」が分かる。更新ありが最上段、件数が見える。
2. 失敗を黙らせない。署名不一致・ダウングレード・Drive 未認証は理由と次の一手を言葉で出す。
3. 端末ごとの真実は端末で決める。Drive の meta は候補、PackageManager が現実。
4. 配布の規約はファイル名と meta.json に置き、アプリ側に個別プロジェクトの知識を持たせない。
5. 自分専用でも雑にしない。Good Lock 相当の視認性と密度を目標にする。
