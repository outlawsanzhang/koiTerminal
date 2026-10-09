#!/bin/bash
# (yes you can just make a copy of the SDK and replace the android.jar,
# but this is probably nicer)
set -e
# <!-- UPDATE --> Update version
BUILD_TOOLS_VER="36.0.0"
SDK_VER_MAJOR="37"
SDK_VER="$SDK_VER_MAJOR.0"

if [[ -z "$1" ]] || [[ -z "$2" ]] || [[ -z "$3" ]]; then
	echo "usage: tools/build_fake_sdk.sh <sdk_in> <sdk_out> <fakejar>"
	exit 1
fi
sdk_in="$(readlink -f "$1")"
sdk_out="$2"
fakejar="$(readlink -f "$3")"
IFS=$'\n'
[[ -e "$sdk_out" ]] && rm -r "$sdk_out"
mkdir "$sdk_out"
for i in $(cd "$sdk_in"; ls -1); do
	[[ "$i" == "platforms" ]] && continue
	[[ "$i" == "build-tools" ]] && continue
	ln -s "$sdk_in/$i" "$sdk_out/$i"
done
mkdir -p "$sdk_out/build-tools/$BUILD_TOOLS_VER"
for i in $(cd "$sdk_in/build-tools/$BUILD_TOOLS_VER/"; ls -1); do
	[[ "$i" == "aidl" ]] && continue
	ln -s "$sdk_in/build-tools/$BUILD_TOOLS_VER/$i" "$sdk_out/build-tools/$BUILD_TOOLS_VER/$i"
done
cat << END > "$sdk_out/build-tools/$BUILD_TOOLS_VER/aidl"
#!/bin/sh
exec "$sdk_in/build-tools/$BUILD_TOOLS_VER/aidl" --min_sdk_version="$SDK_VER_MAJOR" "\$@"
END
chmod +x "$sdk_out/build-tools/$BUILD_TOOLS_VER/aidl"
mkdir -p "$sdk_out/platforms/android-$SDK_VER"
for i in $(cd "$sdk_in/platforms/android-$SDK_VER/"; ls -1); do
	[[ "$i" == "android.jar" ]] && continue
	ln -s "$sdk_in/platforms/android-$SDK_VER/$i" "$sdk_out/platforms/android-$SDK_VER/$i"
done
ln -s "$fakejar" "$sdk_out/platforms/android-$SDK_VER/android.jar"
