# koiTerminal
A more permissive version of the Linux Terminal app, forked from the GrapheneOS repo, supporting custom virtual machine images. Currently in proof-of-concept stage. <!-- UPDATE -->

The main goal is to allow users to install this as a non-system, standalone app on a non-rooted device, and run a full VM with a Linux image that is not provided by Google.
Because, come on, there was a NestBox app by kdrag0n that was able to do this years ago! Unfortunately, it was not maintained and stopped working on newer OS versions.

This repo also has an `upstreamable` branch that can potentially be merged into GrapheneOS, if they ever decide to do anything with it.

Once this repo is in a more presentable state (other distros successfully supported), this document will be rewritten to be more user-friendly instead of only dev-friendly. <!-- UPDATE -->

`koi` stands for `KVM with Other Images`. Perhaps.

### Table of contents <!-- UPDATE -->
- [Features](#added-features)
- [Disclaimers](#disclaimers)
- [Plans](#progress-and-plans)
- [How to use](#how-to-use)
- [How to build](#how-to-build)
- [Misc](#misc)

## Added features<!-- UPDATE -->
- Boots a user-provided Linux VM image
- Exposes VM files to enable modifying VM configurations
- No rooting necessary, requiring only a one-time ADB permission grant.

## Disclaimers
- Proof-of-concept pre-alpha test-build software, provided AS-IS. Beware of sharp edges, and back up often. You have been warned.
- The app is only tested on newer devices running the latest GrapheneOS, so it would be nice to know if it works for other OSes at all.
  Many Android-based OSes and devices do not support Android Virtualization Framework, and may not be based on the latest version of AOSP.
  In addition, 6th-generation Pixels require root to use AVF, so they are not supported.
  This project does not aim to continuously support lower OS versions.
- There is a decent chance that this will be abandonware, especially if a major part of this is upstreamed to GrapheneOS. Again, AS-IS.
- Known sharp edges: <!-- UPDATE -->
    - Closing the app throws an error complaining about some library not being loaded

## Progress and plans
Goals are mainly targeted at things that neither Google nor GrapheneOS is inclined to do in the near future.
These goals may change, and they may or may not be achievable. We will have to see. <!-- UPDATE -->

- [X] Get the app to compile under a different package name
- [X] Fix issues that prevent booting a VM
- [X] Get a modified version of Google's image to run and show its terminal
- [ ] Allow communication with the VM using the console instead of ttyd
    - (may make it stop requiring `Block connections without VPN` to be off)
    - (may allow a "raw" image to run)
- [ ] Make a new Debian image by minimally following [build/debian](build/debian)
    - [ ] Build an image from an official Debian image and get it to boot
    - [ ] Make sure the console and the network both work
- [ ]  (stretch) Enable forcing the VM to use the host vpn
- [ ] Make a new image that is not Debian
- [ ] Enable trying to keep the VM alive in the background
- [ ]  (stretch) Add back gutted features
    - Something to replace virtio (seamless file sharing)
    - Something to replace dynamic VM storage resizing
    - Make the display work
    - Make the mouse work (offset issue)

# How to use
### Grant permissions
After installing, the app needs to be given access to storage and VM permissions, and for GrapheneOS, the Network permission.
For the storage permission, go to `Settings -> Apps -> Special app access -> All files access -> koiTerminal`.
If you are on GrapheneOS, you can use Storage Scopes and grant the `linux` folder (see below for location) instead of full storage access. <!-- UPDATE -->

The VM permissions are trickier. These non-standard permissions require granting via `adb`. You can use a desktop, or use Termux as follows:
```
pkg install android-tools # for Termux, install adb
# Now, turn on developer options and enable wireless debugging. Then,
adb pair localhost:????? # fill in the value from developer options
adb connect localhost:???? # fill in the value from developer options
adb shell pm list users # owner's ID is 0, others' can be obtained here
adb shell pm grant --user ?? com.android.virtualization.koiterminal android.permission.MANAGE_VIRTUAL_MACHINE # fill in the user ID
adb shell pm grant --user ?? com.android.virtualization.koiterminal android.permission.USE_CUSTOM_VIRTUAL_MACHINE # fill in the user ID
# Don't forget to turn off wireless debugging afterwards.
```

### Place the image
Google's official image will not work as its setup requires extra permissions to enable virtio (seamless folder sharing between host and VM).
You can use the [image provided by this repo](https://drive.proton.me/urls/3M1QVHKA88#7mfYjRmXSRpc), or follow the steps in the building section for preparing one yourself. <!-- UPDATE -->

The image should be placed in a `linux` folder which sits at the "root" folder of your user, next to `Android/`, `Download/`, etc. For Storage Scopes on GrapheneOS, grant access to the `linux` folder.
```
user root
|
+- Android/
|
+- Download/
|
+- linux/
|  |
|  +- image.tar.gz
|
+- (everything else)
```

### Launch the app
The app should automatically install the image.
After the install, it should show the terminal.
If you are using a VPN, it may block the local connection used to communicate with the VM.
Make sure to turn off `Block connections without VPN` in the system settings, and enable local network access if your VPN blocks it.
Just like the official Linux Terminal app, if it throws an error, try restarting the app, or use the recovery button to wipe and start over.
Ignore the error that pops up after closing the terminal. <!-- UPDATE -->

# How to build
### Build the OS first
The upstream app is designed to be a component of AOSP, and leverages system APIs such as `android.system.virtualmachine.VirtualMachineManager`.
Therefore, it seems that this app cannot be built normally and has to be built with the OS build system.
It used to be possible to build just the app with `UNBUNDLED_BUILD_SDKS_FROM_SOURCE=true TARGET_BUILD_APPS=VmTerminalApp m apps_only dist`,
but that has not been working recently.
If anyone knows why, or if anyone knows how to build this app normally, tips are greatly appreciated.

Please follow the [GrapheneOS build guide](https://grapheneos.org/build) and build the OS for your device model.
This needs a beefy machine with preferably 32GB of RAM and ~400GiB of storage (~150GiB to download, ~100GiB to check out, ~120GiB to compile, plus any swap file you create).
On my machine that is not very beefy, compilation from scratch takes half a day.

Using the instructions for `Faster builds for development use only` is fine for development as we don't need to sign the OS,
but that will sign the apk with test keys, which everyone has.
To use your own signature, also run `m otatools-package` to build signing tools.

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

Then, build the OS again. This should be a lot quicker than the first time.
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
apksigner sign --ks keys/$DEVICE/vm-app-signing.jks $RELEASE_OUT/VmTerminalApp.apk
```

### Prepare the Linux image
Instead of using the [modified image](https://drive.proton.me/urls/3M1QVHKA88#7mfYjRmXSRpc) provided by this repo, <!-- UPDATE -->
you can alternatively download [Google's image](https://dl.google.com/android/ferrochrome/4000000/aarch64/images.tar.gz) and make the following changes. <!-- UPDATE -->
1. Untar `images.tar.gz` to a folder (e.g., `images/`).
2. Replace the content of `images/vm_config.json` with [the version in this repository](build/debian/vm_config.koiterminal.json).
3. Extract `images/cidata.iso` to a folder (e.g., `cidata/`).
4. Replace the content of `cidata/init.sh` with [the version in this repository](build/debian/cloud-init_config/init.sh).
5. Repackage `images/cidata.iso` (see [how they did that](build/debian/build.sh#L295)).
6. Repackage `images.tar.gz`.
```
mkdir images
tar xzf images.tar.gz --directory images/
cat /path/to/vm_config.koiterminal.json > images/vm_config.json
mount images/cidata.iso /mnt
cp -r /mnt cidata
cat /path/to/init.sh > cidata/init.sh
umount /mnt
genisoimage -output images/cidata.iso -V cidata -J -R cidata/
tar czf images.tar.gz --directory images .
```
<!-- UPDATE -->

# Misc
### License
Same as upstream. See [NOTICE](NOTICE).

Carp in the icon from [public domain](https://freesvg.org/vector-clip-art-of-seamless-pattern-of-carp).

