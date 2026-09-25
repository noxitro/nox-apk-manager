---
version: 1
slug: "main-java-com-noxitro-apkmanager-ui-homescreen-kt"
primary_target: "app/src/main/java/com/noxitro/apkmanager/ui/HomeScreen.kt"
related_targets: ["app/src/main/java/com/noxitro/apkmanager/MainActivity.kt","app/src/main/java/com/noxitro/apkmanager/ui/SettingsScreen.kt"]
---

# Surface: ホーム(アプリ一覧)

Scope: 起動直後の 1 画面。Drive の builds/ を読み、更新の要るアプリを上段にまとめ、1 タップまたは「全て更新」で入れ替える。
Visitor mode: Operate。

Audience: 作者本人。夜、ベッドやソファで Galaxy を片手に、PC で配布したばかりの版を入れ直す。
Job: 「何が新しくなったか」を 1 秒で読み、「全て更新」を押して OS の確認ダイアログに答えるだけにする。
Action: 全て更新 / 行ごとの 更新・インストール。
Proof/content: 実在の 8 プロジェクトと meta.json の説明文・版番号・サイズ。捏造しない。
Constraints: Material 3 標準部品。ダーク前提(夜の利用)。端末ごとに状態が違う。署名不一致・ダウングレードは文で出す。

Memorable moment: 「全て更新」を押すと、上段の板が 1 行ずつ「ダウンロード → 確認待ち → 完了」と進み、完了した行が下段の「最新」へ移る。

Unresolved: Drive の icon.png はまだ無い(PC 側で用意するまでインストール済みアイコン or 頭文字)。

## Direction contract

THESIS: Play ストアの「更新タブ」を Good Lock の縦構造に正しく組んだもの。奇抜さを一切足さず、部品の精度と状態の言葉で勝負する。「更新あり」を上段に束ねる配置以外の工夫を拒む。

OWN-WORLD: Material 3 ダークスキーム(dynamic color 対応、静的フォールバックは藍系 primary)。面は surface のトーナル階層 3 段、色は primary を「更新」ボタン・件数バッジ・進捗にだけ。角丸 16dp のカード、行高 72dp、Roboto、版番号は等幅数字。アイコン 48dp。

STORY: 開く → 上段で「更新あり 3 件」と読む → 「全て更新」を押す → 行ごとに進捗と状態語が進む → 全行が「最新」に落ち着く。失敗した行は赤い状態語と理由文、次の一手(アンインストール / 再試行)を持つ。

FIRST VIEWPORT: 上から、ヒーロー帯(アプリ名・アカウント・最終同期)、絞り込みチップ 4 つ(すべて / 更新あり / 未導入 / 導入済み)、「更新あり N 件」の板(右上に「全て更新」の filled button)、板の中に更新対象の行(アイコン / 名前・説明 1 行・『0.5.0 → 0.6.0 · 15.8 MB』/ tonal「更新」ボタン)、その下に「導入済み」「未導入」の見出しと行。下部は NavigationBar(アプリ / 設定)。

FORM: 業界標準(canon)。候補リストでは Good Lock 直写しが 1 位、割当は 7 位(出発案内板)だったが、ユーザーが標準を選択。seed key 5388eb52。

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
