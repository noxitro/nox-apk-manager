#!/usr/bin/env bash
# 起動スモーク。APK を入れて主画面を開き、落ちないことを確かめる。
#
# 計装テストが全部緑でも、起動直後の初期化で落ちる不具合は素通りする
# (DI の組み立て、初回のマイグレーション、権限の要求など)。
# 「機能追加のたびにクラッシュが残っていないか」を見るのがここの役目。
set -euo pipefail

PKG="$1"
ACTIVITY="$2"
APK="${3:-app/build/outputs/apk/debug/app-debug.apk}"

echo "--- APK を入れる: $APK"
adb install -r -t "$APK"

adb shell am force-stop "$PKG" || true
adb logcat -c
echo "--- 起動: $PKG/$ACTIVITY"
adb shell am start -W -n "$PKG/$ACTIVITY"

# 初期化が走り切るまで待つ。落ちるならたいていこの間に落ちる。
sleep 12

echo "--- プロセスの生存を確かめる"
# Android 7 には pidof が無いことがある。そのときのエラー文を PID と取り違えないよう、
# 数字だけを受け取り、取れなければ ps の出力から探す。
PID="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | grep -E '^[0-9 ]+$' || true)"
if [ -z "$PID" ]; then
  PID="$(adb shell ps | tr -d '\r' | awk -v p="$PKG" '$NF == p { print $2 }' | head -1 || true)"
fi

echo "--- 自分のプロセスのログ"
adb logcat -d > logcat-full.txt || true
grep -E "FATAL EXCEPTION|AndroidRuntime.*$PKG|ANR in $PKG" logcat-full.txt > crash.txt || true

if [ -s crash.txt ]; then
  echo "::error::起動中にクラッシュを検出した"
  sed 's/^/    /' crash.txt
  # 前後の文脈も出す
  grep -A 30 "FATAL EXCEPTION" logcat-full.txt | head -60 | sed 's/^/    /' || true
  exit 1
fi

if [ -z "$PID" ]; then
  echo "::error::起動後にプロセスが居ない。クラッシュか、起動自体に失敗している"
  tail -100 logcat-full.txt | sed 's/^/    /'
  exit 1
fi

echo "起動スモーク: OK (pid=$PID、クラッシュ無し)"
