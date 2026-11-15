#!/bin/bash
# Mac 构建脚本，步骤和 build.ps1 一样。Windows 用 build.ps1。
set -euo pipefail

# JDK 必须是 17：build-tools 34.0.0 的 d8 8.2.2 读到 JDK 21 javac 生成的匿名类 MethodParameters 属性会 NPE。
ROOT="$(cd "$(dirname "$0")" && pwd)"
JDK=/opt/homebrew/opt/openjdk@17
SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
OUT="$ROOT/build"

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$BT:$PATH"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/7] 生成 cookie.txt"
raw="$(tr -d '\r\n' < "$ROOT/../.cookie" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
uin="$(printf '%s' "$raw" | tr ';' '\n' | sed -n 's/^[[:space:]]*uin=//p')"
skey="$(printf '%s' "$raw" | tr ';' '\n' | sed -n 's/^[[:space:]]*qm_keyst=//p')"
[ -n "$uin" ] && [ -n "$skey" ] || { echo ".cookie 里找不到 uin 或 qm_keyst"; exit 1; }
mkdir -p "$ROOT/assets"
# printf 不写 BOM
printf '%s\n%s\n%s\n' "$raw" "$uin" "$skey" > "$ROOT/assets/cookie.txt"

echo "[2/7] 版本号"
code=$(( $(sed -n 1p "$ROOT/version.txt" | tr -d '\r') + 1 ))
name="$(sed -n 2p "$ROOT/version.txt" | tr -d '\r[:space:]')"
# version.txt 在仓库里是 CRLF，保持一致
printf '%s\r\n%s\r\n' "$code" "$name" > "$ROOT/version.txt"
echo "      versionCode=$code versionName=$name"

echo "[3/7] 编译资源"
aapt2 compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "[4/7] 链接资源"
# --replace-version 不能去掉：不加的话 aapt2 只在 manifest 没有版本号时才注入 --version-code
aapt2 link -o "$OUT/base.apk" -I "$AJAR" \
    --manifest "$ROOT/AndroidManifest.xml" --java "$OUT/gen" \
    -A "$ROOT/assets" --replace-version --version-code "$code" --version-name "$name" "$OUT/res.zip"

echo "[5/7] 编译 Java"
find "$ROOT/src" "$OUT/gen" -name '*.java' > "$OUT/srcs.txt"
javac -encoding UTF-8 -nowarn -source 8 -target 8 -classpath "$AJAR" -d "$OUT/classes" @"$OUT/srcs.txt"

echo "[6/7] 转 dex 并打包"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
d8 --lib "$AJAR" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt"
(cd "$OUT/dex" && zip -q "$OUT/base.apk" classes.dex)

echo "[7/7] 签名"
apksigner sign --ks "$ROOT/qmix.jks" --ks-pass pass:qmix123 \
    --key-pass pass:qmix123 --out "$OUT/qmix.apk" "$OUT/base.apk"

ls -l "$OUT/qmix.apk"
