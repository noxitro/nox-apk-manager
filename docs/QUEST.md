# Meta Quest 3 への移植 — 実機検証の手順書

作成 2026-09-06 / 対象 `351b367`

## この文書は何か

このアプリを Meta Quest 3(Horizon OS)で動かせるかを、**設計を始める前に実機で確かめる**ための手順。
書いてあるのは検証だけで、実装方針はまだ決めない。各項目に **判定** を書く欄があり、
そこが埋まった時点で「VR 版を作るか / 何を作り替えるか」を決める。

前提となる見立て:

- 作るのは VR アプリではなく、**同じ APK を Quest の 2D パネルアプリとして成立させたもの**。
  Compose の画面はそのまま使う。Unity / OpenXR は使わない(使うと Compose 資産が全部消える)。
- 移植を阻むものは 2 つ。**GMS 非搭載**(認証が動かない、◎ 確実)と
  **PackageInstaller が Horizon OS で通るか**(▲ 未知)。後者が本丸で、通らなければ企画ごと無い。

## 実機を共有しているときの注意

Quest 3 は他の作業(別アプリの実機確認など)と取り合いになる。
**別の作業でヘッドセットを使っている間は、この文書の adb コマンドを一切実行しない。**
特に次の 3 つは相手の作業を壊す。

| コマンド | 壊すもの |
|---|---|
| `adb logcat -c` | ログバッファを全消しする。相手が見ている最中のログが消える |
| `adb install` / `am force-stop` | ヘッドセットの前面が切り替わる。相手の操作の途中に割り込む |
| `adb push /sdcard/...` | 転送中は帯域と端末を占有する。大きい APK ほど長い |

`adb devices` の確認だけは無害。実行するのは、ヘッドセットが空いていることを
**人間が確認してから**にする。

## 確度の記法

- ◎ 実機で確かめた / コードで確認した
- ○ 仕様上そうなるはずだが未確認
- ▲ 未知。この文書で確かめる対象

## 検証 0: 準備

1. Quest 3 の開発者モードを有効にする(Meta Horizon アプリ → デバイス → 開発者モード)。
   Meta の開発者アカウント登録が要る。
2. USB で PC につなぎ、ヘッドセット内の「USB デバッグを許可」を承認する。
3. `adb devices` に出ることを確認する。

```
adb devices
```

## 検証 1: 現行 APK がパネルアプリとして開くか

**確かめること**: 何も手を入れていない debug ビルドが、Quest で起動して読めるか。

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

ヘッドセットで **アプリ → 提供元不明のアプリ(Unknown Sources)** から起動する。

見るところ:

- 起動して落ちないか。`AppContainer` は `DriveAuth(app)` を即座に作るが、
  中身は `AuthorizationRequest.builder()` だけで GMS への接続はしないので、
  **起動時点では落ちないはず**(○)。落ちるならここが最初の修正点。
- パネルの縦横比と列数。幅が 760dp(380dp × 2)を超えていれば一覧が 2 列に、
  ナビゲーションが左端のレールになるはず。ならなければ、パネルの実効 dp 幅を
  `adb shell wm size` / `wm density` で確かめる。
- コントローラのレイポインタでボタンが押せるか。チップ・下部ナビ・ボトムシートが操作できるか。

> **判定 ◎ 通った(2026-09-06、Quest 3 / Horizon OS build 207 / Android 14・SDK 34 / arm64-v8a)**。
> 落ちずに起動し、コントローラのレイポインタでチップ・行・下部ナビ・ボタンがすべて操作できた。
>
> **想定が外れた点**: パネルは横長ではなく **400dp × 640dp の縦長**だった
> (`mBounds=Rect(0,0-500,800)` / 200dpi → `sw400dp w400dp h640dp`、`nrml port`)。
> ヘッドセット全体は `w3302dp h1766dp` だが、提供元不明の 2D アプリに割り当てられる既定の窓は
> **スマホと同じ形**。したがって列数は 1 列、ナビは下部バーのままで、スマホと同じ見え方になる。
> 先に入れた複数列・レール化は既定では発動しない(利用者がパネルを広げたときに効く)。
> **横長を前提にした作り替えは不要だった。**

## 検証 2: PackageInstaller が通るか ← 本丸

