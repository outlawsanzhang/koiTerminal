# koiTerminal
A more permissive version of the Linux Terminal app, forked from the GrapheneOS repo, supporting custom virtual machine images (such as Alpine). Currently in proof-of-concept stage. <!-- UPDATE -->

<img src="https://raw.githubusercontent.com/outlawsanzhang/koiTerminal/refs/heads/koiterminal/assets/alpine-2026012800.jpg" width="50%" height="50%">

The main goal is to allow users to install this as a non-system, standalone app on a non-rooted device, and run a full VM with a Linux image that is not provided by Google.
Because, come on, there was a NestBox app by kdrag0n that was able to do this years ago! Unfortunately, it was not maintained and stopped working on newer OS versions.

This repo also has an `upstreamable` branch that can potentially be merged into GrapheneOS, if they ever decide to do anything with it.

Once this repo is in a more presentable state (>=3 distros successfully supported), this document will be rewritten to be more user-friendly instead of only dev-friendly. <!-- UPDATE -->

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
- Provides images for other distros (currently: Alpine)
- Exposes VM files (configs, storage, etc.) to enable modifying VM configurations
- Does not force you to give the VM access to all files
- No rooting necessary, requiring only a one-time permission grant using ADB.
- Can connect to the VM using the serial console, which enables:
    - Booting from installation media using u-boot (instructions to come) <!-- UPDATE -->
    - Booting from fresh OS installs
    <!--
        - [!] Default u-boot is a pre-built blob from google's repo: [[https://github.com/GrapheneOS/platform_manifest/blob/2026012800/default.xml#L22][device/google/cuttlefish_prebuilts]]/[[https://android.googlesource.com/device/google/cuttlefish_prebuilts/+/refs/tags/android-16.0.0_r4/bootloader/crosvm_aarch64/][bootloader/crosvm_aarch64/u-boot.bin]]
        - Currently, the `u-boot` bootloader (which is used when neither `kernel/initrd` nor `bootloader` is specified) uses a pre-compiled blob that GrapheneOS grabbed from Google.
    --> <!-- UPDATE -->
    - Supporting using the `Block connections without VPN` setting
    - Changing font size :)

## Disclaimers
- Proof-of-concept pre-alpha test-build software, provided AS-IS. Beware of sharp edges, and back up often. You have been warned.
- The app is only tested on newer devices running the latest GrapheneOS, so it would be nice to know if it works for other OSes at all.
  Many Android-based OSes and devices do not support Android Virtualization Framework, and may not be based on the latest version of AOSP.
  In addition, 6th-generation Pixels require root to use AVF, so they are not supported.
  This project does not aim to continuously support lower OS versions.
- There is a decent chance that this will be abandonware, especially if a major part of this is upstreamed to GrapheneOS. Again, AS-IS.
- Known sharp edges: <!-- UPDATE -->
    - The serial console may not show and the app may need to be force stopped for it to work again.
    - Just crashes when files referenced in `vm_config.json` are not found, without indicating which.
    - Ctrl virtual button does not work in serial console.

## Progress and plans
Goals are mainly targeted at things that neither Google nor GrapheneOS is inclined to do in the near future.
These goals may change, and they may or may not be achievable. We will have to see. <!-- UPDATE -->

- [X] Get the app to compile under a different package name
- [X] Fix issues that prevent booting a VM
- [X] Get a modified version of Google's image to run and show its terminal
- [X] Allow communication with the VM using the console instead of ttyd
    - Stops requiring `Block connections without VPN` to be off
    - Allows a "raw" image to run
- [X] Make a new image that is not Debian
- [ ] High priority FIXME for serial console
    - [ ] Fix virtual Ctrl button
    - [ ] Fix issue where the serial terminal cannot be closed and reopened
    - [ ] Implement copy/paste menus for the terminal emulator from Termux
- [ ] Make an image based on Secureblue
    - [ ]  (stretch) Allow users to install systems themselves using iso installers, and build u-boot ourselves
- [ ] FIXME for serial console
    - [ ] Find out which kernel versions and what configurations work. How about 6.6 LTS?
    - [ ] Make pty changes work
    - [ ] Deleting folder does not work in the DocumentProvider
    - [ ] Stop app from messing with qcow2 disk size
    - [ ] "VM already exists" bug
- [ ]  (stretch) Enable forcing the VM to use the host vpn
- [ ] Support for multiple `vm_config.json` files for different modes (install, update, use, airgap, etc.) or just different VMs.
- [ ] Enable trying to keep the VM alive in the background
- [ ]  (stretch) Add back gutted features
    - Something to replace virtio (seamless file sharing)
    - Something to replace dynamic VM storage resizing
    - Make the display work
    - Make the mouse work (offset issue)

