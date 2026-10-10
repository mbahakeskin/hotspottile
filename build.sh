#!/usr/bin/env bash
# Gradle'siz derleme: aapt2 (kaynaklar + R.java) -> javac -> d8 (dex) -> aapt2 (paketle) -> zipalign -> apksigner.
# Kullanim: ./build.sh            (derle; DEBUG_MODE=1 ile debuggable, run-as icin)
#           ./build.sh install    (derle + adb install -r + adb pm grant WRITE_SECURE_SETTINGS)
set -euo pipefail
cd "$(dirname "$0")"

SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/36.1.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
PKG="com.mbaha.hotspottile"

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/res-compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== kaynaklari derle (aapt2 compile) =="
find app/res -type f | while read -r f; do
  "$AAPT2" compile "$f" -o "$OUT/res-compiled"
done

echo "== baglantila (R.java + temel apk) =="
"$AAPT2" link \
  -I "$PLATFORM" \
  --manifest app/AndroidManifest.xml \
  -o "$OUT/base.apk" \
  --java "$OUT/gen" \
  --min-sdk-version 33 --target-sdk-version 36 ${DEBUG_MODE:+--debug-mode} \
  --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-0.1.0}" \
  "$OUT"/res-compiled/*.flat

echo "== java derle =="
# stubs/: SDK'nin gizledigi (@SystemApi) tethering arayuzlerinin derleme-zamani bildirimleri.
# Calisma zamaninda bunlarin yerine cihazin GERCEK sistem siniflari (ayni paket/isim) yuklenir.
SRC=$(find app/src stubs "$OUT/gen" -name "*.java")
javac -encoding UTF-8 --release 11 \
  -cp "$PLATFORM" \
  -d "$OUT/classes" \
  $SRC

echo "== dex =="
"$D8" --lib "$PLATFORM" --output "$OUT/dex" $(find "$OUT/classes" -name "*.class")

echo "== apk paketle =="
cp "$OUT/base.apk" "$OUT/app.unsigned.apk"
( cd "$OUT/dex" && zip -q -X "../app.unsigned.apk" classes.dex )

echo "== hizala + imzala =="
KS=keystore/local.jks
if [ ! -f "$KS" ]; then
  mkdir -p keystore
  keytool -genkeypair -v -keystore "$KS" -storepass hotspottile -keypass hotspottile \
    -alias local -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=hotspottile, OU=local, O=local, L=local, S=local, C=TR" >/dev/null
fi
"$ZIPALIGN" -f -p 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:hotspottile --key-pass pass:hotspottile \
  --out "$OUT/app.apk" "$OUT/app.aligned.apk"

echo "== tamam: $OUT/app.apk =="

if [ "${1:-}" = "install" ]; then
  echo "== kuruluyor =="
  adb install -r -g "$OUT/app.apk"
  echo "== WRITE_SECURE_SETTINGS veriliyor (tek seferlik) =="
  adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
  echo "== kuruldu =="
fi
