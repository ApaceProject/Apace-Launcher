#!/bin/bash
# Apace Launcher — signed APK build without Gradle (JDK 17 + Android build-tools + platform-28).
# Works locally and in GitHub Actions. Everything can be overridden via env:
#   PROJ, JDK (or JAVA_HOME), SDK (or ANDROID_HOME/ANDROID_SDK_ROOT), BT, ANDROID_JAR, OUT
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJ=${PROJ:-$SCRIPT_DIR/../apacemobile}
JDK=${JDK:-${JAVA_HOME:-/home/z/my-project/work/jdk17}}
SDK=${SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/z/my-project/work/android-sdk}}}
BUILD=$PROJ/build
KEYSTORE=$PROJ/keystore/apacemobile.jks
OUT=${OUT:-$BUILD/ApaceLauncher.apk}

# build-tools dir: env BT > $SDK/build-tools/* (newest) > legacy $SDK/android-13
if [ -z "${BT:-}" ]; then
  if [ -d "$SDK/build-tools" ] && ls "$SDK/build-tools"/*/aapt >/dev/null 2>&1; then
    BT=$(ls -d "$SDK/build-tools"/* | sort -V | tail -1)
  else
    BT=$SDK/android-13
  fi
fi
# platform android.jar: env ANDROID_JAR > $SDK/platforms/android-28 > legacy $SDK/android-9
if [ -z "${ANDROID_JAR:-}" ]; then
  if [ -f "$SDK/platforms/android-28/android.jar" ]; then
    ANDROID_JAR=$SDK/platforms/android-28/android.jar
  else
    ANDROID_JAR=$SDK/android-9/android.jar
  fi
fi

echo "PROJ=$PROJ"
echo "JDK=$JDK  SDK=$SDK"
echo "BT=$BT"
echo "ANDROID_JAR=$ANDROID_JAR"

export PATH="$JDK/bin:$PATH"

rm -rf "$BUILD"
mkdir -p "$BUILD/obj" "$BUILD/dex" "$PROJ/keystore"

echo "── [1/6] javac ──"
rm -rf "$BUILD/gen"; mkdir -p "$BUILD/gen"
"$BT/aapt" package -f -m -J "$BUILD/gen" -M "$PROJ/AndroidManifest.xml" \
  -S "$PROJ/res" -I "$ANDROID_JAR"
find "$PROJ/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JDK/bin/javac" -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d "$BUILD/obj" @"$BUILD/sources.txt"

echo "── [2/6] aapt (manifest + resources + assets) ──"
"$BT/aapt" package -f -M "$PROJ/AndroidManifest.xml" \
  -S "$PROJ/res" -A "$PROJ/assets" -I "$ANDROID_JAR" \
  -F "$BUILD/base.apk"

echo "── [3/6] d8 (classes.dex) ──"
find "$BUILD/obj" -name '*.class' > "$BUILD/classes.txt"
"$JDK/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" @"$BUILD/classes.txt"
cp "$BUILD/dex/classes.dex" "$BUILD/classes.dex"
cd "$BUILD" && "$BT/aapt" add base.apk classes.dex >/dev/null

echo "── [4/6] zipalign ──"
"$BT/zipalign" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "── [5/6] keystore + apksigner ──"
if [ ! -f "$KEYSTORE" ]; then
  "$JDK/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias apace \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass apacemobile -keypass apacemobile \
    -dname "CN=Apace Launcher, OU=ApaceLauncher, O=ApaceLauncher, C=PL"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:apacemobile --key-pass pass:apacemobile \
  --out "$OUT" "$BUILD/aligned.apk"

echo "── [6/6] verify ──"
"$BT/apksigner" verify --print-certs "$OUT" | head -6
"$BT/aapt" dump badging "$OUT" | head -6
ls -la "$OUT"
echo "BUILD OK: $OUT"
