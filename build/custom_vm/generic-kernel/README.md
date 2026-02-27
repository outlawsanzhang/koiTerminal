# Building Linux kernels for koiTerminal or AVF

### Foreword
Not all kernels are able to boot as a VM.
As of writing (Feb. 2026), the experience is that kernels from 6.1 LTS are stable, 6.6 LTS ones are generally fine,
but anything above that may mess up the VM's filesystem or do not even run.
Kernels provided by operating systems themselves seem to have issues here and there,
such as failing to mount partitions and hanging during certain operations.

The most reliable way seems to be compiling the latest kernel ourselves,
either a latest generic LTS kernel, or a Debian kernel using Google's script.
Google's script is found at `build/debian/build.sh`, and this README focuses on the generic kernel.

Compiling the kernel requires about 10-20G of free space and takes an hour or a few hours.
This guide assumes a Debian 13 environment, preferrably as a docker or a VM.

### Dependencies
Install the requirements with:
```
sudo apt install apt-utils binfmt-support qemu-user-static qemu-utils qemu-system-arm bc bison build-essential ca-certificates cpio debhelper dh-exec dh-python erofs-utils flex gcc-12 initramfs-tools kernel-wedge libelf-dev libpci-dev libssl-dev lz4 pahole python3-docutils python3-jinja2 quilt rsync wget zstd gcc-aarch64-linux-gnu gcc-12-aarch64-linux-gnu gcc-arm-linux-gnueabihf libc6-dev-arm64-cross imagemagick graphviz dvipng python3-venv fonts-noto-cjk latexmk texlive-lang-chinese texlive-xetex python3-sphinx python3-sphinx-rtd-theme
```

### Script for building a generic kernel

The build script can be found at `build/custom_vm/generic-kernel/build_generic_kernel.sh`.
It supports using either the kernel configs from the Debian build
(attached at `build/custom_vm/generic-kernel/debian-kernel.config`)
or modifying the aarch64 default config.

Copy the `build/custom_vm/generic-kernel` folder to your choice of `/path/to/builder` and invoke the script with:
```
workdir=/path/to/workdir
kernel_ver=6.1.135 # This is the one from Google's Debian build script.
# You can try the latest LTS, which are 6.1.161, 6.6.122, 6.12.68, and 6.18.8 at the time of writing.
# The higher the version, the less stable it is.

/path/to/builder/build_generic_kernel.sh -a aarch64 -k $kernel_ver -c defconfig -W "$workdir" -d "$workdir"/images
# You can remove `-c defconfig` to use the Debian config instead.
```

### Script for building the initrd of the kernel
This initrd, like the one built with Google's Debian build script,
uses busybox to mount the filesystem of the VM and mount the kernel modules on top.

Invoke the build script with:
```
/path/to/builder/build_initrd.sh -a aarch64 -W "$workdir" -d "$workdir"/images
```

After it finishes, the following files will be ready to include in a VM image.
```
$workdir/images/vmlinuz
$workdir/images/kernel_extras_part
$workdir/images/initrd.img
```
