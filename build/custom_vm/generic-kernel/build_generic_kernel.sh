#!/bin/bash
# This file largely mimics build/debian/build_custom_kernel.sh and https://itsfoss.com/compile-linux-kernel/

set -x
set -e

SCRIPT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

show_help() {
    set +x
    echo "Usage: $0 [OPTION]..."
    echo "Example: $0 -a aarch64 -k 6.1.135 -W ./ -d ./images -c defconfig"
    echo "Builds generic kernel."
    echo "Options:"
    echo "-a ARCH      Architecture of the image [default is host arch: $(uname -m)]"
    echo "-d DEST_DIR  Destination directory for output packages. Requires DEST_DIR/kernel_extras"
    echo "             folder to hold kernel build and installation results. [default: $SCRIPT_DIR]"
    echo "-c           Specify the config file path, or use the string 'defconfig' to modify from"
    echo "             the default kernel config. [default: $ker_config]"
    echo "-h           Print usage and this help message and exit."
    echo "-w           Save temp work directory [for debugging]"
    echo "-W WORK_DIR  Specify work dir instead of temporarily creating. Imply -w [for debugging]"
}

check_sudo() {
    if [ "$EUID" -ne 0 ]; then
        echo "Please run as root." ; exit 1
    fi
}

parse_options() {
    while getopts "a:d:k:c:hwW:" option; do
        case ${option} in
            a)
                arch="$OPTARG"
                ;;
            d)
                dest_dir="$OPTARG"
                ;;
            c)
                ker_config="$OPTARG"
                ;;
            k)
                generic_kver="$OPTARG"
                (echo "$minimum_kver" && echo "$generic_kver") | sort --check --version-sort 2>/dev/null >/dev/null || \
                    { echo "Please use a version higher than $minimum_kver." && exit 1; }
                ;;
            h)
                show_help ; exit
                ;;
            w)
                save_workdir=1
                ;;
            W)
                workdir="${OPTARG%/}"
                save_workdir=1
                may_skip_build=1
                ;;
            *)
                echo "Invalid option: $OPTARG" ; exit 1
                ;;
        esac
    done
    case "$arch" in
        aarch64)
            export ARCH="arm64"
            export CROSS_COMPILE="aarch64-linux-gnu-"
            ;;
        *)
            echo "Invalid architecture: $arch" ; exit 1
            ;;
    esac
    if [[ "${*:$OPTIND:1}" ]]; then
        output="${*:$OPTIND:1}"
    fi
}

