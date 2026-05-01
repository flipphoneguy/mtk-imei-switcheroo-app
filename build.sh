#!/data/data/com.termux/files/usr/bin/bash
set -e

ANDROID_JAR="${HOME}/.android/android.jar"
FRAMEWORK_RES="${HOME}/.android/framework-res.apk"

KEYSTORE="${HOME}/.android/debug.keystore"
KEYSTORE_ALIAS="androiddebugkey"
KEYSTORE_PASS="android"
KEY_PASS="android"

APK_OUT="ImeiSwitcheroo.apk"
BUILD_DIR="build"

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

command -v aapt2     >/dev/null || fail "aapt2 not found"     "pkg install aapt2"
command -v ecj       >/dev/null || fail "ecj not found"       "pkg install ecj"
command -v d8        >/dev/null || fail "d8 not found"        "pkg install d8"
command -v apksigner >/dev/null || fail "apksigner not found" "pkg install apksigner"
command -v zip       >/dev/null || fail "zip not found"       "pkg install zip"
[ -f "$ANDROID_JAR"   ] || fail "android.jar not found at: $ANDROID_JAR"
[ -f "$FRAMEWORK_RES" ] || fail \
    "framework-res.apk not found at: $FRAMEWORK_RES" \
    "cp /system/framework/framework-res.apk ~/.android/"
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
    --min-sdk-version 23 \
    --target-sdk-version 35 \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$BUILD_DIR/resources.zip"

# ── 3. Compile Java ────────────────────────────────────────────────────────
echo "[3/5] Compiling Java..."
find src/ "$BUILD_DIR/gen/" -name "*.java" > "$BUILD_DIR/sources.txt"
ecj \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" \
    @"$BUILD_DIR/sources.txt"

# ── 4. Dex ─────────────────────────────────────────────────────────────────
echo "[4/5] Dexing..."
CLASS_FILES=$(find "$BUILD_DIR/classes" -name "*.class" | tr '\n' ' ')
d8 \
    --output "$BUILD_DIR/dex" \
    --lib "$ANDROID_JAR" \
    --min-api 23 \
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
