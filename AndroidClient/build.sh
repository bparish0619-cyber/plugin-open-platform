#!/usr/bin/env bash
# Build and sign the MemoMind BLE client APK without Gradle or the Google SDK
# manager. Tools come from Ubuntu/Debian packages:
#   sudo apt-get install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23
# Usage: ./build.sh [test|apk|all]   (default: all)
set -euo pipefail

cd "$(dirname "$0")"
NAME=memomind-ble-client
VERSION_NAME=0.1.0
VERSION_CODE=1
MIN_SDK=23
TARGET_SDK=34
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
OUT=build

run_tests() {
    rm -rf "$OUT/test" && mkdir -p "$OUT/test"
    javac -d "$OUT/test" src/main/java/com/memomind/bleclient/GmCodec.java \
        src/test/java/com/memomind/bleclient/GmCodecTest.java
    python3 -I tools/export_vectors.py > "$OUT/test/vectors.tsv"
    java -cp "$OUT/test" com.memomind.bleclient.GmCodecTest "$OUT/test/vectors.tsv"
}

build_apk() {
    [ -f "$ANDROID_JAR" ] || { echo "android.jar not found: $ANDROID_JAR" >&2; exit 1; }
    rm -rf "$OUT/gen" "$OUT/classes" "$OUT"/*.apk "$OUT/classes.dex"
    mkdir -p "$OUT/gen" "$OUT/classes" dist

    aapt package -f -m -J "$OUT/gen" -M src/main/AndroidManifest.xml -S src/main/res \
        -I "$ANDROID_JAR" --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
        --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" -F "$OUT/unsigned.apk"

    javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
        $(find src/main/java "$OUT/gen" -name '*.java')

    dalvik-exchange --dex --min-sdk-version="$MIN_SDK" --output="$OUT/classes.dex" "$OUT/classes"
    (cd "$OUT" && zip -q unsigned.apk classes.dex)

    zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

    if [ ! -f "$KEYSTORE" ]; then
        mkdir -p "$(dirname "$KEYSTORE")"
        keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
            -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US"
    fi
    apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
        --ks-key-alias androiddebugkey --min-sdk-version "$MIN_SDK" \
        --out "dist/$NAME-$VERSION_NAME.apk" "$OUT/aligned.apk"
    apksigner verify --min-sdk-version "$MIN_SDK" --print-certs "dist/$NAME-$VERSION_NAME.apk"
    rm -f "dist/$NAME-$VERSION_NAME.apk.idsig"
    echo "APK: $(pwd)/dist/$NAME-$VERSION_NAME.apk"
}

case "${1:-all}" in
    test) run_tests ;;
    apk) build_apk ;;
    all) run_tests; build_apk ;;
    *) echo "usage: $0 [test|apk|all]" >&2; exit 2 ;;
esac