build_custom_kernel() {
    dest_dir="$(realpath "$dest_dir")"
    workdir="$(realpath "$workdir")"
    if [[ "$may_skip_build" == 1 && -f "${dest_dir}/vmlinuz" ]]; then
        echo "Skipping build_custom_kernel(). ${dest_dir}/vmlinuz already exists"
        return
    fi
    mkdir -p "${dest_dir}"
    mkdir -p "${workdir}/kernel"

    pushd "${workdir}/kernel" > /dev/null
    ls "linux-${generic_kver}.tar.xz" || wget "https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-${generic_kver}.tar.xz"
    ls "linux-${generic_kver}.tar.sign" || wget "https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-${generic_kver}.tar.sign"

    gpg2 --locate-keys torvalds@kernel.org gregkh@kernel.org 
    ls "linux-${generic_kver}.tar" || unxz --keep "linux-${generic_kver}.tar.xz"
    gpg2 --verify "linux-${generic_kver}.tar.sign"
    tar -xf "linux-${generic_kver}.tar"

    pushd "linux-${generic_kver}"
    if [[ "$ker_config" == "defconfig" ]]; then
        make ARCH=$ARCH defconfig
        scripts/config -m CONFIG_DRM_VIRTIO_GPU -m CONFIG_HW_RANDOM_VIRTIO -m CONFIG_SCSI_VIRTIO -m CONFIG_SND_VIRTIO -m CONFIG_VIRTIO_FS -m CONFIG_VIRTIO_INPUT -m CONFIG_VIRTIO_MEM -m CONFIG_LIBNVDIMM -m CONFIG_VIRTIO_PMEM -m CONFIG_VSOCKETS -m CONFIG_VIRTIO_VSOCKETS -m CONFIG_VHOST_VSOCK -m CONFIG_EROFS_FS -m CONFIG_VSOCKETS_DIAG -m CONFIG_VSOCKETS_LOOPBACK -m CONFIG_VSOCKMON -m CONFIG_BLK_DEV_PMEM -e CONFIG_BTT -m CONFIG_OF_PMEM -m CONFIG_DEV_DAX -m CONFIG_DEV_DAX_HMEM -m CONFIG_DEV_DAX_KMEM -d CONFIG_EROFS_FS_DEBUG -e CONFIG_EROFS_FS_XATTR -e CONFIG_EROFS_FS_POSIX_ACL -e CONFIG_EROFS_FS_SECURITY -e CONFIG_EROFS_FS_ZIP -d CONFIG_EROFS_FS_ZIP_LZMA -m CONFIG_ISO9660_FS -e CONFIG_JOLIET -e CONFIG_ZISOFS -m CONFIG_UDF_FS
    else
        cp "$ker_config" .config
        make olddefconfig
    fi
    openssl req -new -nodes -utf8 -sha256 -days 36500 -batch -x509 -config certs/default_x509.genkey -outform PEM -out certs/signing_key.pem -keyout certs/signing_key.pem

    time { make -j$(nproc) 2>&1 | tee build.log; }

    mkdir -p "${dest_dir}/kernel_extras"
    make modules_install INSTALL_MOD_PATH="${dest_dir}/kernel_extras" INSTALL_MOD_STRIP=1 CONFIG_MODULE_SIG_KEY=certs/signing_key.pem -j$(nproc)
    make headers_install INSTALL_HDR_PATH="${dest_dir}/kernel_extras/usr"
    make dtbs_install INSTALL_DTBS_PATH="${dest_dir}/kernel_extras/boot/dtb-${generic_kver}" # Is this needed / path correct?
    make install INSTALL_PATH="${dest_dir}/kernel_extras/boot" # probably also not necessary
    popd > /dev/null

    mv "${dest_dir}/kernel_extras/boot/vmlinux-${generic_kver}" "${dest_dir}/vmlinux"
    lz4 -BD -12 -q "${dest_dir}/vmlinux" "${dest_dir}/vmlinux.lz4"
    mv "${dest_dir}/vmlinux.lz4" "${dest_dir}/vmlinuz"

    unlink "${dest_dir}/kernel_extras/lib/modules/${generic_kver}/build"
    unlink "${dest_dir}/kernel_extras/lib/modules/${generic_kver}/source" || true

    pushd ${dest_dir} > /dev/null
    mkfs.erofs kernel_extras_part kernel_extras | tee >(grep "UUID:" | grep -o -E '\b[0-9a-f-]{36}\b' > kernel_extras_guid.log)
    (
        kernel_extras_loopdev="$(sudo losetup -f --show kernel_extras_part)"
        kernel_extras_guid="$(sudo blkid -s UUID -o value "${kernel_extras_loopdev}")"
        echo kernel_extras_guid=$kernel_extras_guid
        sudo losetup -d "${kernel_extras_loopdev}"
    ) || true
    popd > /dev/null
    popd > /dev/null
}

clean_up() {
    [ "$save_workdir" -eq 1 ] || rm -rf "${workdir}"
}

set -e
trap clean_up EXIT

abi_flavour=
kernel_extras_guid=
save_workdir=0
may_skip_build=1
dest_dir=$SCRIPT_DIR
workdir=
minimum_kver="6.1.135"
ker_config="$SCRIPT_DIR/debian-kernel.config" 

parse_options "$@"

if [[ -n "${workdir}" ]]; then
    mkdir -p "${workdir}" || true
else
    workdir=$(mktemp -d)
fi
echo $workdir

build_custom_kernel
