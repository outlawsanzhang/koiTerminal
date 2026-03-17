# Building the Alpine image

The following steps were used to build the provided [Alpine image](https://drive.proton.me/urls/A7QHDFFBWM#0cgJvmQovbFN).
Follow them to recreate that image instead of downloading it.

There may be a number of other ways to install Alpine instead of building it like this. Please see [Advanced](#advanced).

# Step 1. Required tools
It is recommended to do all of this in a contained environment, such as a VM or a docker container. The following instructions uses Debian 13.

Install the requirements with:
```
sudo apt install apt-utils binfmt-support qemu-user-static qemu-utils qemu-system-arm bc bison build-essential ca-certificates cpio debhelper dh-exec dh-python erofs-utils flex gcc-12 initramfs-tools kernel-wedge libelf-dev libpci-dev libssl-dev lz4 pahole python3-docutils python3-jinja2 quilt rsync wget zstd gcc-aarch64-linux-gnu gcc-12-aarch64-linux-gnu gcc-arm-linux-gnueabihf libc6-dev-arm64-cross imagemagick graphviz dvipng python3-venv fonts-noto-cjk latexmk texlive-lang-chinese texlive-xetex python3-sphinx python3-sphinx-rtd-theme
```

# Step 2. Install alpine with QEMU

<!-- References:
    https://gist.github.com/rgl/b02c24f9eb1b4bdb4ac6f970d4bfc885
    https://wiki.alpinelinux.org/wiki/Enable_Serial_Console_on_Boot
-->
Download and verify the image:
```
gpg2 --batch --keyserver keyserver.ubuntu.com --recv-keys 0482D84022F52DF1C4E7CD43293ACD0907D9495A
wget https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-virt-3.23.3-aarch64.iso
wget https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-virt-3.23.3-aarch64.iso.asc
gpg2 --verify alpine-virt-3.23.3-aarch64.iso.asc 
```
Make a raw volume image:
```
truncate -s 500M root_volume
```
Prepare required material, replacing `/path/to/this/folder` with the folder containing this README:
```
cp /path/to/this/folder/install_script.sh ./
cp /usr/share/AAVMF/AAVMF_{CODE,VARS}.fd ./
```
Start QEMU:
```
stty intr ^] # Ctrl-] for interrupting QEMU, so Ctrl-C goes to VM
qemu-system-aarch64 \
    -machine virt \
    -accel tcg,thread=multi \
    -cpu cortex-a57 \
    -smp 2 \
    -m 2g \
    -nographic \
    -drive if=pflash,file=AAVMF_CODE.fd,format=raw,readonly=on \
    -drive if=pflash,file=AAVMF_VARS.fd,format=raw,readonly=on \
    -drive if=virtio,file=root_volume,format=raw,cache=unsafe,discard=unmap,id=hd0 \
    -drive if=virtio,file=install_script.sh,format=raw,readonly=on,cache=unsafe,discard=unmap,id=hd1 \
    -drive if=virtio,file=alpine-virt-3.23.3-aarch64.iso,media=cdrom,cache=unsafe,readonly=on,id=cc \
    -nic user; stty intr ^C # change back interrupt to Ctrl-C
```
This mounts the install script as a tiny volume.
In QEMU, input `root` as the user name. Then, in the console, run the install script:
```
ash /dev/vdb
```
If the network does not work inside QEMU during install, you may have to specify the DNS (replace `-nic user` with `-nic user,dns=xx.xx.xx.xx`).
Once the VM powers down, we need to extract the root partition.
```
LOOP_DEV="$(sudo losetup -f --show --partscan root_volume)"
dd if="$LOOP_DEV"p3 of=root_part
sudo losetup -d "$LOOP_DEV"
```

# Step 3. Build a general kernel
If anyone knows how to make the original kernel from Alpine work, that would be fantastic.
The one extracted from 3.22 boots, but fails to mount the filesystem. Maybe an even earlier version is needed.

Without that option, let's build the kernel ourselves, following [this README](build/custom_vm/generic-kernel/README.md).
The provided image uses `kernel_ver=6.1.161` and keeps `-c defconfig`.

# Step 4. Build initrd and assemble image
Copy files from this folder and the kernel build workdir to the same folder.
```
cp /path/to/this/folder/{build_id,vm_config.json} ./
cp /path/to/kernel/workdir/images/{initrd.img,kernel_extras_part,vmlinuz} ./
ls ./root_part # Assume the Alpine install from QEMU is also here
```
Make the VM image archive.
```
tar czf images.tar.gz build_id initrd.img kernel_extras_part root_part vm_config.json vmlinuz
```

# Advanced
(work in progress)

Hypothetically, you can
- (needs work) Install directly using an official ISO image that has an older kernel, and never upgrade the kernel to another LTS version. Requires either a working official kernel, or a way to inject our kernel to the read-only ISO and boot from it.
    - (documentation available upon request) Install it offline
- (needs work) Build u-boot
