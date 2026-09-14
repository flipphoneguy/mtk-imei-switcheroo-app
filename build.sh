#!/usr/bin/env bash
set -e

# Toolchain: Termux defaults, or auto-detected from an Android SDK. Override any via env.
ANDROID_JAR="${ANDROID_JAR:-${HOME}/.android/android.jar}"
FRAMEWORK_RES="${FRAMEWORK_RES:-${HOME}/.android/framework-res.apk}"

KEYSTORE="${KEYSTORE:-${HOME}/.android/debug.keystore}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-androiddebugkey}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
KEY_PASS="${KEY_PASS:-android}"

APK_OUT="ImeiSwitcheroo.apk"
BUILD_DIR="build"
MIN_SDK=23
TARGET_SDK=35

# Desktop Android SDK: newest build-tools onto PATH, platform android.jar for both jars.
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${HOME}/Android/Sdk}}"
if [ -d "$SDK_ROOT" ]; then
    BT_DIR="$(ls -d "$SDK_ROOT"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
    [ -n "$BT_DIR" ] && export PATH="${BT_DIR%/}:$PATH"
    PLATFORM_JAR="$SDK_ROOT/platforms/android-$TARGET_SDK/android.jar"
    [ -f "$PLATFORM_JAR" ] || PLATFORM_JAR="$(ls "$SDK_ROOT"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
    [ -f "$ANDROID_JAR" ]   || ANDROID_JAR="$PLATFORM_JAR"
    [ -f "$FRAMEWORK_RES" ] || FRAMEWORK_RES="$ANDROID_JAR"
fi

# Java compiler: ecj (Termux), else javac from PATH / JAVA_HOME / Android Studio's JBR.
JAVAC=""
if command -v ecj >/dev/null; then JAVAC=ecj
elif command -v javac >/dev/null; then JAVAC=javac
elif [ -x "${JAVA_HOME:-/nonexistent}/bin/javac" ]; then JAVAC="$JAVA_HOME/bin/javac"
else
    for cand in /snap/android-studio/current/jbr/bin/javac /snap/android-studio/*/jbr/bin/javac \
                /opt/android-studio/jbr/bin/javac \
                "$HOME"/.local/share/JetBrains/Toolbox/apps/android-studio/jbr/bin/javac; do
        [ -x "$cand" ] && { JAVAC="$cand"; break; }
    done
fi
# d8/apksigner exec `java`, so put the chosen JDK on PATH.
case "$JAVAC" in
    */bin/javac) export JAVA_HOME="$(dirname "$(dirname "$JAVAC")")"; export PATH="$(dirname "$JAVAC"):$PATH" ;;
esac

# Read version from VERSION file (used for versionName)
VERSION_FILE="VERSION"
if [ -f "$VERSION_FILE" ]; then
    VERSION_NAME="$(cat "$VERSION_FILE" | tr -d '[:space:]')"
else
    VERSION_NAME="1.0.0"
fi
# Convert "1.2.3" -> versionCode "10203"
VERSION_CODE=$(echo "$VERSION_NAME" | awk -F. '{ printf "%d%02d%02d", $1,$2,$3 }')

# ── Sanity checks ──────────────────────────────────────────────────────────
fail() { echo "✗ $1"; [ -n "$2" ] && echo "  $2"; exit 1; }

command -v aapt2     >/dev/null || fail "aapt2 not found"     "pkg install aapt2, or install Android SDK build-tools"
[ -n "$JAVAC" ]                 || fail "no Java compiler found" "pkg install ecj, or install a JDK / Android Studio"
command -v d8        >/dev/null || fail "d8 not found"        "pkg install d8, or install Android SDK build-tools"
command -v apksigner >/dev/null || fail "apksigner not found" "pkg install apksigner, or install Android SDK build-tools"
command -v zip       >/dev/null || fail "zip not found"       "pkg install zip"
[ -f "$ANDROID_JAR"   ] || fail "android.jar not found at: $ANDROID_JAR" "set ANDROID_JAR=..., or install an SDK platform"
[ -f "$FRAMEWORK_RES" ] || fail \
    "framework-res.apk not found at: $FRAMEWORK_RES" \
    "cp /system/framework/framework-res.apk ~/.android/ (Termux), or set FRAMEWORK_RES=..."
[ -f "$KEYSTORE"      ] || fail "Keystore not found at: $KEYSTORE"

echo "════════════════════════════════════"
echo "  Building ImeiSwitcheroo v${VERSION_NAME} (code ${VERSION_CODE})"
echo "════════════════════════════════════"

# ── 0. Sync AndroidManifest.xml versions from VERSION ──────────────────────
MANIFEST="AndroidManifest.xml"
if [ -f "$MANIFEST" ]; then
    sed -i \
        -e "s@android:versionCode=\"[0-9][0-9]*\"@android:versionCode=\"${VERSION_CODE}\"@" \
        -e "s@android:versionName=\"[^\"]*\"@android:versionName=\"${VERSION_NAME}\"@" \
        "$MANIFEST"
fi

# ── 0b. Clean ──────────────────────────────────────────────────────────────
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex"

# ── 1. Compile resources ───────────────────────────────────────────────────
echo "[1/5] Compiling resources..."
aapt2 compile --dir res/ -o "$BUILD_DIR/resources.zip"

# ── 2. Link resources ──────────────────────────────────────────────────────
echo "[2/5] Linking resources..."
aapt2 link \
    -o "$BUILD_DIR/app_res.apk" \
    --manifest AndroidManifest.xml \
    -I "$FRAMEWORK_RES" \
    --java "$BUILD_DIR/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$BUILD_DIR/resources.zip"

# ── 3. Compile Java ────────────────────────────────────────────────────────
echo "[3/5] Compiling Java..."
find src/ "$BUILD_DIR/gen/" -name "*.java" > "$BUILD_DIR/sources.txt"
if [ "$JAVAC" = ecj ]; then
    ecj -cp "$ANDROID_JAR" -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
else
    # Java 8 bytecode; -Xlint:-options mutes the "source 8 obsolete" notice.
    "$JAVAC" -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" \
        -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
fi

# ── 4. Dex ─────────────────────────────────────────────────────────────────
echo "[4/5] Dexing..."
CLASS_FILES=$(find "$BUILD_DIR/classes" -name "*.class" | tr '\n' ' ')
d8 \
    --output "$BUILD_DIR/dex" \
    --lib "$ANDROID_JAR" \
    --min-api "$MIN_SDK" \
    $CLASS_FILES

# ── 5. Pack + sign ─────────────────────────────────────────────────────────
echo "[5/5] Packaging and signing..."
cp "$BUILD_DIR/app_res.apk" "$BUILD_DIR/app_unsigned.apk"
(cd "$BUILD_DIR/dex" && zip -j "../app_unsigned.apk" classes.dex)

apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEYSTORE_ALIAS" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --out "$APK_OUT" \
    "$BUILD_DIR/app_unsigned.apk"

rm -f "${APK_OUT}.idsig"

echo ""
echo "════════════════════════════════════"
echo "  ✓  ${APK_OUT}"
echo "════════════════════════════════════"
SIZE=$(stat -c%s "$APK_OUT" 2>/dev/null || stat -f%z "$APK_OUT")
echo "Size: $((SIZE/1024)) KB"
echo "Install via ADB:   adb install -r ${APK_OUT}"
echo "Install locally:   cp ${APK_OUT} /sdcard/"
