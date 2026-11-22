#!/bin/sh
# The iOS app, built on macOS with Xcode's command line tools. One IPA in the target folder, without the bundled sounds:
# leoniydew-ios-<version>-no-sounds.ipa (the app downloads the sound library from the `sounds-<version>` release). It is
# ad-hoc signed only, with the `Payload/Leoniydew.app` layout that AltStore, SideStore and Sideloadly install, signing
# it with the user's own Apple ID.
#
#   tools/ios-dist.sh [NAME]   NAME: the IPA's base name, leoniydew-ios-<version> by default
set -eu
cd "$(dirname "$0")/.."

target=aarch64-apple-ios
platform=iPhoneOS
version=$(sed -n 's/^version = "\(.*\)"$/\1/p' Cargo.toml | head -n 1)
name=${1:-leoniydew-ios-$version}
mkdir -p "${CARGO_TARGET_DIR:-target}"
out=$(cd "${CARGO_TARGET_DIR:-target}" && pwd)
export IPHONEOS_DEPLOYMENT_TARGET=14.0

# winit runs UIKit's application from the native binary's `main`, so the binary is the app's executable.
cargo build --release --locked --target "$target" --bin leoniydew-native

work=$out/ios
rm -rf "$work"
app=$work/Payload/Leoniydew.app
mkdir -p "$app"
cp "$out/$target/release/leoniydew-native" "$app/Leoniydew"
# The icon on its opaque background (iOS draws transparency black), at each size the plist names.
for icon in AppIcon60x60@2x:120 AppIcon60x60@3x:180 AppIcon76x76@2x~ipad:152 AppIcon83.5x83.5@2x~ipad:167; do
    sips -z "${icon#*:}" "${icon#*:}" assets/branding/leoniydew-ios-512.png --out "$app/${icon%:*}.png" >/dev/null
done
cat > "$app/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key><string>en</string>
    <key>CFBundleExecutable</key><string>Leoniydew</string>
    <key>CFBundleIdentifier</key><string>com.mageline.leoniydew</string>
    <key>CFBundleName</key><string>Leoniydew</string>
    <key>CFBundleDisplayName</key><string>Leoniydew</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
    <key>CFBundleShortVersionString</key><string>$version</string>
    <key>CFBundleVersion</key><string>$version</string>
    <key>CFBundleSupportedPlatforms</key><array><string>$platform</string></array>
    <key>CFBundleIcons</key><dict><key>CFBundlePrimaryIcon</key><dict>
        <key>CFBundleIconFiles</key><array><string>AppIcon60x60</string></array>
    </dict></dict>
    <key>CFBundleIcons~ipad</key><dict><key>CFBundlePrimaryIcon</key><dict>
        <key>CFBundleIconFiles</key><array><string>AppIcon60x60</string><string>AppIcon76x76</string><string>AppIcon83.5x83.5</string></array>
    </dict></dict>
    <key>MinimumOSVersion</key><string>$IPHONEOS_DEPLOYMENT_TARGET</string>
    <key>LSRequiresIPhoneOS</key><true/>
    <key>UIDeviceFamily</key><array><integer>1</integer><integer>2</integer></array>
    <key>UIRequiredDeviceCapabilities</key><array><string>arm64</string><string>metal</string></array>
    <key>UILaunchScreen</key><dict/>
    <key>UIStatusBarHidden</key><true/>
    <key>UIViewControllerBasedStatusBarAppearance</key><false/>
    <key>UISupportedInterfaceOrientations</key><array>
        <string>UIInterfaceOrientationPortrait</string>
        <string>UIInterfaceOrientationLandscapeLeft</string>
        <string>UIInterfaceOrientationLandscapeRight</string>
    </array>
    <key>UISupportedInterfaceOrientations~ipad</key><array>
        <string>UIInterfaceOrientationPortrait</string>
        <string>UIInterfaceOrientationPortraitUpsideDown</string>
        <string>UIInterfaceOrientationLandscapeLeft</string>
        <string>UIInterfaceOrientationLandscapeRight</string>
    </array>
    <key>UIFileSharingEnabled</key><true/>
    <key>LSSupportsOpeningDocumentsInPlace</key><true/>
    <key>NSMicrophoneUsageDescription</key><string>Leoniydew uses the microphone to record audio into your songs.</string>
</dict>
</plist>
PLIST
plutil -lint "$app/Info.plist" >/dev/null
printf 'APPL????' > "$app/PkgInfo"

sign() {
    codesign --force --sign - --timestamp=none "$app"
}
ipa() {
    sign
    rm -f "$out/$1.ipa"
    (cd "$work" && zip -qry "$out/$1.ipa" Payload)
    echo "wrote $out/$1.ipa"
}
ipa "$name-no-sounds"
