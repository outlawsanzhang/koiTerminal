> [!NOTE]
> Please update to the latest app version before using newer VM images.

# Secureblue
The image is built according to the official recommendation (rebase from a Fedora Atomic install).
Hats off to their very recent ARM support.
### Known issues
> [!IMPORTANT]
> Due to kernel version issues, the default boot option will not work on some devices (such as the 8-th generation Pixels).
> If Secureblue does not boot, please use the serial terminal to select the second boot option (labeled `ostree:1`),
> which uses an older kernel. Afterwards, run `echo rpm-ostree cleanup --pending | run0` to remove the incompatible option.
> 
> Note that using the older kernel is a security degredation that introduces 1+ year (and counting) of unpatched vulnerability.

1. (upstream) `ujust set-brew` [broken](https://github.com/secureblue/secureblue/issues/2098) for now
1. Build script does not yet verify Secureblue signatures, so ghcr.io (GitHub) is currently a trusted party.
1. Port forwarding has not been ported, but Internet should work.
1. Automatic VM shutdown on app close has not been ported. Please shutdown within the VM after use (`run0 poweroff`).
   If necessary, you can force a shutdown using the `Unplug` button in the bottom snackbar that pops up.
1. Display does not work.
1. The web-based terminal (ttyd) has not been ported.
1. Features requiring kernel patches (such as dynamic memory) are not ported.

### Tips
1. Please follow the [post-install recommendations](https://secureblue.dev/post-install) (displayed in the terminal on first boot as well),
   especially [setting up a separate admin account](https://secureblue.dev/post-install#wheel).
1. The automatic updates are large and may consume a lot of mobile data if not on WiFi.
1. For your convenience, the disks are split between `secureblue-system.qcow2` and `secureblue-user.qcow2`.
   It is possible to duplicate the latter and install different software on different user disks,
   potentially with some software airgapped.
   Unfortunately, Fedora Atomic does not allow `/etc` to be a mount point during install and it has to be read-write during boot,
   so making `-system` read-only does not work.
   Additionally, please note that Secureblue does not claim to provide anonymity or anti-fingerprinting benefits,
   and desktop Linux is generally bad at sandboxing.

### VM image
- [:dvd: image](https://drive.proton.me/urls/Y02GSZFJV8#Y3AMG5zub5Wv)
- [:hammer_and_wrench: building guide](build/custom_vm/secureblue/README.md)

# NixOS
The build script is adapted from [`nixos-avf`](https://github.com/nix-community/nixos-avf), and the image is similar to the one provided there.
### Known issues
1. OS update is not tested.
1. Display does not work.
1. This image (and `nixos-avf` as well) uses the `u-boot.bin` binary blob provided by the host OS.
   There is no guarantee that this is built from source.
   For example, GrapheneOS grabbed this directly from Google.
    - See source code: [[https://github.com/GrapheneOS/platform_manifest/blob/2026012800/default.xml#L22][device/google/cuttlefish_prebuilts]]/[[https://android.googlesource.com/device/google/cuttlefish_prebuilts/+/refs/tags/android-16.0.0_r4/bootloader/crosvm_aarch64/][bootloader/crosvm_aarch64/u-boot.bin]]
1. The upstream build script uses the GPL 3.0 license. The adapted script cannot be provided here, and is forked to a [separate repository](https://github.com/outlawsanzhang/nixos-avf-koiTerminal?tab=readme-ov-file).

### VM image
- [:dvd: image](https://github.com/outlawsanzhang/nixos-avf-koiTerminal/releases)
- [:hammer_and_wrench: building guide](https://github.com/outlawsanzhang/nixos-avf-koiTerminal?tab=readme-ov-file#building-initial-image-optional-for-development)

# Debian
The image is now built from Google's scripts. (Modifying an image downloaded from Google is deprecated)
### Known issues
1. On the first boot, the serial terminal takes a while until it allows logging in, about one minute after the web-based terminal is ready.
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
