#!/bin/bash
# This file largely mimics build/debian/build_custom_kernel.sh

set -x

SCRIPT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

show_help() {
    set +x
    echo "Usage: $0 [OPTION]..."
    echo "Example: $0 -a aarch64 -W ./ -d ./images"
    echo "Builds initrd.img for our custom kernel."
    echo "Options:"
    echo "-a ARCH      Architecture of the image [default is host arch: $(uname -m)]"
    echo "-d DEST_DIR  Destination directory for output packages. Requires DEST_DIR/kernel_extras"
    echo "             folder to hold kernel build and installation results. [default: $SCRIPT_DIR]"
    echo "-h           Print usage and this help message and exit."
    echo "-w           Save temp work directory [for debugging]"
    echo "-W WORK_DIR  Specify work dir instead of temporarily creating. Imply -w [for debugging]"
}

parse_options() {
    while getopts "a:d:hwW:" option; do
        case ${option} in
            a)
                arch="$OPTARG"
                ;;
            d)
                dest_dir="$OPTARG"
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
            debian_arch="arm64"
            ;;
        # x86_64)
        #     debian_arch="amd64"
        #     ;;
        *)
            echo "Invalid architecture: $arch" ; exit 1
            ;;
    esac
    if [[ "${*:$OPTIND:1}" ]]; then
        output="${*:$OPTIND:1}"
    fi
}

# abi_flavour=
# kernel_extras_guid=
# save_workdir=0
# may_skip_build=0
# dest_dir=$SCRIPT_DIR
# workdir=

abi_flavour=?

build_initrd() {
    dest_dir="$(realpath "$dest_dir")"
    workdir="$(realpath "$workdir")"
    if [[ "$may_skip_build" == 1 && -f "${dest_dir}/initrd.img" ]]; then
        echo "Skipping build_initrd(). ${dest_dir}/initrd.img already exists"
        return
    fi

    if [[ -f "$dest_dir"/kernel_extras_guid.log ]]; then
        kernel_extras_guid="$(cat "$dest_dir"/kernel_extras_guid.log)"
    else
        if [[ -z "$kernel_extras_guid" ]]; then
            kernel_extras_loopdev="$(sudo losetup -f --show "${dest_dir}/kernel_extras_part")"
            kernel_extras_guid="$(blkid -s UUID -o value "${kernel_extras_loopdev}")"
            sudo losetup -d "${kernel_extras_loopdev}"
        fi
    fi

    mkdir -p "${workdir}/initrd"
    pushd "${workdir}/initrd" > /dev/null

    local initrd_modules
    mapfile -t initrd_modules < <(grep -vE '^\s*#|^\s*$' "${SCRIPT_DIR}/initrd/modules")

    local modules_parent="$(realpath "${dest_dir}/kernel_extras/lib/modules")"
    if [[ $(ls "$modules_parent/" | wc -l) != 1 ]]; then
        echo "Multiple \$abi_flavour possible in $modules_parent/. Exiting...\n$(ls "$modules_parent"/)" >&2
        exit 1
    fi
    local modules_src="$(ls -d "$modules_parent"/*)"
    abi_flavour="$(basename "$modules_src")"
    for modname in "${initrd_modules[@]}" ; do
        modprobe --dirname "${dest_dir}/kernel_extras" \
                 --set-version "$abi_flavour" \
                 --show-depends \
                 "$modname" | awk '/insmod/ {print $2}'
    done | sort -u | sed "s;^${modules_src}\/;;" > modules.list

    mkdir -p archive/{bin,lib,sbin}
    cp -arv "${SCRIPT_DIR}/initrd/scripts" archive/

    local busybox_base_url="https://busybox.net/downloads/"
    local busybox_version="1.37.0"

    ls "busybox-${busybox_version}.tar.bz2" || wget "${busybox_base_url}/busybox-${busybox_version}.tar.bz2"
    ls "busybox-${busybox_version}.tar.bz2.sig" || wget "${busybox_base_url}/busybox-${busybox_version}.tar.bz2.sig"
    gpg2 --batch --keyserver keyserver.ubuntu.com --recv-keys C9E9416F76E610DBD09D040F47B70C55ACC9965B
    if ! gpg2 --verify busybox-${busybox_version}.tar.bz2.sig; then
        echo "Busybox bad signature" >&2
        exit 1
    fi
    tar -xf busybox-${busybox_version}.tar.bz2

    pushd "busybox-${busybox_version}" > /dev/null
    if [[ "$arch" != "$(uname -m)" ]]; then
        export ARCH="${arch}"
        export CROSS_COMPILE="${arch}-linux-gnu-"
    fi
    make distclean
    make defconfig
    # NOTE: Overrides for busybox default configs must be PREPENDED.
    mv .config .config.orig
    cat "${SCRIPT_DIR}/initrd/busybox/config" > .config
    cat .config.orig >> .config
    make oldconfig
    make -j$(nproc)
    make install CONFIG_PREFIX="${workdir}/initrd/archive"
    popd > /dev/null

    pushd "${workdir}/initrd/archive" > /dev/null
    local modules_dest="lib/modules/${abi_flavour}"
    mkdir -p "${modules_dest}"
    pushd "${modules_dest}" > /dev/null
    while read -r modpath ; do
        mkdir -p "$(dirname "$modpath")"
        cp -av "${modules_src}/${modpath}" "$modpath"
    done < "${workdir}/initrd/modules.list"
    popd > /dev/null
    depmod -b . $abi_flavour

    echo "KERNEL_EXTRAS_UUID=${kernel_extras_guid}" >> scripts/env-setup
    cat > sbin/early_load_modules <<EOF
#!/bin/sh
set -e

. /scripts/env-setup
. /scripts/helper-utils

EOF
    for mod in "${initrd_modules[@]}" ; do
        if find "${modules_dest}" -name "$mod.ko" | grep .; then
            echo "modprobe $mod || __error 'Failed to load $mod'" >> sbin/early_load_modules
        fi
    done
    cp "${SCRIPT_DIR}/initrd/init" init
    chmod +x init sbin/early_load_modules

    find . | cpio --create --format=newc | zstd -19 -f -o "${dest_dir}/initrd.img"

    popd > /dev/null
    popd > /dev/null
}

clean_up() {
    [ "$save_workdir" -eq 1 ] || rm -rf "${workdir}"
}

set -e
trap clean_up EXIT

abi_flavour=
# kernel_extras_guid=
save_workdir=0
may_skip_build=0
dest_dir=$SCRIPT_DIR
workdir=

parse_options "$@"

if [[ -n "${workdir}" ]]; then
    mkdir -p "${workdir}" || true
else
    workdir=$(mktemp -d)
fi
echo $workdir

build_initrd
