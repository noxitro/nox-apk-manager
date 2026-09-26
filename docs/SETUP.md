# セットアップ手順

> **進捗メモ(2026-09-06)**: **認証をサービスアカウントに一本化した。**
> 旧方式(OAuth の Android クライアント + 同意画面 + テストユーザー)は**廃止**。
> 理由は 2 つ。
> 1. Google Play 開発者サービスが無い端末(Meta Quest 3)で動かない。
> 2. 同意画面の公開ステータスが「テスト」だと、**refresh token が 7 日で失効する**
>    (`drive.readonly` は name / email / profile の部分集合ではないため)。週に 1 回承認し直す運用になる。
>
> サービスアカウントはユーザー認証を通らないので、この 2 つがどちらも消える。
> 詳しい裏取りは [QUEST.md](QUEST.md) の付録。

アプリが Drive を読む仕組みは次のとおり。

- **サービスアカウント**(GCP が発行する、人ではないアカウント)の秘密鍵で読む。
- Drive の `builds/` フォルダを、そのサービスアカウントのアドレスに **閲覧者** として共有しておく。
- 端末で Google アカウントにログインする必要は**無い**。同意画面もテストユーザー登録も**無い**。
- Google Play 開発者サービスが**要らない**(Meta Quest のような GMS 非搭載端末でも動く)。

所要 10 分。無料。審査(verification)も不要。

---

## パート 1: Google Cloud Console(PC のブラウザで)

### 1-1. プロジェクトを用意する

既に `nox-apk-manager` プロジェクトがあるならそれを使う。無ければ作る。

1. https://console.cloud.google.com/ を開く(Drive に使っているのと同じ Google アカウント)。
2. 画面上部のプロジェクト選択メニュー →「新しいプロジェクト」。名前は `nox-apk-manager`。
3. **プロジェクト選択メニューが、今使うプロジェクトに切り替わっていること**を確認する。
   ここが別プロジェクトのままだと、以降の設定が全部よそに入る。

### 1-2. Google Drive API を有効にする

1. 左メニュー **「API とサービス」→「ライブラリ」**。
2. `Google Drive API` を開いて **「有効にする」**。

忘れると、トークンは取れるのに Drive の読み取りが 403 で失敗する。

### 1-3. サービスアカウントを作る

1. https://console.cloud.google.com/iam-admin/serviceaccounts を開く(プロジェクトを確認)。
2. **「+ サービス アカウントを作成」**。名前は `nox-drive-reader`。ID は自動で埋まる。
3. **「このサービス アカウントにプロジェクトへのアクセスを許可する」(ロール)は空のまま「続行」**。
   ロールは GCP のリソースに対する権限で、今回触るのは Drive なので要らない。
4. 最後の「ユーザーにこのサービス アカウントへのアクセスを許可」も空のまま **「完了」**。

一覧に出る **メールアドレス**(`nox-drive-reader@<プロジェクト>.iam.gserviceaccount.com`)を控える。

### 1-4. 鍵(JSON)を作る

1. 作ったサービスアカウントのメールアドレスをクリック → **「キー」タブ**。
2. **「鍵を追加」→「新しい鍵を作成」→ JSON** →「作成」。ファイルが自動でダウンロードされる。
3. **リポジトリの外**に移す。

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.secrets" | Out-Null
Move-Item "$env:USERPROFILE\Downloads\<ダウンロードされた名前>.json" "$env:USERPROFILE\.secrets\nox-drive-sa.json"
```

> この JSON は**パスワードと同じ**。リポジトリ・Drive・チャットに置かない。
> 漏らしたときは、同じ「キー」タブで鍵を削除すれば即座に無効になる(サービスアカウントごと消す必要はない)。

これで Console の作業は終わり。同意画面(OAuth consent screen)には**触らない**。

---

## パート 2: Drive で `builds/` を共有する

1. https://drive.google.com/ を開き、マイドライブ直下の **`builds`** フォルダを右クリック → **共有**。
2. 1-3 で控えたサービスアカウントのアドレスを入れ、権限は **閲覧者**。通知はオフでよい。
3. `builds` を開いた状態の URL `https://drive.google.com/drive/folders/<ここ>` から **フォルダ ID** を控える
   (次の確認で使う。アプリ側は名前で探すので、アプリの設定には要らない)。

共有するのは `builds` フォルダ 1 つだけでよい。中のプロジェクトフォルダは自動で見える。

---

## パート 3: PC で読めることを確かめる

端末に入れる前に、鍵と共有が正しいかをここで潰す。

```powershell
pwsh scripts\check-drive-service-account.ps1 -KeyFile "$env:USERPROFILE\.secrets\nox-drive-sa.json" -FolderId <フォルダID>
```

`[1] トークン取得` `[2] builds/ の一覧` `[3] <project>/ の一覧` が 3 つとも緑なら正しい。

| 出たもの | 意味 | 対処 |
|---|---|---|
| `account not found` | 鍵が別プロジェクトのもの、またはサービスアカウントを消した | 1-3 からやり直す |
| `[1]` で HTTP 400 / `invalid_grant`(上記以外) | Drive API が無効、鍵の失効、PC の時計のずれ | 1-2 を確認。時計を合わせる |
| `[2]` で 403 | `builds/` が共有されていない | パート 2 をやり直す |
| `[2]` が 0 件 | フォルダ ID が別のフォルダ | URL から取り直す |

---

## パート 4: 端末に鍵を入れる

