# テスト方針と一覧 — nox-apk-manager

対象コミット `a820265` / 生成 2026-10-06
この文書は **テストコードから機械的に生成** している。手で数えた値は 1 つも無い。

## この文書の使い方

**何が保証されているかの正本**。「テストが通った」を判断するときは、ここに書かれた
層・件数・下限と、実際の CI 結果を突き合わせる。

判断の材料はこの 3 つで、順に強い。

| 材料 | 取り方 | 何が分かる |
|---|---|---|
| 結果 XML の `tests=` | `app/build/test-results/` と `app/build/outputs/androidTest-results/` | **実際に何件走ったか**。唯一の証明 |
| CI の結論 | `gh run list -R noxitro/nox-apk-manager --limit 1` | 直近の push が緑か赤か |
| この文書 | 本ファイル | 走ったものが何を守っているはずか |

**`BUILD SUCCESSFUL` を根拠にしてはいけない。** `connectedAndroidTest` も
`testDebugUnitTest` も、**テストが 0 件でも成功で返る**。ランナークラスの指定漏れや
フィルタの書き間違いで 1 件も走らなくても緑になる。実際に兄弟プロジェクトで
「計装テストが 1 件も走らずに緑」が起きている(`docs/IMPLEMENTATION.md`)。
CI は `.github/scripts/check-test-results.py` で結果 XML の `tests=` を数え、
下限を下回れば落とす。**緑ではなく件数を見ること。**

この文書自体がコードとずれていないかは CI が毎回検査する
(`.github/scripts/verify-testing-doc.py`)。テストを増減したらこの文書も更新する。
更新は生成なので手書きしない。

## 層の分担

| 層 | 走る場所 | 何を守るか | 見ないもの |
|---|---|---|---|
| 単体テスト (JVM) | ランナー上、端末不要。1〜3 分 | 解析と判定。配布ファイル名と版、鍵の検査、Drive のエラーの見分け、中止の理由の文、画面の行(Robolectric) | 本物の OS のインストーラ、Drive の実体 |
| 計装テスト (エミュレータ) | Android 上。端末 1 台あたり 10〜20 分(大半はエミュレータの起動) | **本物の PackageInstaller と OS の確認画面**。新規・無確認の更新・取り消し・署名違い・巻き戻しで返る結果 | One UI / HyperOS 独自の画面と保護機能、Drive からの取得 |
| 起動スモーク | 計装テストの後 | **入れて開いて落ちないこと**。DI の組み立て、初回マイグレーション、権限要求 | 機能の正しさ |

起動スモークを別に持つのは、**計装テストが全部緑でも起動時に落ちる不具合が素通りする**
ため。テストは自分が名指しした物しか守らない。

## 単体テスト(45 件)

| 領域 | 何を守るか | テストクラス | 件数 |
|---|---|---|---|
| `ui` | ホームの行と「更新あり」の板(Robolectric で JVM 上に描く)。失敗した行に次の一手(アンインストール / 再試行)が出ること、自分自身が「全て更新」に含まれないことが件数とボタンで分かること。接続方法の画面の「今の状態」。分からないもの(共有の可否など)を未と決めつけず、違う手順に誘導しないこと | `SetupStatusTest`<br>`UpdateBoardTest`<br>`JobStateTest` | 17 |
| `auth` | Drive の認証と鍵の取り込み。サービスアカウントの JWT の署名と中身、送られた・選ばれた鍵の検査(文字コード、OAuth クライアント JSON との取り違え)と、弾いた理由が出ること | `PushedKeyTest`<br>`ServiceAccountAuthTest` | 15 |
| `model` | 配布ファイル名の解釈と版の比較。`<名前>-<版>-<debug|release>.apk` の規約から外れた名前を弾き、versionCode があればそれを、無ければ版名を数値で比べる | `CatalogTest` | 6 |
| `install` | インストール。単体: OS が中止を返したとき、理由を捨てずに次の一手(確認画面で取り消した / セッションの破棄 / 端末の保護機能 など)が分かる文にし、知らない理由も OS の文のまま出すこと。計装(E2E): 本物の PackageInstaller にダミーのアプリ(:fixture)を入れさせ、確認画面を押して、新規 / 自分が入れたアプリの無確認の更新 / 取り消し / 署名違い / 版の巻き戻し がそれぞれ正しい結果で返ること | `AbortReasonTest` | 5 |
| `drive` | Drive のエラーの見分け。Drive API が無効な 403 を「builds/ が未共有」と取り違えないこと | `DriveExceptionTest` | 2 |

