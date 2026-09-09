#!/usr/bin/env bash
#
# Builds ImeiSwitcheroo.apk without Gradle.
#
# Runs on:
#   • Termux            tools from `pkg install aapt2 ecj d8 apksigner zip openjdk-17`,
#                       android.jar + framework-res.apk in ~/.android
#   • Ubuntu / Linux    tools from an Android SDK (build-tools + platforms, e.g. installed
#                       by Android Studio) and any JDK (system, ~/jdks, or Studio's bundled one)
#
# Everything is auto-detected. Override with environment variables when needed:
#   ANDROID_HOME    SDK root (also honours ANDROID_SDK_ROOT; probes ~/Android/Sdk, /opt/android-sdk, ...)
#   BUILD_TOOLS     directory holding aapt2 / d8 / apksigner (default: newest $ANDROID_HOME/build-tools/*)
#   AAPT2, D8, APKSIGNER   individual tool paths
#   ANDROID_JAR     platform jar to compile against (default: platforms/android-$TARGET_SDK, else ~/.android/android.jar)
#   FRAMEWORK_RES   framework resources for `aapt2 link` (default: ~/.android/framework-res.apk if present, else ANDROID_JAR)
#   JAVA_HOME       JDK used to run d8 / apksigner (and javac)
#   JAVAC           Java compiler (default: ecj if installed, else javac)
#   KEYSTORE, KEYSTORE_ALIAS, KEYSTORE_PASS, KEY_PASS   signing key (default: the Android debug key,
#                   ~/.android/debug.keystore, generated with keytool if missing)
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

APK_OUT="ImeiSwitcheroo.apk"
BUILD_DIR="build"
MIN_SDK=23
TARGET_SDK=35

DEFAULT_KEYSTORE="${HOME}/.android/debug.keystore"
KEYSTORE="${KEYSTORE:-$DEFAULT_KEYSTORE}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-androiddebugkey}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
KEY_PASS="${KEY_PASS:-android}"

# Read version from VERSION file (used for versionName)
VERSION_FILE="VERSION"
if [ -f "$VERSION_FILE" ]; then
    VERSION_NAME="$(tr -d '[:space:]' < "$VERSION_FILE")"
else
    VERSION_NAME="1.0.0"
fi
# Convert "1.2.3" -> versionCode "10203"
VERSION_CODE=$(echo "$VERSION_NAME" | awk -F. '{ printf "%d%02d%02d", $1,$2,$3 }')

# ── Platform / toolchain detection ─────────────────────────────────────────
if [ -n "${TERMUX_VERSION:-}" ] || [ -d /data/data/com.termux/files/usr ]; then
    ON_TERMUX=1
else
    ON_TERMUX=0
fi

fail() { echo "✗ $1"; [ -n "${2:-}" ] && echo "  $2"; exit 1; }

# hint <what>: install suggestion for the current platform
hint() {
    case "$1:$ON_TERMUX" in
        tools:1) echo "pkg install aapt2 d8 apksigner" ;;
        tools:0) echo "install Android SDK build-tools (Android Studio ▸ SDK Manager, or: sdkmanager \"build-tools;${TARGET_SDK}.0.0\") and set ANDROID_HOME, or set BUILD_TOOLS" ;;
        java:1)  echo "pkg install openjdk-17" ;;
        java:0)  echo "sudo apt install default-jdk   (or set JAVA_HOME; Android Studio's bundled JDK is auto-detected)" ;;
        javac:1) echo "pkg install ecj" ;;
        javac:0) echo "sudo apt install default-jdk   (or set JAVAC)" ;;
        zip:1)   echo "pkg install zip" ;;
        zip:0)   echo "sudo apt install zip" ;;
        jar:1)   echo "put android.jar in ~/.android/ (or set ANDROID_JAR)" ;;
        jar:0)   echo "install \"platforms;android-${TARGET_SDK}\" via SDK Manager and set ANDROID_HOME (or set ANDROID_JAR)" ;;
    esac
}

# newest_subdir <parent> [<preferred>]: <parent>/<preferred> if it exists, else the highest
# version-sorted subdirectory of <parent>
newest_subdir() {
    local parent="$1" preferred="${2:-}" best
    [ -d "$parent" ] || return 1
    if [ -n "$preferred" ] && [ -d "$parent/$preferred" ]; then
        echo "$parent/$preferred"
        return 0
    fi
    best="$(printf '%s\n' "$parent"/*/ | sort -V | tail -n1)"
    best="${best%/}"
    [ -d "$best" ] || return 1
    echo "$best"
}

