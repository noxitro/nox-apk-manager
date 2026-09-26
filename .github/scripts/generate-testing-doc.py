# -*- coding: utf-8 -*-
"""docs/TESTING.md を、このリポジトリのテストコードから生成する。

    python .github/scripts/generate-testing-doc.py

**コミット済みの内容**から数える。CI はチェックアウトしたコミットを検査するので、
未コミットのテストを含めると必ずずれる。テストを増減したら、コミットしてから
これを実行して docs/TESTING.md を更新する。手で数字を書き換えない。
ずれは CI の verify-testing-doc.py が毎回検査する。
"""
import io, json, re, subprocess, datetime, pathlib

_HERE = pathlib.Path(__file__).resolve()
REPO = _HERE.parents[2].name
ROOT = _HERE.parents[3]
TEST_LINE = re.compile(r"\s*@Test(?![A-Za-z0-9_])")

# 領域ごとの「何を守っているか」。パッケージの接頭辞で引く。
AREAS = {
    "nox-apk-manager": {
        "model": "配布ファイル名の解釈と版の比較。`<名前>-<版>-<debug|release>.apk` の規約から外れた名前を弾き、versionCode があればそれを、無ければ版名を数値で比べる",
        "auth": "Drive の認証と鍵の取り込み。サービスアカウントの JWT の署名と中身、送られた・選ばれた鍵の検査(文字コード、OAuth クライアント JSON との取り違え)と、弾いた理由が出ること",
        "drive": "Drive のエラーの見分け。Drive API が無効な 403 を「builds/ が未共有」と取り違えないこと",
        "ui": "接続方法の画面の「今の状態」。分からないもの(共有の可否など)を未と決めつけず、違う手順に誘導しないこと",
    }
}

CI = {'nox-apk-manager': (12, None, '起動スモーク')}

SLUG = {"nox-apk-manager": "apkmanager"}


def git(repo, *args):
    return subprocess.run(["git", "-C", str(ROOT / repo), *args],
                          capture_output=True, text=True, encoding="utf-8").stdout


def inventory(repo, kind):
    """テストクラスと @Test 件数。**コミット済みの内容**から読む。

    CI はチェックアウトしたコミットを検査するので、文書もコミット済みの状態を
    写していないと必ずずれる。作業ツリーの未コミット分は意図的に見ない。
    """
    out = {}
    files = [f for f in git(repo, "ls-files", f"app/src/{kind}/").splitlines()
             if f.endswith(".kt")]
    for f in files:
        text = git(repo, "show", f"HEAD:{f}")
        n = sum(1 for line in text.splitlines() if TEST_LINE.match(line))
        if n == 0:
            continue
        cls = re.sub(r"^app/src/[a-zA-Z]+/java/com/noxitro/[a-z]+/", "", f)[:-3]
        pkg, _, name = cls.rpartition("/")
        out[cls] = {"package": pkg, "name": name, "tests": n,
                    "live": "@LargeTest" in text}
    return out


def area_of(pkg, repo):
    areas = AREAS[repo]
    best = ""
    for key in areas:
        if pkg == key or pkg.startswith(key + "/"):
            if len(key) > len(best):
                best = key
    return best


def table(inv, repo):
    rows = {}
    for cls, info in sorted(inv.items()):
        a = area_of(info["package"], repo) or info["package"]
        rows.setdefault(a, []).append(info)
    lines = ["| 領域 | 何を守るか | テストクラス | 件数 |", "|---|---|---|---|"]
    for a in sorted(rows, key=lambda k: -sum(i["tests"] for i in rows[k])):
        infos = sorted(rows[a], key=lambda i: -i["tests"])
        total = sum(i["tests"] for i in infos)
        names = "<br>".join(
            (f"`{i['name']}`" + ("**(実サイト接続)**" if i["live"] else "")) for i in infos)
        desc = AREAS[repo].get(a, "—")
        lines.append(f"| `{a}` | {desc} | {names} | {total} |")
    return "\n".join(lines), sum(i["tests"] for i in inv.values())


