# NixOS
The build script is adapted from [`nixos-avf`](https://github.com/nix-community/nixos-avf), and the image is similar to the one provided there.
### Known issues
1. OS update is not tested.
1. Display probably does not work.
1. This image (and `nixos-avf` as well) uses the `u-boot.bin` binary blob provided by the host OS.
   There is no guarantee that this is built from source.
   For example, GrapheneOS grabbed this directly from Google.
    - See source code: [[https://github.com/GrapheneOS/platform_manifest/blob/2026012800/default.xml#L22][device/google/cuttlefish_prebuilts]]/[[https://android.googlesource.com/device/google/cuttlefish_prebuilts/+/refs/tags/android-16.0.0_r4/bootloader/crosvm_aarch64/][bootloader/crosvm_aarch64/u-boot.bin]]
1. The upstream build script uses the GPL 3.0 license. The adapted script cannot be provided here, and is forked to a [separate repository](https://github.com/outlawsanzhang/nixos-avf-koiTerminal?tab=readme-ov-file).

### VM image
- [:dvd: image](https://drive.proton.me/urls/J0ERDQ0ZZ4#w08ddfcz7zLy)
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
1. Display feature has not been ported.
1. Features requiring kernel patches (such as dynamic memory) are not ported.
1. Custom kernel is needed, which breaks updates within the VM.

### VM image
- [:dvd: image](https://drive.proton.me/urls/A7QHDFFBWM#0cgJvmQovbFN)
- [:hammer_and_wrench: building guide](build/custom_vm/alpine/README.md)

<!-- UPDATE each guide -->
