---
name: Nox APK Manager
description: 自作アプリ専用のプライベートストア。Play ストアの更新タブを Good Lock の縦構造に組んだ Material 3 標準の画面。
colors:
  primary: "#A8C7FA"
  on-primary: "#062E6F"
  secondary-container: "#42474E"
  on-secondary-container: "#DEE3EB"
  tertiary: "#7FE0C0"
  error: "#F2B8B5"
  surface: "#111318"
  on-surface: "#E2E2E9"
  on-surface-variant: "#C4C6D0"
  outline-variant: "#44474E"
  surface-container: "#1D2024"
  surface-container-high: "#282A2F"
typography:
  headline:
    fontFamily: "Roboto, sans-serif"
    fontSize: "24sp"
    fontWeight: 600
  title:
    fontFamily: "Roboto, sans-serif"
    fontSize: "17sp"
    fontWeight: 600
  body:
    fontFamily: "Roboto, sans-serif"
    fontSize: "14sp"
    fontWeight: 400
  label:
    fontFamily: "Roboto, sans-serif"
    fontSize: "14sp"
    fontWeight: 600
  numeric:
    fontFamily: "Roboto, sans-serif"
    fontSize: "12sp"
    fontWeight: 400
    fontFeature: "tnum"
rounded:
  icon: "12dp"
  board: "20dp"
  progress: "2dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  xl: "20dp"
components:
  button-update:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
  button-install:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-secondary-container}"
  board:
    backgroundColor: "{colors.surface-container}"
    rounded: "{rounded.board}"
  app-icon-placeholder:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-secondary-container}"
    rounded: "{rounded.icon}"
    size: "48dp"
---

# Design System: Nox APK Manager

## Overview

**Creative North Star: "更新タブだけのストア"**

Play ストアの「更新を保留中」タブを、Samsung Good Lock の縦構造(見出し帯 → 絞り込みチップ → 更新の板 → 行)に組んだもの。
独自の世界観は持たず、Material 3 の標準部品と標準の色役割だけで組む。Android 12 以降は壁紙由来の動的カラー、
それ未満は藍系のダーク静的スキーム。夜にベッドで開く用途なのでダークを第一に設計し、ライトは同じ役割で自動に従う。

色は「更新」ボタン・件数・進捗・状態語にだけ使い、面はすべて無彩のトーナル階層で組む。
密度は Good Lock 相当(行高 72dp、アイコン 48dp、1 行の説明)。

**Key Characteristics:**
- 更新のある行を上段の板(surfaceContainer)に束ね、それ以外は素の面に並べる
- 版番号・サイズ・件数は等幅数字(`tnum`)。矢印 `→` で「入っている版 → 取れる版」
- 状態は色だけで語らない。必ず状態語(更新あり / 最新 / 未導入 / 端末の方が新しい / 状態不明)を添える
- 進行中は行の直下に進捗バーと 1 行の実況が伸びる(モーダルにしない)

## Colors

Material 3 の色役割をそのまま使う。値は動的カラーで差し替わるので、下の hex は Android 11 以前の静的フォールバック。