Suggested by community:
- Only applies to ttyd:
    - [ ] Changing font size (MainActivity.kt#L251)
    - [ ] Fix backspace bug, I mean wtf.

Suggested by community, but either may not be easily done or Google is better suited to do it:
- [ ] USB support
- [ ] Custom fonts. Reference: https://github.com/tsl0922/ttyd/wiki/Serving-web-fonts (need to recompile ttyd)

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
adb connect localhost:????? # fill in the value from developer options
adb shell pm list users # owner's ID is 0, others' can be obtained here
adb shell pm grant --user ?? com.android.virtualization.koiterminal android.permission.MANAGE_VIRTUAL_MACHINE # fill in the user ID
adb shell pm grant --user ?? com.android.virtualization.koiterminal android.permission.USE_CUSTOM_VIRTUAL_MACHINE # fill in the user ID
# Don't forget to turn off wireless debugging afterwards.
```

### Obtain a VM image
Google's official image will not work as its setup requires extra permissions to enable virtio (seamless folder sharing between host and VM).

This project provides the following images (and image building guides for those wishing to customize further): <!-- UPDATE -->
- Modified Debian from Google: [:dvd: image](https://drive.proton.me/urls/3M1QVHKA88#7mfYjRmXSRpc), [:hammer_and_wrench: building guide](build/debian/README.md)
- Alpine: [:dvd: image](https://drive.proton.me/urls/A7QHDFFBWM#0cgJvmQovbFN), [:hammer_and_wrench: building guide](build/custom_vm/alpine/README.md)
- Running ISO-based OS installer (coming soon): [:dvd: partial image](), [:page_with_curl: usage guide]()
<!-- UPDATE each guide -->

Note that these images are built or modified so that the kernel version is closer to 6.1 or at least no higher than 6.12.
It seems from experience that anything higher than 6.6 will not run properly or straight-up refuse to boot. <!-- UPDATE -->

### Place the image

The image (`image.tar.gz`) should be placed in a `linux` folder which sits at the "root" folder of your user, next to `Android/`, `Download/`, etc. For Storage Scopes on GrapheneOS, grant access to the `linux` folder.
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
After the install, it should be able to show the terminal in at least one of two ways:

(1) If the image supports ttyd (right now the only one is the Debian image from Google), then the terminal should just appear. <!-- UPDATE -->
If you are using a VPN, it may block the local connection used to communicate with the VM.
Make sure to turn off `Block connections without VPN` in the system settings, and enable your VPN's local network access if it also blocks local connections.

(2) If the image supports the serial console, you can press the add serial console tab button (plus sign with a tail) to connect to the VM's console.
Right now, all images should support this method, although for the modified Debian image, kernel logs may appear on your console and make a mess. <!-- UPDATE -->
This method works with the `Block connections without VPN` option and connects directly to the VM.
Note the VM is still outside the VPN, connected straight to the Internet. <!-- UPDATE -->
This console uses code from Termux, and inherits some of its features like zooming.
However, each VM can have only one serial console tab, unlike the multi-tab ttyd.

Just like the official Linux Terminal app, if it throws an error, or if it is stuck, try restarting the app, or use the recovery button to wipe and start over.

# How to build koiTerminal
### Build the OS first
The upstream app is designed to be a component of AOSP, and leverages system APIs such as `android.system.virtualmachine.VirtualMachineManager`.
Therefore, it seems that this app cannot be built normally and has to be built with the OS build system.
It used to be possible to build just the app with `UNBUNDLED_BUILD_SDKS_FROM_SOURCE=true TARGET_BUILD_APPS=VmTerminalApp m apps_only dist`,
but that has not been working recently.
If anyone knows why, or if anyone knows how to build this app normally, tips are greatly appreciated.

Please follow the [GrapheneOS build guide](https://grapheneos.org/build) and build the OS for your device model.
Use the version tag that corresponds to the tag in koiterminal.
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

# Misc
### License
All new files and files from upstream GrapheneOS: released under Apache 2.0. See [LICENSE](LICENSE). See upstream license [NOTICE](NOTICE).

Folders from Termux: released under the same license as `terminal-view` and `terminal-emulator` directories from Termux (Apache 2.0). See [their LICENCE.md](https://github.com/termux/termux-app/blob/master/LICENCE.md). These include:
- Files under `android/TerminalApp/java/com/termux`

Vector graphics:
- Carp in the icon [public domain](https://freesvg.org/vector-clip-art-of-seamless-pattern-of-carp).
- Serial connection [CC0](https://openclipart.org/detail/244265/power-cable-icon-redrawn)

