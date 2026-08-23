# How to build koiTerminal

### Build the OS first
The upstream app is designed to be a component of AOSP, and leverages system APIs such as `android.system.virtualmachine.VirtualMachineManager`.
Therefore, it seems that this app cannot be built normally and has to be built with the OS build system.

Please follow the [GrapheneOS build guide](https://grapheneos.org/build) and build the OS for your device model.
Use the version tag that corresponds to the tag in koiterminal.

You do not have to build the entire OS to reach koiTerminal.
If you do a full OS build, you need a beefy machine with preferably 32GB of RAM and ~400GiB of storage
(~150GiB to download, ~100GiB to check out, ~120GiB to compile, plus any swap file you create).
On my machine that is not very beefy, compilation from scratch takes half a day.

Alternatively, when the guide instructs you to build with the `m` command,
instead run `m VmTerminalApp.com.android.virt` to build everything that leads up to the Terminal app.
This may save about ~100GiB of storage and several hours of time compared to a full OS build.

Using the instructions for `Faster builds for development use only` is fine for development as we don't need to sign the OS,
but that will sign the apk with test keys, which everyone has.
To use your own signature, also run `m otatools-package` to build signing tools. <!-- Is this needed? -->

### Tips for building
1. For the correct version of Node.js, you can use [nvm](https://github.com/nvm-sh/nvm).
2. Yarn can be installed from Node.js: `npm install -g yarn`
3. You may need to manually rename the factory image download: `mv vendor/adevtool/dl/<image>.zip.tmp vendor/adevtool/dl/<image>.zip`
4. You may need `git config --global fetch.fsck.badTimezone ignore`
5. Although `repo` is robust to network failures, it is not robust to running out of storage on your drive.

### Tips for building in Whonix
1. Somehow it needs `git config --global core.symlinks true`
2. Ignore the issue with git rev-parse broken in .mk.
    Add `torsocks_bin=/usr/bin/torsocks` to the `git` line in `vendor/google_devices/$DEVICE/adevtool-version-check.mk`
    To figure out what the issue is, you will ned to run the wrapped command yourself to show all of stdout.
3. For the spike of memory usage, you can use a swap file: `# swapon ~/swap.tmp`
4. If you get 403, 404, 429, 503, etc. HTTP responses, switch routes, then retry, retry, and retry again.

### Build koiTerminal
Please finish building the whole OS before following the rest of this guide.

After building the OS, change the Virtualization package to this repo:
```
cd packages/modules/Virtualization
git remote add koiterminal https://github.com/outlawsanzhang/koiTerminal.git
git fetch koiterminal
git checkout koiterminal
cd ../../..
```

:gift: NEW: Then, the following changes are needed for a few rust dependencies:
```
pushd external/rust/android-crates-io/crates/
# Add fast-socks5 crate, and sync over (cargo_embargo generated) Android.bp. The commit bae2fc0 was used
git clone https://github.com/dizda/fast-socks5.git
ln -s ../../../../../packages/modules/Virtualization/android/forwarder_host/fs5-Android-bp fast-socks5/Android.bp
# Enable tokio-stream for apex com.android.virt. This should only add one line to the original Android.bp.
cp ../../../../../packages/modules/Virtualization/android/forwarder_host/ts-Android-bp tokio-stream/Android.bp
```

Then, build the Terminal app / OS again. This should be a lot quicker than the first time.
When it completes, the app should be produced at `out/target/product/$DEVICE/apex/com.android.virt/priv-app/VmTerminalApp@*/VmTerminalApp.apk`.

### Sign the build
Using the test key means anyone can update your app into anything else.
It is best to sign the apk with your own key.

First, generate your keystore if you do not have one.
```
DEVICE=... # fill your device here
CN=... # fill your name here
keytool -genkeypair -alias VmTerminalApp -keyalg RSA -keysize 4096 -validity 10000 -keystore keys/$DEVICE/vm-app-signing.jks -dname "CN=$CN"
```
You can sign the app with apksigner:
```
DEVICE=... # fill your device here
RELEASE_OUT=releases/$BUILD_NUMBER/release-$DEVICE-$BUILD_NUMBER

rm -rf $RELEASE_OUT
mkdir -p $RELEASE_OUT

cp out/target/product/$DEVICE/apex/com.android.virt/priv-app/VmTerminalApp@*/VmTerminalApp.apk $RELEASE_OUT/VmTerminalApp.apk
out_adevtool_deps/host/linux-x86/bin/apksigner sign --ks keys/$DEVICE/vm-app-signing.jks $RELEASE_OUT/VmTerminalApp.apk
```

### Android Studio build
This doesn't work yet - it only builds enough of the UI for install, but running VMs doesn't work yet

1. Install:
  - Android Platform 37.0 and Build Tools 36.0.0
  - Rust
  - Rust Android targets
  - cargo-ndk
2. Download modified `android.jar`:
```
cd koiTerminal
./tools/setup.sh
```
3. Build Rust:
```
./tools/build_rust.sh
```
4. build in Android Studio
