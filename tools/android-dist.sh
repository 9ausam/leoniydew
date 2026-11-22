#!/bin/sh
# Build the Android APK for 64-bit and 32-bit ARM WITHOUT the bundled sounds (-arm-base.apk): the app downloads the sound
# library from the `sounds-<version>` release of the repository (#66). This is the only variant this repository builds.
# Release signing uses environment variables, never checked-in keys.
#
#   tools/android-dist.sh [--release]     (--base is accepted and ignored: every build is a base build)
set -eu
cd "$(dirname "$0")/.."

variant=debug
task=assembleDebug
base=-base
for arg in "$@"; do
    case $arg in
        --release) variant=release; task=assembleRelease ;;
        --base) ;;
        *) echo "usage: tools/android-dist.sh [--release]" >&2; exit 2 ;;
    esac
done
if [ "$variant" = release ]; then
    : "${ANDROID_KEYSTORE_FILE:?set the path to your release keystore}"
    : "${ANDROID_KEYSTORE_PASSWORD:?set your keystore password}"
    : "${ANDROID_KEY_ALIAS:?set your signing key alias}"
    : "${ANDROID_KEY_PASSWORD:?set your signing key password}"
    [ -f "$ANDROID_KEYSTORE_FILE" ] || { echo 'release keystore does not exist' >&2; exit 1; }
    ANDROID_KEYSTORE_FILE=$(cd "$(dirname "$ANDROID_KEYSTORE_FILE")" && pwd)/$(basename "$ANDROID_KEYSTORE_FILE")
    export ANDROID_KEYSTORE_FILE
fi
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK directory}"
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.0.13004108}
sdk_tools=$ANDROID_HOME/build-tools/36.0.0
for tool in cargo python3 unzip java keytool; do
    command -v "$tool" >/dev/null || { echo "needs $tool on PATH" >&2; exit 1; }
done

mkdir -p "${CARGO_TARGET_DIR:-target}"
export CARGO_TARGET_DIR=$(cd "${CARGO_TARGET_DIR:-target}" && pwd)
export LEONIYDEW_ANDROID_BUILD_DIR=$CARGO_TARGET_DIR/android
export CARGO_BUILD_JOBS=${CARGO_BUILD_JOBS:-4}
mkdir -p "$LEONIYDEW_ANDROID_BUILD_DIR/assets" "$LEONIYDEW_ANDROID_BUILD_DIR/jniLibs"
# No sounds in the APK: the assets folder holds no `content/`.
rm -rf "$LEONIYDEW_ANDROID_BUILD_DIR/assets/content"
export LEONIYDEW_ANDROID_BASE=$base
# signalsmith-stretch's C++ links libc++_shared.so, which the APK has to ship beside libleoniydew.so.
cargo ndk -t arm64-v8a -t armeabi-v7a --platform 26 --link-libcxx-shared -o "$LEONIYDEW_ANDROID_BUILD_DIR/jniLibs" rustc --release --locked --lib -- -C link-arg=-Wl,-z,max-page-size=16384
debug_keystore=$PWD/android/debug.keystore
if [ "$variant" = debug ] && [ ! -f "$debug_keystore" ]; then
    # Keep the existing signing identity when moving it out of disposable build output.
    if [ -f "$LEONIYDEW_ANDROID_BUILD_DIR/debug.keystore" ]; then
        cp -p "$LEONIYDEW_ANDROID_BUILD_DIR/debug.keystore" "$debug_keystore"
    else
        keytool -genkeypair -noprompt -keystore "$debug_keystore" \
            -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
            -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' >/dev/null 2>&1
    fi
fi
# Packaged afresh, so no stale output is reused.
rm -rf "$LEONIYDEW_ANDROID_BUILD_DIR/outputs/apk/$variant"
android/gradlew -p android --no-daemon "$task"

apk=$LEONIYDEW_ANDROID_BUILD_DIR/outputs/apk/$variant/app-$variant.apk
"$sdk_tools/apksigner" verify "$apk"
"$sdk_tools/zipalign" -c -P 16 4 "$apk"
python3 - "$apk" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    for abi in ('arm64-v8a', 'armeabi-v7a'):
        for lib in ('libleoniydew.so', 'libc++_shared.so'):
            assert apk.getinfo(f'lib/{abi}/{lib}').compress_type == zipfile.ZIP_STORED, abi + '/' + lib
    assert not any(name.startswith('assets/content/') for name in apk.namelist()), 'sounds in the base APK'
    print('Verified APK signature and alignment, without sounds')
PY
version=$(sed -n 's/^version = "\(.*\)"$/\1/p' Cargo.toml | head -n 1)
suffix=
[ "$variant" = release ] || suffix=-debug
out=$CARGO_TARGET_DIR/leoniydew-android-$version-arm$base$suffix.apk
cp "$apk" "$out"
echo "wrote $out"