## 計装テスト(5 件)

CI で走るのは 5 件。

| 領域 | 何を守るか | テストクラス | 件数 |
|---|---|---|---|
| `install` | インストール。単体: OS が中止を返したとき、理由を捨てずに次の一手(確認画面で取り消した / セッションの破棄 / 端末の保護機能 など)が分かる文にし、知らない理由も OS の文のまま出すこと。計装(E2E): 本物の PackageInstaller にダミーのアプリ(:fixture)を入れさせ、確認画面を押して、新規 / 自分が入れたアプリの無確認の更新 / 取り消し / 署名違い / 版の巻き戻し がそれぞれ正しい結果で返ること | `InstallE2ETest` | 5 |

## CI が何をいつ走らせるか

`.github/workflows/ci.yml`。

| ジョブ | 契機 | 内容 | 下限件数 |
|---|---|---|---|
| 単体テストとビルド | **push のたび**(全ブランチ)・PR・手動 | `testDebugUnitTest` と `assembleDebug` | 31 |
| 計装テストと起動スモーク(2 台) | **main への push**・PR・手動 | `connectedDebugAndroidTest` と起動スモーク | 3 |

計装テストは端末 2 台(Galaxy 相当: API 35・1080×2340 / Xiaomi Pad 8 相当: API 36・3200×2136)で
別々のジョブとして走り、下限件数は **1 台あたり** の値。このリポジトリは公開なので、標準の
GitHub ホストランナーは無料で、月の無料枠も減らない。非公開の兄弟リポジトリのように手動実行に
絞る理由は無い。ブランチへの push で走らせないのは、PR を開いている間に同じ commit で 2 回走るのを避けるため。
**任意のタイミングで全部走らせたいときは Actions タブの Run workflow から手動実行する。**

下限件数は実測の約 7 割。数件の増減で赤くせず、「0 件で緑」だけを確実に捕まえる値。

## 成功の判定

次が全部成立したときだけ「問題なし」と言える。1 つでも欠けたら言えない。

1. 単体テストの実行件数が 31 件以上で、失敗もエラーも 0
2. 計装テストの実行件数が 3 件以上で、失敗もエラーも 0
3. 起動スモークで logcat に `FATAL EXCEPTION` が無く、プロセスが生きている
4. この文書とコードがずれていない(`verify-testing-doc.py` が緑)

件数は Actions のサマリに出る。失敗したテスト名も一覧で出る。

## 分かっていない範囲

- **Drive の実際の応答は CI で見ていない。** 取得とメタデータの読み取りは単体テストの偽物の応答で見ているだけで、
  E2E が入れる APK もテスト APK に同梱したもの。Drive 側の仕様が変われば緑のまま実機で壊れる。
- **エミュレータの OS は素の Android。** Galaxy / Xiaomi に寄せているのは画面の大きさと Android の版だけで、
  One UI / HyperOS が独自に挟む画面や保護機能(オートブロッカー、HyperOS のセキュリティ検査など)は再現しない。
  実機でしか出ない中止は、画面に出る OS の理由(0.7.1)から辿る。
- **Android 14 以前(API 34 以下)の確認画面は CI で見ていない。** minSdk は 26。
- **性能と実機固有の挙動は見ていない。** ジェスチャー、リフレッシュレート、メーカー独自の制約。

<!-- ここから下は機械可読。verify-testing-doc.py が読む。手で書き換えない。 -->
```json
{
  "schema": 1,
  "repo": "nox-apk-manager",
  "generatedAt": "2026-10-06",
  "commit": "a820265",
  "unit": {
    "total": 45,
    "classes": {
      "auth/PushedKeyTest": 8,
      "auth/ServiceAccountAuthTest": 7,
      "drive/DriveExceptionTest": 2,
      "install/AbortReasonTest": 5,
      "model/CatalogTest": 6,
      "ui/JobStateTest": 5,
      "ui/SetupStatusTest": 6,
      "ui/UpdateBoardTest": 6
    }
  },
  "instrumented": {
    "total": 5,
    "liveExcludedFromCi": 0,
    "runInCi": 5,
    "classes": {
      "install/InstallE2ETest": 5
    }
  },
  "ciFloors": {
    "unit": 31,
    "instrumented": 3
  }
}
```
