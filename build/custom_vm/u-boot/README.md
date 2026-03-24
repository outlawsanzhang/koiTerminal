# U-Boot
A custom VM in AVF can be spun up either directly from a kernel and initrd, like Google's Debian image,
or with a bootloader (e.g. u-boot) to load the kernel and initrd from disk.
If neither `kernel` nor `bootloader` is specified in `vm_config.json`, it defaults to using the bootloader
provided by the host OS, which is u-boot.

For GrapheneOS, the default `u-boot.bin` is a pre-built blob from google's repo:
[[https://github.com/GrapheneOS/platform_manifest/blob/2026012800/default.xml#L22][device/google/cuttlefish_prebuilts]]/[[https://android.googlesource.com/device/google/cuttlefish_prebuilts/+/refs/tags/android-16.0.0_r4/bootloader/crosvm_aarch64/][bootloader/crosvm_aarch64/u-boot.bin]]

You can also [build `u-boot.bin` from source](https://source.android.com/docs/devices/cuttlefish/bootloader-dev).