**確かめること**: Quest の上で、このアプリから APK のインストールが成立するか。
認証は迂回する。`AppContainer` は debug ビルドのとき、アプリ専用の外部ストレージに
`builds/` があればそれを Drive の代わりに読む([ApkManagerApplication.kt:44](../app/src/main/java/com/noxitro/apkmanager/ApkManagerApplication.kt#L44))。

```
adb push "G:/マイドライブ/builds" /sdcard/Android/data/com.noxitro.apkmanager/files/builds
adb shell am force-stop com.noxitro.apkmanager
```

アプリを開き直すと、Drive 認証なしで一覧が出る(`isLocalSource`)。そこで **1 本だけ**入れてみる。

見るところ、上から順に潰す:

1. **一覧が出るか**。出なければ外部ストレージのパスが Horizon OS で違う(▲)。
2. 「導入」を押したとき **提供元不明アプリの許可**を求められるか。Android なら
   `ACTION_MANAGE_UNKNOWN_APP_SOURCES` で OS の設定画面に飛ぶが、
   **Horizon OS は設定 UI が AOSP と違うので、この Intent が resolve しない可能性がある**(▲)。
   飛ばずに落ちる / 何も起きないなら、それが分かった時点で記録する。
3. **OS の確認ダイアログが VR 空間に出るか**。パネルアプリの上に system dialog が
   出せるかは未知(▲)。出ないまま無言で止まるなら、`InstallEvents.emitUserAction` に
   Intent は来ているのに開けていない可能性がある。`adb logcat` で確認する。
4. **インストールが成功するか**。成功したら、入れたアプリが
   「提供元不明のアプリ」に現れるか(adb install と同じ扱いになるか)。
5. **2 回目の更新が無確認で通るか**。`setRequireUserAction(USER_ACTION_NOT_REQUIRED)` は
   installer of record が自分自身のときだけ効く。1 回このアプリ経由で入れた後の
   2 回目が無確認なら、Quest でも「全て更新」が成立する。

ログの取り方:

```
adb logcat -c
adb logcat | grep -iE "packageinstaller|apkmanager|INSTALL_FAILED"
```

> **判定 ◎ 全部通った(2026-09-06)**。Quest 3 でこのアプリからのインストールと無確認更新が成立する。
>
> | # | 見るところ | 結果 |
> |---|---|---|
> | 1 | 一覧が出るか | **◎** ローカル源ではなく Drive 本番で 10 件出た(下の「認証」参照) |
> | 2 | 提供元不明アプリの許可を求められるか | **◎** `MANAGE_UNKNOWN_APP_SOURCES` は `com.oculus.vrshell` の中継アクティビティに解決され、「不明なアプリのインストール」画面が開いた。**AOSP と設定 UI が違っても Intent は通る** |
> | 3 | OS の確認ダイアログが VR 空間に出るか | **◎** 「導入」を押すと Android のダイアログがパネルとして VR 内に出た。取りこぼしではなく、許可が無いという正しい内容 |
> | 4 | インストールが成功するか | **◎** LaunchDrawer(1.4 MB)を導入し、`installer=com.noxitro.apkmanager` で入った。一覧の「未導入 7」が「6」に減り、導入済みへ移った |
> | 5 | 2 回目の更新が無確認で通るか | **◎** ただし**マニフェストの不足が 1 件見つかった**(下記)。直したら、前面がアプリのまま `lastUpdateTime` だけが動いた(23:14:12 → 23:15:31)。ダイアログは出ない |
>
> 許可(`REQUEST_INSTALL_PACKAGES`)はユーザーの同意を得て `appops set ... allow` で与えた。
> 通常運用では「設定 → インストールの許可 → OS の設定を開く」で人間が入れる。
>
> **見つかった不足**: `UPDATE_PACKAGES_WITHOUT_USER_ACTION` をマニフェストに宣言していなかった。
> これが無いと `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` は**黙って無視され**、
> installer of record であっても毎回確認ダイアログが出る。エラーも警告も出ないので、
> 実機で 2 回入れてみるまで気付けない。**Quest 固有の話ではなく、スマホでも同じだった。**

## 検証 3: 認証がどう失敗するか

**確かめること**: GMS 非搭載の端末で `DriveAuth.authorize()` が何を投げるか。
release ビルド、または `builds/` を置いていない debug ビルドで「Google で接続」を押す。

期待: `com.google.android.gms` に接続できず例外か、`ApiException`(SERVICE_MISSING)。
**どう失敗するかを記録する**。UI が固まるのか、エラー表示に落ちるのかで、
差し替え後のフォールバック文言が決まる。

> **判定 — 対象が消滅(2026-09-06)**。GMS 依存の `DriveAuth` ごと削除したので、確かめる失敗が無くなった。
> 代わりに**サービスアカウントでの読み取りが実機で通った**: Quest 3 上で Google アカウントのログインを
> 一度もせずに `builds/` を読み、10 プロジェクトが一覧に出た(同期時刻つき)。
> 鍵は `adb push` → 初回起動で DataStore に取り込み → **外部ストレージのファイルは消える**まで実機で確認済み。
> なお `files/` はアプリの初回起動まで存在しないので、**push の前に一度アプリを開く**必要がある
> (先に push すると `secure_mkdirs failed` で落ちる)。

## 検証 4(実機不要): サービスアカウントで builds/ が読めるか

**先にこれをやる。** ヘッドセットが空くのを待つ必要が無く、ここが通れば認証の設計が確定して、
実機で見るべきものが検証 2(PackageInstaller)だけに絞れる。

### 4-1. サービスアカウントを作る(PC のブラウザ)

`docs/SETUP.md` で作った GCP プロジェクト `nox-apk-manager` をそのまま使う。

1. https://console.cloud.google.com/iam-admin/serviceaccounts?project=nox-apk-manager を開く。
2. **「サービス アカウントを作成」**。名前は `nox-drive-reader`。
   ロールの付与は **不要**(GCP のリソースには触らない。Drive 側の共有だけで足りる)。
3. 作ったアカウントを開き、**「キー」タブ → 「鍵を追加」→「新しい鍵を作成」→ JSON**。
   ダウンロードされた JSON を、**リポジトリの外**に置く(例: `~/.secrets/nox-drive-sa.json`)。
4. アカウントのメールアドレス(`...@<プロジェクト>.iam.gserviceaccount.com`)を控える。

同意画面もテストユーザーも要らない。**ここが 7 日問題の外側にいる理由**。

### 4-2. builds/ を共有する

1. https://drive.google.com/ でマイドライブ直下の **`builds`** フォルダを右クリック → **共有**。
2. 4-1 で控えたアドレスを入れ、権限は **閲覧者**。通知はオフでよい。
3. 同じ画面の URL からフォルダ ID を控える(`https://drive.google.com/drive/folders/<ここ>`)。

### 4-3. 確かめる

```
pwsh scripts/check-drive-service-account.ps1 -KeyFile ~/.secrets/nox-drive-sa.json -FolderId <フォルダID>
```

スクリプトは 3 段階を順に見る。依存は無く、PowerShell 7 の .NET だけで JWT を署名する。

1. JWT bearer でアクセストークンが取れるか
2. `builds/` 直下(project フォルダ)が一覧できるか ← **403 ならここ**。共有し忘れ
3. その 1 つ下(APK と `meta.json`)まで降りられるか

3 まで緑なら、アプリ側は今の [DriveSource](../app/src/main/java/com/noxitro/apkmanager/drive/DriveSource.kt) の
形をほぼ変えずに済む(変わるのは `findBuildsFolder()` がフォルダ ID を設定から取る点だけ)。

> **判定 ◎ 通った(2026-09-06)**。
> サービスアカウントに `builds/` を
> 閲覧者で共有した状態で、[1] トークン取得 [2] 直下 10 件 [3] 別アプリのフォルダの中身 2 件、すべて成功。
> **同意画面もテストユーザーも GMS も通らずに Drive が読める**ことが実証された。
> `supportsAllDrives` は要らない(マイドライブの共有アイテムとして普通に見える)。

## 検証が終わったら決めること

検証 2 が ○ だった場合にだけ、次に進む。残りは 2 つ。

1. ~~**認証の差し替え**~~ **完了(2026-09-06)**。全端末をサービスアカウントに一本化し、
   `DriveAuth` と `play-services-auth` を削除した(`docs/SETUP.md`)。
   スマホ版の 7 日問題も同時に消えている。鍵は `adb push` で入れ、端末側の文字入力は不要。
2. ~~**レイアウト**~~ **先に入れた(2026-09-06、phone + tablet 相当まで)**。
   下部ナビは `NavigationSuiteScaffold` にしたので、幅の広い窓では左端のレールになる。
   一覧は `LazyVerticalGrid` + `GridCells.Adaptive(380dp)` で、幅があれば複数列に流れる。
   板は 1 枚の面のまま中で列を分ける(The Flat Board Rule を崩さないため)。
   設定は本文の幅を 640dp で頭打ちにした。
   **どれも実機で見ていない。検証 1 で確かめる対象。**
3. **配布**。Quest 用のビルドを `builds/nox-apk-manager/` に置くか、
   phone 版と同じ APK 1 本で済ませるか。

## 付録: 認証方式の裏取り(2026-09-06)

実機に触らずに確かめられる分を先に潰した。結論は **device flow は使えない**。

| 方式 | 可否 | 根拠 |
|---|---|---|
| OAuth 2.0 device flow(TV・限定入力デバイス) | **×**(◎) | 対応スコープが `openid` / `email` / `profile` / `drive.appdata` / `drive.file` / YouTube 2 種に限られる。**`drive.readonly` は入っていない**。`drive.file` は Google Picker で開いたファイルにしか効かないので、`builds/` を一覧する用途に使えない |
| OOB(コード貼り付け) | **×**(◎) | 2023-01-31 に全クライアントで遮断済み。フィッシング対策 |
| PC で取った refresh token を端末に持ち込む | **○**(◎ 動くことは確実) | PC 側で Desktop クライアント + loopback フローを 1 回通し、`drive.readonly` の refresh token を得て端末に置く。以降アプリはトークンエンドポイントで更新するだけ。VR 側の入力はゼロ |
| サービスアカウント + `builds/` を共有 | **◎ 採用**(2026-09-06 実証) | ユーザー認証を一切しない。GCP でサービスアカウントを作り、Drive の `builds/` フォルダをそのアドレスに閲覧者として共有する。アプリは秘密鍵で JWT を署名してトークンを取る。**同意画面を通らないので 7 日問題が無い**。GMS も不要。検証 4 で 3 段階すべて通過 |
| 端末内ブラウザ + loopback リダイレクト | **▲**(要実機) | AppAuth 相当。アプリが `127.0.0.1:<port>` で待ち受け、Horizon Browser を開いて Google にログインし、同じ端末の loopback に戻る。ブラウザとアプリが同一端末なので custom scheme より当てになるが、Quest で戻れるかは未検証。VR でメールアドレスとパスワードを打つ手間は残る |

**7 日問題**(◎ 2026-09-06 に公式ドキュメントで確認): 同意画面の公開ステータスが「テスト」の外部アプリは、
**refresh token が 7 日で失効する**(要求スコープが name / email / profile の部分集合のときを除く)。
`drive.readonly` は該当するので、`docs/SETUP.md` の現行運用(テストユーザー)のままだと
**週に 1 回、承認をやり直すことになる**。これは Quest だけの話ではなく、**今のスマホ版にも効いている**。
公開ステータスを「本番」に上げれば 7 日の上限は外れるが、`drive.readonly` は機微スコープなので
未審査だと「確認されていないアプリ」の警告画面を挟むことになる(○ 未確認)。

**判断**: 軸を **サービスアカウント**に変える。理由は 3 つ。

1. **7 日問題も GMS 問題も同時に消える**。ユーザー認証の経路を丸ごと使わないので、
   同意画面・テストユーザー・トークンの失効という話が全部無くなる。
2. VR 側の入力がゼロなのは refresh token 持ち込みと同じで、さらに持ち込みの手間が 1 回きりで済む。
3. 読み取り専用という現行の設計([DriveSource](../app/src/main/java/com/noxitro/apkmanager/drive/DriveSource.kt) は
   書き込みを持たない)と、フォルダ 1 つを閲覧者として共有する形が正確に一致する。

次点は refresh token 持ち込み + 公開ステータスを「本番」に上げる方式。
サービスアカウントで一覧が通らなかったときはこちらに落とす。

**確かめた**(検証 4、2026-09-06):

- 共有した `builds/` に対する `files.list`(`q = "'<folderId>' in parents"`)は **通る**(◎)。
  `supportsAllDrives` は不要。2 階層目(`<project>/` の中の APK と `meta.json`)まで降りられる。
- サービスアカウントは自分のマイドライブを持たないので `builds/` を名前で探せない。
  現行の `findBuildsFolder()` は使えず、**フォルダ ID を設定として持つ**形に変える(◎ 確定した変更点)。

**残る設計判断**(実装時に決める):

- 秘密鍵(JSON)の持ち込み方と置き場所。`adb push` / 設定画面に貼る / QR。**リポジトリには入れない。**
- JWT 署名を自前で書くか(OkHttp + `java.security` で 50 行程度)、
  google-auth-library を足すか。後者は依存が重い。
- 現行の GMS 認証を残すか、全端末でサービスアカウントに一本化するか。
  一本化すると `play-services-auth` と `DriveAuth` ごと消せて、スマホ版の 7 日問題も同時に消える。

出典:
- https://developers.google.com/identity/protocols/oauth2/limited-input-device
- https://developers.google.com/identity/protocols/oauth2/resources/oob-migration
- https://developers.google.com/identity/protocols/oauth2

## 分かったことを書き戻す場所

Horizon OS 側の挙動(◎ になった項目)は、この文書と
`global-llm-wiki` の `nox-apk-manager構築記録` の両方に残す。書き戻しはユーザーの明示依頼があるときだけ。