### Primary
- **Sky Primary** (#A8C7FA): 「更新」の filled button、既定版のラベル。画面で最も目立つ色で、更新のある行にしか出ない。

### Secondary
- **Slate Container** (#42474E): tonal button「導入」、アイコン無しの頭文字プレースホルダ、絞り込みチップの選択面。

### Tertiary
- **Mint Done** (#7FE0C0): インストール完了の状態語とチェック。

### Neutral
- **Night Surface** (#111318): 画面の地。
- **Board** (#1D2024): 「更新あり N 件」の板と、板の中の行。
- **Ink** (#E2E2E9): 本文。
- **Muted Ink** (#C4C6D0): 説明文・版番号・状態語。
- **Hairline** (#44474E): 板の見出しと行の間の 1px 区切り。
- **Error** (#F2B8B5): 失敗の状態語と理由文。

### Named Rules
**The One Button Rule.** filled(primary)のボタンは「更新」と「全て更新」だけ。導入は tonal、開くは outlined。役割が色で分かる。
**The Words-Too Rule.** 状態は必ず文字を伴う。色は補助で、色覚や動的カラーの当たり外れに依存しない。

## Typography

**Display Font:** Roboto(システム)
**Body Font:** Roboto(システム)
**Numeric:** Roboto + `tnum`(等幅数字)

**Character:** 1 ファミリーで通す製品 UI。段差は 1.125〜1.2 に留め、太さ(SemiBold)で見出しを立てる。

### Hierarchy
- **Headline** (SemiBold, 24sp): 画面名(ヒーロー帯の「Nox APK Manager」「設定」)。
- **Title** (SemiBold, 17sp): 行のアプリ名、板の「更新あり」。
- **Body** (Regular, 14sp): 説明文、状態の説明。1 行で省略(ellipsis)。
- **Label** (SemiBold, 14sp): ボタン。
- **Numeric** (Regular, 12sp, `tnum`): 版番号・サイズ・件数・時刻。

### Named Rules
**The Tabular Rule.** 数字が縦に並ぶ場所(版番号・サイズ・件数)は必ず `tnum`。桁がずれると差分が読めない。

## Layout

縦 1 列の LazyColumn。上から、ヒーロー帯(左 20dp / 右 8dp)、横スクロールのチップ列(16dp)、
「更新あり N 件」の板(左右 12dp の余白で角丸 20dp、中に行)、「導入済み」「未導入」の見出し(20dp、上 20 / 下 4)と行。
行は左右 16dp、上下 12dp、アイコン 48dp、アイコンと本文の間 16dp、本文とボタンの間 12dp。
進捗と実況はアイコン幅ぶん(64dp)インデントして行の下に出る。
下部は NavigationBar(アプリ / 設定)。edge-to-edge で Scaffold の inset に従い、一覧の下端に 16dp を足す。
詳細は ModalBottomSheet(skipPartiallyExpanded、左右 24dp)。

## Elevation & Depth

影は使わない。深さはトーナル階層だけ: 地(surface) → 板(surfaceContainer) → シート(標準の bottom sheet 面)。
板の中の見出しと行の間は 1px の outlineVariant。

### Named Rules
**The Flat Board Rule.** 板は色面と角丸で「まとまり」を示す。影も枠線も持たない。

## Shapes

- 板: 角丸 20dp(上辺は見出しカード、下辺は 12dp の締めの帯で閉じる)
- アプリアイコン: 角丸 = サイズの 25%(48dp で 12dp)
- 進捗バー: 角丸 2dp
- ボタン・チップ・シート: Material 3 の既定形状

## Components

### Buttons
- **Primary(更新 / 全て更新):** filled、`ButtonWithIconContentPadding`。作業中と一括更新中は無効化。
- **Tonal(導入):** 未導入・状態不明の行。入れる APK が無ければ無効。
- **Outlined(開く / 中止 / 接続し直す / OS の設定を開く):** 二次操作。
- **Text(入れる):** 詳細シートの版一覧で、既定でない版を入れる。
- **Text(確認画面を開く / アンインストール / 再試行):** 行の実況の下に出る次の一手。一括更新の最中は無効。

### Chips
- **FilterChip** 4 つ(すべて / 更新あり / 未導入 / 導入済み)。ラベル末尾に件数。横スクロール、間隔 8dp。

### Cards / Containers
- **更新の板:** surfaceContainer、角丸 20dp、見出し行(タイトル + 件数・合計サイズ + 主ボタン)、hairline、行。

### App Row
- **構造:** アイコン 48dp | 名前(Title)・説明(Body, muted, 1 行)・版行(Numeric) | 主ボタン。
- **版行:** `0.5.0 → 0.6.0 · 15.8 MB · release`。入っている版は muted、取れる版は Ink + SemiBold。
- **進行:** 行の下に `LinearProgressIndicator`(ダウンロード中は確定進捗、それ以外は不確定)と実況文。完了は tertiary + チェック、失敗は error + 感嘆アイコン。タップで畳む。
  失敗には次の一手を Text ボタンで添える(署名不一致・ダウングレードは「アンインストール」、それ以外は「再試行」)。

### Navigation
- **NavigationBar** 2 項目。選択は filled アイコン、非選択は outlined アイコン。

### Empty / Error / Loading
- 読み込み: 中央の `CircularProgressIndicator` と 1 行の説明。
- エラー: `CloudOff` アイコン、見出し、理由、主ボタン(「Google で接続」または「もう一度読む」)。
- 絞り込みが空: `Inbox` アイコンと状況を言う 1 文。

## Do's and Don'ts

### Do:
- **Do** 更新のある行を板に束ね、板の見出しに件数と合計サイズを出す。
- **Do** 版番号・サイズ・件数を `tnum` で組む。
- **Do** 失敗を行の中で言葉で出し、次の一手(アンインストール / 再試行)を含める。
- **Do** Material 3 の標準部品をそのまま使い、色は役割トークンから取る。

### Don't:
- **Don't** primary を装飾に使わない。「更新」系のボタンと既定版ラベル以外に出さない。
- **Don't** 影・枠線・グラデーションで面を飾らない。深さはトーナル階層のみ。
- **Don't** 状態を色だけで示さない。
- **Don't** 進行状況をモーダルやトーストにしない。行の直下で語る。