# Android SDK root: explicit env vars first, then the usual install locations
SDK=""
for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/android-sdk" \
         /opt/android-sdk /usr/lib/android-sdk "$HOME/Library/Android/sdk"; do
    if [ -n "$d" ] && { [ -d "$d/build-tools" ] || [ -d "$d/platforms" ]; }; then
        SDK="$d"
        break
    fi
done

BUILD_TOOLS="${BUILD_TOOLS:-}"
if [ -z "$BUILD_TOOLS" ] && [ -n "$SDK" ]; then
    BUILD_TOOLS="$(newest_subdir "$SDK/build-tools" || true)"
fi

# find_tool <name>: $BUILD_TOOLS/<name> if present (SDK install), else <name> from PATH (Termux pkg)
find_tool() {
    if [ -n "$BUILD_TOOLS" ] && [ -x "$BUILD_TOOLS/$1" ]; then
        echo "$BUILD_TOOLS/$1"
    else
        command -v "$1"
    fi
}
AAPT2="${AAPT2:-$(find_tool aapt2 || true)}"
D8="${D8:-$(find_tool d8 || true)}"
APKSIGNER="${APKSIGNER:-$(find_tool apksigner || true)}"

# JDK: d8 and apksigner are `java -jar` wrappers, so `java` must be on PATH.
# Honour JAVA_HOME; otherwise, if there is no java on PATH, look in the usual places
# (system JDKs, Android Studio's ~/jdks downloads, Android Studio's bundled JBR).
jdk_candidates() {
    [ -n "${JAVA_HOME:-}" ] && echo "$JAVA_HOME"
    { ls -d /usr/lib/jvm/*/ "$HOME"/jdks/*/ 2>/dev/null || true; } | sort -Vr
    echo /snap/android-studio/current/jbr
    echo /opt/android-studio/jbr
    echo "$HOME/android-studio/jbr"
    echo /usr/local/android-studio/jbr
    ls -d "$HOME"/.local/share/JetBrains/Toolbox/apps/*/jbr 2>/dev/null || true
}
if [ -n "${JAVA_HOME:-}" ] || ! command -v java >/dev/null 2>&1; then
    while IFS= read -r d; do
        d="${d%/}"
        if [ -x "$d/bin/java" ]; then
            export JAVA_HOME="$d"
            export PATH="$JAVA_HOME/bin:$PATH"
            break
        fi
    done < <(jdk_candidates)
fi

# Java compiler: ecj (Termux) or javac (any JDK)
if [ -z "${JAVAC:-}" ]; then
    if command -v ecj >/dev/null 2>&1; then
        JAVAC=ecj
    elif command -v javac >/dev/null 2>&1; then
        JAVAC=javac
    else
        JAVAC=""
    fi
fi

# android.jar: SDK platform matching TARGET_SDK (else the newest installed), or ~/.android (Termux)
if [ -z "${ANDROID_JAR:-}" ]; then
    ANDROID_JAR=""
    if [ -n "$SDK" ]; then
        p="$(newest_subdir "$SDK/platforms" "android-${TARGET_SDK}" || true)"
        if [ -n "$p" ] && [ -f "$p/android.jar" ]; then
            ANDROID_JAR="$p/android.jar"
        fi
    fi
    if [ -z "$ANDROID_JAR" ] && [ -f "$HOME/.android/android.jar" ]; then
        ANDROID_JAR="$HOME/.android/android.jar"
    fi
fi

# Framework resources for aapt2 link: a device framework-res.apk if provided, otherwise the
# SDK's android.jar (it ships the same resource table).
if [ -z "${FRAMEWORK_RES:-}" ]; then
    if [ -f "$HOME/.android/framework-res.apk" ]; then
        FRAMEWORK_RES="$HOME/.android/framework-res.apk"
    else
        FRAMEWORK_RES="$ANDROID_JAR"
    fi
fi

# ── Sanity checks ──────────────────────────────────────────────────────────
[ -n "$AAPT2" ]     || fail "aapt2 not found"     "$(hint tools)"
[ -n "$D8" ]        || fail "d8 not found"        "$(hint tools)"
[ -n "$APKSIGNER" ] || fail "apksigner not found" "$(hint tools)"
command -v java >/dev/null 2>&1 || fail "java not found (needed by d8 and apksigner)" "$(hint java)"
[ -n "$JAVAC" ]     || fail "no Java compiler found (ecj or javac)" "$(hint javac)"
command -v zip  >/dev/null 2>&1 || fail "zip not found" "$(hint zip)"
[ -n "$ANDROID_JAR" ] && [ -f "$ANDROID_JAR" ] || fail "android.jar not found" "$(hint jar)"
[ -f "$FRAMEWORK_RES" ] || fail "framework resources not found at: $FRAMEWORK_RES"

if [ ! -f "$KEYSTORE" ]; then
    if [ "$KEYSTORE" != "$DEFAULT_KEYSTORE" ]; then
        fail "Keystore not found at: $KEYSTORE"
    fi
    command -v keytool >/dev/null 2>&1 || fail "Keystore not found at: $KEYSTORE (and no keytool to create one)" "$(hint java)"
    echo "Debug keystore not found; creating $KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -keypass "$KEY_PASS" \
        -alias "$KEYSTORE_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

echo "════════════════════════════════════"
echo "  Building ImeiSwitcheroo v${VERSION_NAME} (code ${VERSION_CODE})"
echo "════════════════════════════════════"
echo "  aapt2:       $AAPT2"
echo "  d8:          $D8"
echo "  apksigner:   $APKSIGNER"
echo "  javac:       $(command -v "$JAVAC")"
echo "  java:        $(command -v java)"
echo "  android.jar: $ANDROID_JAR"
echo "  framework:   $FRAMEWORK_RES"
echo "  keystore:    $KEYSTORE"
echo ""

# ── 0. Sync AndroidManifest.xml versions from VERSION ──────────────────────
MANIFEST="AndroidManifest.xml"
if [ -f "$MANIFEST" ]; then
    sed \
        -e "s@android:versionCode=\"[0-9][0-9]*\"@android:versionCode=\"${VERSION_CODE}\"@" \
        -e "s@android:versionName=\"[^\"]*\"@android:versionName=\"${VERSION_NAME}\"@" \
        "$MANIFEST" > "$MANIFEST.tmp"
    mv "$MANIFEST.tmp" "$MANIFEST"
fi

# ── 0b. Clean ──────────────────────────────────────────────────────────────
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex"

# ── 1. Compile resources ───────────────────────────────────────────────────
echo "[1/5] Compiling resources..."
"$AAPT2" compile --dir res/ -o "$BUILD_DIR/resources.zip"

# ── 2. Link resources ──────────────────────────────────────────────────────
echo "[2/5] Linking resources..."
"$AAPT2" link \
    -o "$BUILD_DIR/app_res.apk" \
    --manifest "$MANIFEST" \
    -I "$FRAMEWORK_RES" \
    --java "$BUILD_DIR/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$BUILD_DIR/resources.zip"

# ── 3. Compile Java ────────────────────────────────────────────────────────
echo "[3/5] Compiling Java ($(basename "$JAVAC"))..."
find src/ "$BUILD_DIR/gen/" -name "*.java" > "$BUILD_DIR/sources.txt"
case "$(basename "$JAVAC")" in
    ecj*)
        "$JAVAC" \
            -cp "$ANDROID_JAR" \
            -d "$BUILD_DIR/classes" \
            @"$BUILD_DIR/sources.txt"
        ;;
    *)
        # Java 8 bytecode against android.jar as the platform: accepted by every d8 release
        "$JAVAC" \
            -source 8 -target 8 -Xlint:-options \
            -bootclasspath "$ANDROID_JAR" \
            -d "$BUILD_DIR/classes" \
            @"$BUILD_DIR/sources.txt"
        ;;
esac

# ── 4. Dex ─────────────────────────────────────────────────────────────────
echo "[4/5] Dexing..."
CLASS_FILES=()
while IFS= read -r -d '' f; do
    CLASS_FILES+=("$f")
done < <(find "$BUILD_DIR/classes" -name "*.class" -print0)
"$D8" \
    --output "$BUILD_DIR/dex" \
    --lib "$ANDROID_JAR" \
    --min-api "$MIN_SDK" \
    "${CLASS_FILES[@]}"

# ── 5. Pack + sign ─────────────────────────────────────────────────────────
echo "[5/5] Packaging and signing..."
cp "$BUILD_DIR/app_res.apk" "$BUILD_DIR/app_unsigned.apk"
(cd "$BUILD_DIR/dex" && zip -j "../app_unsigned.apk" classes.dex)

"$APKSIGNER" sign \
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
if [ "$ON_TERMUX" = 1 ]; then
    echo "Install locally:   cp ${APK_OUT} /sdcard/"
fi
