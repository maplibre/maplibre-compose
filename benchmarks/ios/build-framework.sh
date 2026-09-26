#!/bin/sh
set -eu
cd "$SRCROOT/../.."
./gradlew :benchmarks:ios:embedAndSignAppleFrameworkForXcode

framework="$SRCROOT/build/sdk/MapLibre.xcframework/$CLASSIC_SDK_SLICE/MapLibre.framework"
mkdir -p "$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH"
rm -rf "$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH/MapLibre.framework"
cp -R "$framework" "$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH/"
if [ -n "${EXPANDED_CODE_SIGN_IDENTITY:-}" ]; then
  codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" "$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH/MapLibre.framework"
fi
mkdir -p "$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"
rm -rf "$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/benchmarks"
cp -R benchmarks/build/fixtures/benchmarks "$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/"
