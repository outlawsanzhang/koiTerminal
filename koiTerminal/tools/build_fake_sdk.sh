#!/bin/bash
# (yes you can just make a copy of the SDK and replace the android.jar,
# but this is probably nicer)
set -e
if [[ -z "$1" ]] || [[ -z "$2" ]] || [[ -z "$3" ]]; then
	echo "usage: tools/build_fake_sdk.sh <sdk_in> <sdk_out> <fakejar>"
	exit 1
fi
sdk_in="$(readlink -f "$1")"
sdk_out="$2"
fakejar="$(readlink -f "$3")"
[[ -e "$sdk_out" ]] && rm -r "$sdk_out"
mkdir "$sdk_out"
for i in $(cd "$sdk_in"; echo *); do
	[[ "$i" == "platforms" ]] && continue
	[[ "$i" == "build-tools" ]] && continue
	ln -s "$sdk_in/$i" "$sdk_out/$i"
done
mkdir -p "$sdk_out/build-tools/36.0.0"
for i in $(cd "$sdk_in/build-tools/36.0.0/"; echo *); do
	[[ "$i" == "aidl" ]] && continue
	ln -s "$sdk_in/build-tools/36.0.0/$i" "$sdk_out/build-tools/36.0.0/$i"
done
cat << END > "$sdk_out/build-tools/36.0.0/aidl"
#!/bin/sh
exec "$sdk_in/build-tools/36.0.0/aidl" --min_sdk_version=37 "\$@"
END
chmod +x "$sdk_out/build-tools/36.0.0/aidl"
mkdir -p "$sdk_out/platforms/android-37.0"
for i in $(cd "$sdk_in/platforms/android-37.0/"; echo *); do
	[[ "$i" == "android.jar" ]] && continue
	ln -s "$sdk_in/platforms/android-37.0/$i" "$sdk_out/platforms/android-37.0/$i"
done
ln -s "$fakejar" "$sdk_out/platforms/android-37.0/android.jar"
