# Prepare the modified Debian image
There are two ways of obtaining the Debian image: building it yourself, or modifying a downloaded Google image (deprecated).
The Debian image provided in this repo is built from scratch since 2026021200.

### Method 1: building from scratch
The build script from [build/debian](build/debian) has been modified to make building easier (cached downloads, increased verbosity),
and fix some breakage in build scripts and kernel building tools.
Meanwhile, functional changes disable the virtio folder sharing that do not work, and share the ttyd CA as a tiny disk partition instead.
They also open up the serial console and use cloud-init to set the console to auto login.

To build the image, in a x64 Debian trixie environment (recommended to use a docker image or VM due to the script installing a lot of packages), run the following as root:
```
cd build/debian
./build.sh -a aarch64 -b "2026021200 2026" -W `pwd`/workdir -c
```
The tag needs to ends with "` 2026`" or later. Otherwise, the app complains.

### Method 2: modifying from Google's image (easier, but deprecated)
> [!NOTE] Google seems to be updating their image on an hourly basis, and the build script is not open-sourced immeidately.
> These instructions probably do not work once there is a new version, so we need to use an archive.

Download [Google's image (Wayback Machine archive)](https://web.archive.org/web/20260207005005/https://dl.google.com/android/ferrochrome/4000000/aarch64/images.tar.gz) and make the following changes: <!-- UPDATE -->
1. Untar `images.tar.gz` to a folder (e.g., `images/`).
2. Replace the content of `images/vm_config.json` with [the version in this repository](build/debian-snapshot/vm_config.koiterminal.json).
3. Extract `images/cidata.iso` to a folder (e.g., `cidata/`).
4. Replace the content of `cidata/init.sh` with [the version in this repository](build/debian-snapshot/cloud-init_config/init.sh).
5. Repackage `images/cidata.iso` (see [how they did that](build/debian/build.sh#L295)).
6. Repackage `images.tar.gz`.
```
mkdir images
tar xzf images.tar.gz --directory images/
cat /path/to/vm_config.koiterminal.json > images/vm_config.json
mount images/cidata.iso /mnt
cp -pR /mnt cidata
cat /path/to/init.sh > cidata/init.sh
umount /mnt
genisoimage -output images/cidata.iso -V cidata -J -R cidata/
tar czf images.tar.gz --directory images .
```

Modifications are the same as building from scratch: disable virtiofs folder sharing, share ttyd CA, enable serial console.

# An overview of how the original app with the Debian image works
The terminal uses an internal (for now) API to create a VM, passing a bunch of file and pipe handles and a bunch of configs to `crosvm`. Crosvm launches the VM either with direct boot (kernel + initrd) or bootloader (sudh as u-boot).

The [`build/debian`](build/debian) directory contains scripts and configs for building Google's Debian image + kernel, and [`build/custom_vm`](build/custom_vm) directory contains my modified scripts for building the Alpine image + kernel.

Alpine and older versions of the Debian image (one that is built with the open-sourced `build/debian` folder) both use a [similar](build/debian/build_custom_kernel.sh#L180) initrd [design](build/custom_vm/generic-kernel/build_initrd.sh) based on busybox, which loads the bare minimum modules from initrd to read the filesystem (see e.g. [`build/custom_vm/generic-kernel/initrd/modules`](build/custom_vm/generic-kernel/initrd/modules)), mounts the `kernel_extras_part` partition and links its contents (mainly kernel modules) to appropriate system folders, and finally chroots into the real root to run its `/sbin/init`. Newer versions of the hourly-built Debian downloaded from Google, as of 2026 Feb, has no `/init` in `initrd.img` but only has kernel modules, and seems to boot differently.

When the Debian VM boots from the real root for the first time, it uses [cloud-init](build/debian/cloud-init_config) to run a bunch of setup, including [adding all these services](build/debian/cloud-init_config/root_files/etc/systemd). These services launch after that or when the VM boots subsequently. Among them are `ttyd` and `avahi`.

[`ttyd` is this](build/debian/build.sh#L210), which is not a serial console but just a terminal emulator provided inside a webpage. They forces it to use the `ca.crt` from host (mounted at `/mnt/internal/ca.crt` with virtiofs), and [put `avahi` on top of its default port](build/debian/cloud-init_config/root_files/etc/systemd/system/avahi_ttyd.service). Avahi publishes `ttyd`'s webpage as a network service with the name `ttyd` using [mDNS/DNS-SD](https://developer.android.com/reference/android/net/nsd/NsdManager). From what I gather, mDNS is like how your computer discovers your printer by nickname in the local network.

The app itself, after launching the VM, waits for it to do all the above, and signify its readiness by [waiting for the `ttyd` network service](android/TerminalApp/java/com/android/virtualization/koiterminal/VmLauncherService.kt#L311-L314). Once the `ttyd` network service is found and passes the certificate check, the terminal tab loads a webview displaying the page `ttyd` provided, and the user finally sees the "console".

