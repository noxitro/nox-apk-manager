#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""docs/TESTING.md がテストコードとずれていないかを検査する。

TESTING.md は「何が保証されているか」の正本として使う。正本が黙って古くなると、
テストを消しても・増やしても気づけず、文書を読んで判断する側(人でも LLM でも)が
実態と違う結論を出す。そうさせないために CI で毎回突き合わせる。

見るもの:
  - 文書に載っているテストクラスが今も存在するか(消えた = 保証が減った)
  - コードにあるテストクラスが文書に載っているか(増えた = 文書が古い)
  - クラスごとの件数が一致するか
  - CI の下限件数が文書とワークフローで一致するか

直し方は生成し直すこと。手で数字を書き換えない。
"""
import json
import os
import pathlib
import re
import subprocess
import sys

TEST_LINE = re.compile(r"\s*@Test(?![A-Za-z0-9_])")
ROOT = pathlib.Path(__file__).resolve().parents[2]


def git(*args) -> str:
    return subprocess.run(["git", "-C", str(ROOT), *args],
                          capture_output=True, text=True, encoding="utf-8").stdout


def inventory(kind: str) -> dict:
    """app/src/<kind> のテストクラスと @Test 件数。**コミット済みの内容**から読む。

    作業ツリーではなく HEAD を見るのは、文書がコミット済みの状態を写しているため。
    未コミットのテストでいちいち赤くしない。CI ではチェックアウト = そのコミットなので同じ。
    """
    out = {}
    files = [f for f in git("ls-files", f"app/src/{kind}/").splitlines()
             if f.endswith(".kt")]
    for f in files:
        text = git("show", f"HEAD:{f}")
        n = sum(1 for line in text.splitlines() if TEST_LINE.match(line))
        if n == 0:
            continue
        cls = re.sub(r"^app/src/[a-zA-Z]+/java/com/noxitro/[a-z]+/", "", f)[:-3]
        out[cls] = n
    return out


def workflow_floors() -> dict:
    wf = ROOT / ".github" / "workflows" / "ci.yml"
    text = wf.read_text(encoding="utf-8")
    floors = {}
    for label, key in (("単体テスト", "unit"), ("計装テスト", "instrumented")):
        m = re.search(rf'--min (\d+) --label "{label}"', text)
        floors[key] = int(m.group(1)) if m else None
    return floors


def main() -> int:
    doc = ROOT / "docs" / "TESTING.md"
    if not doc.exists():
        print(f"::error::{doc} が無い。テストの正本が存在しない。")
        return 1

    m = re.search(r"```json\n(.*?)\n```", doc.read_text(encoding="utf-8"), re.S)
    if not m:
        print("::error::docs/TESTING.md に機械可読ブロック(```json)が無い。")
        return 1
    manifest = json.loads(m.group(1))

    problems = []
    for kind, key in (("test", "unit"), ("androidTest", "instrumented")):
        actual = inventory(kind)
        documented = manifest.get(key, {}).get("classes", {})

        for cls, n in sorted(documented.items()):
            if cls not in actual:
                problems.append(f"{key}: 文書にある `{cls}` ({n} 件) がコードに無い。"
                                f"消したなら文書を生成し直す。")
            elif actual[cls] != n:
                problems.append(f"{key}: `{cls}` の件数が違う。文書 {n} / コード {actual[cls]}。")
        for cls, n in sorted(actual.items()):
            if cls not in documented:
                problems.append(f"{key}: コードにある `{cls}` ({n} 件) が文書に無い。"
                                f"足したなら文書を生成し直す。")

        total = sum(actual.values())
        if total != manifest.get(key, {}).get("total"):
            problems.append(f"{key}: 合計が違う。文書 {manifest.get(key, {}).get('total')} / コード {total}。")

    floors = workflow_floors()
    for key in ("unit", "instrumented"):
        doc_floor = manifest.get("ciFloors", {}).get(key)
        if floors.get(key) != doc_floor:
            problems.append(f"CI の下限件数が違う。文書 {doc_floor} / ci.yml {floors.get(key)} ({key})。")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if problems:
        print(f"::error::docs/TESTING.md がコードとずれている({len(problems)} 件)。")
        for p in problems:
            print(f"::error::{p}")
        if summary:
            with open(summary, "a", encoding="utf-8") as fh:
                fh.write(f"❌ **docs/TESTING.md がコードとずれている**({len(problems)} 件)\n\n")
                for p in problems[:20]:
                    fh.write(f"- {p}\n")
                fh.write("\n")
        return 1

    print(f"docs/TESTING.md はコードと一致している "
          f"(単体 {manifest['unit']['total']} 件 / 計装 {manifest['instrumented']['total']} 件)")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(f"✅ **docs/TESTING.md** コードと一致 "
                     f"(単体 {manifest['unit']['total']} / 計装 {manifest['instrumented']['total']})\n\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
