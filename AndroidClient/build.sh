#!/usr/bin/env bash
# Build and sign the MemoMind BLE client APK without Gradle or the Google SDK
# manager. Tools come from Ubuntu/Debian packages:
#   sudo apt-get install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23
#
# Usage: ./build.sh [test|apk|all]   (default: all)
#
# Environment overrides:
#   ANDROID_JAR      API 23 stub jar used to compile Java (public API only).
#   FRAMEWORK_JAR    Jar with API 34 framework resources for linking the manifest
#                    (needed for android:usesPermissionFlags). Downloaded once from
#                    Maven Central (Robolectric android-all, Apache 2.0) if unset.
#   KEYSTORE         Signing keystore; defaults to ~/.android/debug.keystore,
#                    created on first use.
#   KEYSTORE_PASS, KEY_ALIAS, KEY_PASS   Signing credentials (debug defaults).
set -euo pipefail

cd "$(dirname "$0")"
NAME=memomind-ble-client
VERSION_NAME=0.2.0
VERSION_CODE=2
MIN_SDK=23
TARGET_SDK=34
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
FRAMEWORK_URL=https://repo.maven.apache.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
FRAMEWORK_SHA256=6be2218c6a53fe3c57bc22ebdc723edcb7270a8a6f187545708aa5c0ed813977
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
export KEYSTORE_PASS=${KEYSTORE_PASS:-android}
export KEY_PASS=${KEY_PASS:-$KEYSTORE_PASS}
KEY_ALIAS=${KEY_ALIAS:-androiddebugkey}
OUT=build

# Desktop JVM tests: codec checks plus every reference vector from bluetooth_wire.py.
run_tests() {
    rm -rf "$OUT/test" && mkdir -p "$OUT/test"
    javac -d "$OUT/test" src/main/java/com/memomind/bleclient/GmCodec.java \
        src/test/java/com/memomind/bleclient/GmCodecTest.java
    python3 -I tools/export_vectors.py > "$OUT/test/vectors.tsv"
    java -cp "$OUT/test" com.memomind.bleclient.GmCodecTest "$OUT/test/vectors.tsv"
}

# Resolve the framework-resource jar, downloading and verifying it if needed.
framework_jar() {
    if [ -n "${FRAMEWORK_JAR:-}" ]; then echo "$FRAMEWORK_JAR"; return; fi
    local cache="${XDG_CACHE_HOME:-$HOME/.cache}/$NAME/android-all-34.jar"
    if [ ! -f "$cache" ] || ! echo "$FRAMEWORK_SHA256  $cache" | sha256sum -c --quiet - 2>/dev/null; then
        mkdir -p "$(dirname "$cache")"
        echo "Downloading framework resources (137 MB, once) ..." >&2
        curl -fsSL --retry 5 --retry-delay 15 -o "$cache.tmp" "$FRAMEWORK_URL"
        echo "$FRAMEWORK_SHA256  $cache.tmp" | sha256sum -c --quiet - >&2
        mv "$cache.tmp" "$cache"
    fi
    echo "$cache"
}

build_apk() {
    [ -f "$ANDROID_JAR" ] || { echo "android.jar not found: $ANDROID_JAR" >&2; exit 1; }
    local framework
    framework=$(framework_jar)
    rm -rf "$OUT/gen" "$OUT/classes" "$OUT"/*.apk "$OUT/classes.dex" dist
    mkdir -p "$OUT/gen" "$OUT/classes" dist

    # 1. Compile manifest and resources; generate R.java.
    aapt package -f -m -J "$OUT/gen" -M src/main/AndroidManifest.xml -S src/main/res \
        -I "$framework" --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
        --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" -F "$OUT/unsigned.apk"

    # 2. Compile Java 8 bytecode against the public API 23 stubs.
    javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
        $(find src/main/java "$OUT/gen" -name '*.java')

    # 3. Convert to DEX and add it to the APK.
    dalvik-exchange --dex --min-sdk-version="$MIN_SDK" --output="$OUT/classes.dex" "$OUT/classes"
    (cd "$OUT" && zip -q unsigned.apk classes.dex)

    # 4. Align, then sign (v1-v3) and verify.
    zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
    if [ ! -f "$KEYSTORE" ]; then
        mkdir -p "$(dirname "$KEYSTORE")"
        keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -keypass "$KEY_PASS" \
            -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US"
    fi
    apksigner sign --ks "$KEYSTORE" --ks-pass env:KEYSTORE_PASS --key-pass env:KEY_PASS \
        --ks-key-alias "$KEY_ALIAS" --min-sdk-version "$MIN_SDK" \
        --out "dist/$NAME-$VERSION_NAME.apk" "$OUT/aligned.apk"
    apksigner verify --min-sdk-version "$MIN_SDK" --print-certs "dist/$NAME-$VERSION_NAME.apk"
    rm -f "dist/$NAME-$VERSION_NAME.apk.idsig"
    echo "APK: $(pwd)/dist/$NAME-$VERSION_NAME.apk"
}

case "${1:-all}" in
    test) run_tests ;;
    apk) build_apk ;;
    all) run_tests; build_apk ;;
    version) echo "$VERSION_NAME" ;;
    *) echo "usage: $0 [test|apk|all|version]" >&2; exit 2 ;;
esac
