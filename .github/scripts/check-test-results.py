#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""JUnit の結果 XML を読み、本当にテストが走ったかを確かめる。

`connectedAndroidTest` も `test` も、**テストが 0 件でも BUILD SUCCESSFUL / exit 0** を返す。
ランナークラスの指定漏れやフィルタの書き間違いで 1 件も走らなくても緑になり、
「通った」と誤読される。緑ではなく結果 XML の `tests=` を見るのが唯一の証明。

使い方:
    check-test-results.py <結果ディレクトリ> --min <最低件数> --label <表示名>
"""
import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("directory")
    ap.add_argument("--min", type=int, default=1, help="これ未満なら失敗にする")
    ap.add_argument("--label", default="テスト")
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(args.directory, "**", "*.xml"), recursive=True))
    if not files:
        print(f"::error::{args.label}: 結果 XML が 1 つも無い ({args.directory})。")
        print("テストが起動していない可能性が高い。BUILD SUCCESSFUL は証明にならない。")
        return 1

    total = failures = errors = skipped = 0
    failed_cases = []
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as e:
            print(f"::error::結果 XML を読めない: {path} ({e})")
            return 1
        suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
        for suite in suites:
            total += int(suite.get("tests", 0))
            failures += int(suite.get("failures", 0))
            errors += int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", 0))
            for case in suite.iter("testcase"):
                for bad in list(case.iter("failure")) + list(case.iter("error")):
                    name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
                    first_line = (bad.text or bad.get("message") or "").strip().split("\n")[0]
                    failed_cases.append(f"{name}: {first_line[:200]}")

    ran = total - skipped
    print(f"{args.label}: 実行 {ran} 件 / 収集 {total} 件 "
          f"(失敗 {failures}, エラー {errors}, スキップ {skipped}) / XML {len(files)} ファイル")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            mark = "❌" if (failures or errors or ran < args.min) else "✅"
            fh.write(f"{mark} **{args.label}** 実行 {ran} / 失敗 {failures} / エラー {errors} / スキップ {skipped}\n\n")
            for case in failed_cases[:20]:
                fh.write(f"- `{case}`\n")
            if len(failed_cases) > 20:
                fh.write(f"- ほか {len(failed_cases) - 20} 件\n")
            fh.write("\n")

    for case in failed_cases:
        print(f"::error::{case}")

    if failures or errors:
        print(f"::error::{args.label}: 失敗 {failures} 件、エラー {errors} 件。")
        return 1
    if ran < args.min:
        print(f"::error::{args.label}: 実行されたのは {ran} 件で、最低 {args.min} 件に足りない。")
        print("フィルタの指定ミスかランナーの設定漏れを疑うこと。0 件の緑は緑ではない。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
