# Android Virtualization Framework (AVF)

Android Virtualization Framework (AVF) provides secure and private execution environments for
executing code. AVF is ideal for security-oriented use cases that require stronger isolation
assurances over those offered by Android’s app sandbox.

Visit [our public doc site](https://source.android.com/docs/core/virtualization) to learn more about
what AVF is, what it is for, and how it is structured. This repository contains source code for
userspace components of AVF.

If you want a quick start, see the [getting started guideline](docs/getting_started.md)
and follow the steps there.

For in-depth explanations about individual topics and components, visit the following links.

AVF components:
* [pVM firmware](guest/pvmfw/README.md)
* [Android Boot Loader (ABL)](docs/abl.md)
* [Microdroid](build/microdroid/README.md)
* [Microdroid kernel](guest/kernel/README.md)
* [Microdroid payload](libs/libmicrodroid_payload_metadata/README.md)
* [vmbase](libs/libvmbase/README.md)
* [Service VM](guest/service_vm/README.md)
* [Encrypted Storage](guest/encryptedstore/README.md)

AVF APIs:
* [Java API](libs/framework-virtualization/README.md)
* [VM Payload API](libs/libvm_payload/README.md)

How-Tos:
* [Building and running a demo app in Java](android/MicrodroidDemoApp/README.md)
* [Building and running a demo app in C++](android/vm_demo_native/README.md)
* [Debugging](docs/debug)
* [Using custom VM](docs/custom_vm.md)
* [pVM DICE chain](docs/pvm_dice_chain.md)
* [pVM Remote Attestation](docs/vm_remote_attestation.md)
* [Device assignment](docs/device_assignment.md)
* [Microdroid vendor modules](docs/microdroid_vendor_modules.md)
* [Huge Pages](docs/hugepages.md)
* [Shutdown](docs/shutdown.md)

# Misc
### License
Icon from [public domain](https://freesvg.org/vector-clip-art-of-seamless-pattern-of-carp)

