> [!NOTE]
> Please update to the latest app version before using newer VM images.
> 
> The public key that signs any VM image checksum files are embedded in the apk.
> You can rename `VmTerminalApp.apk` to `apk.zip` and extract it, and find the pubkey under `assets/koi-vm-signing-key.asc`.

# Secureblue
The image is built according to the official recommendation (rebase from a Fedora Atomic install).
Hats off to their very recent ARM support.

:gift: Now with support for network control, Android VPN support, and port-forwarding.

### Known issues
> [!IMPORTANT]
> Due to kernel version issues, a Long-Term Support kernel version compiled by a Fedora maintainer for Fedora is used.
> Please see [COPR project page](https://copr.fedorainfracloud.org/coprs/kwizart/kernel-longterm-6.18/) and [maintainer](https://copr.fedorainfracloud.org/coprs/kwizart/).
> This does add an extra trusted party and would require manual maintenance to, for example, switch to a COPR for a newer kernel LTS branch.
> 
> An improvement over the previous situation is, these kernel packages follow the latest LTS updates, so they are no longer subject to years of known vulnerabilities.

1. Automatic VM shutdown has a terribly short timeout (5 seconds). Please shutdown within the VM after use (`run0 poweroff`).
1. Display does not work.
1. The web-based terminal (ttyd) has not been ported.
1. Features requiring kernel patches (such as dynamic memory) are not ported.

### Tips
1. Please follow the [post-install recommendations](https://secureblue.dev/post-install) (displayed in the terminal on first boot as well),
   especially [setting up a separate admin account](https://secureblue.dev/post-install#wheel).
1. The automatic updates are large and may consume a lot of mobile data if not on WiFi. And they are VERY SLOW.
1. For your convenience, the disks are split between `secureblue-system.qcow2`, `secureblue-var.qcow2`, and `secureblue-home.qcow2`.
   It is possible to duplicate the latter and install different software on different user disks,
   potentially with some software airgapped.
   An empty `secureblue-empty.qcow2` is provided for your convenience.
   Unfortunately, Fedora Atomic does not allow `/etc` to be a mount point during install and it has to be read-write during boot,
   so making `-system` read-only does not work yet.
   Additionally, please note that Secureblue does not claim to provide anonymity or anti-fingerprinting benefits,
   and desktop Linux is generally bad at sandboxing.
1. To switch to another LTS kernel branch (for example 6.18 on Fedora 44, which will become supported one day), you can use the following (in run0) and reboot:
```
dnf copr disable kwizart/kernel-longterm-6.12
dnf copr enable kwizart/kernel-longterm-6.18 fedora-44-aarch64 -y
rpm-ostree uninstall kernel-longterm{,-core,-modules,-modules-extra} $(echo --install" "kernel-longterm{,-core,-modules,-modules-extra})
```

### VM image
- [:dvd: image](https://koiterminal-cloudflare-redirects.outlawsanzhang.workers.dev/downloads/vm-images/secureblue/2026091900/images.tar.gz)
- [:lock_with_ink_pen: signature](https://koiterminal-cloudflare-redirects.outlawsanzhang.workers.dev/downloads/vm-images/secureblue/2026091900/images.tar.gz.SHA256SUM.asc)
- [:hammer_and_wrench: building guide](build/custom_vm/secureblue/README.md)

# NixOS
The build script is adapted from [`nixos-avf`](https://github.com/nix-community/nixos-avf), and the image is similar to the one provided there.
### Known issues
1. OS update is not tested.
1. Display does not work.
1. This image (and `nixos-avf` as well) uses the `u-boot.bin` binary blob provided by the host OS.
   There is no guarantee that this is built from source.
   For example, GrapheneOS grabbed this directly from Google.
    - See source code: [https://github.com/GrapheneOS/platform_manifest/blob/2026012800/default.xml#L22](device/google/cuttlefish_prebuilts)/[https://android.googlesource.com/device/google/cuttlefish_prebuilts/+/refs/tags/android-16.0.0_r4/bootloader/crosvm_aarch64/](bootloader/crosvm_aarch64/u-boot.bin)
1. The upstream build script uses the GPL 3.0 license. The adapted script cannot be provided here, and is forked to a [separate repository](https://github.com/outlawsanzhang/nixos-avf-koiTerminal?tab=readme-ov-file).

### VM image
- [:dvd: image](https://github.com/outlawsanzhang/nixos-avf-koiTerminal/releases)
- [:hammer_and_wrench: building guide](https://github.com/outlawsanzhang/nixos-avf-koiTerminal?tab=readme-ov-file#building-initial-image-optional-for-development)

# Debian
The image is now built from Google's old scripts from Android 16.

The Android 17 script skips building the kernel and downloads it from Google instead.
It does have some new features though, like the ttyd that works even with `Block connections without VPN` option on.
It will have to be reworked before koiTerminal can use it.

Modifying an image downloaded from Google is deprecated.

### Known issues
1. On the first boot, the serial terminal takes a while until it allows logging in, about one minute after the web-based terminal is ready. The login prompt needs to be restarted by pressing Ctrl+D.
1. Kernel logs can appear on the serial console and make a mess. This mostly happens during the first minute after log in.

### VM image
- [:dvd: image](https://drive.proton.me/urls/J0ERDQ0ZZ4#w08ddfcz7zLy)
- [:hammer_and_wrench: building guide](build/debian/README.md)

# Alpine
Currently, fixing the Alpine image is put on hold to work on other images.
### Known issues
1. Network is not working.
1. Port forwarding has not been ported.
1. Automatic VM shutdown on app close has not been ported. Please shutdown within the VM after use (`poweroff`).
   If necessary, you can force a shutdown using the `Unplug` button in the bottom snackbar that pops up.
1. Display does not work.
1. The web-based terminal (ttyd) has not been ported.
1. Features requiring kernel patches (such as dynamic memory) are not ported.
1. Custom kernel is needed, which breaks updates within the VM.

### VM image
- [:dvd: image](https://drive.proton.me/urls/A7QHDFFBWM#0cgJvmQovbFN)
- [:hammer_and_wrench: building guide](build/custom_vm/alpine/README.md)

<!-- UPDATE each guide -->