def build(repo):
    slug = SLUG[repo]
    unit = inventory(repo, "test")
    instr = inventory(repo, "androidTest")
    unit_tbl, unit_total = table(unit, repo)
    instr_tbl, instr_total = table(instr, repo) if instr else ("(このリポジトリにはまだ計装テストが無い)", 0)
    live_total = sum(i["tests"] for i in instr.values() if i["live"])
    unit_floor, instr_floor, instr_kind = CI[repo]
    sha = git(repo, "rev-parse", "--short", "HEAD").strip()
    today = datetime.date.today().isoformat()

    manifest = {
        "schema": 1,
        "repo": repo,
        "generatedAt": today,
        "commit": sha,
        "unit": {"total": unit_total,
                 "classes": {c: i["tests"] for c, i in sorted(unit.items())}},
        "instrumented": {"total": instr_total,
                         "liveExcludedFromCi": live_total,
                         "runInCi": instr_total - live_total,
                         "classes": {c: i["tests"] for c, i in sorted(instr.items())}},
        "ciFloors": {"unit": unit_floor, "instrumented": instr_floor},
    }

    instr_ci_line = (
        f"CI で走るのは {instr_total - live_total} 件"
        + (f"(実サイトに接続する {live_total} 件は既定で除外)" if live_total else "")
    ) if instr_total else "CI では起動スモークだけを走らせる"

    return f'''# テスト方針と一覧 — {repo}

対象コミット `{sha}` / 生成 {today}
この文書は **テストコードから機械的に生成** している。手で数えた値は 1 つも無い。

## この文書の使い方

**何が保証されているかの正本**。「テストが通った」を判断するときは、ここに書かれた
層・件数・下限と、実際の CI 結果を突き合わせる。

判断の材料はこの 3 つで、順に強い。

| 材料 | 取り方 | 何が分かる |
|---|---|---|
| 結果 XML の `tests=` | `app/build/test-results/` と `app/build/outputs/androidTest-results/` | **実際に何件走ったか**。唯一の証明 |
| CI の結論 | `gh run list -R noxitro/{repo} --limit 1` | 直近の push が緑か赤か |
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
| 単体テスト (JVM) | ランナー上、端末不要。1〜3 分 | 解析と計算。HTML パーサ、URL 組み立て、絞り込み、集計 | 画面の描画、DB の実体、DI の組み立て |
| 計装テスト (エミュレータ) | Android 上。6〜18 分 | Room の実体とマイグレーション、Compose の描画と操作、経路の引数 | 実サイトの応答(既定で除外) |
| 起動スモーク | 計装テストの後 | **入れて開いて落ちないこと**。DI の組み立て、初回マイグレーション、権限要求 | 機能の正しさ |

起動スモークを別に持つのは、**計装テストが全部緑でも起動時に落ちる不具合が素通りする**
ため。テストは自分が名指しした物しか守らない。

## 単体テスト({unit_total} 件)

{unit_tbl}

## 計装テスト({instr_total} 件)

{instr_ci_line}。

{instr_tbl}

## CI が何をいつ走らせるか

`.github/workflows/ci.yml`。

| ジョブ | 契機 | 内容 | 下限件数 |
|---|---|---|---|
| 単体テストとビルド | **push のたび**(全ブランチ)・PR・手動 | `testDebugUnitTest` と `assembleDebug` | {unit_floor} |
| {instr_kind} | **main への push**・PR・手動 | {"`connectedDebugAndroidTest` と起動スモーク" if instr_total else "起動スモーク"} | {instr_floor if instr_floor else "—"} |

計装テストを push のたびに走らせないのは、private リポジトリでは実行時間が課金対象で、
エミュレータのジョブが 6〜18 分かかるため。手元の作業ブランチでは単体テストだけが走る。
**任意のタイミングで全部走らせたいときは Actions タブの Run workflow から手動実行する。**

下限件数は実測の約 7 割。数件の増減で赤くせず、「0 件で緑」だけを確実に捕まえる値。

## 成功の判定

次が全部成立したときだけ「問題なし」と言える。1 つでも欠けたら言えない。

1. 単体テストの実行件数が {unit_floor} 件以上で、失敗もエラーも 0
{f"2. 計装テストの実行件数が {instr_floor} 件以上で、失敗もエラーも 0" if instr_floor else "2. (計装テストは未整備)"}
3. 起動スモークで logcat に `FATAL EXCEPTION` が無く、プロセスが生きている
4. この文書とコードがずれていない(`verify-testing-doc.py` が緑)

件数は Actions のサマリに出る。失敗したテスト名も一覧で出る。

## 分かっていない範囲

- **実サイトの応答は CI で見ていない。** 構造が変わればパーサのテストは緑のまま実機で壊れる。
{"  実サイトに接続する `@LargeTest` は不安定なため CI からは外している。手動実行で `live_tests` を true にすれば含められる。" if live_total else "  fixture は取り直しが要る。"}
- **エミュレータ 1 機種でしか見ていない**(API 34 / pixel_6)。画面サイズや API レベルの差は未検証。
- **性能と実機固有の挙動は見ていない。** ジェスチャー、リフレッシュレート、メーカー独自の制約。

<!-- ここから下は機械可読。verify-testing-doc.py が読む。手で書き換えない。 -->
```json
{json.dumps(manifest, ensure_ascii=False, indent=2)}
```
'''

p = ROOT / REPO / "docs"
p.mkdir(exist_ok=True)
io.open(p / "TESTING.md", "w", encoding="utf-8", newline="\n").write(build(REPO))
print(f"{REPO}/docs/TESTING.md を生成した")
