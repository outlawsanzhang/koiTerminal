#!/bin/bash
set -e
cd "$(dirname "$0")/.."
sdk_home="$ANDROID_HOME"
if [[ -z "$sdk_home" ]] && [[ -e "$HOME/Library/Android/sdk" ]]; then
	sdk_home="$HOME/Library/Android/sdk"
fi
if [[ -z "$sdk_home" ]] && [[ -e "$HOME/Android/sdk" ]]; then
	sdk_home="$HOME/Android/sdk"
fi
if [[ -z "$sdk_home" ]]; then
	echo "Can't find Android SDK: set ANDROID_HOME"
	exit 1
fi
if [[ ! -e "tools/android-37-custom.jar" ]]; then
	wget -O "tools/android-37-custom.jar" "https://github.com/zhuowei/android-hidden-api/releases/download/20260822/android-37-custom.jar"
fi
./tools/build_fake_sdk.sh "$sdk_home" "modified_sdk" "tools/android-37-custom.jar"
echo "sdk.dir=$(readlink -f "$PWD/modified_sdk")" > local.properties