### 4-1. APK を入れる

```powershell
adb install -r "G:\マイドライブ\builds\nox-apk-manager\nox-apk-manager-0.4.0-debug.apk"
```

### 4-2. 鍵を送る

**先に一度アプリを開く。** `files/` はアプリの初回起動で作られるので、
その前に push すると `remote secure_mkdirs failed: Operation not permitted` で落ちる(2026-09-06 実機で確認)。

```powershell
adb push "$env:USERPROFILE\.secrets\nox-drive-sa.json" /sdcard/Android/data/com.noxitro.apkmanager/files/nox-drive-sa.json
```

アプリを起動すると、この鍵を読んで端末内の設定(DataStore)に移し、**置いたファイルは消す**。
名前が `nox-drive-sa.json` でなくても、同じ場所の `*.json` にサービスアカウントの鍵があれば取り込む。
UTF-16 や BOM 付きで保存し直した JSON も読める。
秘密鍵を外部ストレージに残さないため。取り込めたかは「設定」タブに出るサービスアカウントのアドレスで分かる。

鍵を入れる前に開くと「Drive の鍵がまだ入っていません」と出る。押す必要のあるボタンは無く、
`adb push` してから「もう一度読む」を押せばよい。**端末側で文字を入力する場面は一度も無い**
(Meta Quest のような入力の辛い端末でも、鍵の投入は PC で完結する)。

複数台に入れるときは、同じ鍵を各端末に push すればよい。台数の制限は無い。

### 4-3. インストール許可を与える

1. アプリ下部の **「設定」** タブ → **「インストールの許可」** → **「OS の設定を開く」**。
2. **「この提供元のアプリを許可」** をオンにする。

これが無いと、更新を押しても OS がインストールを拒む。

一度このアプリ経由で入れたアプリは、次回から**確認ダイアログ無し**で更新される
(`UPDATE_PACKAGES_WITHOUT_USER_ACTION` + installer of record。2026-09-06 に Quest 3 で確認)。
adb など別の経路で入れたアプリの初回だけはダイアログが出る。

---

## パート 5: うまくいかないとき

| 症状 | 原因 | 対処 |
|---|---|---|
| 「Drive の鍵がまだ入っていません」+「探した場所 / あったもの」 | 鍵が見つからない(push していない・場所が違う) | パート 4-2。push 先が「探した場所」と同じか、「あったもの」に鍵が無いかを見る |
| 「〜.json を取り込めませんでした: OAuth クライアントの JSON です」 | 旧方式の OAuth クライアントの JSON を送った | 1-4 のサービスアカウントの鍵を送り直す |
| 「〜.json を取り込めませんでした: JSON として読めません」など | ファイルが壊れている・別のファイル | Cloud Console からダウンロードしたものをそのまま送る |
| 「builds/ を読めません(HTTP 403)」 | `builds/` がサービスアカウントに共有されていない | パート 2 |
| 「builds フォルダがありません」 | 共有したフォルダの名前が `builds` でない | フォルダ名を確認する。アプリは名前で探す |
| `[1]` から失敗する(PC の確認スクリプト) | Drive API 無効・鍵の失効・時計のずれ | パート 3 の表 |
| 更新を押すと「署名が違うので上書きできません」 | 端末に入っているのが別の鍵で署名された版(debug ⇄ release の取り違え等) | その 1 本をアンインストールしてから入れ直す |
| 「端末に入っている版の方が新しい」 | Drive より新しい版を手で入れてある | 戻すならアンインストールしてから入れ直す |
| OS の確認ダイアログが出ずに失敗する | インストール許可が無い | パート 4-3 |

鍵を入れ替えたときは、アプリの再インストールは要らない。push してアプリを開き直せば反映される。

---

## パート 6: 以後の配布(PC 側の日常操作)

各プロジェクトでビルドしたら、そのリポジトリのルートで:

```powershell
E:\dev\github.com\noxitro\nox-apk-manager\scripts\publish-apk.ps1 -Project photo-viewer -Apk app\build\outputs\apk\release\app-release.apk
```

- 配布先 `G:\マイドライブ\builds\<Project>\<Project>-<versionName>-<variant>.apk` にコピーし、同じフォルダの `meta.json` を更新する。
- versionName / variant / package 名は APK 自身から `aapt2` で読むので、引数で間違えようがない。
- 説明文を変えるときは `-Description "..."`。`icon.png` を同じフォルダに置けば未インストールでもアイコンが出る。
- 配布フォルダ全体の `meta.json` を作り直すなら `scripts\regen-meta.ps1`。
- **機能を足したら `versionName` / `versionCode` を上げてから置く**。据え置くと同名で上書きされ、端末側も更新と認識しない。
- PC で置く代わりに、`main` への push で GitHub Actions に置かせることもできる。手順は [CD.md](CD.md)。

---

## パート 7: 認証無しで動作を確かめる(debug ビルドのみ)

Cloud Console の設定前でも、Drive の代わりにアプリ専用の外部ストレージを読ませて一覧とインストールを試せる。

```powershell
adb push "G:\マイドライブ\builds" /sdcard/Android/data/com.noxitro.apkmanager/files/
```

置いた状態で debug ビルドを起動すると、認証を飛ばして一覧が出る。
実物の APK なので「導入」「更新」もそのまま通る(OS の確認ダイアログは出る)。
このフォルダを消せば、次回起動から Drive を読みにいく。
