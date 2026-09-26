#!/usr/bin/env python3
"""ビルド済みの APK を Google Drive の builds/<project>/ に置く(GitHub Actions の CD 用)。

nox-apk-manager の scripts/publish-apk.ps1 と同じ規約で置く:

    builds/<project>/<project>-<versionName>-<variant>.apk
    builds/<project>/meta.json   … package 名・版・variant・sha256・説明
    builds/<project>/icon.png    … 任意。未インストールの行に出す

publish-apk.ps1 はフォルダの APK を全部読み直して meta.json を作るが、CI では古い APK を
ダウンロードしたくないので、Drive にある meta.json に新しい版を足して書き直す。
Drive へのアクセスは rclone(リモートの場所は --remote か環境変数 NOX_DRIVE_BUILDS)。
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

# builds/ 直下のフォルダ名。Drive のパスと APK のファイル名に使うので、区切り文字などは許さない
PROJECT_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


def fail(message: str) -> None:
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def find_build_tool(name: str) -> str:
    """Android SDK の build-tools から一番新しいものを選ぶ(環境変数 AAPT2 / APKSIGNER で指定もできる)。"""
    if os.environ.get(name.upper()):
        return os.environ[name.upper()]
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        fail(f"ANDROID_HOME が設定されていない({name} を探せない)")
    tools = sorted(
        (p for p in (Path(sdk) / "build-tools").iterdir() if (p / name).exists()),
        key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)],
    )
    if not tools:
        fail(f"{name} が build-tools に無い: {sdk}")
    return str(tools[-1] / name)


def read_badging(apk: Path) -> dict:
    """APK 自身から package 名・版・既定のアプリ名を読む(引数で嘘を書けない)。"""
    out = subprocess.run(
        [find_build_tool("aapt2"), "dump", "badging", str(apk)], capture_output=True, text=True, encoding="utf-8"
    )
    if out.returncode != 0:
        fail(f"aapt2 dump badging に失敗: {apk}\n{out.stderr}")
    package = next((line for line in out.stdout.splitlines() if line.startswith("package:")), None)
    if package is None:
        fail(f"package 行が無い: {apk}")

    def attr(key: str) -> str | None:
        m = re.search(rf"\b{key}='([^']*)'", package)
        return m.group(1) if m else None

    label = re.search(r"^application-label:'([^']*)'", out.stdout, re.MULTILINE)
    return {
        "packageName": attr("name"),
        "versionCode": int(attr("versionCode") or 0),
        "versionName": attr("versionName"),
        "label": label.group(1) if label else None,
    }


def signer_sha256(apk: Path) -> str:
    """署名の証明書の SHA-256。署名されていない APK は端末に入れられないので止める。"""
    out = subprocess.run(
        [find_build_tool("apksigner"), "verify", "--print-certs", str(apk)],
        capture_output=True, text=True, encoding="utf-8",
    )
    digest = re.search(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", out.stdout)
    if out.returncode != 0 or digest is None:
        fail(f"APK が署名されていない(端末に入れられない): {apk}\n{out.stderr or out.stdout}")
    return digest.group(1).lower()


def normalize_digest(value: str) -> str:
    return re.sub(r"[\s:]", "", value).lower()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def rclone(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    cmd = [os.environ.get("RCLONE", "rclone"), *args]
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    if check and result.returncode != 0:
        fail(f"rclone {' '.join(args)} に失敗:\n{result.stderr}")
    return result


def list_remote(remote_dir: str) -> set[str]:
    """フォルダのファイル名。まだフォルダが無ければ空。"""
    result = rclone("lsjson", "--files-only", "--no-modtime", "--no-mimetype", remote_dir, check=False)
    if result.returncode == 3:  # directory not found
        return set()
    if result.returncode != 0:
        fail(f"Drive のフォルダを読めない: {remote_dir}\n{result.stderr}")
    return {entry["Name"] for entry in json.loads(result.stdout or "[]")}


def iso(timestamp: float | None = None) -> str:
    when = dt.datetime.fromtimestamp(timestamp, dt.timezone.utc) if timestamp else dt.datetime.now(dt.timezone.utc)
    return when.isoformat()


def build_meta(project: str, info: dict, entry: dict, existing: dict | None, remote_files: set[str],
               description: str | None) -> dict:
    """既存の meta.json に新しいビルドを足す。Drive から消えたファイルの行は落とす。"""
    old_builds = (existing or {}).get("builds") or []
    builds = [b for b in old_builds if b.get("file") in remote_files and b.get("file") != entry["file"]]
    builds.append(entry)
    builds.sort(key=lambda b: b["file"].lower())

    known = {b["file"] for b in builds}
    for name in sorted(remote_files):
        if name.lower().endswith(".apk") and name not in known:
            # manager はファイル名から版を推定して一覧に出すので、ここでは載せないだけにする
            print(f"::warning::meta.json に無い APK は載せない(ファイル名から推定される): {name}")

    return {
        "schema": 1,
        "project": project,
        "packageName": info["packageName"],
        "label": info["label"] or (existing or {}).get("label"),
        "description": description if description else (existing or {}).get("description") or "",
        "builds": builds,
        "updatedAt": iso(),
    }


def existing_file(value: str | None, what: str) -> Path | None:
    if not value:
        return None
    path = Path(value)
    if not path.is_file():
        fail(f"{what}が無い: {path}")
    return path


def summary(lines: list[str]) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project", required=True, help="builds/ 直下のフォルダ名。APK のファイル名にも使う")
    parser.add_argument("--apk", required=True, help="置く APK")
    parser.add_argument("--variant", default="release", choices=["release", "debug"])
    parser.add_argument("--description-file", help="meta.json の description にする文章(UTF-8)。省くと Drive の説明を残す")
    parser.add_argument("--icon", help="一覧に出すアイコンの PNG")
    parser.add_argument("--expect-cert", help="署名の証明書の SHA-256。APK の署名がこれと違えば置かない")
    parser.add_argument("--remote", default=os.environ.get("NOX_DRIVE_BUILDS", "gdrive:builds"),
                        help="rclone で見た builds/ の場所(既定: gdrive:builds)")
    parser.add_argument("--force", action="store_true", help="同じ versionCode の版が Drive にあっても置き直す")
    parser.add_argument("--dry-run", action="store_true", help="meta.json を表示するだけで Drive には書かない")
    args = parser.parse_args()

    project = args.project
    if not PROJECT_NAME.match(project):
        fail(f"--project に使えない名前(英数字と . _ - だけ): {project}")
    apk = Path(args.apk)
    if not apk.is_file():
        fail(f"APK が無い(ビルドの出力先を確かめる): {apk}")
    description_file = existing_file(args.description_file, "説明文のファイル")
    icon = existing_file(args.icon, "アイコン")

    info = read_badging(apk)
    if not info["packageName"] or not info["versionName"] or re.search(r"[\\/]", info["versionName"]):
        fail(f"APK の package 名か versionName が読めない(ファイル名にできない): {info}")
    signer = signer_sha256(apk)
    if args.expect_cert and normalize_digest(args.expect_cert) != signer:
        fail(f"APK の署名が違う(端末の版に上書きできない): APK {signer} / 期待 {normalize_digest(args.expect_cert)}")

    dest_name = f"{project}-{info['versionName']}-{args.variant}.apk"
    remote_dir = f"{args.remote.rstrip('/')}/{project}"
    remote_files = list_remote(remote_dir)

    existing = None
    if "meta.json" in remote_files:
        text = rclone("cat", f"{remote_dir}/meta.json").stdout
        try:
            existing = json.loads(text)
        except json.JSONDecodeError:
            print("::warning::Drive の meta.json が壊れているので作り直す")

    if existing and existing.get("packageName") not in (None, info["packageName"]):
        fail(f"同じフォルダに別 package の APK は置けない: {existing.get('packageName')} / {info['packageName']}")

    published = [
        b for b in (existing or {}).get("builds") or []
        if b.get("variant") == args.variant and b.get("file") in remote_files
        and int(b.get("versionCode") or 0) >= info["versionCode"]
    ]
    if published and not args.force:
        latest = max(published, key=lambda b: int(b.get("versionCode") or 0))
        message = (f"{project} {info['versionName']}(versionCode {info['versionCode']})は置かない: "
                   f"Drive に versionCode {latest['versionCode']} の {latest['file']} がある。"
                   "配布するときは versionCode を上げる")
        print(message)
        summary([f"### {project}: 置かなかった", "", message])
        return 0

    entry = {
        "file": dest_name,
        "variant": args.variant,
        "versionName": info["versionName"],
        "versionCode": info["versionCode"],
        "size": apk.stat().st_size,
        "sha256": sha256(apk),
        "builtAt": iso(apk.stat().st_mtime),
    }
    # Windows で書いたファイルの BOM は落とす
    description = description_file.read_text(encoding="utf-8-sig").strip() if description_file else None
    meta = build_meta(project, info, entry, existing, remote_files | {dest_name}, description)
    meta_text = json.dumps(meta, ensure_ascii=False, indent=2) + "\n"

    if args.dry_run:
        print(meta_text)
        return 0

    # APK を先に置く(meta.json が無いファイルを指さないように)
    rclone("copyto", str(apk), f"{remote_dir}/{dest_name}")
    with tempfile.TemporaryDirectory() as tmp:
        meta_path = Path(tmp) / "meta.json"
        meta_path.write_text(meta_text, encoding="utf-8")  # BOM 無し(manager は BOM があると読めない)
        rclone("copyto", str(meta_path), f"{remote_dir}/meta.json")
    if icon:
        rclone("copyto", "--checksum", str(icon), f"{remote_dir}/icon.png")

    print(f"placed: {remote_dir}/{dest_name} ({info['packageName']} v{info['versionName']} code {info['versionCode']})")
    summary([
        f"### {project}: {info['versionName']}(versionCode {info['versionCode']})を置いた",
        "",
        f"- `{remote_dir}/{dest_name}`({entry['size'] / 1_000_000:.1f} MB)",
        f"- sha256 `{entry['sha256']}`",
        f"- 署名 SHA-256 `{signer}`",
        "- nox-apk-manager を開くと更新として出る",
    ])
    return 0


if __name__ == "__main__":
    sys.exit(main())
